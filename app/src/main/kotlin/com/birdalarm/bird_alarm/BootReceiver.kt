package com.birdalarm.bird_alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 重启和覆盖安装后补排闹钟。AlarmManager 的闹钟重启就全没了，覆盖安装后也不保证还在；
 * 以前要等打开 App（Store.sync）才会重排，这期间闹钟不响。
 *
 * 只按原生 prefs 里的「接下来若干次」（upcoming_triggers）补排，不起界面层（Store）。
 * 那张表是上次同步时写的，最多 8 次，关机期间错过的直接跳过；8 次都过完了就只能等打开 App。
 *
 * 不收 LOCKED_BOOT_COMPLETED：prefs 在凭据加密存储里，开机后解锁之前读不到。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // 进程没了，铃也就停了。留着 ringing_asset 的话打开 App 会弹出没有声音的响铃页，界面层也不肯重排
        AlarmControl.stopSound(context)
        val now = System.currentTimeMillis()
        armNextUpcoming(context, now)
        val snoozeUntil = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(AlarmSoundService.SNOOZE_UNTIL, 0L)
        if (snoozeUntil > now) armSnooze(context, snoozeUntil)
    }
}
