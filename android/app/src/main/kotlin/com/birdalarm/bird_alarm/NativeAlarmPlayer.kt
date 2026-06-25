package com.birdalarm.bird_alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import java.io.File

object NativeAlarmPlayer {
    private var player: MediaPlayer? = null

    fun isPlaying(): Boolean = player?.isPlaying == true

    // 决定本轮响铃的鸟鸣并持久化（若已决定则复用）。在建通知前调用，确保通知能显示正确鸟名。
    fun ensureRingingAsset(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences("bird_alarm_native", Context.MODE_PRIVATE)
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
            mediaPlayer.setVolume(1f, 1f)
            mediaPlayer.prepare()
            mediaPlayer.start()
            player = mediaPlayer
        } catch (_: Exception) {
            // 播放彻底失败（资源/缓存/默认铃声都不可用，或 prepare/start 抛错）：释放并清掉本轮状态。
            // 关键：prepare()/start() 由 AlarmReceiver.onReceive 无包裹调用，这里若不吞掉异常会让
            // 广播接收器抛错崩进程；同时清掉 ringing_asset，避免 isAlarmRinging 谎报「仍在响」。
            try {
                mediaPlayer.release()
            } catch (_: Exception) {
            }
            player = null
            appContext
                .getSharedPreferences("bird_alarm_native", Context.MODE_PRIVATE)
                .edit()
                .remove("ringing_asset")
                .apply()
        }
    }

    fun stop(context: Context) {
        player?.run {
            try {
                stop()
            } catch (_: Exception) {
            }
            release()
        }
        player = null
        context.applicationContext
            .getSharedPreferences("bird_alarm_native", Context.MODE_PRIVATE)
            .edit()
            .remove("ringing_asset")
            .putBoolean("launch_alarm", false)
            .apply()
    }
}
