package com.birdalarm.bird_alarm.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import click.erikaalk.coloroskit.components.CoFloatingButton
import click.erikaalk.coloroskit.components.CoFloatingNavigationBar
import click.erikaalk.coloroskit.components.CoNavItem
import click.erikaalk.coloroskit.components.CoSnackBarHost
import click.erikaalk.coloroskit.tokens.CoTokens
import com.birdalarm.bird_alarm.MainActivity
import com.birdalarm.bird_alarm.Permissions
import com.birdalarm.bird_alarm.RepeatRule
import com.birdalarm.bird_alarm.Store
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val glyph: Glyph) {
    Alarms("闹钟", Glyph.Alarm),
    Library("鸟鸣", Glyph.Bird),
    Settings("设置", Glyph.Settings),
}

/** 二级页面。都从一级页面往下进，一根栈就够。 */
enum class Route { Xeno, Permissions, About }

private val N = CoTokens.FloatingNavBar

/** 一级页面给悬浮底栏让出的高度（DESIGN §6：栏高 + 8dp，系统导航条和末尾 32dp 由 BirdPage 加）。 */
private val TabBottom = N.height + N.toNavBar

/** 悬浮按钮在底栏上方右侧，和底栏隔一个底栏上边距。 */
private val FabBottom = N.height + N.toNavBar + N.barPaddingTop

@Composable
fun BirdAlarmApp() {
    val ringing = Store.ringingAsset
    val holder = rememberSaveableStateHolder()
    var stack by rememberSaveable { mutableStateOf(listOf<Route>()) }
    // 正在编辑的闹钟：id，新建时为 ""
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val pager = rememberPagerState { Tab.entries.size }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val push: (Route) -> Unit = { stack = stack + it }
    val pop: () -> Unit = {
        stack.lastOrNull()?.let { holder.removeState(it.name) }
        stack = stack.dropLast(1)
    }
    // 读课表是 Cadence 定义的 dangerous 权限。弹窗关掉回到前台时 refreshOnResume 会重读课表
    val scheduleAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // 开始响铃时收掉编辑面板，响完不再弹回来
    LaunchedEffect(ringing) { if (ringing != null) editing = null }
    BackHandler(enabled = ringing == null && stack.isNotEmpty(), onBack = pop)
    // 响铃中返回键不退出：响铃页只能关闭或贪睡
    BackHandler(enabled = ringing != null) { }

    Box(Modifier.fillMaxSize().background(CoTokens.Color.bgGrouped.current)) {
        if (ringing != null) {
            // 响铃页盖住整个应用。页面（连同面板、弹出菜单这些独立窗口）先拿出组合，否则会压在响铃页上面点不到
            RingScreen(ringing)
        } else {
            val route = stack.lastOrNull()
            holder.SaveableStateProvider(route?.name ?: "tabs") {
                when (route) {
                    null -> HorizontalPager(pager, beyondViewportPageCount = Tab.entries.size - 1) { page ->
                        // 既能点底栏也能左右滑动翻页
                        when (Tab.entries[page]) {
                            Tab.Alarms -> AlarmsScreen(TabBottom + CoTokens.Fab.size + N.barPaddingTop) { editing = it.id }
                            Tab.Library -> LibraryScreen(TabBottom) { push(Route.Xeno) }
                            Tab.Settings -> SettingsScreen(TabBottom, { push(Route.Permissions) }, { push(Route.About) })
                        }
                    }
                    Route.Xeno -> XenoScreen(pop)
                    Route.Permissions -> PermissionScreen(pop)
                    Route.About -> AboutScreen(pop)
                }
            }
            CoFloatingButton(
                onClick = { editing = "" },
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = CoTokens.List.cardMarginH, bottom = FabBottom),
                visible = route == null && pager.targetPage == Tab.Alarms.ordinal,
                contentDescription = "新建闹钟",
            )
            CoFloatingNavigationBar(
                items = Tab.entries.map { t -> CoNavItem(t.label) { selected, tint -> GlyphIcon(t.glyph, tint, selected) } },
                selectedIndex = pager.targetPage,
                onSelect = { scope.launch { pager.animateScrollToPage(it) } },
                visible = route == null,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            CoSnackBarHost(Store.snackbar, Modifier.align(Alignment.BottomCenter))
            editing?.let { id ->
                AlarmEditorSheet(
                    existing = Store.alarms.firstOrNull { it.id == id },
                    onDismiss = { editing = null },
                    onSaved = { alarm ->
                        Store.saveAlarm(alarm)
                        if (alarm.rule == RepeatRule.ClassSchedule && !Permissions.hasScheduleAccess(context)) {
                            scheduleAccess.launch(MainActivity.CADENCE_READ_PERMISSION)
                        }
                    },
                )
            }
        }
    }
}
