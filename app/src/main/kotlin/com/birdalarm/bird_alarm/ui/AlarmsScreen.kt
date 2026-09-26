package com.birdalarm.bird_alarm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import click.erikaalk.coloroskit.CoText
import click.erikaalk.coloroskit.coThemeColor
import click.erikaalk.coloroskit.components.CoBarAction
import click.erikaalk.coloroskit.components.CoBottomSheet
import click.erikaalk.coloroskit.components.CoButton
import click.erikaalk.coloroskit.components.CoButtonType
import click.erikaalk.coloroskit.components.CoCard
import click.erikaalk.coloroskit.components.CoCardPosition
import click.erikaalk.coloroskit.components.CoCardRow
import click.erikaalk.coloroskit.components.CoEmptyState
import click.erikaalk.coloroskit.components.CoMenuItem
import click.erikaalk.coloroskit.components.CoNumberPicker
import click.erikaalk.coloroskit.components.CoPanelTitleBar
import click.erikaalk.coloroskit.components.CoPopupMenu
import click.erikaalk.coloroskit.components.CoSeekBar
import click.erikaalk.coloroskit.components.CoSegmentedButton
import click.erikaalk.coloroskit.components.CoSwitch
import click.erikaalk.coloroskit.components.CoTextField
import click.erikaalk.coloroskit.components.LocalCoBottomSheetClose
import click.erikaalk.coloroskit.tokens.CoTokens
import com.birdalarm.bird_alarm.BirdAlarm
import com.birdalarm.bird_alarm.RepeatRule
import com.birdalarm.bird_alarm.Store
import com.birdalarm.bird_alarm.hhmm
import com.birdalarm.bird_alarm.nextRingStatus
import com.birdalarm.bird_alarm.repeatText
import com.birdalarm.bird_alarm.weekdayShort
import com.birdalarm.bird_alarm.weekdaysText
import java.time.LocalTime

private val L = CoTokens.List

/** 独立卡片列表（闹钟）每张 86dp，卡间 12dp（DESIGN §6）。 */
private val AlarmCardHeight = 86.dp
private val AlarmCardGap = 12.dp

/** 判定「双击」的时间窗口。星期格自己按时间戳判双击，单击零延迟（onDoubleTap 会让单击等 300ms 才生效）。 */
private const val DOUBLE_TAP_MS = 320L

@Composable
fun AlarmsScreen(bottomExtra: Dp, onEdit: (BirdAlarm) -> Unit) {
    val now by rememberMinuteClock()
    // 按响铃时间排；时间相同按 id（创建先后）
    val alarms = Store.alarms.sortedWith(compareBy({ it.time }, { it.id }))
    val status = remember(now, Store.alarms, Store.calendarVersion) { nextRingStatus(now, Store.nextRing(now)) }
    BirdPage("闹钟", bottomExtra = bottomExtra) {
        item(key = "status") { StatusLine(status) }
        if (alarms.isEmpty()) item(key = "empty") { CoEmptyState("暂无闹钟") }
        items(alarms, key = { it.id }) { alarm ->
            AlarmCard(alarm, onEdit)
            Spacer(Modifier.height(AlarmCardGap))
        }
    }
}

/**
 * 闹钟卡片：大号时间 + 「重复 ｜ 标签」+ 开关。关闭的闹钟文字降到 30%（DESIGN §11.1）。
 * 删除走**长按**弹出菜单或编辑面板里的删除，**别做成左滑**：整页要留给左右滑动切页，左滑删除会抢手势。
 */
@Composable
private fun AlarmCard(alarm: BirdAlarm, onEdit: (BirdAlarm) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val meta = buildList {
        add(repeatText(alarm))
        if (alarm.rule == RepeatRule.ClassSchedule) add("无早八 ${alarm.noEarlyTime.hhmm()}")
        add(alarm.label)
    }
    Box {
        CoCardRow(CoCardPosition.Full, onClick = { onEdit(alarm) }, onLongClick = { menu = true }) {
            // 卡片行自带上下 10dp + 首尾各 2dp 内边距，内容再撑到整张卡 86dp
            Column(
                Modifier.weight(1f).heightIn(min = AlarmCardHeight - (L.paddingV + L.edgePadding) * 2).alpha(if (alarm.enabled) 1f else 0.3f),
                verticalArrangement = Arrangement.Center,
            ) {
                BasicText(alarm.time.hhmm(), style = CoTokens.Type.displayM.dp().copy(color = CoTokens.Color.label1.current))
                RowText(metaText(meta), maxLines = 1)
            }
            Spacer(Modifier.size(L.switchGap))
            // 设计库的 CoSwitch 点按时不消费抬手事件，卡片的点击会跟着触发：一点开关就连编辑面板一起打开，
            // 而面板底部的“删除闹钟”正好在底栏位置，连点两下就误删了（2026-09-26 模拟器上复现）。
            // 这里在开关外面把抬手吃掉；库里的修复见 coloros-ui-kit#14，合进去之后可以去掉
            Box(Modifier.pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); waitForUpOrCancellation()?.consume() } }) {
                CoSwitch(alarm.enabled, { Store.setEnabled(alarm.id, it) })
            }
        }
        // 锚点放在卡片内侧右下：锚在整张卡上时菜单会贴到屏幕边缘外
        Box(Modifier.align(Alignment.BottomEnd).padding(end = L.cardMarginH + L.paddingH)) {
            CoPopupMenu(menu, { menu = false }, listOf(CoMenuItem("删除") { Store.deleteAlarm(alarm.id) }))
        }
    }
}

