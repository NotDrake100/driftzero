package `in`.driftzero.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Always-on Compose overlay puck. Drawn above the MapView so labels, buildings,
 * and chrome cannot hide it. Position this at the projected lat/lng (or the map
 * center before the first projection).
 *
 * Heading is degrees clockwise from north, matching Android [android.location.Location.bearing].
 */
@Composable
fun LocationPuck(
    bearingDeg: Float,
    modifier: Modifier = Modifier,
) {
    val fill = LocationPuckStyle.fill
    val outline = LocationPuckStyle.outline
    val halo = LocationPuckStyle.halo
    Canvas(
        modifier = modifier
            .size(LocationPuckStyle.boxSize)
            .semantics { contentDescription = "Current location" },
    ) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val fillRadius = LocationPuckStyle.fillRadius.toPx()
        val outlineWidth = LocationPuckStyle.outlineWidth.toPx()
        val haloWidth = LocationPuckStyle.haloWidth.toPx()
        val coneLength = LocationPuckStyle.headingConeLength.toPx()
        val coneHalf = LocationPuckStyle.headingConeHalfWidth.toPx()
        val haloRadius = fillRadius + outlineWidth + haloWidth
        val outlineRadius = fillRadius + outlineWidth

        rotate(degrees = bearingDeg, pivot = center) {
            val tip = Offset(center.x, center.y - fillRadius - outlineWidth - coneLength)
            val left = Offset(center.x - coneHalf, center.y - fillRadius * 0.15f)
            val right = Offset(center.x + coneHalf, center.y - fillRadius * 0.15f)
            val cone = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(left.x, left.y)
                lineTo(right.x, right.y)
                close()
            }
            drawPath(path = cone, color = halo)
            drawPath(
                path = cone,
                color = outline,
                style = Stroke(width = outlineWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawPath(path = cone, color = fill)

            drawCircle(color = halo, radius = haloRadius, center = center)
            drawCircle(color = outline, radius = outlineRadius, center = center)
            drawCircle(color = fill, radius = fillRadius, center = center)
            drawCircle(
                color = halo.copy(alpha = 0.38f),
                radius = fillRadius * 0.28f,
                center = Offset(center.x - fillRadius * 0.18f, center.y - fillRadius * 0.22f),
            )
        }
    }
}
