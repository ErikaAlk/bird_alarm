package com.birdalarm.bird_alarm

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 响铃页盲操手势的三种震法，刻意做得能闭着眼分辨（摸黑时这是唯一能确认自己干了什么的信号）：
 * 越过触发线一记极短轻震，关闭一记长实震，贪睡三记短震。
 */
enum class HapticPattern(val timings: LongArray, val amplitudes: IntArray) {
    Tick(longArrayOf(0, 30), intArrayOf(0, 200)),
    Dismiss(longArrayOf(0, 380), intArrayOf(0, 255)),
    Snooze(longArrayOf(0, 80, 110, 80, 110, 80), intArrayOf(0, 255, 0, 255, 0, 255)),
}

object Haptics {
    /**
     * 直接用 Vibrator 指定时长与振幅，按 USAGE_ALARM 归类。**别换成 View.performHapticFeedback**：
     * 那个强度由系统触感设置决定，实测基本感觉不到，三种动作也震得一样。
     * manifest 里的 VIBRATE 权限是必需的（缺了 Vibrator 静默失败）。
     */
    fun vibrate(context: Context, pattern: HapticPattern) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!vibrator.hasVibrator()) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        try {
            val effect = if (vibrator.hasAmplitudeControl()) {
                VibrationEffect.createWaveform(pattern.timings, pattern.amplitudes, -1)
            } else {
                // 马达不支持调幅（少见）：只能用时长表达强弱
                VibrationEffect.createWaveform(pattern.timings, -1)
            }
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, attributes)
        } catch (_: Exception) {
            // 厂商实现异常时忽略，不影响关闹钟本身
        }
    }
}
