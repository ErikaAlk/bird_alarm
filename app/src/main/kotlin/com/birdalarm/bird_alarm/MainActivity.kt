package com.birdalarm.bird_alarm

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.lifecycleScope
import click.erikaalk.coloroskit.CoTheme
import com.birdalarm.bird_alarm.ui.BirdAlarmApp
import com.birdalarm.bird_alarm.ui.BirdThemeColor
import kotlinx.coroutines.launch

/**
 * 唯一的 Activity。锁屏全屏响铃 = 这个 Activity（manifest 里 showWhenLocked）+ 应用内响铃页，
 * **不靠从后台启动新 Activity**（targetSdk 34+ 的 BAL 会拦）。响铃页显示与否只看原生 prefs 的 ringing_asset，
 * 这里监听它的变化交给 Store；另外负责响铃时点亮屏幕、响完释放「屏幕常亮」。
 */
class MainActivity : ComponentActivity() {
    private val enginePrefs by lazy { nativePrefs(this) }

    // 同一进程里 AlarmReceiver / AlarmSoundService 写 prefs 会回调到这里（主线程）。
    // SharedPreferences 只弱引用监听器，所以放在字段里
    private val ringingListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || key == "ringing_asset") Store.onRinging(prefs.getString("ringing_asset", null))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val launchedByAlarm = intent?.getBooleanExtra("launch_alarm", false) == true || AlarmControl.hasPendingLaunch(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Store.init(this)
        enginePrefs.registerOnSharedPreferenceChangeListener(ringingListener)
        Store.onRinging(AlarmControl.ringingAsset(this))
        AlarmControl.clearPendingLaunch(this)
        if (launchedByAlarm && Store.ringingAsset != null) prepareAlarmWindow()
        // 缺的权限每次打开引导一个（响铃时不弹，免得设置页盖住响铃页）
        if (Store.ringingAsset == null && savedInstanceState == null) Permissions.requestMissing(this)

        lifecycleScope.launch {
            snapshotFlow { Store.ringingAsset != null }.collect { ringing -> if (ringing) prepareAlarmWindow() else releaseAlarmWindow() }
        }
        setContent {
            val dark = when (Store.settings.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            // 状态栏图标颜色跟着应用自己的深浅走，不跟系统
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(style, style)
            }
            CoTheme(dark = dark, themeColor = BirdThemeColor) { BirdAlarmApp() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Store.onRinging(AlarmControl.ringingAsset(this))
        if (intent.getBooleanExtra("launch_alarm", false)) {
            AlarmControl.clearPendingLaunch(this)
            if (Store.ringingAsset != null) prepareAlarmWindow()
        }
    }

    override fun onResume() {
        super.onResume()
        Store.onRinging(AlarmControl.ringingAsset(this))
        Store.refreshOnResume()
    }

    override fun onStop() {
        super.onStop()
        // 退到后台 / 熄屏且没在响：顺手清掉可能残留的「屏幕常亮」（双保险）
        if (Store.ringingAsset == null) releaseAlarmWindow()
    }

    override fun onDestroy() {
        enginePrefs.unregisterOnSharedPreferenceChangeListener(ringingListener)
        super.onDestroy()
    }

    // 响铃页显示在锁屏之上、并点亮屏幕。不调 requestDismissKeyguard：不用解锁就能关闭 / 贪睡
    private fun prepareAlarmWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON)
    }

    /**
     * 响完释放「屏幕常亮」。**省电关键**：FLAG_KEEP_SCREEN_ON 配合 showWhenLocked 会让本应用整夜强制亮屏。
     * 只清这两个 flag，**绝不动 setShowWhenLocked / setTurnScreenOn**：那是锁屏全屏响铃的命脉，清了下一次会被 BAL 拦掉。
     * 原生仍在响（新一轮刚开始）就不释放。
     */
    private fun releaseAlarmWindow() {
        if (AlarmControl.ringingAsset(this) != null) return
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON)
    }

    companion object {
        // 「已守护」常驻通知（普通通知，非前台服务）。id 与响铃(1001)/倒计时(1010)错开。
        const val GUARD_NOTIFICATION_ID = 1011
        const val GUARD_CHANNEL_ID = "bird_alarm_guard"
        const val GUARD_CONTENT_REQUEST_CODE = 1012
        const val CADENCE_READ_PERMISSION = "click.erikaalk.cadence.permission.READ_SCHEDULE"
    }
}
