package com.birdalarm.bird_alarm.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import click.erikaalk.coloroskit.coThemeColor
import click.erikaalk.coloroskit.components.CoBarAction
import click.erikaalk.coloroskit.components.CoBottomSheet
import click.erikaalk.coloroskit.components.CoCard
import click.erikaalk.coloroskit.components.CoCardPosition
import click.erikaalk.coloroskit.components.CoCategoryTitle
import click.erikaalk.coloroskit.components.CoListItem
import click.erikaalk.coloroskit.components.CoMenuItem
import click.erikaalk.coloroskit.components.CoPanelTitleBar
import click.erikaalk.coloroskit.components.CoPopupMenu
import click.erikaalk.coloroskit.components.CoTextField
import click.erikaalk.coloroskit.components.CoTrailing
import click.erikaalk.coloroskit.components.LocalCoBottomSheetClose
import click.erikaalk.coloroskit.tokens.CoTokens
import click.erikaalk.coloroskit.tokens.LocalCoDark
import com.birdalarm.bird_alarm.AlarmPermission
import com.birdalarm.bird_alarm.BirdAlarmAssets
import com.birdalarm.bird_alarm.BuildConfig
import com.birdalarm.bird_alarm.Permissions
import com.birdalarm.bird_alarm.Store
import com.birdalarm.bird_alarm.ThemeMode
import java.net.URI

private val L = CoTokens.List

enum class SettingsSheet { Webhook, ApiKey }

@Composable
fun SettingsScreen(bottomExtra: Dp, onOpenPermissions: () -> Unit, onOpenAbout: () -> Unit) {
    val s = Store.settings
    var sheet by rememberSaveable { mutableStateOf<SettingsSheet?>(null) }
    BirdPage("设置", bottomExtra = bottomExtra) {
        item(key = "look") {
            CardGroup {
                row { p -> MenuRow("深色模式", p, s.themeMode.label, ThemeMode.entries.map { m -> CoMenuItem(m.label, checked = m == s.themeMode) { Store.setThemeMode(m) } }) }
            }
        }
        groupGap("ringGap")
        item(key = "ring") {
            CardGroup {
                row { p -> CoListItem("闹铃渐响", p, summary = "闹钟响铃时逐渐增大至设定音量", trailing = CoTrailing.Switch(s.fadeInSeconds > 0) { Store.setFadeInEnabled(it) }) }
                if (s.fadeInSeconds > 0) row { p ->
                    MenuRow("渐响时长", p, "${s.fadeInSeconds} 秒", listOf(10, 30, 60).map { v -> CoMenuItem("$v 秒", checked = v == s.fadeInSeconds) { Store.setFadeIn(v) } })
                }
            }
        }
        groupGap("lightGap")
        item(key = "light") {
            CardGroup {
                // 数据要离开设备：写清发什么、发给谁（DESIGN §10）
                row { p -> CoListItem("联动 HA 光闹钟", p, summary = "把接下来几次的亮灯时间发给 Home Assistant", trailing = CoTrailing.Switch(s.lightSync) { Store.setLightSync(it) }) }
                if (s.lightSync) row { p ->
                    val host = runCatching { URI(s.lightWebhook).host }.getOrNull()
                    CoListItem(
                        "webhook 地址", p, summary = Store.lightStatus.ifEmpty { null },
                        trailing = CoTrailing.Status(host ?: "未填写", arrow = false), onClick = { sheet = SettingsSheet.Webhook },
                    )
                }
            }
        }
        groupGap("xenoGap")
        item(key = "xeno") {
            CardGroup {
                row { p ->
                    CoListItem(
                        "xeno-canto API Key", p, trailing = CoTrailing.Status(if (s.xenoApiKey.isEmpty()) "未填写" else "已填写", arrow = false),
                        onClick = { sheet = SettingsSheet.ApiKey },
                    )
                }
            }
        }
        groupGap("systemGap")
        item(key = "system") {
            CardGroup {
                row { p -> CoListItem("权限自检", p, trailing = CoTrailing.Arrow, onClick = onOpenPermissions) }
                // 就地执行的动作：行尾写“运行”，不用箭头（箭头意味着进下一页）
                row { p ->
                    CoListItem("测试闹钟", p, summary = "10 秒后响一次", trailing = CoTrailing.Custom {
                        BasicText("运行", style = L.summary.toTextStyle().copy(color = coThemeColor.primaryText.current))
                    }, onClick = { Store.testAlarm() })
                }
            }
        }
        groupGap("aboutGap")
        item(key = "about") {
            CardGroup { row { p -> CoListItem("关于鸟瘾闹钟", p, trailing = CoTrailing.Status("v" + BuildConfig.VERSION_NAME), onClick = onOpenAbout) } }
        }
    }
    when (sheet) {
        SettingsSheet.Webhook -> TextSheet(
            title = "webhook 地址", initial = s.lightWebhook, label = "https://…/api/webhook/…", keyboardType = KeyboardType.Uri,
            footer = "每次排闹钟都会把接下来几次的亮灯时间发到这个地址，由 HA 写进灯里",
            validate = { v -> if (v.isBlank() || runCatching { URI(v.trim()) }.getOrNull()?.let { it.scheme in listOf("http", "https") && !it.host.isNullOrEmpty() } == true) null else "地址要以 http:// 或 https:// 开头" },
            onSave = Store::setWebhook, onDismiss = { sheet = null },
        )
        SettingsSheet.ApiKey -> TextSheet(
            title = "xeno-canto API Key", initial = s.xenoApiKey, label = "API Key", secret = true,
            footer = "在 xeno-canto.org 注册后，个人页面里有免费的 Key。不填也能搜索，但请求次数有限",
            onSave = Store::setApiKey, onDismiss = { sheet = null },
        )
        null -> Unit
    }
}