/**
 * 新建 / 编辑闹钟的面板。多字段表单，明确提交：顶栏“取消 / 完成”（DESIGN §11.2）。
 * 闹钟是随手能重建的对象，删除不再确认（DESIGN §11.4）。
 */
@Composable
fun AlarmEditorSheet(existing: BirdAlarm?, onDismiss: () -> Unit, onSaved: (BirdAlarm) -> Unit) {
    val start = existing?.time ?: LocalTime.now().withSecond(0).withNano(0)
    var hour by rememberSaveable { mutableIntStateOf(start.hour) }
    var minute by rememberSaveable { mutableIntStateOf(start.minute) }
    var noEarlyHour by rememberSaveable { mutableIntStateOf((existing?.noEarlyTime ?: BirdAlarm.DEFAULT_NO_EARLY).hour) }
    var noEarlyMinute by rememberSaveable { mutableIntStateOf((existing?.noEarlyTime ?: BirdAlarm.DEFAULT_NO_EARLY).minute) }
    var rule by rememberSaveable { mutableStateOf(existing?.rule ?: RepeatRule.Weekdays) }
    var days by rememberSaveable { mutableStateOf(existing?.repeatDays?.toList().orEmpty()) }
    var lead by rememberSaveable { mutableIntStateOf(existing?.lightLeadMinutes ?: BirdAlarm.DEFAULT_LIGHT_LEAD) }
    var label by rememberSaveable { mutableStateOf(existing?.label ?: BirdAlarm.DEFAULT_LABEL) }

    CoBottomSheet(onDismissRequest = onDismiss) {
        val close = LocalCoBottomSheetClose.current
        CoPanelTitleBar(
            if (existing == null) "新建闹钟" else "编辑闹钟",
            dismiss = CoBarAction("取消", onClick = close, text = "取消"),
            confirm = CoBarAction("完成", text = "完成", onClick = {
                onSaved(
                    BirdAlarm(
                        id = existing?.id ?: "${System.currentTimeMillis() * 1000}",
                        time = LocalTime.of(hour, minute),
                        repeatDays = days.toSet(),
                        rule = rule,
                        enabled = existing?.enabled ?: true,
                        label = label.trim().ifEmpty { BirdAlarm.DEFAULT_LABEL },
                        lightLeadMinutes = lead,
                        noEarlyTime = LocalTime.of(noEarlyHour, noEarlyMinute),
                    ),
                )
                close()
            }),
        )
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(bottom = L.listBottomPadding)) {
            // 时间直接用滚轮，少一次弹窗。「课表」一个闹钟两个时间：早八那天响左边，其余工作日响右边
            if (rule == RepeatRule.ClassSchedule) {
                Row(Modifier.fillMaxWidth().padding(horizontal = L.cardMarginH)) {
                    TimeWheel("早八", hour, minute, { hour = it }, { minute = it }, Modifier.weight(1f))
                    TimeWheel("无早八", noEarlyHour, noEarlyMinute, { noEarlyHour = it }, { noEarlyMinute = it }, Modifier.weight(1f))
                }
            } else {
                TimeWheel(null, hour, minute, { hour = it }, { minute = it }, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(L.groupTop))
            CoCard {
                CoSegmentedButton(RepeatRule.entries.map { it.label }, rule.ordinal, { rule = RepeatRule.entries[it] }, Modifier.fillMaxWidth())
                if (rule == RepeatRule.Weekdays) {
                    Spacer(Modifier.height(L.paddingV))
                    WeekdayPicker(days.toSet()) { days = it.sorted() }
                }
                Spacer(Modifier.height(L.paddingV))
                RowText(metaText(listOf(ruleDetail(rule, days.toSet()))))
            }
            if (Store.settings.lightSync) {
                Spacer(Modifier.height(L.groupTop))
                CoCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RowTitle("光闹钟提前亮起", Modifier.weight(1f))
                        RowText(metaText(listOf("$lead 分钟")))
                    }
                    Spacer(Modifier.height(L.paddingV))
                    CoSeekBar(lead / 30f, { lead = Math.round(it * 30) }, steps = 30)
                }
            }
            Spacer(Modifier.height(L.groupTop))
            CoCard(contentPadding = PaddingValues(horizontal = L.paddingH)) {
                CoTextField(label, { label = it }, label = "标签", keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
            }
            if (existing != null) {
                Spacer(Modifier.height(L.groupTop))
                CoButton(
                    "删除闹钟", { Store.deleteAlarm(existing.id); close() },
                    Modifier.fillMaxWidth().padding(horizontal = L.cardMarginH),
                    type = CoButtonType.Secondary, textColor = CoTokens.Color.error.current,
                )
            }
        }
    }
}

