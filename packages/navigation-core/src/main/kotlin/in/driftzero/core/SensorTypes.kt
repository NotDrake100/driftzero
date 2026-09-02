package `in`.driftzero.core

enum class ClockDomain {
    ANDROID_ELAPSED_REALTIME,
    EXTERNAL_MONOTONIC,
    DATASET_DECLARED,
    ;

    fun contractName(): String = when (this) {
        ANDROID_ELAPSED_REALTIME -> "android_elapsed_realtime"
        EXTERNAL_MONOTONIC -> "external_monotonic"
        DATASET_DECLARED -> "dataset_declared"
    }
}

enum class SensorKind {
    ACCELEROMETER,
    GYROSCOPE,
    MAGNETOMETER,
    GNSS_FIX,
    GNSS_STATUS,
    RAW_GNSS,
    ;

    fun contractName(): String = name.lowercase()
}

enum class VectorFrame {
    ANDROID_DEVICE,
    VEHICLE_FLU,
    UNSPECIFIED,
    ;

    fun contractName(): String = when (this) {
        ANDROID_DEVICE -> "android_device"
        VEHICLE_FLU -> "vehicle_flu"
        UNSPECIFIED -> "unspecified"
    }
}

data class Quality(
    val available: Boolean,
    val accuracyCode: Int,
    val flags: Set<String> = emptySet(),
) {
    init {
        require(accuracyCode in -1..3) { "accuracy_code must be in [-1, 3]" }
    }
}

data class Vector3Payload(
    val x: Double,
    val y: Double,
    val z: Double,
    val unit: String,
    val frame: VectorFrame,
    val biasX: Double? = null,
    val biasY: Double? = null,
    val biasZ: Double? = null,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "vector components must be finite" }
        biasX?.let { require(it.isFinite()) }
        biasY?.let { require(it.isFinite()) }
        biasZ?.let { require(it.isFinite()) }
    }
}

data class GnssFixPayload(
    val latitude: LatitudeDeg,
    val longitude: LongitudeDeg,
    val horizontalAccuracyM: Metres,
    val providerTimeMs: Long,
    val altitudeM: Double? = null,
    val speedMps: MetresPerSecond? = null,
    val bearingRad: HeadingRadians? = null,
    val isMock: Boolean? = null,
) {
    init {
        require(providerTimeMs >= 0L) { "provider_time_ms must be non-negative" }
        altitudeM?.let { require(it.isFinite()) }
    }
}

data class GnssStatusPayload(
    val satellitesVisible: Int,
    val satellitesUsed: Int,
    val constellations: Set<String> = emptySet(),
) {
    init {
        require(satellitesVisible >= 0 && satellitesUsed >= 0)
    }
}

data class RawGnssPayload(
    val constellation: String,
    val svid: Int,
) {
    init {
        require(constellation.isNotEmpty())
        require(svid >= 1)
    }
}

sealed interface SensorPayload

data class VectorPayload(val vector: Vector3Payload) : SensorPayload

data class FixPayload(val fix: GnssFixPayload) : SensorPayload

data class StatusPayload(val status: GnssStatusPayload) : SensorPayload

data class RawPayload(val raw: RawGnssPayload) : SensorPayload

data class SensorFrame(
    val sourceId: String,
    val sequence: Long,
    val timestamp: Nanoseconds,
    val clockDomain: ClockDomain,
    val kind: SensorKind,
    val quality: Quality,
    val payload: SensorPayload,
    val schemaVersion: String = SCHEMA_VERSION,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "schema_version must be $SCHEMA_VERSION" }
        require(sourceId.isNotEmpty() && sourceId.length <= 128)
        require(sequence >= 0L)
        when (kind) {
            SensorKind.ACCELEROMETER -> {
                val vector = (payload as? VectorPayload)?.vector
                    ?: error("accelerometer requires VectorPayload")
                require(vector.unit == "m/s^2")
            }
            SensorKind.GYROSCOPE -> {
                val vector = (payload as? VectorPayload)?.vector
                    ?: error("gyroscope requires VectorPayload")
                require(vector.unit == "rad/s")
            }
            SensorKind.MAGNETOMETER -> {
                val vector = (payload as? VectorPayload)?.vector
                    ?: error("magnetometer requires VectorPayload")
                require(vector.unit == "uT")
            }
            SensorKind.GNSS_FIX -> require(payload is FixPayload)
            SensorKind.GNSS_STATUS -> require(payload is StatusPayload)
            SensorKind.RAW_GNSS -> require(payload is RawPayload)
        }
    }
}
