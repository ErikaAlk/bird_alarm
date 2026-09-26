package com.birdalarm.bird_alarm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import click.erikaalk.coloroskit.tokens.CoTokens

/**
 * 应用自己的图标（设计库不带图标资产，DESIGN §13）。24 格画布、线宽 1.4、圆头；路径取自 Lucide（ISC 许可）。
 * 底栏选中换实心版：[solid] 填充，[cut] 从实心里挖掉（离屏合成），[keep] 在实心版里仍按线画。
 */
enum class Glyph(
    val outline: List<String>,
    val solid: List<String> = emptyList(),
    val cut: List<String> = emptyList(),
    val keep: List<String> = emptyList(),
) {
    Alarm(
        outline = listOf(
            "M4 13a8 8 0 1 0 16 0a8 8 0 1 0 -16 0", "M12 9v4l2 2",
            "M5 3 2 6", "m22 6-3-3", "M6.38 18.7 4 21", "M17.64 18.67 20 21",
        ),
        solid = listOf("M4 13a8 8 0 1 0 16 0a8 8 0 1 0 -16 0"),
        cut = listOf("M12 9v4l2 2"),
        keep = listOf("M5 3 2 6", "m22 6-3-3", "M6.38 18.7 4 21", "M17.64 18.67 20 21"),
    ),
    Bird(
        outline = listOf(
            "M16 7h.01", "M3.4 18H12a8 8 0 0 0 8-8V7a4 4 0 0 0-7.28-2.3L2 20", "m20 7 2 .5-2 .5",
            "M10 18v3", "M14 17.75V21", "M7 18a6 6 0 0 0 3.84-10.61",
        ),
        solid = listOf("M3.4 18H12a8 8 0 0 0 8-8V7a4 4 0 0 0-7.28-2.3L2 20Z"),
        cut = listOf("M16 7h.01", "M7 18a6 6 0 0 0 3.84-10.61"),
        keep = listOf("m20 7 2 .5-2 .5", "M10 18v3", "M14 17.75V21"),
    ),
    Settings(
        outline = listOf(
            "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915",
            "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0",
        ),
        solid = listOf(
            "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915",
        ),
        cut = listOf("M9.2 12a2.8 2.8 0 1 0 5.6 0a2.8 2.8 0 1 0 -5.6 0Z"),
    ),
    Import(outline = listOf("M12 3v12", "m17 8-5-5-5 5", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4")),
    Play(outline = listOf("M6 4.8a1.5 1.5 0 0 1 2.26-1.3l11.1 6.9a1.9 1.9 0 0 1 0 3.2l-11.1 6.9A1.5 1.5 0 0 1 6 19.2Z")),
    Pause(outline = listOf("M14 4h3a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1Z", "M7 4h3a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1Z")),
    Download(outline = listOf("M12 15V3", "m7 10 5 5 5-5", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4")),
    Plus(outline = listOf("M5 12h14", "M12 5v14")),
    External(outline = listOf("M15 3h6v6", "M10 14 21 3", "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6")),
    ChevronUp(outline = listOf("m18 15-6-6-6 6")),
    ChevronDown(outline = listOf("m6 9 6 6 6-6")),
}

/** 线宽（24 格单位）。DESIGN §13：约 1.33～1.4dp。 */
private const val STROKE = 1.4f

@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, selected: Boolean = false, modifier: Modifier = Modifier, size: Dp = CoTokens.TopBar.iconSize) {
    val solid = selected && glyph.solid.isNotEmpty()
    Canvas(
        modifier.size(size)
            .then(if (solid) Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen } else Modifier),
    ) {
        grid {
            val line = Stroke(STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)
            if (!solid) {
                glyph.outline.forEach { drawPath(pathOf(it), tint, style = line) }
                return@grid
            }
            // 实心版：填充 + 同一线宽描一圈，外轮廓和线性版一样大
            glyph.solid.forEach { drawPath(pathOf(it), tint); drawPath(pathOf(it), tint, style = line) }
            glyph.cut.forEach { drawPath(pathOf(it), Color.Black, style = line, blendMode = BlendMode.Clear) }
            glyph.keep.forEach { drawPath(pathOf(it), tint, style = line) }
        }
    }
}

private inline fun DrawScope.grid(block: DrawScope.() -> Unit) {
    val k = size.minDimension / 24f
    if (k > 0f) scale(k, k, pivot = Offset.Zero, block = block)
}

private val pathCache = HashMap<String, Path>()

private fun pathOf(data: String): Path = pathCache.getOrPut(data) { PathParser().parsePathString(data).toPath() }
