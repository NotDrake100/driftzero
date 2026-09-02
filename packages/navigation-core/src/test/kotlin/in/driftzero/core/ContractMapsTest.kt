package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractMapsTest {
    @Test
    fun accelerometerFrameUsesContractKeysAndUnit() {
        val frame = SensorFrame(
            sourceId = "phone-imu",
            sequence = 0L,
            timestamp = Nanoseconds(1_000_000L),
            clockDomain = ClockDomain.ANDROID_ELAPSED_REALTIME,
            kind = SensorKind.ACCELEROMETER,
            quality = Quality(available = true, accuracyCode = 3),
            payload = VectorPayload(
                Vector3Payload(
                    x = 0.12,
                    y = -0.04,
                    z = 9.81,
                    unit = "m/s^2",
                    frame = VectorFrame.ANDROID_DEVICE,
                ),
            ),
        )
        val map = ContractMaps.sensorFrame(frame)
        assertEquals("1.0.0", map["schema_version"])
        assertEquals("android_elapsed_realtime", map["clock_domain"])
        assertEquals("accelerometer", map["kind"])
        @Suppress("UNCHECKED_CAST")
        val payload = map["payload"] as Map<String, Any?>
        assertEquals("m/s^2", payload["unit"])
        assertEquals("android_device", payload["frame"])
    }

    @Test
    fun navigationStateUsesRequiredContractKeys() {
        val state = sampleState()
        val map = ContractMaps.navigationState(state)
        val required = listOf(
            "schema_version",
            "sequence",
            "timestamp_ns",
            "mode",
            "position",
            "motion",
            "uncertainty",
            "gnss_health",
            "map_match",
            "health",
            "provenance",
        )
        required.forEach { key -> assertTrue(key, map.containsKey(key)) }
        assertEquals("LOW_CONFIDENCE", map["mode"])
        @Suppress("UNCHECKED_CAST")
        val provenance = map["provenance"] as Map<String, Any?>
        assertEquals(64, (provenance["config_hash"] as String).length)
    }

    @Test
    fun headingRejectsTwoPi() {
        var rejected = false
        try {
            HeadingRadians(TWO_PI)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun accelerometerRejectsWrongUnit() {
        var rejected = false
        try {
            SensorFrame(
                sourceId = "phone-imu",
                sequence = 1L,
                timestamp = Nanoseconds(1L),
                clockDomain = ClockDomain.ANDROID_ELAPSED_REALTIME,
                kind = SensorKind.ACCELEROMETER,
                quality = Quality(true, 2),
                payload = VectorPayload(
                    Vector3Payload(0.0, 0.0, 9.8, "rad/s", VectorFrame.ANDROID_DEVICE),
                ),
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    private fun sampleState(): NavigationState = NavigationState(
        sequence = 0L,
        timestamp = Nanoseconds(1_000_000L),
        mode = NavigationMode.LOW_CONFIDENCE,
        position = GeoPoint(LatitudeDeg(12.9716), LongitudeDeg(77.5946)),
        motion = Motion(MetresPerSecond(0.0), HeadingRadians(0.0)),
        uncertainty = Uncertainty(Metres(250.0), 3.0, isCalibrated = false),
        gnssHealth = GnssHealth(0.0, 0.0, setOf("no_fix")),
        mapMatch = MapMatch(MapMatchStatus.NO_MAP, 0.0),
        health = ComponentHealth(
            sensorOk = false,
            modelOk = false,
            filterOk = false,
            mapOk = false,
            flags = setOf("scaffold"),
        ),
        provenance = Provenance(
            coreVersion = CORE_VERSION,
            configHash = "a".repeat(64),
        ),
    )
}
