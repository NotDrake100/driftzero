package `in`.driftzero.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun CompassMark(
    bearingDeg: Float,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    val colors = InstrumentTheme.colors
    Canvas(modifier.size(size)) {
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val radius = this.size.minDimension / 2f - 8.dp.toPx()
        drawCircle(
            color = colors.ink,
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = 2.dp.toPx()),
        )
        rotate(degrees = bearingDeg, pivot = Offset(cx, cy)) {
            val tip = Offset(cx, cy - radius + 3.dp.toPx())
            val needle = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(cx - 4.5.dp.toPx(), cy + 2.dp.toPx())
                lineTo(cx, cy - 4.dp.toPx())
                lineTo(cx + 4.5.dp.toPx(), cy + 2.dp.toPx())
                close()
            }
            drawPath(needle, colors.ink)
            drawLine(
                color = colors.lampAlert,
                start = Offset(cx, cy + 6.dp.toPx()),
                end = Offset(cx, cy + radius - 4.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Butt,
            )
        }
    }
}

@Composable
internal fun DestinationMark(modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val colors = InstrumentTheme.colors
    Canvas(modifier.size(size)) {
        val cx = this.size.width / 2f
        val headCy = this.size.height * 0.38f
        val headR = 4.dp.toPx()
        val stroke = 2.dp.toPx()
        drawCircle(
            color = colors.ink,
            radius = headR,
            center = Offset(cx, headCy),
            style = Stroke(width = stroke),
        )
        drawCircle(
            color = colors.ink,
            radius = 1.5.dp.toPx(),
            center = Offset(cx, headCy),
        )
        drawLine(
            color = colors.ink,
            start = Offset(cx, headCy + headR),
            end = Offset(cx, this.size.height * 0.78f),
            strokeWidth = stroke,
            cap = StrokeCap.Butt,
        )
    }
}

@Composable
internal fun LocateMark(modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val colors = InstrumentTheme.colors
    Canvas(modifier.size(size)) {
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val outer = this.size.minDimension / 2f - 8.dp.toPx()
        drawCircle(
            color = colors.ink,
            radius = outer,
            center = Offset(cx, cy),
            style = Stroke(width = 2.dp.toPx()),
        )
        drawCircle(
            color = colors.marker,
            radius = 5.dp.toPx(),
            center = Offset(cx, cy),
        )
    }
}

@Composable
internal fun ClearMark(modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val colors = InstrumentTheme.colors
    Canvas(modifier.size(size)) {
        val pad = 7.dp.toPx()
        val stroke = 2.dp.toPx()
        drawLine(
            color = colors.inkDim,
            start = Offset(pad, pad),
            end = Offset(this.size.width - pad, this.size.height - pad),
            strokeWidth = stroke,
            cap = StrokeCap.Butt,
        )
        drawLine(
            color = colors.inkDim,
            start = Offset(this.size.width - pad, pad),
            end = Offset(pad, this.size.height - pad),
            strokeWidth = stroke,
            cap = StrokeCap.Butt,
        )
    }
}
