package com.birdalarm.bird_alarm

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : FlutterActivity() {
    private val channelName = "bird_alarm/system_alarm"
    private var launchedByAlarm = false
    private var channel: MethodChannel? = null
    // 音频转码很耗时，放后台线程跑，避免阻塞平台主线程导致 UI 卡死。
    private val transcodeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var engineDestroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        launchedByAlarm = intent?.getBooleanExtra("launch_alarm", false) == true ||
            hasPendingAlarmLaunch()
        super.onCreate(savedInstanceState)
        if (launchedByAlarm) {
            prepareAlarmWindow()
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
        channel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "scheduleAlarmAt" -> {
                    val triggerAtMillis = call.argument<Long>("triggerAtMillis")
                    if (triggerAtMillis == null) {
                        result.error("missing_trigger", "triggerAtMillis is required", null)
                    } else {
                        saveSoundPool(
                            call.argument<List<String>>("soundPaths"),
                            call.argument<Map<String, String>>("soundNames")
                        )
                        scheduleAlarm(triggerAtMillis)
                        result.success(null)
                    }
                }
                "cancelAlarm" -> {
                    cancelAlarm()
                    result.success(null)
                }
                "consumeLaunchAlarm" -> {
                    val value = launchedByAlarm || hasPendingAlarmLaunch()
                    launchedByAlarm = false
                    val ringingAsset = getRingingAsset()
                    clearPendingAlarmLaunch()
                    result.success(
                        mapOf(
                            "launched" to value,
                            "assetPath" to ringingAsset
                        )
                    )
                }
                "prepareAlarmWindow" -> {
                    prepareAlarmWindow()
                    result.success(null)
                }
                "releaseAlarmWindow" -> {
                    releaseAlarmWindow()
                    result.success(null)
                }
                "requestAlarmPermissions" -> {
                    requestAlarmPermissions()
                    result.success(null)
                }
                "stopAlarmSound" -> {
                    stopAlarmSound()
                    result.success(null)
                }
                "isAlarmRinging" -> {
                    // 本轮是否仍在响：ringing_asset 在响铃那一刻写入、被任意「停止」路径
                    // （通知关闭/贪睡、app 内关闭、服务销毁）清除。供 Flutter 遮罩判断是否该自关。
                    result.success(getRingingAsset() != null)
                }
                "snoozeAlarm" -> {
                    snoozeAlarm()
                    result.success(null)
                }
                "getSkippedTrigger" -> {
                    // 用户在倒计时通知里点「关闭闹钟」时，原生写入被跳过那一次的触发时刻。
                    // Flutter 重排闹钟时据此跳过这一次发生，避免「关了又被重排回来」。
                    result.success(getSkippedTrigger())
                }
                "testSystemAlarm" -> {
                    clearScheduledSystemAlarms()
                    clearPendingAlarmLaunch()
                    armForegroundAlarmService(System.currentTimeMillis() + 10_000)
                    result.success(null)
                }
                "transcodeAudio" -> {
                    val inputPath = call.argument<String>("inputPath")
                    val outputPath = call.argument<String>("outputPath")
                    val gain = call.argument<Double>("gain") ?: 2.5
                    if (inputPath == null || outputPath == null) {
                        result.error("missing_path", "inputPath and outputPath are required", null)
                    } else {
                        // 在后台线程转码，完成后切回主线程返回结果；result 只回调一次，
                        // 且用 engineDestroyed 守卫，避免转码途中 Activity 销毁时回调到失效引擎崩溃。
                        transcodeExecutor.execute {
                            try {
                                val output = transcodeAudio(inputPath, outputPath, gain.toFloat())
                                mainHandler.post {
                                    if (!engineDestroyed) {
                                        try {
                                            result.success(output)
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                            } catch (error: Exception) {
                                mainHandler.post {
                                    if (!engineDestroyed) {
                                        try {
                                            result.error("transcode_failed", error.message, null)
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                else -> result.notImplemented()
            }
        }
        if (launchedByAlarm) {
            notifyFlutterAlarmSoon()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("launch_alarm", false)) {
            launchedByAlarm = true
            prepareAlarmWindow()
            notifyFlutterAlarmSoon()
        }
    }

    override fun onDestroy() {
        engineDestroyed = true
        transcodeExecutor.shutdown()
        super.onDestroy()
    }

    private fun scheduleAlarm(triggerAtMillis: Long) {
        // 关键省电改动：不再在「排闹钟那一刻」就启动一个全程前台服务。
        // 旧实现 armForegroundAlarmService() 会从设闹钟一直挂前台服务到响铃（整夜七八小时），
        // 把 app 钉在前台态、挡住系统休眠，是整夜耗电的元凶（前台活动时长 ≈ 整晚）。
        // 真正的响铃由下面的精确闹钟（setAlarmClock + setExactAndAllowWhileIdle）到点拉起
        // AlarmReceiver，再由它启动前台服务播音——精确闹钟触发的广播被系统允许在后台启动前台服务，
        // 所以「不漏响」不依赖这个常驻服务。
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val operation = alarmBroadcastPendingIntent(1001)
        val idleOperation = alarmBroadcastPendingIntent(1004)
        val info = AlarmManager.AlarmClockInfo(triggerAtMillis, alarmActivityPendingIntent())
        alarmManager.setAlarmClock(info, operation)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                idleOperation
            )
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, idleOperation)
        }
        schedulePreAlarmCountdown(triggerAtMillis)
        // 「已守护」改用普通常驻通知（不是前台服务）：保留可见反馈，但完全不耗电。
        showGuardNotification()
    }

    // 响铃前 10 分钟安排一个倒计时通知（Live Update）；若已不足 10 分钟则立即显示。
    private fun schedulePreAlarmCountdown(triggerAtMillis: Long) {
        val leadAt = triggerAtMillis - AlarmReceiver.PRE_ALARM_LEAD_MILLIS
        if (leadAt <= System.currentTimeMillis()) {
            AlarmReceiver.showCountdownNotification(this, triggerAtMillis)
            return
        }
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    leadAt,
                    preAlarmPendingIntent(triggerAtMillis)
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    leadAt,
                    preAlarmPendingIntent(triggerAtMillis)
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun preAlarmPendingIntent(triggerAtMillis: Long): PendingIntent {
        return PendingIntent.getBroadcast(
            this,
            AlarmReceiver.PRE_ALARM_REQUEST_CODE,
            Intent(this, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_PRE_ALARM)
                .putExtra(AlarmReceiver.EXTRA_TRIGGER_AT, triggerAtMillis),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelAlarm() {
        clearScheduledSystemAlarms()
        cancelAlarmNotification()
        stopAlarmSound()
    }

    private fun clearScheduledSystemAlarms() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(alarmBroadcastPendingIntent(1001))
        alarmManager.cancel(alarmBroadcastPendingIntent(1004))
        // 贪睡再排的闹钟(1005)也要取消：否则贪睡后又在 app 里禁用/删除闹钟，5 分钟后仍会响。
        alarmManager.cancel(alarmBroadcastPendingIntent(AlarmSoundService.SNOOZE_REQUEST_CODE))
        alarmManager.cancel(alarmActivityPendingIntent())
        alarmManager.cancel(preAlarmPendingIntent(0L))
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(AlarmReceiver.COUNTDOWN_NOTIFICATION_ID)
        notificationManager.cancel(GUARD_NOTIFICATION_ID)
    }

    private fun armForegroundAlarmService(triggerAtMillis: Long) {
        val intent = Intent(this, AlarmSoundService::class.java).apply {
            action = AlarmSoundService.ACTION_ARM
            putExtra(AlarmSoundService.EXTRA_TRIGGER_AT_MILLIS, triggerAtMillis)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // setAlarmClock 的 show-intent：用户点系统状态栏「下一个闹钟」芯片时打开本应用。
    // 只是「查看/编辑」入口，绝不带 launch_alarm（带了会被当成响铃→放鸟叫+假全屏页）。
    private fun alarmActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun alarmBroadcastPendingIntent(requestCode: Int): PendingIntent {
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            putExtra("launch_alarm", true)
        }
        return PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notifyFlutterAlarmSoon() {
        Handler(Looper.getMainLooper()).postDelayed({
            channel?.invokeMethod("alarmFired", null)
        }, 700)
        Handler(Looper.getMainLooper()).postDelayed({
            channel?.invokeMethod("alarmFired", null)
        }, 2_000)
    }

    private fun hasPendingAlarmLaunch(): Boolean {
        return getSharedPreferences("bird_alarm_native", Context.MODE_PRIVATE)
            .getBoolean("launch_alarm", false)
    }

    private fun clearPendingAlarmLaunch() {
        getSharedPreferences("bird_alarm_native", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("launch_alarm", false)
            .apply()
    }

    private fun getRingingAsset(): String? {
        return getSharedPreferences("bird_alarm_native", Context.MODE_PRIVATE)
            .getString("ringing_asset", null)
            ?.removePrefix("flutter_assets/assets/")
    }

    // 被「倒计时通知 → 关闭闹钟」跳过的那一次触发时刻（毫秒）；无则返回 0。
    private fun getSkippedTrigger(): Long {
        return getSharedPreferences("bird_alarm_native", Context.MODE_PRIVATE)
            .getLong("skip_trigger_at", 0L)
    }

    // 持久化 Flutter 下发的"可离线播放音库"（含下载的鸟鸣）与"路径→中文名"映射，
    // 供响铃那一刻随机选鸟、以及响铃通知显示正确鸟名。
    private fun saveSoundPool(paths: List<String>?, names: Map<String, String>?) {
        val prefs = getSharedPreferences("bird_alarm_native", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val pool = paths?.filter { it.isNotBlank() }
        if (pool.isNullOrEmpty()) {
            editor.remove("sound_pool").remove("sound_names")
        } else {
            editor.putString("sound_pool", pool.joinToString("\n"))
            val json = JSONObject()
            names?.forEach { (key, value) -> json.put(key, value) }
            editor.putString("sound_names", json.toString())
        }
        editor.apply()
    }

    private fun cancelAlarmNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(1001)
        stopAlarmSound()
    }

    private fun stopAlarmSound() {
        stopService(Intent(this, AlarmSoundService::class.java))
        // 关掉本轮闹钟时一并清掉启动标志（持久 + 内存）：否则随后迟到的 onNewIntent/resumed/
        // alarmFired 会让 consumeLaunchAlarm 再次返回 launched=true，而 ringing_asset 此刻已被
        // 移除 → assetPath=null → Flutter 随机选一只鸟弹出"第二个响铃遮罩"（需点两次关闭）。
        getSharedPreferences("bird_alarm_native", Context.MODE_PRIVATE)
            .edit()
            .remove("ringing_asset")
            .putBoolean("launch_alarm", false)
            .apply()
        launchedByAlarm = false
    }

    private fun snoozeAlarm() {
        // 供 Flutter 响铃遮罩的"贪睡"按钮使用：把贪睡动作交给前台服务处理
        // （停当前铃 + N 分钟后重排）。服务此时已在前台运行，startService 即可送达。
        startService(
            Intent(this, AlarmSoundService::class.java)
                .setAction(AlarmSoundService.ACTION_SNOOZE)
        )
    }

    // 让响铃时的主界面（Flutter 全屏响铃遮罩）显示在锁屏之上、并点亮屏幕。
    // 不调用 requestDismissKeyguard：用户无需解锁即可在遮罩上关闭/贪睡。
    private fun prepareAlarmWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )
    }

    // 响铃被关闭/贪睡/通知关闭后，释放「屏幕常亮」标志，让屏幕恢复正常熄屏。
    // 这是省电关键：FLAG_KEEP_SCREEN_ON 一旦设上、配合 showWhenLocked，本应用会一直显示在锁屏上
    // 且强制亮屏到天亮（亮屏时长 ≈ 整晚）。这里把它清掉，屏幕就会按系统超时正常熄灭。
    // 注意：只清这两个 window flag，绝不调用 setShowWhenLocked(false)/setTurnScreenOn(false)——
    // 那是锁屏全屏响铃的命脉，清了会导致下一次响铃从后台 startActivity 被 BAL 拦截、全屏弹不出来。
    // 若原生此刻仍在响铃（ringing_asset 还在），则跳过释放，避免和刚开始的新一轮抢标志。
    private fun releaseAlarmWindow() {
        if (getRingingAsset() != null) return
        runOnUiThread {
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
            )
        }
    }

    // 「已守护」常驻通知：普通低优先级通知（非前台服务），保留可见反馈但不把进程钉在前台。
    // 排闹钟时显示，响铃时由 AlarmReceiver 清掉（让位给响铃通知），取消闹钟时一并清掉。
    private fun showGuardNotification() {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                GUARD_CHANNEL_ID,
                "鸟瘾闹钟守护",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "已设定的下一次鸟鸣闹钟提示"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            GUARD_CONTENT_REQUEST_CODE,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, GUARD_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("鸟瘾闹钟已启用")
            .setContentText("下一次鸟鸣闹钟已守护")
            .setCategory(Notification.CATEGORY_STATUS)
            .setPriority(Notification.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .build()
        notificationManager.notify(GUARD_NOTIFICATION_ID, notification)
    }

    private fun requestAlarmPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
        // Android 14 (API 34)+ 默认不再授予 USE_FULL_SCREEN_INTENT，否则锁屏响铃会被
        // 降级成普通横幅通知而非全屏。引导用户授予"全屏通知"权限以恢复锁屏全屏响铃。
        if (Build.VERSION.SDK_INT >= 34) {
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!notificationManager.canUseFullScreenIntent()) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:$packageName")
                        )
                    )
                    return
                } catch (_: Exception) {
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:$packageName")
                        )
                    )
                    return
                } catch (_: Exception) {
                }
            }
        }
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun transcodeAudio(inputPath: String, outputPath: String, gain: Float): String {
        val decoded = decodeToPcm(inputPath, gain)
        encodeAac(decoded.pcm, decoded.sampleRate, decoded.channelCount, outputPath)
        return outputPath
    }

    private data class DecodedAudio(
        val pcm: ByteArray,
        val sampleRate: Int,
        val channelCount: Int
    )

    private fun decodeToPcm(inputPath: String, gain: Float): DecodedAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputPath)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (index in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(index)
            val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = index
                format = candidate
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            throw IllegalArgumentException("No audio track found")
        }

        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: throw IllegalArgumentException("Missing audio mime")
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val decoder = MediaCodec.createDecoderByType(mime)
        val bufferInfo = MediaCodec.BufferInfo()
        val output = ByteArrayOutputStream()
        decoder.configure(format, null, null, 0)
        decoder.start()

        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val inputBufferIndex = decoder.dequeueInputBuffer(10_000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                    val sampleSize =
                        if (inputBuffer == null) -1 else extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            sampleSize,
                            extractor.sampleTime,
                            0
                        )
                        extractor.advance()
                    }
                }
            }

            when (val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> {
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)
                            amplifyPcm16(chunk, gain)
                            output.write(chunk)
                        }
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                        decoder.releaseOutputBuffer(outputBufferIndex, false)
                    }
                }
            }
        }

        decoder.stop()
        decoder.release()
        extractor.release()
        return DecodedAudio(output.toByteArray(), sampleRate, channelCount)
    }

    private fun amplifyPcm16(bytes: ByteArray, gain: Float) {
        var index = 0
        while (index + 1 < bytes.size) {
            val low = bytes[index].toInt() and 0xff
            val high = bytes[index + 1].toInt()
            val sample = (high shl 8) or low
            val amplified = max(Short.MIN_VALUE.toInt(), min(Short.MAX_VALUE.toInt(), (sample * gain).toInt()))
            bytes[index] = (amplified and 0xff).toByte()
            bytes[index + 1] = ((amplified shr 8) and 0xff).toByte()
            index += 2
        }
    }

    private fun encodeAac(pcm: ByteArray, sampleRate: Int, channelCount: Int, outputPath: String) {
        File(outputPath).parentFile?.mkdirs()
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val bufferInfo = MediaCodec.BufferInfo()
        val bytesPerFrame = max(1, channelCount * 2)
        var trackIndex = -1
        var muxerStarted = false
        var inputOffset = 0
        var inputDone = false

        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        while (true) {
            if (!inputDone) {
                val inputBufferIndex = encoder.dequeueInputBuffer(10_000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputBufferIndex)
                    inputBuffer?.clear()
                    val remaining = pcm.size - inputOffset
                    if (remaining <= 0) {
                        val presentationTimeUs =
                            (inputOffset / bytesPerFrame) * 1_000_000L / sampleRate
                        encoder.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            0,
                            presentationTimeUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        val size = min(inputBuffer?.capacity() ?: 0, remaining)
                        inputBuffer?.put(pcm, inputOffset, size)
                        val presentationTimeUs =
                            (inputOffset / bytesPerFrame) * 1_000_000L / sampleRate
                        encoder.queueInputBuffer(inputBufferIndex, 0, size, presentationTimeUs, 0)
                        inputOffset += size
                    }
                }
            }

            when (val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (inputDone) continue
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                else -> {
                    if (outputBufferIndex >= 0) {
                        val outputBuffer: ByteBuffer? = encoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                            }
                        }
                        val done =
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        encoder.releaseOutputBuffer(outputBufferIndex, false)
                        if (done) break
                    }
                }
            }
        }

        encoder.stop()
        encoder.release()
        muxer.stop()
        muxer.release()
    }

    companion object {
        // 「已守护」常驻通知（普通通知，非前台服务）。id 与响铃(1001)/倒计时(1010)错开。
        const val GUARD_NOTIFICATION_ID = 1011
        const val GUARD_CHANNEL_ID = "bird_alarm_guard"
        const val GUARD_CONTENT_REQUEST_CODE = 1012
    }
}
