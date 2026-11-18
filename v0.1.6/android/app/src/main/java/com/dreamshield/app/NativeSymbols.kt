package com.dreamshield.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** One quiet outline vocabulary. The containing control supplies its accessible label. */
enum class DreamIconKind {
    Camera, ArrowDown, ArrowUpRight, Check, ChevronRight, ChevronDown, Download,
    Moon, Sparkle, Clock, Pulse, Grid
}

@Composable
fun DreamIcon(kind: DreamIconKind, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Canvas(modifier.size(24.dp)) {
        val x = size.width / 24f
        val y = size.height / 24f
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(vararg points: Pair<Float, Float>, close: Boolean = false) {
            val path = Path().apply {
                points.forEachIndexed { index, point ->
                    if (index == 0) moveTo(point.first * x, point.second * y)
                    else lineTo(point.first * x, point.second * y)
                }
                if (close) close()
            }
            drawPath(path, tint, style = stroke)
        }
        when (kind) {
            DreamIconKind.Camera -> {
                drawRoundRect(tint, Offset(3f * x, 5f * y), Size(18f * x, 15f * y),
                    cornerRadius = CornerRadius(2.5f * x, 2.5f * y), style = stroke)
                line(8f to 5f, 9.5f to 2.5f, 14.5f to 2.5f, 16f to 5f)
                drawCircle(tint, radius = 4f * x, center = Offset(12f * x, 12.5f * y), style = stroke)
            }
            DreamIconKind.ArrowDown -> {
                line(12f to 4f, 12f to 20f)
                line(6f to 14f, 12f to 20f, 18f to 14f)
            }
            DreamIconKind.ArrowUpRight -> {
                line(5f to 19f, 19f to 5f)
                line(7f to 5f, 19f to 5f, 19f to 17f)
            }
            DreamIconKind.Check -> line(5f to 12f, 10f to 17f, 20f to 6f)
            DreamIconKind.ChevronRight -> line(9f to 5f, 16f to 12f, 9f to 19f)
            DreamIconKind.ChevronDown -> line(5f to 9f, 12f to 16f, 19f to 9f)
            DreamIconKind.Download -> {
                line(12f to 3f, 12f to 14f)
                line(7f to 9f, 12f to 14f, 17f to 9f)
                line(4f to 16f, 4f to 21f, 20f to 21f, 20f to 16f)
            }
            DreamIconKind.Moon -> {
                val path = Path().apply {
                    moveTo(15.5f * x, 4.2f * y)
                    cubicTo(10.2f * x, 4.8f * y, 6.5f * x, 9.4f * y, 7.2f * x, 14.6f * y)
                    cubicTo(7.9f * x, 18.8f * y, 12.2f * x, 21.2f * y, 16.8f * x, 19.4f * y)
                    cubicTo(13.2f * x, 18.2f * y, 11.4f * x, 14.6f * y, 12.2f * x, 10.6f * y)
                    cubicTo(12.8f * x, 7.8f * y, 14.1f * x, 5.6f * y, 15.5f * x, 4.2f * y)
                    close()
                }
                drawPath(path, tint, style = stroke)
            }
            DreamIconKind.Sparkle -> {
                line(12f to 3.5f, 14.2f to 9.8f, 20.5f to 12f, 14.2f to 14.2f,
                    12f to 20.5f, 9.8f to 14.2f, 3.5f to 12f, 9.8f to 9.8f, close = true)
            }
            DreamIconKind.Clock -> {
                drawCircle(tint, radius = 8.2f * x, center = Offset(12f * x, 12.5f * y), style = stroke)
                line(12f to 8.2f, 12f to 12.5f, 15.2f to 14.5f)
                line(6.5f to 3.5f, 4f to 6f)
                line(17.5f to 3.5f, 20f to 6f)
            }
            DreamIconKind.Pulse -> {
                line(3.5f to 12.5f, 7.5f to 12.5f, 10f to 7.5f, 13.5f to 17.5f, 16f to 12.5f, 20.5f to 12.5f)
            }
            DreamIconKind.Grid -> {
                drawCircle(tint, radius = 2.4f * x, center = Offset(8f * x, 8f * y), style = stroke)
                drawCircle(tint, radius = 2.4f * x, center = Offset(16f * x, 8f * y), style = stroke)
                drawCircle(tint, radius = 2.4f * x, center = Offset(8f * x, 16f * y), style = stroke)
                line(16f to 13.6f, 16f to 18.4f)
                line(13.6f to 16f, 18.4f to 16f)
            }
        }
    }
}
