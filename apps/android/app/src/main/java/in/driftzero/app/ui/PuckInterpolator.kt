package `in`.driftzero.app.ui

import `in`.driftzero.core.NavigationState
import `in`.driftzero.core.puckHeadingRad
import `in`.driftzero.core.puckLatitudeDeg
import `in`.driftzero.core.puckLongitudeDeg
import kotlin.math.PI

/** What the map draws this frame. Display only; never fed back into the filter. */
data class DisplayPuck(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val headingRad: Double,
    val radiusM: Double,
    val heading95Rad: Double,
    val speedMps: Double,
)

/**
 * Slides the drawn puck from where it was toward the newest [NavigationState]
 * and arrives within [catchUpNs] of the state's own timestamp. Retargeting
 * never restarts; the slide starts from the current drawn pose. Heading takes
 * the shortest arc. With [reduceMotion] every sample draws the target.
 */
class PuckInterpolator(
    private val catchUpNs: Long = DEFAULT_CATCH_UP_NS,
    var reduceMotion: Boolean = false,
) {
    private var from: DisplayPuck? = null
    private var to: DisplayPuck? = null
    private var startNs: Long = 0L
    private var endNs: Long = 0L
    private var last: DisplayPuck? = null

    fun target(state: NavigationState, nowNs: Long) {
        val next = DisplayPuck(
            latitudeDeg = state.puckLatitudeDeg(),
            longitudeDeg = state.puckLongitudeDeg(),
            headingRad = state.puckHeadingRad(),
            radiusM = state.uncertainty.horizontal95.value,
            heading95Rad = state.uncertainty.heading95Rad,
            speedMps = state.motion.speed.value,
        )
        if (to == next) {
            return
        }
        from = last ?: next
        to = next
        startNs = nowNs
        endNs = nowNs + catchUpNs
    }

    fun sample(nowNs: Long): DisplayPuck? {
        val target = to ?: return null
        val start = from ?: target
        if (reduceMotion || nowNs >= endNs || endNs <= startNs) {
            last = target
            return target
        }
        val t = ((nowNs - startNs).toDouble() / (endNs - startNs).toDouble()).coerceIn(0.0, 1.0)
        val drawn = DisplayPuck(
            latitudeDeg = lerp(start.latitudeDeg, target.latitudeDeg, t),
            longitudeDeg = lerpLongitude(start.longitudeDeg, target.longitudeDeg, t),
            headingRad = lerpHeading(start.headingRad, target.headingRad, t),
            radiusM = lerp(start.radiusM, target.radiusM, t),
            heading95Rad = lerp(start.heading95Rad, target.heading95Rad, t),
            speedMps = lerp(start.speedMps, target.speedMps, t),
        )
        last = drawn
        return drawn
    }

    companion object {
        const val DEFAULT_CATCH_UP_NS: Long = 100_000_000L
        private const val TWO_PI = 2.0 * PI

        fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

        fun lerpHeading(a: Double, b: Double, t: Double): Double {
            var delta = (b - a) % TWO_PI
            if (delta > PI) delta -= TWO_PI
            if (delta < -PI) delta += TWO_PI
            val h = (a + delta * t) % TWO_PI
            return if (h < 0.0) h + TWO_PI else h
        }

        fun lerpLongitude(a: Double, b: Double, t: Double): Double {
            var delta = (b - a) % 360.0
            if (delta > 180.0) delta -= 360.0
            if (delta < -180.0) delta += 360.0
            val lon = a + delta * t
            return ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        }
    }
}
