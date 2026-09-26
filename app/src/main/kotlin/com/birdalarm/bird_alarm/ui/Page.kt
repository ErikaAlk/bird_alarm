package com.birdalarm.bird_alarm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import click.erikaalk.coloroskit.CoThemeColor
import click.erikaalk.coloroskit.components.CoBackIcon
import click.erikaalk.coloroskit.components.CoBarAction
import click.erikaalk.coloroskit.components.CoCardPosition
import click.erikaalk.coloroskit.components.CoTopBar
import click.erikaalk.coloroskit.components.coScrolledPx
import click.erikaalk.coloroskit.material.CoCircleShape
import click.erikaalk.coloroskit.material.CoPressMask
import click.erikaalk.coloroskit.tokens.CoTokens
import click.erikaalk.coloroskit.tokens.CoTypeStyle
import click.erikaalk.coloroskit.tokens.Themed
import kotlinx.coroutines.delay
import java.time.LocalDateTime

/** 设计规范版本（全局 DESIGN.md 第 17 章）。规范升版要整套重过，改完才动它。 */
const val DESIGN_SYSTEM_REVISION = "2026.09.24-coloros17"

/**
 * 应用主题色：薄荷绿（本项目一直用的颜色）。设计库只收了蓝和橙，这里按 COUI 的比例自己派生（设计库 README）：
 * 容器 15%（暗 25%）、禁用 30%、焦点 12%（暗 20%）、焦点描边取 primaryText 的 40%。
 * 亮色压深到白字对比 3.1:1（和 COUI 橙的 primaryText 同档），primaryText 再深一档给可点文字用。
 * 怎么用见 DESIGN §4：每屏只点一处。
 */
val BirdThemeColor = CoThemeColor(
    primary = Themed(Color(0xFF00A67E), Color(0xFF00B38A)),
    primaryText = Themed(Color(0xFF008F6B), Color(0xFF2EC99E)),
    primaryContainer = Themed(Color(0x2600A67E), Color(0x4000B38A)),
    primaryDisabled = Themed(Color(0x4D00A67E), Color(0x4D00B38A)),
    focus = Themed(Color(0x1F00A67E), Color(0x3300B38A)),
    focusOutline = Themed(Color(0x66008F6B), Color(0x662EC99E)),
)

private val L = CoTokens.List

/**
 * 一页：灰底 + 列表 + 设计库顶栏。列表从顶栏底下滚过去，顶栏背景滚过 30dp 才渐显。
 * [largeTitle] 时标题 32dp 随滚动收成 24dp 落进顶栏（DESIGN §8：首页和设置页用折叠大标题）。
 * [bottomExtra] 是一级页面给悬浮底栏让出的高度；系统导航条和列表末尾的 32dp 这里自己加。
 */
@Composable
fun BirdPage(
    title: String,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    onBack: (() -> Unit)? = null,
    actions: List<CoBarAction> = emptyList(),
    largeTitle: Boolean = true,
    bottomExtra: Dp = 0.dp,
    content: LazyListScope.() -> Unit,
) {
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bar = if (largeTitle) CoTokens.TopBar.largeTitleHeight else CoTokens.TopBar.height
    Box(modifier.fillMaxSize().background(CoTokens.Color.bgGrouped.current)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(top = status + bar, bottom = nav + L.listBottomPadding + bottomExtra),
            content = content,
        )
        CoTopBar(
            title = title,
            modifier = Modifier.align(Alignment.TopCenter),
            navigation = onBack?.let { CoBarAction("返回", onClick = it, icon = { tint -> CoBackIcon(tint) }) },
            actions = actions,
            scrolledPx = { state.coScrolledPx() },
            largeTitle = largeTitle,
            snap = if (largeTitle) state else null,
        )
    }
}

/** 卡片与卡片之间（不带分组标题时）。带标题的间距由 CoCategoryTitle 自己的上边距给。 */
fun LazyListScope.groupGap(key: String) = item(key = key) { Spacer(Modifier.height(L.groupTop)) }

