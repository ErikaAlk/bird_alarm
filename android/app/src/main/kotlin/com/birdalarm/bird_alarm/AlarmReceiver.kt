package com.birdalarm.bird_alarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PRE_ALARM -> {
                showCountdownNotification(context, intent.getLongExtra(EXTRA_TRIGGER_AT, 0L))
                return
            }
            ACTION_CANCEL_UPCOMING -> {
                cancelUpcoming(context, intent.getLongExtra(EXTRA_TRIGGER_AT, 0L))
                return
            }
        }

        cancelThisAlarmRound(context)
        // 响铃了就清掉响铃前的倒计时通知和「已守护」常驻通知（让位给响铃通知）。
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).apply {
            cancel(COUNTDOWN_NOTIFICATION_ID)
            cancel(MainActivity.GUARD_NOTIFICATION_ID)
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

        // 响铃通知统一由 AlarmSoundService 负责（含全屏意图 + 关闭/贪睡动作），这里不再
        // 单独 notify(1001)，避免抢同一 id。锁屏/息屏时尝试直接拉起全屏响铃页——targetSdk 36 上
        // 后台 startActivity 多半被 BAL 拦掉（已 try/catch 吞掉），真正的全屏兜底靠响铃通知的
        // setFullScreenIntent + MainActivity 的 showWhenLocked；亮屏已解锁时只用通知，不打断用户。
        if (shouldUseFullScreen(context)) {
            try {
                context.startActivity(fullScreenAlarmIntent(context))
            } catch (_: Exception) {
            }
        }
    }

    private fun cancelThisAlarmRound(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // 含贪睡再排的 1005：本轮重新响起时，作废上一轮可能还挂着的贪睡。
        listOf(1001, 1004, AlarmSoundService.SNOOZE_REQUEST_CODE).forEach { requestCode ->
            alarmManager.cancel(alarmBroadcastPendingIntent(context, requestCode))
        }
    }

    companion object {
        const val ACTION_PRE_ALARM = "com.birdalarm.bird_alarm.PRE_ALARM"
        const val ACTION_CANCEL_UPCOMING = "com.birdalarm.bird_alarm.CANCEL_UPCOMING"
        const val EXTRA_TRIGGER_AT = "trigger_at"
        const val PRE_ALARM_REQUEST_CODE = 1007
        const val CANCEL_REQUEST_CODE = 1008
        const val COUNTDOWN_CONTENT_REQUEST_CODE = 1009
        const val COUNTDOWN_NOTIFICATION_ID = 1010
        const val COUNTDOWN_CHANNEL_ID = "bird_alarm_countdown"
        const val PRE_ALARM_LEAD_MILLIS = 10 * 60 * 1000L

        // 倒计时通知（动态倒计时 + 「关闭闹钟」按钮）。响铃前 10 分钟用它预告；贪睡时也复用它显示
        // 「N 分钟后再响」并支持提前关。Android 16 (API 36) 上请求提级为 Live Update（状态栏胶囊/锁屏/息屏常驻）。
        fun showCountdownNotification(
            context: Context,
            triggerAt: Long,
            title: String = "🐦 鸟瘾闹钟即将响起",
            text: String = "鸟鸣闹钟即将在设定时间响起",
        ) {
            if (triggerAt <= 0L) return
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    COUNTDOWN_CHANNEL_ID,
                    "鸟瘾闹钟倒计时",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "响铃前的倒计时提醒"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                notificationManager.createNotificationChannel(channel)
            }

            val contentIntent = PendingIntent.getActivity(
                context,
                COUNTDOWN_CONTENT_REQUEST_CODE,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val cancelIntent = PendingIntent.getBroadcast(
                context,
                CANCEL_REQUEST_CODE,
                Intent(context, AlarmReceiver::class.java)
                    .setAction(ACTION_CANCEL_UPCOMING)
                    .putExtra(EXTRA_TRIGGER_AT, triggerAt),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(context, COUNTDOWN_CHANNEL_ID)
                } else {
                    @Suppress("DEPRECATION")
                    Notification.Builder(context)
                }
            builder
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setAutoCancel(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setWhen(triggerAt)
                .setShowWhen(true)
                .setContentIntent(contentIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭闹钟", cancelIntent)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // 显示一个动态倒计时（剩余 X 分 X 秒）。
                builder.setUsesChronometer(true)
                builder.setChronometerCountDown(true)
            }
            requestPromotedOngoing(builder)
            notificationManager.notify(COUNTDOWN_NOTIFICATION_ID, builder.build())
        }

        // 用户在倒计时通知里点「关闭闹钟」：取消本轮即将到来的响铃，并清掉相关通知/服务。
        // skipTriggerAt = 这一次被跳过的触发时刻；记到 prefs，Flutter 重排时跳过它，避免「关了又被排回来」。
        fun cancelUpcoming(context: Context, skipTriggerAt: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            // 含贪睡再排的 1005，确保连带挂着的贪睡也一起取消。
            listOf(1001, 1004, AlarmSoundService.SNOOZE_REQUEST_CODE).forEach { requestCode ->
                alarmManager.cancel(alarmBroadcastPendingIntent(context, requestCode))
            }
            try {
                context.stopService(Intent(context, AlarmSoundService::class.java))
            } catch (_: Exception) {
            }
            NativeAlarmPlayer.stop(context)
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(COUNTDOWN_NOTIFICATION_ID)
            notificationManager.cancel(AlarmSoundService.NOTIFICATION_ID)
            notificationManager.cancel(MainActivity.GUARD_NOTIFICATION_ID)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("launch_alarm", false)
                .putLong("skip_trigger_at", skipTriggerAt)
                .apply()
        }

        // 在 Android 16 (API 36) 上把常驻通知请求提级为 Live Update。
        // 用 extra 字符串 + addExtras，避免依赖 compileSdk 36 才有的符号，低版本上自动忽略。
        fun requestPromotedOngoing(builder: Notification.Builder) {
            if (Build.VERSION.SDK_INT >= 36) {
                builder.addExtras(
                    Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) }
                )
            }
        }
    }
}
