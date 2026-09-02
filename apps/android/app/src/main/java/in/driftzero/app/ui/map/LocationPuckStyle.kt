package `in`.driftzero.app.ui.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Visual contract for the consumer location puck.
 *
 * Sized at or above Google Maps scale so a Google-blue fill does not disappear
 * on light grey OpenFreeMap liberty roads. Halo and navy outline supply contrast
 * independent of the basemap. Production-path UI; not an evaluation overlay.
 */
object LocationPuckStyle {
    /** Fill ARGB. Google-maps-like blue, saturated enough for grey asphalt. */
    const val FILL_ARGB: Long = 0xFF1E6BFF

    /** Dark navy outline ARGB. */
    const val OUTLINE_ARGB: Long = 0xFF0A2A6B

    /** White halo ARGB. */
    const val HALO_ARGB: Long = 0xFFFFFFFF

    /** Low-opacity accuracy fill ARGB (alpha in the high byte). */
    const val ACCURACY_ARGB: Long = 0x2E1E6BFF

    const val HALO_WIDTH_DP: Int = 4
    const val OUTLINE_WIDTH_DP: Int = 3
    const val FILL_RADIUS_DP: Int = 18
    const val HEADING_CONE_LENGTH_DP: Int = 22
    const val HEADING_CONE_HALF_WIDTH_DP: Int = 12
    const val PULSE_MAX_RADIUS_PX: Float = 92f
    const val PULSE_ALPHA: Float = 0.45f
    const val ICON_SCALE: Float = 1.85f
    const val ACCURACY_ALPHA: Float = 0.18f

    val fill: Color = Color(FILL_ARGB)
    val outline: Color = Color(OUTLINE_ARGB)
    val halo: Color = Color(HALO_ARGB)
    val accuracy: Color = Color(ACCURACY_ARGB)

    val fillRadius: Dp = FILL_RADIUS_DP.dp
    val haloWidth: Dp = HALO_WIDTH_DP.dp
    val outlineWidth: Dp = OUTLINE_WIDTH_DP.dp
    val headingConeLength: Dp = HEADING_CONE_LENGTH_DP.dp
    val headingConeHalfWidth: Dp = HEADING_CONE_HALF_WIDTH_DP.dp

    /** Box that contains halo + cone. Circle sits at the geometric center. */
    val boxSize: Dp = ((FILL_RADIUS_DP + OUTLINE_WIDTH_DP + HALO_WIDTH_DP) * 2 + HEADING_CONE_LENGTH_DP + 8).dp
}
