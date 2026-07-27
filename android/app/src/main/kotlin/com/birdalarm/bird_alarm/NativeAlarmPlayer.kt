package com.birdalarm.bird_alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File

object NativeAlarmPlayer {
    private var player: MediaPlayer? = null

    // 渐响：起始音量与调音步长。起点取 8%——再低在嘈杂环境里等于没响，太高就失去「不吓人」的意义。
    private const val FADE_START_VOLUME = 0.08f
    private const val FADE_STEP_MILLIS = 200L
    private val fadeHandler = Handler(Looper.getMainLooper())
    private var fadeRunnable: Runnable? = null

    fun isPlaying(): Boolean = player?.isPlaying == true

    // 渐响时长（秒）；0 = 关闭渐响，一上来就是满音量。由 Flutter 设置页通过
    // MethodChannel(updateSoundSettings) 写进同一份 prefs，响铃那一刻读。
    fun fadeInSeconds(context: Context): Int =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("fade_in_seconds", 0)

    // 音量从 FADE_START_VOLUME 平滑爬到满音量。用平方曲线：人耳对低音量更敏感，
    // 线性爬升听感上「前半段就已经很大了」，平方能让开头更轻、结尾更快。
    private fun startFadeIn(mediaPlayer: MediaPlayer, seconds: Int) {
        cancelFade()
        val totalMillis = seconds * 1000L
        val startedAt = SystemClock.elapsedRealtime()
        mediaPlayer.setVolume(FADE_START_VOLUME, FADE_START_VOLUME)
        val runnable = object : Runnable {
            override fun run() {
                // 只给「当前这一轮」的播放器调音：铃声已被换掉/停掉时立刻收手。
                if (player !== mediaPlayer) return
                val ratio =
                    ((SystemClock.elapsedRealtime() - startedAt).toFloat() / totalMillis)
                        .coerceIn(0f, 1f)
                val volume = FADE_START_VOLUME + (1f - FADE_START_VOLUME) * ratio * ratio
                try {
                    mediaPlayer.setVolume(volume, volume)
                } catch (_: Exception) {
                    return // 播放器已释放
                }
                if (ratio < 1f) fadeHandler.postDelayed(this, FADE_STEP_MILLIS)
            }
        }
        fadeRunnable = runnable
        fadeHandler.postDelayed(runnable, FADE_STEP_MILLIS)
    }

    private fun cancelFade() {
        fadeRunnable?.let { fadeHandler.removeCallbacks(it) }
        fadeRunnable = null
    }

    // 决定本轮响铃的鸟鸣并持久化（若已决定则复用）。在建通知前调用，确保通知能显示正确鸟名。
    fun ensureRingingAsset(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString("ringing_asset", null)?.let { return it }
        // 从 Flutter 下发的完整音库（含下载到本机的鸟鸣）里随机选；为空时回退内置 10 个。
        val pool = prefs.getString("sound_pool", null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: BirdAlarmAssets.sounds
        return pool.random().also {
            prefs.edit().putString("ringing_asset", it).apply()
        }
    }

    fun start(context: Context) {
        if (player?.isPlaying == true) return
        val appContext = context.applicationContext
        val assetPath = ensureRingingAsset(appContext)

        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        audioManager.requestAudioFocus(
            null,
            AudioManager.STREAM_ALARM,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        )

        val mediaPlayer = MediaPlayer()
        try {
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mediaPlayer.isLooping = true
            try {
                val localFile = File(assetPath)
                if (assetPath.startsWith("/") && localFile.exists()) {
                    // 下载到本机的鸟鸣是普通文件，按文件路径直接播放。
                    mediaPlayer.setDataSource(localFile.absolutePath)
                } else {
                    val descriptor = appContext.assets.openFd(assetPath)
                    mediaPlayer.setDataSource(
                        descriptor.fileDescriptor,
                        descriptor.startOffset,
                        descriptor.length
                    )
                    descriptor.close()
                }
            } catch (_: Exception) {
                try {
                    val file = File(appContext.cacheDir, assetPath.substringAfterLast('/'))
                    appContext.assets.open(assetPath).use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    mediaPlayer.setDataSource(file.absolutePath)
                } catch (_: Exception) {
                    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                        ?: throw IllegalStateException("no playable alarm source")
                    mediaPlayer.setDataSource(appContext, uri)
                }
            }
            // 渐响开着就从很轻开始、随后爬到满音量；关着则维持原来「一上来就最大声」的行为。
            val fadeSeconds = fadeInSeconds(appContext)
            val initialVolume = if (fadeSeconds > 0) FADE_START_VOLUME else 1f
            mediaPlayer.setVolume(initialVolume, initialVolume)
            mediaPlayer.prepare()
            mediaPlayer.start()
            player = mediaPlayer
            if (fadeSeconds > 0) startFadeIn(mediaPlayer, fadeSeconds)
        } catch (_: Exception) {
            // 播放彻底失败（资源/缓存/默认铃声都不可用，或 prepare/start 抛错）：释放并清掉本轮状态。
            // 关键：prepare()/start() 由 AlarmReceiver.onReceive 无包裹调用，这里若不吞掉异常会让
            // 广播接收器抛错崩进程；同时清掉 ringing_asset，避免 isAlarmRinging 谎报「仍在响」。
            cancelFade()
            try {
                mediaPlayer.release()
            } catch (_: Exception) {
            }
            player = null
            appContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove("ringing_asset")
                .apply()
        }
    }

    fun stop(context: Context) {
        cancelFade()
        player?.run {
            try {
                stop()
            } catch (_: Exception) {
            }
            release()
        }
        player = null
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("ringing_asset")
            .putBoolean("launch_alarm", false)
            .apply()
    }
}
