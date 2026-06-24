package com.birdalarm.bird_alarm

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

class MainActivity : FlutterActivity() {
    private val channelName = "bird_alarm/system_alarm"
    private var launchedByAlarm = false
    private var channel: MethodChannel? = null

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
                "requestAlarmPermissions" -> {
                    requestAlarmPermissions()
                    result.success(null)
                }
                "stopAlarmSound" -> {
                    stopAlarmSound()
                    result.success(null)
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
                        try {
                            result.success(transcodeAudio(inputPath, outputPath, gain.toFloat()))
                        } catch (error: Exception) {
                            result.error("transcode_failed", error.message, null)
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

    private fun scheduleAlarm(triggerAtMillis: Long) {
        armForegroundAlarmService(triggerAtMillis)
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
        alarmManager.cancel(alarmActivityPendingIntent())
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

    private fun alarmActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, AlarmRingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra("launch_alarm", true)
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

    private fun cancelAlarmNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(1001)
        stopAlarmSound()
    }

    private fun stopAlarmSound() {
        stopService(Intent(this, AlarmSoundService::class.java))
        getSharedPreferences("bird_alarm_native", Context.MODE_PRIVATE)
            .edit()
            .remove("ringing_asset")
            .apply()
    }

    private fun prepareAlarmWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )
    }

    private fun requestAlarmPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
        // Android 14 (API 34)+ 默认不再授予 USE_FULL_SCREEN_INTENT，否则锁屏响铃会被降级成
        // 普通横幅通知而非全屏响铃页。引导用户授予「全屏通知」权限以恢复锁屏全屏响铃。
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
}
