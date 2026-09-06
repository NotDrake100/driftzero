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
    fun gnssFixRoundTripsSpeedAndBearing() {
        val frame = SensorFrame(
            sourceId = "phone-gnss",
            sequence = 4L,
            timestamp = Nanoseconds(2_000_000_000L),
            clockDomain = ClockDomain.DATASET_DECLARED,
            kind = SensorKind.GNSS_FIX,
            quality = Quality(available = true, accuracyCode = 2),
            payload = FixPayload(
                GnssFixPayload(
                    latitude = LatitudeDeg(12.97),
                    longitude = LongitudeDeg(77.59),
                    horizontalAccuracyM = Metres(3.0),
                    providerTimeMs = 1_700_000_000_000L,
                    altitudeM = 920.0,
                    speedMps = MetresPerSecond(10.0),
                    bearingRad = HeadingRadians(0.5),
                    isMock = false,
                    verticalAccuracyM = Metres(4.5),
                ),
            ),
        )
        val json = ContractJson.stringify(ContractMaps.sensorFrame(frame))
        val parsed = ContractMaps.sensorFrameFrom(ContractJson.parseObject(json))
        val fix = (parsed.payload as FixPayload).fix
        assertEquals(10.0, fix.speedMps!!.value, 0.0)
        assertEquals(0.5, fix.bearingRad!!.value, 0.0)
        assertEquals(920.0, fix.altitudeM!!, 0.0)
        assertEquals(false, fix.isMock)
        assertEquals(4.5, fix.verticalAccuracyM!!.value, 0.0)
        assertEquals(1_700_000_000_000L, fix.providerTimeMs)
    }

    @Test
    fun gravityFrameRoundTripsAndKeepsMps2() {
        val frame = SensorFrame(
            sourceId = "phone-imu",
            sequence = 2L,
            timestamp = Nanoseconds(3_000_000L),
            clockDomain = ClockDomain.ANDROID_ELAPSED_REALTIME,
            kind = SensorKind.GRAVITY,
            quality = Quality(available = true, accuracyCode = 2),
            payload = VectorPayload(
                Vector3Payload(
                    x = 0.0,
                    y = 0.0,
                    z = 9.81,
                    unit = "m/s^2",
                    frame = VectorFrame.ANDROID_DEVICE,
                ),
            ),
        )
        val json = ContractJson.stringify(ContractMaps.sensorFrame(frame))
        val parsed = ContractMaps.sensorFrameFrom(ContractJson.parseObject(json))
        assertEquals(SensorKind.GRAVITY, parsed.kind)
        val vector = (parsed.payload as VectorPayload).vector
        assertEquals("m/s^2", vector.unit)
        assertEquals(9.81, vector.z, 0.0)
    }

    @Test
    fun gnssFixRoundTripsSpeedAccuracy() {
        val frame = SensorFrame(
            sourceId = "phone-gnss",
            sequence = 5L,
            timestamp = Nanoseconds(2_000_000_000L),
            clockDomain = ClockDomain.DATASET_DECLARED,
            kind = SensorKind.GNSS_FIX,
            quality = Quality(available = true, accuracyCode = 2),
            payload = FixPayload(
                GnssFixPayload(
                    latitude = LatitudeDeg(12.97),
                    longitude = LongitudeDeg(77.59),
                    horizontalAccuracyM = Metres(3.0),
                    providerTimeMs = 1_700_000_000_000L,
                    speedMps = MetresPerSecond(10.0),
                    speedAccuracyMps = MetresPerSecond(0.4),
                    bearingAccuracyRad = 0.05,
                ),
            ),
        )
        val parsed = ContractMaps.sensorFrameFrom(
            ContractJson.parseObject(ContractJson.stringify(ContractMaps.sensorFrame(frame))),
        )
        val fix = (parsed.payload as FixPayload).fix
        assertEquals(0.4, fix.speedAccuracyMps!!.value, 0.0)
        assertEquals(0.05, fix.bearingAccuracyRad!!, 0.0)
    }

    @Test
    fun writtenStateSchemaRoundTrip() {
        val state = sampleState()
        val json = ContractJson.stringify(ContractMaps.navigationState(state))
        val parsed = ContractMaps.navigationStateFrom(ContractJson.parseObject(json))
        val again = ContractJson.stringify(ContractMaps.navigationState(parsed))
        assertEquals(json, again)
        assertEquals(state.sequence, parsed.sequence)
        assertEquals(state.timestamp, parsed.timestamp)
        assertEquals(state.mode, parsed.mode)
        assertEquals(state.position.latitude.value, parsed.position.latitude.value, 0.0)
        assertEquals(state.position.longitude.value, parsed.position.longitude.value, 0.0)
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
        val keys = ContractJson.parseObject(json).keys
        required.forEach { key -> assertTrue(key, keys.contains(key)) }
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
