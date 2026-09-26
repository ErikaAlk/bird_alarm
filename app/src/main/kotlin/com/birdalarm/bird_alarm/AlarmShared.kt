package com.birdalarm.bird_alarm

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

// 原生侧各组件（接收器 / 服务 / 播放器 / 主活动）共用的状态存储文件名。集中成常量，避免某处手抄
// 出错时静默读到一个空的 prefs，导致 ringing_asset / launch_alarm 等协调标志失灵、闹钟行为出错。
const val PREFS_NAME = "bird_alarm_native"

// Android 16 (API 36) 把常驻通知请求提级为 Live Update 用的 extra key。
// 用字符串字面量而非 compileSdk 36 才有的符号，低版本上自动忽略。
const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

// 锁屏或息屏 → 拉起全屏响铃页；亮屏且已解锁 → 只用通知提醒。
// 这是「锁屏全屏响铃」的关键门控判断，集中一处，避免接收器与前台服务两份拷贝将来改岔。
fun shouldUseFullScreen(context: Context): Boolean {
    val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return keyguardManager.isKeyguardLocked || !powerManager.isInteractive
}

// 响铃时把主界面(MainActivity, 含 showWhenLocked)带到前台、由应用内响铃页接管的 Intent。
// 这四个 flag 与 launch_alarm 是绕过 Android 14+ BAL 的关键，集中一处，避免某路径漏 flag 导致全屏弹不出来。
fun fullScreenAlarmIntent(context: Context): Intent {
    return Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        putExtra("launch_alarm", true)
    }
}

// 排 / 取消「闹钟广播」用的 PendingIntent（请求码区分多路：1001/1004 主闹钟、1005 贪睡）。
// 排程与取消必须用同一份配方（同 component、同 extras、同 flags）才能匹配上，集中一处，
// 避免各处手抄漂移导致 cancel 匹配不到、闹钟取消不掉。
fun alarmBroadcastPendingIntent(context: Context, requestCode: Int): PendingIntent {
    return PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, AlarmReceiver::class.java).putExtra("launch_alarm", true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

// 在 triggerAt 排贪睡(1005)，并显示「贪睡中」倒计时通知（可点「关闭闹钟」提前结束，走 cancelUpcoming → cancelSnooze）。
// snooze_until 由调用方先写好。响铃时点贪睡（AlarmSoundService.snooze）和开机补排（BootReceiver）共用。
fun armSnooze(context: Context, triggerAt: Long) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val pendingIntent = alarmBroadcastPendingIntent(context, AlarmSoundService.SNOOZE_REQUEST_CODE)
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    } catch (_: Exception) {
    }
    AlarmReceiver.showCountdownNotification(context, triggerAt, "😴 贪睡中", "稍后再次响铃，点“关闭闹钟”可提前结束")
}

// 撤掉贪睡(1005)一律走这里：闹钟和 snooze_until 要一起清，只撤闹钟不清记录的话，
// 界面会以为还有贪睡在等、响铃结束时跳过重排（AlarmControl.snoozePending）。
fun cancelSnooze(context: Context) {
    (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        .cancel(alarmBroadcastPendingIntent(context, AlarmSoundService.SNOOZE_REQUEST_CODE))
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(AlarmSoundService.SNOOZE_UNTIL).apply()
}

// setAlarmClock 的 show-intent：点系统状态栏「下一个闹钟」芯片时打开本应用（仅查看入口，不带 launch_alarm）。
fun alarmShowIntent(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    return PendingIntent.getActivity(
        context, 1001, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

fun preAlarmPendingIntent(context: Context, triggerAtMillis: Long): PendingIntent {
    return PendingIntent.getBroadcast(
        context,
        AlarmReceiver.PRE_ALARM_REQUEST_CODE,
        Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_PRE_ALARM)
            .putExtra(AlarmReceiver.EXTRA_TRIGGER_AT, triggerAtMillis),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

// 响铃前 10 分钟安排倒计时通知（Live Update）；若已不足 10 分钟则立即显示。
fun schedulePreAlarmCountdown(context: Context, triggerAtMillis: Long) {
    val leadAt = triggerAtMillis - AlarmReceiver.PRE_ALARM_LEAD_MILLIS
    if (leadAt <= System.currentTimeMillis()) {
        AlarmReceiver.showCountdownNotification(context, triggerAtMillis)
        return
    }
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, leadAt, preAlarmPendingIntent(context, triggerAtMillis)
            )
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP, leadAt, preAlarmPendingIntent(context, triggerAtMillis)
            )
        }
    } catch (_: Exception) {
    }
}

