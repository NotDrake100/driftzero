package `in`.driftzero.core

enum class NavigationMode {
    GNSS_FUSED,
    GNSS_DEGRADED,
    DEAD_RECKONING,
    REACQUIRING,
    LOW_CONFIDENCE,
}

enum class MapMatchStatus {
    MATCHED,
    AMBIGUOUS,
    UNMATCHED,
    NO_MAP,
}

data class Motion(
    val speed: MetresPerSecond,
    val heading: HeadingRadians,
    val yawRateRadps: Double? = null,
) {
    init {
        yawRateRadps?.let { require(it.isFinite()) }
    }
}

data class Uncertainty(
    val horizontal95: Metres,
    val heading95Rad: Double,
    val isCalibrated: Boolean,
) {
    init {
        require(heading95Rad.isFinite() && heading95Rad >= 0.0)
    }
}

data class GnssHealth(
    val score: Double,
    val lastTrustedFixAgeS: Double,
    val riskFlags: Set<String> = emptySet(),
) {
    init {
        require(score in 0.0..1.0)
        require(lastTrustedFixAgeS.isFinite() && lastTrustedFixAgeS >= 0.0)
    }
}

data class MapMatch(
    val status: MapMatchStatus,
    val confidence: Double,
    val roadSegmentId: String? = null,
    val displayLatitudeDeg: Double? = null,
    val displayLongitudeDeg: Double? = null,
    val displayHeadingRad: Double? = null,
) {
    init {
        require(confidence in 0.0..1.0)
        displayLatitudeDeg?.let { require(it.isFinite() && it in -90.0..90.0) }
        displayLongitudeDeg?.let { require(it.isFinite() && it in -180.0..180.0) }
        displayHeadingRad?.let { require(it.isFinite() && it >= 0.0 && it < TWO_PI) }
    }
}

/**
 * Copies matcher status onto a filter state. Position and motion stay the
 * ESKF estimate. [MapMatchResult.displayPose] is display-only.
 */
fun NavigationState.withMapMatch(result: MapMatchResult): NavigationState {
    val mapPresent = result.match.status != MapMatchStatus.NO_MAP
    val display = result.displayPose
    val match = result.match.copy(
        displayLatitudeDeg = display?.position?.latitude?.value,
        displayLongitudeDeg = display?.position?.longitude?.value,
        displayHeadingRad = display?.heading?.value,
    )
    return copy(
        mapMatch = match,
        health = health.copy(mapOk = mapPresent),
        provenance = result.packageId?.let { provenance.copy(mapPackageId = it) } ?: provenance,
    )
}

/** Centerline overlay when the matcher is decided. Otherwise the ESKF point. */
fun NavigationState.puckLatitudeDeg(): Double {
    val lat = mapMatch.displayLatitudeDeg
    return if (mapMatch.status == MapMatchStatus.MATCHED && lat != null) {
        lat
    } else {
        position.latitude.value
    }
}

fun NavigationState.puckLongitudeDeg(): Double {
    val lon = mapMatch.displayLongitudeDeg
    return if (mapMatch.status == MapMatchStatus.MATCHED && lon != null) {
        lon
    } else {
        position.longitude.value
    }
}

fun NavigationState.puckHeadingRad(): Double {
    val heading = mapMatch.displayHeadingRad
    return if (mapMatch.status == MapMatchStatus.MATCHED && heading != null) {
        heading
    } else {
        motion.heading.value
    }
}

data class ComponentHealth(
    val sensorOk: Boolean,
    val modelOk: Boolean,
    val filterOk: Boolean,
    val mapOk: Boolean,
    val flags: Set<String> = emptySet(),
)

data class Provenance(
    val coreVersion: String,
    val configHash: String,
    val modelVersion: String? = null,
    val mapPackageId: String? = null,
) {
    init {
        require(coreVersion.isNotEmpty())
        require(SHA256_HEX.matches(configHash)) { "config_hash must be 64 hex characters" }
    }
}

data class NavigationState(
    val sequence: Long,
    val timestamp: Nanoseconds,
    val mode: NavigationMode,
    val position: GeoPoint,
    val motion: Motion,
    val uncertainty: Uncertainty,
    val gnssHealth: GnssHealth,
    val mapMatch: MapMatch,
    val health: ComponentHealth,
    val provenance: Provenance,
    val schemaVersion: String = SCHEMA_VERSION,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION)
        require(sequence >= 0L)
    }
}

private val SHA256_HEX = Regex("^[a-fA-F0-9]{64}$")
