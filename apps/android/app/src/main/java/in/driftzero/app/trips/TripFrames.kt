package `in`.driftzero.app.trips

import `in`.driftzero.core.ClockDomain
import `in`.driftzero.core.CoastFix
import `in`.driftzero.core.FixPayload
import `in`.driftzero.core.GnssFixPayload
import `in`.driftzero.core.HeadingRadians
import `in`.driftzero.core.LatitudeDeg
import `in`.driftzero.core.LongitudeDeg
import `in`.driftzero.core.Metres
import `in`.driftzero.core.MetresPerSecond
import `in`.driftzero.core.Nanoseconds
import `in`.driftzero.core.Quality
import `in`.driftzero.core.SensorFrame
import `in`.driftzero.core.SensorKind
import `in`.driftzero.core.Vector3Payload
import `in`.driftzero.core.VectorFrame
import `in`.driftzero.core.VectorPayload
import `in`.driftzero.core.wrapHeadingRad

/**
 * Contract [SensorFrame] copies for trip JSONL. No filter work.
 *
 * Units: accel m/s^2, gyro rad/s, GNSS latitude/longitude deg, speed m/s,
 * course bearing_rad (radians, clockwise from north, wrapped), timestamps
 * integer nanoseconds on [ClockDomain.ANDROID_ELAPSED_REALTIME].
 * [FLAG_GNSS_HELD] marks a Hold GNSS fix that must stay score-only.
 */
internal object TripFrames {
    const val SOURCE_ID: String = "phone"
    const val FLAG_GNSS_HELD: String = "gnss_held"

    fun accel(
        sequence: Long,
        timestamp: Nanoseconds,
        x: Double,
        y: Double,
        z: Double,
        accuracyCode: Int = 2,
    ): SensorFrame = vector(sequence, timestamp, SensorKind.ACCELEROMETER, "m/s^2", x, y, z, accuracyCode)

    fun gyro(
        sequence: Long,
        timestamp: Nanoseconds,
        x: Double,
        y: Double,
        z: Double,
        accuracyCode: Int = 2,
    ): SensorFrame = vector(sequence, timestamp, SensorKind.GYROSCOPE, "rad/s", x, y, z, accuracyCode)

    fun gravity(
        sequence: Long,
        timestamp: Nanoseconds,
        x: Double,
        y: Double,
        z: Double,
        accuracyCode: Int = 2,
    ): SensorFrame = vector(sequence, timestamp, SensorKind.GRAVITY, "m/s^2", x, y, z, accuracyCode)

    fun linearAccel(
        sequence: Long,
        timestamp: Nanoseconds,
        x: Double,
        y: Double,
        z: Double,
        accuracyCode: Int = 2,
    ): SensorFrame =
        vector(sequence, timestamp, SensorKind.LINEAR_ACCELERATION, "m/s^2", x, y, z, accuracyCode)

    fun gyroUncal(
        sequence: Long,
        timestamp: Nanoseconds,
        x: Double,
        y: Double,
        z: Double,
        biasX: Double,
        biasY: Double,
        biasZ: Double,
    ): SensorFrame = SensorFrame(
        sourceId = SOURCE_ID,
        sequence = sequence,
        timestamp = timestamp,
        clockDomain = ClockDomain.ANDROID_ELAPSED_REALTIME,
        kind = SensorKind.GYROSCOPE_UNCALIBRATED,
        quality = Quality(available = true, accuracyCode = 2),
        payload = VectorPayload(
            Vector3Payload(
                x,
                y,
                z,
                "rad/s",
                VectorFrame.ANDROID_DEVICE,
                biasX = biasX,
                biasY = biasY,
                biasZ = biasZ,
            ),
        ),
    )

    fun gnss(sequence: Long, fix: CoastFix, held: Boolean = false): SensorFrame = SensorFrame(
        sourceId = SOURCE_ID,
        sequence = sequence,
        timestamp = fix.timestamp,
        clockDomain = ClockDomain.ANDROID_ELAPSED_REALTIME,
        kind = SensorKind.GNSS_FIX,
        quality = Quality(
            available = true,
            accuracyCode = 2,
            flags = if (held) setOf(FLAG_GNSS_HELD) else emptySet(),
        ),
        payload = FixPayload(
            GnssFixPayload(
                latitude = LatitudeDeg(fix.latitudeDeg),
                longitude = LongitudeDeg(fix.longitudeDeg),
                horizontalAccuracyM = Metres(fix.horizontalAccuracyM),
                providerTimeMs = fix.timestamp.value / 1_000_000L,
                altitudeM = fix.altitudeM,
                speedMps = fix.speedMps?.let { MetresPerSecond(it) },
                bearingRad = fix.headingRad?.let { HeadingRadians(wrapHeadingRad(it)) },
                speedAccuracyMps = fix.speedAccuracyMps?.let { MetresPerSecond(it) },
                bearingAccuracyRad = fix.bearingAccuracyRad,
                isMock = fix.isMock,
                verticalAccuracyM = fix.verticalAccuracyM?.let { Metres(it) },
            ),
        ),
    )

    private fun vector(
        sequence: Long,
        timestamp: Nanoseconds,
        kind: SensorKind,
        unit: String,
        x: Double,
        y: Double,
        z: Double,
        accuracyCode: Int = 2,
    ): SensorFrame = SensorFrame(
        sourceId = SOURCE_ID,
        sequence = sequence,
        timestamp = timestamp,
        clockDomain = ClockDomain.ANDROID_ELAPSED_REALTIME,
        kind = kind,
        quality = Quality(available = true, accuracyCode = accuracyCode.coerceIn(-1, 3)),
        payload = VectorPayload(
            Vector3Payload(x, y, z, unit, VectorFrame.ANDROID_DEVICE),
        ),
    )
}
