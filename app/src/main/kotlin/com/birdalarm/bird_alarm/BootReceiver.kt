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
 * 收 LOCKED_BOOT_COMPLETED：引擎 prefs 在设备加密存储里（[nativePrefs]），开机后还没解锁就能补排。
 * 解锁后还会再来一次 BOOT_COMPLETED，重排一遍无害。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        // 进程没了，铃也就停了。留着 ringing_asset 的话打开 App 会弹出没有声音的响铃页，界面层也不肯重排。
        // 本进程正在响就别动：解锁前响起来、响着的时候解锁，这时才收到 BOOT_COMPLETED
        if (!NativeAlarmPlayer.isPlaying()) AlarmControl.stopSound(context)
        val now = System.currentTimeMillis()
        armNextUpcoming(context, now)
        val snoozeUntil = nativePrefs(context)
            .getLong(AlarmSoundService.SNOOZE_UNTIL, 0L)
        if (snoozeUntil > now) armSnooze(context, snoozeUntil)
    }

    private companion object {
        val ACTIONS = setOf(Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
