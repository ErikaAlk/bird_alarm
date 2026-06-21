package com.birdalarm.bird_alarm

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class AlarmRingActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prepareLockscreenWindow()
        showAlarmUi()
        NativeAlarmPlayer.start(this)
    }

    private fun prepareLockscreenWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            // 故意不调用 requestDismissKeyguard：让响铃页停留在锁屏之上，
            // 用户无需解锁即可在本页直接关闭 / 贪睡闹钟。
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )
    }

    private fun currentBirdName(): String {
        val prefs = getSharedPreferences("bird_alarm_native", Context.MODE_PRIVATE)
        return BirdAlarmAssets.cnNameFor(prefs.getString("ringing_asset", null))
    }

    private fun showAlarmUi() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val bgColor = if (night) Color.rgb(18, 22, 26) else Color.rgb(255, 245, 223)
        val titleColor = if (night) Color.rgb(225, 235, 233) else Color.rgb(22, 74, 69)
        val subColor = if (night) Color.rgb(176, 188, 184) else Color.rgb(60, 51, 36)
        val accent = Color.rgb(29, 124, 118)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(56, 56, 56, 56)
            setBackgroundColor(bgColor)
        }
        val title = TextView(this).apply {
            text = "🐦 鸟瘾闹钟"
            textSize = 30f
            setTextColor(titleColor)
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "正在叫的是「${currentBirdName()}」"
            textSize = 21f
            setTextColor(subColor)
            gravity = Gravity.CENTER
            setPadding(0, 22, 0, 44)
        }
        val stopButton = Button(this).apply {
            text = "关闭闹钟"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(accent)
            }
            setPadding(0, 28, 0, 28)
            setOnClickListener { stopAlarm() }
        }
        val snoozeButton = Button(this).apply {
            text = "贪睡 5 分钟"
            textSize = 16f
            setTextColor(accent)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.TRANSPARENT)
                setStroke(3, accent)
            }
            setPadding(0, 24, 0, 24)
            setOnClickListener { snoozeAlarm() }
        }
        val spacer = TextView(this).apply { setPadding(0, 14, 0, 0) }

        val buttonParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        root.addView(title)
        root.addView(subtitle)
        root.addView(stopButton, buttonParams)
        root.addView(spacer)
        root.addView(
            snoozeButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(root)
    }

    private fun stopAlarm() {
        startService(
            Intent(this, AlarmSoundService::class.java).setAction(AlarmSoundService.ACTION_STOP)
        )
        NativeAlarmPlayer.stop(this)
        finish()
    }

    private fun snoozeAlarm() {
        startService(
            Intent(this, AlarmSoundService::class.java).setAction(AlarmSoundService.ACTION_SNOOZE)
        )
        finish()
    }
}
