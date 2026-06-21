package com.birdalarm.bird_alarm

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

class AlarmSoundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var armedRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ARM -> {
                val triggerAtMillis = intent.getLongExtra(EXTRA_TRIGGER_AT_MILLIS, 0L)
                arm(triggerAtMillis)
                return START_STICKY
            }
            ACTION_RING -> {
                ring()
                return START_STICKY
            }
            ACTION_SNOOZE -> {
                snooze()
                return START_NOT_STICKY
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification(isRinging = false))
        return START_STICKY
    }

    override fun onDestroy() {
        armedRunnable?.let { handler.removeCallbacks(it) }
        armedRunnable = null
        NativeAlarmPlayer.stop(this)
        super.onDestroy()
    }

    private fun arm(triggerAtMillis: Long) {
        startForeground(NOTIFICATION_ID, buildNotification(isRinging = false, triggerAtMillis = triggerAtMillis))
        armedRunnable?.let { handler.removeCallbacks(it) }
        val delay = (triggerAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        armedRunnable = Runnable { ring() }
        handler.postDelayed(armedRunnable!!, delay)
    }

    private fun ring() {
        armedRunnable?.let { handler.removeCallbacks(it) }
        armedRunnable = null
        val prefs = getSharedPreferences("bird_alarm_native", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastTrigger = prefs.getLong("last_trigger_at", 0L)
        if (now - lastTrigger < 3_000 && NativeAlarmPlayer.isPlaying()) return
        prefs.edit()
            .putBoolean("launch_alarm", true)
            .putLong("last_trigger_at", now)
            .apply()

        val wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "bird_alarm:service_wake"
            )
        @Suppress("DEPRECATION")
        wakeLock.acquire(30_000)
        // 先确定本轮鸟鸣，确保通知能显示正确鸟名（通知在播放器启动前就要构建）。
        NativeAlarmPlayer.ensureRingingAsset(this)
        startForeground(NOTIFICATION_ID, buildNotification(isRinging = true))
        NativeAlarmPlayer.start(this)
        // 仅在锁屏 / 息屏时拉起全屏响铃页；亮屏解锁时只靠通知（heads-up）提醒，不打断用户。
        if (shouldUseFullScreen()) {
            try {
                startActivity(
                    Intent(this, AlarmRingActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        putExtra("launch_alarm", true)
                    }
                )
            } catch (_: Exception) {
            }
        }
    }

    // 贪睡：停掉当前铃声与通知，N 分钟后重新触发响铃。
    private fun snooze() {
        NativeAlarmPlayer.stop(this)
        val triggerAt = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            SNOOZE_REQUEST_CODE,
            Intent(this, AlarmReceiver::class.java).putExtra("launch_alarm", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (_: Exception) {
        }
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    // 锁屏或息屏 → 需要全屏响铃页唤醒；亮屏且已解锁 → 只用通知提醒。
    private fun shouldUseFullScreen(): Boolean {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return keyguardManager.isKeyguardLocked || !powerManager.isInteractive
    }

    private fun currentBirdName(): String {
        val prefs = getSharedPreferences("bird_alarm_native", Context.MODE_PRIVATE)
        return BirdAlarmAssets.cnNameFor(prefs.getString("ringing_asset", null))
    }

    private fun buildNotification(
        isRinging: Boolean,
        triggerAtMillis: Long = 0L,
    ): Notification {
        val channelId = CHANNEL_ID
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "鸟瘾闹钟响铃",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "闹钟响铃和强制清醒挑战"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }

        val contentActivity =
            if (isRinging) AlarmRingActivity::class.java else MainActivity::class.java
        val activityIntent = Intent(this, contentActivity).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            if (isRinging) putExtra("launch_alarm", true)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            1002,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1003,
            Intent(this, AlarmSoundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snoozeIntent = PendingIntent.getService(
            this,
            1006,
            Intent(this, AlarmSoundService::class.java).setAction(ACTION_SNOOZE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, channelId)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
        val contentText =
            if (isRinging) {
                "正在叫的是「${currentBirdName()}」"
            } else {
                "下一次鸟鸣闹钟已守护"
            }
        val title = if (isRinging) "🐦 鸟瘾闹钟正在响起" else "鸟瘾闹钟已启用"
        return builder
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(contentText)
            .setTicker(if (isRinging) "鸟瘾闹钟正在响铃" else "鸟瘾闹钟已启用")
            .setCategory(if (isRinging) Notification.CATEGORY_ALARM else Notification.CATEGORY_STATUS)
            .setPriority(if (isRinging) Notification.PRIORITY_MAX else Notification.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setWhen(if (triggerAtMillis > 0) triggerAtMillis else System.currentTimeMillis())
            .setShowWhen(true)
            .apply {
                if (isRinging) setFullScreenIntent(contentIntent, true)
            }
            .setContentIntent(contentIntent)
            .apply {
                // 只有"正在响铃"的通知才给 关闭 / 贪睡 按钮；
                // "已启用/已守护"的常驻通知不放任何按钮（避免误点关掉守护）。
                if (isRinging) {
                    addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭", stopIntent)
                    addAction(
                        android.R.drawable.ic_lock_idle_alarm,
                        "贪睡 $SNOOZE_MINUTES 分钟",
                        snoozeIntent
                    )
                }
                // Android 16 (API 36) 上请求把响铃常驻通知提级为 Live Update。
                AlarmReceiver.requestPromotedOngoing(this)
            }
            .build()
    }

    companion object {
        const val ACTION_ARM = "com.birdalarm.bird_alarm.ARM_ALARM_SOUND"
        const val ACTION_RING = "com.birdalarm.bird_alarm.RING_ALARM_SOUND"
        const val ACTION_STOP = "com.birdalarm.bird_alarm.STOP_ALARM_SOUND"
        const val ACTION_SNOOZE = "com.birdalarm.bird_alarm.SNOOZE_ALARM_SOUND"
        const val EXTRA_TRIGGER_AT_MILLIS = "trigger_at_millis"
        const val CHANNEL_ID = "bird_alarm_ringing"
        const val NOTIFICATION_ID = 1001
        const val SNOOZE_MINUTES = 5
        const val SNOOZE_REQUEST_CODE = 1005
    }
}