/** 点开弹出菜单的一行：右侧当前值 + 上下箭头，菜单从这一行弹出。 */
@Composable
private fun MenuRow(title: String, position: CoCardPosition, value: String, items: List<CoMenuItem>) {
    var open by remember { mutableStateOf(false) }
    Box {
        CoListItem(title, position, trailing = CoTrailing.Menu(value), onClick = { open = true })
        // 锚点放在行尾的当前值上：锚在整行上的话，菜单右缘会对到屏幕边缘外
        Box(Modifier.align(Alignment.CenterEnd).padding(end = L.cardMarginH + L.paddingH)) { CoPopupMenu(open, { open = false }, items) }
    }
}

/** 单个文本输入的面板：顶栏“取消 / 保存”，校验失败写在输入框下面（面板是独立窗口，页面上的提示会被它挡住）。 */
@Composable
private fun TextSheet(
    title: String,
    initial: String,
    label: String,
    footer: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    validate: (String) -> String? = { null },
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    CoBottomSheet(onDismissRequest = onDismiss) {
        val close = LocalCoBottomSheetClose.current
        CoPanelTitleBar(
            title,
            dismiss = CoBarAction("取消", onClick = close, text = "取消"),
            confirm = CoBarAction("保存", text = "保存", onClick = {
                error = validate(value)
                if (error == null) {
                    onSave(value)
                    close()
                }
            }),
        )
        Column(Modifier.imePadding().padding(bottom = L.listBottomPadding)) {
            CoCard(contentPadding = PaddingValues(horizontal = L.paddingH)) {
                CoTextField(
                    value, { value = it; error = null }, label = label, error = error,
                    keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboardType, imeAction = ImeAction.Done),
                    visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                )
            }
            Footer(footer)
        }
    }
}

/**
 * 权限自检：逐项列出状态，缺的点按去开。**全都开了也要看得见**：旧版权限齐全时按钮什么都不做，用户以为坏了。
 * 从系统设置页回来自动重查。
 */
@Composable
fun PermissionScreen(onBack: () -> Unit) {
    val activity = LocalActivity.current ?: return
    var version by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        version++
        onPauseOrDispose { }
    }
    val status = remember(version) { Permissions.status(activity) }
    val missing = status.count { !it.value }
    BirdPage("权限自检", onBack = onBack) {
        item(key = "status") { StatusLine(if (missing == 0) "所需权限已全部开启" else "有 $missing 项未开启") }
        item(key = "list") {
            CardGroup {
                AlarmPermission.entries.forEach { perm ->
                    row { p ->
                        val granted = status[perm] == true
                        CoListItem(
                            perm.title, p, summary = perm.purpose,
                            trailing = if (granted) CoTrailing.Status("已开启", arrow = false) else CoTrailing.Status("去开启"),
                            onClick = if (granted) null else ({ Permissions.open(activity, perm) }),
                        )
                    }
                }
            }
        }
        groupGap("detailsGap")
        item(key = "details") {
            CardGroup { row { p -> ExternalRow("应用详情", p) { Permissions.open(activity, null) } } }
        }
        item(key = "footer") { Footer("部分系统还有“自启动”“后台弹出界面”“锁屏显示”等开关，应用查不到它们的状态，需要在应用详情里确认一次") }
    }
}