// 「已守护」常驻通知：普通低优先级通知（非前台服务），保留可见反馈但不把进程钉在前台。
fun showGuardNotification(context: Context) {
    val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            MainActivity.GUARD_CHANNEL_ID, "鸟瘾闹钟守护", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "已设定的下一次鸟鸣闹钟提示"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }
    val contentIntent = PendingIntent.getActivity(
        context, MainActivity.GUARD_CONTENT_REQUEST_CODE,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, MainActivity.GUARD_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
    val notification = builder
        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setContentTitle("鸟瘾闹钟已启用")
        .setContentText("下一次鸟鸣闹钟已守护")
        .setCategory(Notification.CATEGORY_STATUS)
        .setPriority(Notification.PRIORITY_LOW)
        .setOngoing(true)
        .setAutoCancel(false)
        .setShowWhen(false)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setContentIntent(contentIntent)
        .build()
    notificationManager.notify(MainActivity.GUARD_NOTIFICATION_ID, notification)
}

// 在 triggerAtMillis 排一个完整闹钟：精确闹钟(setAlarmClock + setExactAndAllowWhileIdle) +
// 响铃前倒计时 + 已守护通知。App 排程（Store.sync） 与 AlarmReceiver(关闭倒计时后续排下一次) 共用。
fun armAlarmAt(context: Context, triggerAtMillis: Long) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val info = AlarmManager.AlarmClockInfo(triggerAtMillis, alarmShowIntent(context))
    alarmManager.setAlarmClock(info, alarmBroadcastPendingIntent(context, 1001))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmBroadcastPendingIntent(context, 1004)
        )
    } else {
        alarmManager.setExact(
            AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmBroadcastPendingIntent(context, 1004)
        )
    }
    schedulePreAlarmCountdown(context, triggerAtMillis)
    showGuardNotification(context)
}

// App 下发的「接下来若干次」发生时刻（逗号分隔的毫秒）。原生据此在每次响铃后/关闭后推进、续排下一次，
// 这样相近的多个闹钟也能一个接一个自动排上，不依赖打开 App。App 每次同步会刷新整张表。
fun saveUpcomingTriggers(context: Context, csv: String) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString("upcoming_triggers", csv).apply()
}

fun readUpcomingTriggers(context: Context): List<Long> {
    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString("upcoming_triggers", null)
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(",").mapNotNull { it.trim().toLongOrNull() }.sorted()
}

// 排掉「截止 throughMillis（含）之前」的所有发生，把剩下最早且晚于现在的那一次完整排上。
// 当前这次响铃后调用(throughMillis=now)、或在通知点关闭后调用(throughMillis=被关那次的时刻)。
fun armNextUpcoming(context: Context, throughMillis: Long) {
    val now = System.currentTimeMillis()
    val remaining = readUpcomingTriggers(context).filter { it > throughMillis }
    saveUpcomingTriggers(context, remaining.joinToString(","))
    val soonest = remaining.firstOrNull { it > now }
    if (soonest != null) armAlarmAt(context, soonest)
}

/**
 * 界面层对原生闹钟引擎的全部操作（Flutter 版里是 MethodChannel 的那一串方法）。
 * 状态都在 [PREFS_NAME] 这份 prefs 里：响铃发生在原生侧，那一刻 App 可能根本没在跑。
 */
object AlarmControl {
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 排下一次响铃。不在这里挂前台服务（那会整夜挂前台、是耗电元凶），只排精确闹钟，到点由
     * AlarmReceiver 起前台服务播音。[upcoming] 是「接下来若干次」，响铃后 / 在通知里关掉后原生据此续排。
     * [pool] 是能离线播放的音库（原生引用路径 → 中文名），响铃那一刻从里面随机抽一只。
     */
    fun schedule(context: Context, triggerAtMillis: Long, upcoming: List<Long>, pool: List<Pair<String, String>>) {
        saveSoundPool(context, pool)
        saveUpcomingTriggers(context, upcoming.joinToString(","))
        armAlarmAt(context, triggerAtMillis)
    }

