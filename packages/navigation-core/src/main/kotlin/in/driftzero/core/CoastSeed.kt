package `in`.driftzero.core

import kotlin.math.atan2

/**
 * One unique GNSS fix on the persist-seed trail. Speed is the column value
 * in m/s (0 when the exporter had no speed). Heading is the column course
 * in rad clockwise from north, or null.
 */
data class UniqueFixSample(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val timestampNs: Long,
    val speedMps: Double,
    val headingRad: Double?,
) {
    init {
        require(latitudeDeg.isFinite() && longitudeDeg.isFinite())
        require(timestampNs >= 0L)
        require(speedMps.isFinite() && speedMps >= 0.0)
        headingRad?.let { require(it.isFinite()) }
    }
}

/**
 * Last unique GNSS with speed and a 10 m course strictly before [beforeNs].
 * A 0 m/s unique sitting on a blackout start is skipped when an earlier
 * unique qualifies. Matches persist: history is t < start_ns, never the
 * mask-start 0 m/s unique (S-Vw16b).
 */
data class PersistCoastSeed(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val timestampNs: Long,
    val speedMps: Double,
    val headingRad: Double,
) {
    init {
        require(latitudeDeg.isFinite() && longitudeDeg.isFinite())
        require(timestampNs >= 0L)
        require(speedMps.isFinite() && speedMps >= 0.0)
        require(headingRad.isFinite())
    }

    companion object {
        const val MIN_SPEED_MPS: Double = 0.4
        const val MIN_COURSE_M: Double = 10.0

        fun pick(
            trail: List<UniqueFixSample>,
            beforeNs: Long = Long.MAX_VALUE,
            minSpeedMps: Double = MIN_SPEED_MPS,
            minCourseM: Double = MIN_COURSE_M,
        ): PersistCoastSeed? {
            require(minSpeedMps >= 0.0) { "minSpeedMps must be >= 0" }
            require(minCourseM > 0.0) { "minCourseM must be positive" }
            val usable = trail.filter { it.timestampNs < beforeNs }
            if (usable.isEmpty()) {
                return null
            }
            for (end in usable.indices.reversed()) {
                val row = usable[end]
                if (row.speedMps < minSpeedMps) {
                    continue
                }
                val heading = headingFrom10m(usable, end, minCourseM) ?: continue
                return PersistCoastSeed(
                    latitudeDeg = row.latitudeDeg,
                    longitudeDeg = row.longitudeDeg,
                    timestampNs = row.timestampNs,
                    speedMps = row.speedMps,
                    headingRad = heading,
                )
            }
            return null
        }

        private fun headingFrom10m(
            trail: List<UniqueFixSample>,
            end: Int,
            minCourseM: Double,
        ): Double? {
            if (end < 1) {
                return null
            }
            val dest = trail[end]
            var acc = 0.0
            for (index in end - 1 downTo 0) {
                val a = trail[index]
                val b = trail[index + 1]
                acc += Wgs84.distanceMetres(a.latitudeDeg, a.longitudeDeg, b.latitudeDeg, b.longitudeDeg)
                if (acc >= minCourseM) {
                    val (north, east) = Wgs84.northEastMetres(
                        a.latitudeDeg,
                        a.longitudeDeg,
                        dest.latitudeDeg,
                        dest.longitudeDeg,
                    )
                    if (north * north + east * east < 1e-6) {
                        return null
                    }
                    return atan2(east, north)
                }
            }
            return null
        }
    }
}
