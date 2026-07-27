package com.birdalarm.bird_alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

// 鸟鸣下载进度通知：下载期间是「常驻 + 进度条」的通知，并在 Android 16 上请求提级为
// Live Update（状态栏胶囊 / 锁屏常驻），下完自动收起。通知 id 与闹钟那几条（1001 响铃、
// 1010 倒计时、1011 已守护）错开，互不覆盖。
object DownloadNotifier {
    const val CHANNEL_ID = "bird_alarm_download"
    const val NOTIFICATION_ID = 1013
    private const val CONTENT_REQUEST_CODE = 1014

    // progress < 0 表示进度未知（例如正在转码），显示不确定进度条。
    fun update(context: Context, title: String, text: String, progress: Int) {
        val notificationManager = ensureChannel(context)
        val builder = newBuilder(context)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent(context))
        if (progress < 0) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, progress.coerceIn(0, 100), false)
        }
        // 常驻 + 进度条的通知才有资格被提级成 Live Update；低版本上这个 extra 会被忽略。
        AlarmReceiver.requestPromotedOngoing(builder)
        if (Build.VERSION.SDK_INT >= 36 && progress >= 0) {
            // 状态栏胶囊上显示的极短文本（Live Update 的「一眼信息」），这里放百分比。
            builder.setShortCriticalText("${progress.coerceIn(0, 100)}%")
        }
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    // 下载结束：换成一条普通（可滑掉、几秒后自动消失）的完成/失败提示，不再常驻。
    fun finish(context: Context, title: String, text: String) {
        val notificationManager = ensureChannel(context)
        val builder = newBuilder(context)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent(context))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setTimeoutAfter(8_000)
        }
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    fun hide(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context): NotificationManager {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_LOW：进度每变一次就更新一次通知，不能每次都出声。
            val channel = NotificationChannel(
                CHANNEL_ID, "鸟鸣下载", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "下载鸟鸣时的进度提示"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
        return notificationManager
    }

    private fun newBuilder(context: Context): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

    private fun contentIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        CONTENT_REQUEST_CODE,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
