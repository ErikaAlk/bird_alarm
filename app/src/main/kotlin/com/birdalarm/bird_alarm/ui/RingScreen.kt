package com.birdalarm.bird_alarm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import click.erikaalk.coloroskit.components.CoButton
import click.erikaalk.coloroskit.components.CoButtonType
import click.erikaalk.coloroskit.tokens.CoTokens
import com.birdalarm.bird_alarm.AlarmSoundService
import com.birdalarm.bird_alarm.HapticPattern
import com.birdalarm.bird_alarm.Haptics
import com.birdalarm.bird_alarm.Store
import com.birdalarm.bird_alarm.hhmm
import com.birdalarm.bird_alarm.lastOccurrence
import java.time.LocalDateTime
import kotlin.math.abs

/** 触发距离取得比较大：半梦半醒时手在屏幕上蹭一下不该把闹钟关掉。别调小。 */
const val SWIPE_THRESHOLD_DP = 120f
const val SWIPE_FLING_DP_S = 900f

enum class Swipe { Dismiss, Snooze, None }

/** 松手时该干什么：上滑（或向上甩）关闭，下滑（或向下甩）贪睡，都不够就弹回。 */
fun swipeOutcome(dragDp: Float, velocityDpS: Float): Swipe = when {
    dragDp <= -SWIPE_THRESHOLD_DP || velocityDpS <= -SWIPE_FLING_DP_S -> Swipe.Dismiss
    dragDp >= SWIPE_THRESHOLD_DP || velocityDpS >= SWIPE_FLING_DP_S -> Swipe.Snooze
    else -> Swipe.None
}

fun passedThreshold(dragDp: Float): Boolean = abs(dragDp) >= SWIPE_THRESHOLD_DP

/**
 * 全屏响铃页：盖住整个应用，显示时间、闹钟标签和正在叫的鸟 + 关闭 / 贪睡。
 * 由 MainActivity 的 showWhenLocked 让它能显示在锁屏之上，不用解锁（CLAUDE.md 锁屏全屏响铃一节）。
 *
 * **盲操手势**：整屏任意位置上滑关闭 / 下滑贪睡。刚睡醒摸黑按，谁也瞄不准按钮，所以手势不挑落点；
 * 越过触发线先震一下，松手才执行，中途划回去就取消。按钮保留，睁眼时照常能点。
 */
@Composable
fun RingScreen(asset: String) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val c = CoTokens.Color
    val sound = remember(asset) { Store.ringingSound(asset) }
    val label = remember(asset) { lastOccurrence(Store.alarms, LocalDateTime.now())?.label ?: "鸟瘾闹钟" }
    val now by rememberMinuteClock()
    var drag by remember { mutableFloatStateOf(0f) } // dp，向上为负
    var passed by remember { mutableStateOf(false) }
    var fired by remember { mutableStateOf(false) }

    // 关闭与贪睡各震一种花样：摸黑操作时，震动是唯一能确认「我刚才到底干了什么」的信号
    fun act(outcome: Swipe) {
        if (fired || outcome == Swipe.None) return
        fired = true
        if (outcome == Swipe.Dismiss) {
            Haptics.vibrate(context, HapticPattern.Dismiss)
            Store.dismissRinging()
        } else {
            Haptics.vibrate(context, HapticPattern.Snooze)
            Store.snoozeRinging()
        }
    }

    fun reset() {
        drag = 0f
        passed = false
    }

    Box(
        Modifier.fillMaxSize().background(c.bg.current).pointerInput(Unit) {
            val tracker = VelocityTracker()
            detectVerticalDragGestures(
                onDragStart = { tracker.resetTracking() },
                onDragEnd = {
                    val v = with(density) { tracker.calculateVelocity().y.toDp().value }
                    val outcome = swipeOutcome(drag, v)
                    if (outcome == Swipe.None) reset() else act(outcome)
                },
                onDragCancel = { reset() },
            ) { change, amount ->
                if (fired) return@detectVerticalDragGestures
                tracker.addPosition(change.uptimeMillis, change.position)
                drag += with(density) { amount.toDp().value }
                val crossed = passedThreshold(drag)
                // 越过 / 退回触发线都震一下：这是盲操时唯一的反馈
                if (crossed != passed) {
                    passed = crossed
                    Haptics.vibrate(context, HapticPattern.Tick)
                }
            }
        },
    ) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SwipeHint(Glyph.ChevronUp, if (passed && drag < 0) "松手关闭闹钟" else "上滑关闭闹钟", progress = (-drag / SWIPE_THRESHOLD_DP).coerceIn(0f, 1f))
            // 内容跟着手指走一点点，手势有「拖得动」的实感
            Column(
                Modifier.weight(1f).fillMaxWidth().graphicsLayer { translationY = drag * 0.35f * density.density },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText(
                    now.toLocalTime().hhmm(),
                    style = CoTokens.Type.displayL.dp().copy(color = c.label1.current, fontWeight = FontWeight(CoTokens.Type.displayM.weight)),
                )
                BasicText(label, style = CoTokens.Type.headlineS.toTextStyle().copy(color = c.label2.current, textAlign = TextAlign.Center))
                Spacer(Modifier.height(48.dp))
                BasicText(
                    sound.cnName, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = CoTokens.Type.headlineL.toTextStyle().copy(color = c.label1.current, textAlign = TextAlign.Center),
                )
                if (sound.source.isNotEmpty()) {
                    BasicText(sound.source, Modifier.padding(top = 4.dp), style = CoTokens.Type.bodyS.toTextStyle().copy(color = c.label2.current, textAlign = TextAlign.Center))
                }
            }
            CoButton("关闭闹钟", { act(Swipe.Dismiss) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            CoButton("贪睡 ${AlarmSoundService.SNOOZE_MINUTES} 分钟", { act(Swipe.Snooze) }, Modifier.fillMaxWidth(), type = CoButtonType.Secondary)
            Spacer(Modifier.height(16.dp))
            SwipeHint(
                Glyph.ChevronDown, if (passed && drag > 0) "松手贪睡" else "下滑贪睡 ${AlarmSoundService.SNOOZE_MINUTES} 分钟",
                progress = (drag / SWIPE_THRESHOLD_DP).coerceIn(0f, 1f),
            )
        }
    }
}

/** 上下两条滑动提示：跟着手指的位移由次要色变成主色、变实。 */
@Composable
private fun SwipeHint(glyph: Glyph, text: String, progress: Float) {
    val c = CoTokens.Color
    val color = lerp(c.label2.current, c.label1.current, progress)
    Row(Modifier.alpha(0.6f + 0.4f * progress).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(glyph, color, size = 20.dp)
        Spacer(Modifier.width(6.dp))
        BasicText(text, style = CoTokens.Type.bodyM.toTextStyle().copy(color = color))
    }
}