/**
 * 重复规则下面那一行：自定义规则写当前选了哪几天；其余三个并列选项各写一句差异（DESIGN §9）。
 * 「课表」是新概念，只在这里说一次。
 */
private fun ruleDetail(rule: RepeatRule, days: Set<Int>): String = when (rule) {
    RepeatRule.Weekdays -> weekdaysText(days)
    RepeatRule.ChinaWorkdays -> "周末和法定节假日不响，调休补班日照常响"
    RepeatRule.ChinaHolidays -> "周末和法定节假日响，调休补班日不响"
    RepeatRule.ClassSchedule -> "早八那天响左边，其余工作日响右边，读不到课表时按早八"
}

@Composable
private fun TimeWheel(title: String?, hour: Int, minute: Int, onHour: (Int) -> Unit, onMinute: (Int) -> Unit, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        title?.let { CoText(it, style = CoTokens.Type.bodyS, color = CoTokens.Color.label2.current) }
        // 宽度要明确给：滚轮是 Canvas，只靠组件自带的 widthIn(min) 时实际绘制宽度是 0，数字全被裁掉（设计库的 bug）
        val wheel = Modifier.width(CoTokens.Picker.minWidth)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            CoNumberPicker(hour, onHour, 0..23, wheel, wrap = true, format = { "%02d".format(it) })
            CoText(":", style = CoTokens.Type.headlineM)
            CoNumberPicker(minute, onMinute, 0..59, wheel, wrap = true, format = { "%02d".format(it) })
        }
    }
}

/**
 * 星期格：七格等宽、尺寸固定，选中只换底色和字色（选中改变尺寸会把后面的格子挤走）。
 * 单击立刻切换这一天；320ms 内再点同一天算双击 → 全选，已经全选则清空。判断「已全选」要看
 * **第一下之前**的选择：第一下单击已经改过一次，看当前状态的话全选时永远清不掉。
 */
@Composable
private fun WeekdayPicker(selected: Set<Int>, onChange: (Set<Int>) -> Unit) {
    var lastTapAt by remember { mutableLongStateOf(0L) }
    var lastDay by remember { mutableIntStateOf(0) }
    var beforeLastTap by remember { mutableStateOf(emptySet<Int>()) }
    val primary = coThemeColor.primary.current
    Row(Modifier.fillMaxWidth()) {
        for (day in 1..7) {
            val on = day in selected
            Box(
                Modifier.weight(1f).height(48.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Checkbox) {
                        val now = System.currentTimeMillis()
                        if (day == lastDay && now - lastTapAt < DOUBLE_TAP_MS) {
                            lastTapAt = 0L
                            onChange(if (beforeLastTap.size == 7) emptySet() else (1..7).toSet())
                        } else {
                            lastTapAt = now
                            lastDay = day
                            beforeLastTap = selected
                            onChange(if (on) selected - day else selected + day)
                        }
                    }
                    .semantics { this.selected = on; contentDescription = "周" + weekdayShort(day) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(if (on) primary else CoTokens.Chip.bg.current),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        weekdayShort(day),
                        style = CoTokens.Type.buttonM.toTextStyle().copy(
                            color = if (on) CoTokens.Color.onPrimary.current else CoTokens.Chip.text.current,
                            fontWeight = FontWeight(CoTokens.Type.buttonM.weight),
                        ),
                    )
                }
            }
        }
    }
}
