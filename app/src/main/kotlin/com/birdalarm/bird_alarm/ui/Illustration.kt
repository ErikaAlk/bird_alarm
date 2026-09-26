package com.birdalarm.bird_alarm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate

/**
 * 卡通报时鸟（关于页的应用插画、每日一鸟没有照片时的占位）。颜色是插画自己的内容色，不走主题。
 * 深色只换描边色（深棕描边在黑底上会整个消失）；身体、肚子、喙保留，深浅两版认得出是同一只鸟（DESIGN §4 深色手工配色）。
 */
@Composable
fun CartoonBird(dark: Boolean, modifier: Modifier = Modifier) = Canvas(modifier) {
    val k = size.minDimension / 180f
    translate((size.width - 180f * k) / 2, (size.height - 180f * k) / 2) {
        scale(k, k, pivot = Offset.Zero) {
            val outline = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val ink = if (dark) Color(0xFFE4EFE9) else Color(0xFF3C3324)
            val body = Color(0xFF1D9A8A)
            val dot = Color(0xFF2B251D)
            val branch = Color(0xFF7B4E2D)
            val notes = Color(0xFF245B8F)

            drawLine(branch, Offset(18f, 152f), Offset(164f, 140f), 9f, StrokeCap.Round)
            drawLine(branch, Offset(108f, 145f), Offset(142f, 124f), 5f, StrokeCap.Round)

            drawOval(body, Offset(48f, 47f), Size(88f, 101f))
            drawOval(Color(0xFFFFEFBD), Offset(67f, 79f), Size(53f, 58f))
            drawOval(Color(0xFF126D68), Offset(39f, 83f), Size(47f, 38f))
            drawArc(ink, Math.toDegrees(0.6).toFloat(), Math.toDegrees(2.7).toFloat(), false, Offset(39f, 83f), Size(47f, 38f), style = outline)
            drawOval(ink, Offset(48f, 47f), Size(88f, 101f), style = outline)

            val crest = Path().apply { moveTo(78f, 51f); quadraticTo(80f, 27f, 96f, 47f); quadraticTo(103f, 27f, 110f, 51f) }
            drawPath(crest, body)
            drawPath(crest, ink, style = outline)

            val beak = Path().apply { moveTo(130f, 77f); lineTo(162f, 87f); lineTo(130f, 96f); close() }
            drawPath(beak, Color(0xFFFFA13D))
            drawPath(beak, ink, style = outline)

            drawCircle(dot, 7f, Offset(99f, 76f))
            drawCircle(Color.White, 2.2f, Offset(102f, 73f))
            drawCircle(Color(0xFFFF9BA6), 6f, Offset(116f, 94f))

            drawLine(ink, Offset(72f, 145f), Offset(67f, 158f), 4f, StrokeCap.Round)
            drawLine(ink, Offset(107f, 146f), Offset(111f, 158f), 4f, StrokeCap.Round)

            drawCircle(Color.White, 25f, Offset(46f, 55f))
            drawCircle(ink, 25f, Offset(46f, 55f), style = outline)
            drawLine(ink, Offset(46f, 55f), Offset(46f, 40f), 4f, StrokeCap.Round)
            drawLine(ink, Offset(46f, 55f), Offset(58f, 61f), 4f, StrokeCap.Round)
            listOf(Offset(46f, 34f), Offset(67f, 55f), Offset(46f, 76f), Offset(25f, 55f)).forEach { drawCircle(dot, 2f, it) }

            val noteLine = Stroke(3f, cap = StrokeCap.Round)
            drawLine(notes, Offset(147f, 36f), Offset(147f, 55f), 3f, StrokeCap.Round)
            drawCircle(notes, 5f, Offset(141f, 56f), style = noteLine)
            drawLine(notes, Offset(151f, 36f), Offset(163f, 32f), 3f, StrokeCap.Round)
            drawLine(notes, Offset(28f, 20f), Offset(28f, 38f), 3f, StrokeCap.Round)
            drawCircle(notes, 5f, Offset(23f, 39f), style = noteLine)
        }
    }
}
