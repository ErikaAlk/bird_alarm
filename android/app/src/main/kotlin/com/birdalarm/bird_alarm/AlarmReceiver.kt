package com.birdalarm.bird_alarm

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        cancelThisAlarmRound(context)
        val prefs = context.getSharedPreferences("bird_alarm_native", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastTrigger = prefs.getLong("last_trigger_at", 0L)
        if (now - lastTrigger < 30_000) return

        prefs
            .edit()
            .putBoolean("launch_alarm", true)
            .putLong("last_trigger_at", now)
            .apply()

        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "bird_alarm:alarm_wake"
            )
        @Suppress("DEPRECATION")
        wakeLock.acquire(30_000)

        NativeAlarmPlayer.start(context)

        try {
            val soundIntent = Intent(context, AlarmSoundService::class.java)
                .setAction(AlarmSoundService.ACTION_RING)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(soundIntent)
            } else {
                context.startService(soundIntent)
            }
        } catch (_: Exception) {
            // 前台服务启动失败时，上面的 NativeAlarmPlayer 已作为兜底在响铃。
        }

        // 响铃通知统一由 AlarmSoundService 负责（含全屏 intent + 关闭/贪睡动作），
        // 这里不再单独 notify(1001)，避免与服务通知抢同一 id 互相覆盖。
        // 仅在锁屏 / 息屏时主动拉起全屏响铃页；亮屏解锁时交给通知（heads-up），不打断用户。
        if (shouldUseFullScreen(context)) {
            val activityIntent = Intent(context, AlarmRingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra("launch_alarm", true)
            }
            try {
                context.startActivity(activityIntent)
            } catch (_: Exception) {
            }
        }
    }

    // 锁屏或息屏 → 需要全屏响铃页唤醒；亮屏且已解锁 → 只用通知提醒。
    private fun shouldUseFullScreen(context: Context): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return keyguardManager.isKeyguardLocked || !powerManager.isInteractive
    }

    private fun cancelThisAlarmRound(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        listOf(1001, 1004).forEach { requestCode ->
            alarmManager.cancel(
                PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    Intent(context, AlarmReceiver::class.java).putExtra("launch_alarm", true),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
        alarmManager.cancel(
            PendingIntent.getActivity(
                context,
                1001,
                Intent(context, AlarmRingActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    putExtra("launch_alarm", true)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
    }
}
