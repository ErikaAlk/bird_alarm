package com.birdalarm.bird_alarm

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** 权限自检的四项。 */
enum class AlarmPermission(val title: String, val purpose: String) {
    Notifications("通知", "响铃、倒计时和下载进度都靠通知"),
    FullScreenIntent("全屏通知", "锁屏时到点直接显示响铃页，而不是一条横幅"),
    ExactAlarm("精确闹钟", "到点准时响，不被系统推迟"),
    Battery("后台不受限", "省电策略不清理后台，整夜也能按时响"),
}

object Permissions {
    /** 各项权限的当前状态。低于对应 Android 版本的机器视为已开启（系统没有这个开关）。 */
    fun status(context: Context): Map<AlarmPermission, Boolean> {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return mapOf(
            // 运行时权限给了还不够：用户可能在系统里把整个通知关掉
            AlarmPermission.Notifications to (notificationGranted && notificationManager.areNotificationsEnabled()),
            AlarmPermission.FullScreenIntent to (Build.VERSION.SDK_INT < 34 || notificationManager.canUseFullScreenIntent()),
            AlarmPermission.ExactAlarm to (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()),
            AlarmPermission.Battery to powerManager.isIgnoringBatteryOptimizations(context.packageName),
        )
    }

    /** 打开某一项对应的系统设置页。ROM 上这些 Intent 不一定都在，失败一律退回应用详情页，保证点了有反应。 */
    fun open(activity: Activity, permission: AlarmPermission?) {
        val packageUri = Uri.parse("package:${activity.packageName}")
        val intent = when (permission) {
            AlarmPermission.Notifications -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
                    return
                }
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            }
            AlarmPermission.FullScreenIntent ->
                if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri) else appDetails(packageUri)
            AlarmPermission.ExactAlarm ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri) else appDetails(packageUri)
            AlarmPermission.Battery -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
            null -> appDetails(packageUri)
        }
        try {
            activity.startActivity(intent)
        } catch (_: Exception) {
            try {
                activity.startActivity(appDetails(packageUri))
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 打开 App 时依次引导缺的权限（每次只跳一个页面）。Android 14+ 默认不给全屏通知权限，
     * 不开的话锁屏响铃会降级成横幅。
     */
    fun requestMissing(activity: Activity) {
        val status = status(activity)
        if (status[AlarmPermission.Notifications] == false && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
        val next = listOf(AlarmPermission.FullScreenIntent, AlarmPermission.ExactAlarm, AlarmPermission.Battery)
            .firstOrNull { status[it] == false } ?: return
        open(activity, next)
    }

    fun hasScheduleAccess(context: Context): Boolean =
        context.checkSelfPermission(MainActivity.CADENCE_READ_PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun appDetails(packageUri: Uri) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
}