/** 大标题下的状态行（DESIGN §5：14dp、Secondary），和大标题左对齐。 */
@Composable
fun StatusLine(text: String) {
    BasicText(
        text,
        Modifier.fillMaxWidth().padding(horizontal = CoTokens.TopBar.paddingH).padding(bottom = L.categoryMarginV),
        style = CoTokens.Type.bodyM.dp().copy(color = CoTokens.Color.label2.current),
    )
}

/** 页脚说明：卡片外、与卡片里的文字对齐，12sp 次要色。只放隐私、权限、数据上传这类必须写的话（DESIGN §10）。 */
@Composable
fun Footer(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text,
        modifier.fillMaxWidth().padding(start = L.categoryIndent, end = L.categoryIndent, top = L.categoryMarginV),
        style = CoTokens.Type.bodyXS.toTextStyle().copy(color = CoTokens.Color.label2.current),
    )
}

/** 按 dp 排的字：大数字、叠在图片上的字，随系统字号放大会破坏构图（DESIGN §5）。 */
@Composable
fun CoTypeStyle.dp(): TextStyle = with(LocalDensity.current) {
    toTextStyle().copy(fontSize = size.value.dp.toSp(), lineHeight = lineHeight.value.dp.toSp())
}

/** 元信息的分隔符（DESIGN §9：多段用“ ｜ ”连成一行，分隔符再淡一级）。 */
private const val META_SEPARATOR = " ｜ "

@Composable
fun metaText(parts: List<String>): AnnotatedString {
    val separator = CoTokens.Color.label3.current
    return buildAnnotatedString {
        parts.filter { it.isNotEmpty() }.forEachIndexed { i, part ->
            if (i > 0) withStyle(SpanStyle(color = separator)) { append(META_SEPARATOR) }
            append(part)
        }
    }
}

/** 列表行里的标题。 */
@Composable
fun RowTitle(text: String, modifier: Modifier = Modifier) {
    BasicText(text, modifier, maxLines = 2, overflow = TextOverflow.Ellipsis, style = L.title.toTextStyle().copy(color = CoTokens.Color.label1.current))
}

/** 列表行里的摘要、元信息。颜色默认 Secondary。 */
@Composable
fun RowText(text: AnnotatedString, modifier: Modifier = Modifier, maxLines: Int = 2) {
    BasicText(text, modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = L.summary.toTextStyle().copy(color = CoTokens.Color.label2.current))
}

/** 卡片分组里第 [i] 行（共 [n] 行）的位置。 */
fun positionOf(i: Int, n: Int): CoCardPosition = when {
    n == 1 -> CoCardPosition.Full
    i == 0 -> CoCardPosition.Head
    i == n - 1 -> CoCardPosition.Tail
    else -> CoCardPosition.Middle
}

class CardGroupScope {
    internal val rows = mutableListOf<@Composable (CoCardPosition) -> Unit>()
    fun row(content: @Composable (CoCardPosition) -> Unit) {
        rows += content
    }
}

/** 一组卡片行，自动算首中尾。和 CoCardGroup 一样，只是每行可以是任意组合（比如带弹出菜单的行）。 */
@Composable
fun CardGroup(content: CardGroupScope.() -> Unit) {
    val rows = CardGroupScope().apply(content).rows
    Column(Modifier.fillMaxWidth()) { rows.forEachIndexed { i, row -> row(positionOf(i, rows.size)) } }
}

/** 行尾的图标按钮。库外自绘控件，触控区不小于 48dp（DESIGN §16）；按压叠圆形蒙层。 */
@Composable
fun IconButton(glyph: Glyph, contentDescription: String, onClick: () -> Unit, tint: Color = CoTokens.Color.label1.current) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.size(48.dp)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        CoPressMask(interaction, CoCircleShape, L.pressMask, L.pressMaskMinProgress, Modifier.size(40.dp).clip(CoCircleShape))
        GlyphIcon(glyph, tint)
    }
}

/** 当前时间，整分钟跳一次；页面不可见时停。 */
@Composable
fun rememberMinuteClock(): State<LocalDateTime> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(LocalDateTime.now()) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = LocalDateTime.now()
                delay(60_000 - System.currentTimeMillis() % 60_000 + 20)
            }
        }
    }
}