/** 跳到应用外（浏览器、系统设置）的一行：行尾是外链图标，不是进下一页的箭头。 */
@Composable
private fun ExternalRow(title: String, position: CoCardPosition, summary: String? = null, onClick: () -> Unit) {
    CoListItem(title, position, summary = summary, trailing = CoTrailing.Custom { GlyphIcon(Glyph.External, CoTokens.Color.label3.current, size = 20.dp) }, onClick = onClick)
}

/** 关于：版本与来源、原作者致谢与联系方式。改这一页务必保留原作者致谢与免责说明。 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    val c = CoTokens.Color
    BirdPage("关于", onBack = onBack) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(vertical = L.groupTop), horizontalAlignment = Alignment.CenterHorizontally) {
                CartoonBird(LocalCoDark.current, Modifier.size(96.dp))
                Spacer(Modifier.height(8.dp))
                BasicText("鸟瘾闹钟", style = CoTokens.Type.headlineM.toTextStyle().copy(color = c.label1.current))
                RowText(metaText(listOf("v" + BuildConfig.VERSION_NAME, "ErikaAlk fork")))
            }
        }
        item(key = "source") {
            CardGroup {
                row { p -> ExternalRow("本 fork 源码", p, "ErikaAlk/bird_alarm") { open("https://github.com/ErikaAlk/bird_alarm") } }
                row { p -> ExternalRow("原作者项目", p, "oastwy/bird_alarm") { open("https://github.com/oastwy/bird_alarm") } }
            }
        }
        item(key = "fork") {
            Footer(
                "这是 ErikaAlk 基于原作者 oastwy 的“鸟瘾闹钟”做的个人自用 fork，所有改动与原作者无关。" +
                    "在原版基础上去掉了强制认鸟挑战，新增锁屏直接关闹钟、按中国工作日和休息日重复、课表闹钟、闹铃渐响、深色模式、每日一鸟、" +
                    "闹钟与下载的 Live Updates，修复了锁屏和息屏响铃与整夜耗电等问题；2.0 起界面改用原生 Kotlin 重写。",
            )
        }
        item(key = "aboutTitle") { CoCategoryTitle("关于原作者") }
        item(key = "aboutText") {
            CoCard {
                BodyText("鸟瘾综合征，是一种听见树上有动静就想抬头、看见电线杆就想数鸟、早晨醒来先判断窗外是哪种叫声的温和症状。")
                Spacer(Modifier.height(L.paddingV))
                BodyText("我们把鸟鸣、闹钟和识鸟挑战放在一起，希望每次醒来都不是被噪音拽起来，而是被一只随机出现的鸟叫醒。")
            }
        }
        item(key = "thanksTitle") { CoCategoryTitle("致谢") }
        item(key = "thanks") {
            CardGroup {
                BirdAlarmAssets.starters.forEach { sound ->
                    row { p -> CoListItem(sound.cnName, p, trailing = CoTrailing.Status("XC" + sound.source.substringAfter('#'), arrow = false)) }
                }
            }
        }
        item(key = "thanksFooter") {
            Footer(
                "内置鸟鸣来自 xeno-canto，一个由全球鸟友共同维护的野生鸟声共享平台，感谢以上录音的上传者，所有录音均按 Creative Commons 授权使用。" +
                    "“每日一鸟”的照片来自 iNaturalist 与 Wikimedia Commons，只取 CC 授权的图片，作者与许可证标在卡片上。鸟种名录来自 IOC / AviList。",
            )
        }
        item(key = "contactTitle") { CoCategoryTitle("原作者的频道") }
        item(key = "contact") {
            CardGroup {
                row { p -> ExternalRow("小宇宙", p) { open("https://www.xiaoyuzhoufm.com/podcast/6688a873ae8e21859ade308b") } }
                row { p -> ExternalRow("小红书", p) { open("https://www.xiaohongshu.com/user/profile/6516e3ef00000000240167e9") } }
                row { p -> ExternalRow("B站", p) { open("https://space.bilibili.com/3546850323860358") } }
            }
        }
        item(key = "contactFooter") {
            Footer("原作者在小红书、B站、小宇宙、抖音和微博等平台全网同名，联系方式：birderrrr@gmail.com，微信 hotpeaker。本 fork 的问题请到上方 GitHub 反馈，勿打扰原作者")
        }
    }
}

@Composable
private fun BodyText(text: String) {
    BasicText(text, style = CoTokens.Type.bodyM.toTextStyle().copy(color = CoTokens.Color.label1.current, textAlign = TextAlign.Start))
}
