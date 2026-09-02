package `in`.driftzero.core

import kotlin.math.PI

/** Monotonic timestamp as a non-negative integer nanosecond count. */
@JvmInline
value class Nanoseconds(val value: Long) {
    init {
        require(value >= 0L) { "timestamp_ns must be non-negative" }
    }
}

@JvmInline
value class Metres(val value: Double) {
    init {
        require(value.isFinite()) { "metres must be finite" }
        require(value >= 0.0) { "metres must be non-negative" }
    }
}

@JvmInline
value class MetresPerSecond(val value: Double) {
    init {
        require(value.isFinite()) { "m/s must be finite" }
        require(value >= 0.0) { "speed_mps must be non-negative" }
    }
}

/** Heading in radians clockwise from true north, in [0, 2π). */
@JvmInline
value class HeadingRadians(val value: Double) {
    init {
        require(value.isFinite()) { "heading_rad must be finite" }
        require(value >= 0.0 && value < TWO_PI) { "heading_rad must be in [0, 2pi)" }
    }
}

@JvmInline
value class LatitudeDeg(val value: Double) {
    init {
        require(value.isFinite()) { "latitude_deg must be finite" }
        require(value in -90.0..90.0) { "latitude_deg out of range: $value" }
    }
}

@JvmInline
value class LongitudeDeg(val value: Double) {
    init {
        require(value.isFinite()) { "longitude_deg must be finite" }
        require(value in -180.0..180.0) { "longitude_deg out of range: $value" }
    }
}

data class GeoPoint(
    val latitude: LatitudeDeg,
    val longitude: LongitudeDeg,
    val altitudeM: Double? = null,
) {
    init {
        altitudeM?.let { require(it.isFinite()) { "altitude_m must be finite" } }
    }
}

const val TWO_PI: Double = 2.0 * PI
const val SCHEMA_VERSION: String = "1.0.0"
const val CORE_VERSION: String = "0.2.0"