    /** 没有启用的闹钟：撤掉所有排程和相关通知，顺带停掉正在响的。 */
    fun cancel(context: Context) {
        clearScheduledSystemAlarms(context)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(AlarmSoundService.NOTIFICATION_ID)
        stopSound(context)
    }

    private fun clearScheduledSystemAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(alarmBroadcastPendingIntent(context, 1001))
        alarmManager.cancel(alarmBroadcastPendingIntent(context, 1004))
        // 贪睡再排的闹钟(1005)也要取消：否则贪睡后又在 App 里禁用 / 删除闹钟，5 分钟后仍会响
        cancelSnooze(context)
        alarmManager.cancel(alarmShowIntent(context))
        alarmManager.cancel(preAlarmPendingIntent(context, 0L))
        // 闹钟被整体取消：清掉「接下来若干次」，免得续排逻辑据陈旧值误排
        saveUpcomingTriggers(context, "")
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(AlarmReceiver.COUNTDOWN_NOTIFICATION_ID)
        notificationManager.cancel(MainActivity.GUARD_NOTIFICATION_ID)
    }

    /**
     * 关掉本轮响铃。ringing_asset 与 launch_alarm 一起清：响铃页只看 ringing_asset，
     * 清掉它页面就收起；launch_alarm 留着的话，迟到的 onNewIntent 会再把窗口标志设回来。
     */
    fun stopSound(context: Context) {
        context.stopService(Intent(context, AlarmSoundService::class.java))
        prefs(context).edit().remove("ringing_asset").putBoolean("launch_alarm", false).apply()
    }

    /** 贪睡交给前台服务：停当前铃 + N 分钟后重排。服务此时已在前台运行，startService 即可送达。 */
    fun snooze(context: Context) {
        context.startService(Intent(context, AlarmSoundService::class.java).setAction(AlarmSoundService.ACTION_SNOOZE))
    }

    /**
     * 设置页的「测试闹钟」：10 秒后走一遍前台服务响铃链路。它不经 AlarmManager，所以不撤正常的闹钟
     * （Flutter 版会撤，测试铃要是被贪睡掉，正常闹钟就一直空着，直到下次重排）。
     */
    fun test(context: Context) {
        prefs(context).edit().putBoolean("launch_alarm", false).apply()
        val intent = Intent(context, AlarmSoundService::class.java)
            .setAction(AlarmSoundService.ACTION_ARM)
            .putExtra(AlarmSoundService.EXTRA_TRIGGER_AT_MILLIS, System.currentTimeMillis() + 10_000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
    }

    /** 本轮正在响的鸟鸣（原生引用路径）；没在响返回 null。响铃页是否显示只看它。 */
    fun ringingAsset(context: Context): String? = prefs(context).getString("ringing_asset", null)

    /** 被「倒计时通知 → 关闭闹钟」跳过的那一次（毫秒）；无则 0。重排时跳过它，免得关了又被排回来。 */
    fun skippedTrigger(context: Context): Long = prefs(context).getLong("skip_trigger_at", 0L)

    /** 有贪睡在等（通知栏或响铃页上点的都算）：这一轮响完不重排，免得把贪睡撤掉。 */
    fun snoozePending(context: Context): Boolean =
        prefs(context).getLong(AlarmSoundService.SNOOZE_UNTIL, 0L) > System.currentTimeMillis()

    fun hasPendingLaunch(context: Context): Boolean = prefs(context).getBoolean("launch_alarm", false)

    fun clearPendingLaunch(context: Context) {
        prefs(context).edit().putBoolean("launch_alarm", false).apply()
    }

    /** 渐响时长写进原生 prefs：响铃那一刻 App 可能没在跑，不能指望响铃时回头问界面要。 */
    fun saveFadeIn(context: Context, seconds: Int) {
        prefs(context).edit().putInt("fade_in_seconds", seconds.coerceIn(0, 300)).apply()
    }

    private fun saveSoundPool(context: Context, pool: List<Pair<String, String>>) {
        val editor = prefs(context).edit()
        if (pool.isEmpty()) {
            editor.remove("sound_pool").remove("sound_names")
        } else {
            editor.putString("sound_pool", pool.joinToString("\n") { it.first })
            editor.putString("sound_names", org.json.JSONObject(pool.toMap()).toString())
        }
        editor.apply()
    }
}
