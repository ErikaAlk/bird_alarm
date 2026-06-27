package com.birdalarm.bird_alarm

import android.app.AlarmManager
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
        // 走到这里通常是「系统杀掉服务后用 null intent 重启」(START_STICKY 重投)。
        // 绝不把进程长期挂成「空转前台服务」——那会整夜空转耗电。满足前台契约后立刻自停。
        startForeground(NOTIFICATION_ID, buildNotification(isRinging = false))
        stopSelf()
        return START_NOT_STICKY
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
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastTrigger = prefs.getLong("last_trigger_at", 0L)
        if (now - lastTrigger < 3_000 && NativeAlarmPlayer.isPlaying()) {
            // 即便判定为"重复触发"（AlarmReceiver 已抢先起播并写了 last_trigger_at），也必须
            // 补发响铃前台通知再返回：它是全应用唯一带「全屏意图 + 关闭/贪睡键 + Live Update 提级」
            // 的通知，同时满足 startForegroundService 的前台契约。否则会出现"只响声、无通知、
            // 无全屏、无流体云"。鸟名复用 AlarmReceiver 已写好的 ringing_asset。
            NativeAlarmPlayer.ensureRingingAsset(this)
            startForeground(NOTIFICATION_ID, buildNotification(isRinging = true))
            return
        }
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
        // 锁屏/息屏时把主界面(MainActivity, 含 showWhenLocked)带到前台显示 Flutter 全屏
        // 响铃遮罩；亮屏已解锁时不打断用户，只用通知。通知的全屏意图作为兜底。
        if (shouldUseFullScreen(this)) {
            try {
                startActivity(fullScreenAlarmIntent(this))
            } catch (_: Exception) {
            }
        }
    }

    // 贪睡：停掉当前铃声与通知，N 分钟后重新触发响铃。
    private fun snooze() {
        NativeAlarmPlayer.stop(this)
        val triggerAt = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = alarmBroadcastPendingIntent(this, SNOOZE_REQUEST_CODE)
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
        // 贪睡期间显示倒计时通知（动态倒计时 +「关闭闹钟」按钮）：既预告「N 分钟后再响」，
        // 也让用户能提前结束本次贪睡——按钮走 ACTION_CANCEL_UPCOMING → cancelUpcoming，会取消 1005。
        AlarmReceiver.showCountdownNotification(
            this,
            triggerAt,
            "😴 贪睡中",
            "稍后再次响铃 · 点「关闭闹钟」可提前结束",
        )
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

    private fun currentBirdName(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return BirdAlarmAssets.cnNameFor(this, prefs.getString("ringing_asset", null))
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

        // 始终指向 MainActivity：响铃时带 launch_alarm，由 Flutter 显示全屏响铃遮罩。
        val activityIntent = Intent(this, MainActivity::class.java).apply {
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
                // 只有"正在响铃"时才给 关闭 / 贪睡 按钮，并提级为 Live Update（流体云胶囊）。
                // 全屏响铃由 MainActivity(showWhenLocked) + Flutter 全屏遮罩负责，与这里的
                // 提级互不影响，所以 Live Update 与全屏可同时成立。
                if (isRinging) {
                    addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭", stopIntent)
                    addAction(
                        android.R.drawable.ic_lock_idle_alarm,
                        "贪睡 $SNOOZE_MINUTES 分钟",
                        snoozeIntent
                    )
                    AlarmReceiver.requestPromotedOngoing(this)
                }
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
