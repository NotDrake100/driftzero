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

/** Contract [SensorFrame] copies for trip JSONL. No filter work. */
internal object TripFrames {
    const val SOURCE_ID: String = "phone"

    fun accel(sequence: Long, timestamp: Nanoseconds, x: Double, y: Double, z: Double): SensorFrame =
        vector(sequence, timestamp, SensorKind.ACCELEROMETER, "m/s^2", x, y, z)

    fun gyro(sequence: Long, timestamp: Nanoseconds, x: Double, y: Double, z: Double): SensorFrame =
        vector(sequence, timestamp, SensorKind.GYROSCOPE, "rad/s", x, y, z)

    fun gnss(sequence: Long, fix: CoastFix): SensorFrame = SensorFrame(
        sourceId = SOURCE_ID,
        sequence = sequence,
        timestamp = fix.timestamp,
        clockDomain = ClockDomain.ANDROID_ELAPSED_REALTIME,
        kind = SensorKind.GNSS_FIX,
        quality = Quality(available = true, accuracyCode = 2),
        payload = FixPayload(
            GnssFixPayload(
                latitude = LatitudeDeg(fix.latitudeDeg),
                longitude = LongitudeDeg(fix.longitudeDeg),
                horizontalAccuracyM = Metres(fix.horizontalAccuracyM),
                providerTimeMs = fix.timestamp.value / 1_000_000L,
                altitudeM = fix.altitudeM,
                speedMps = fix.speedMps?.let { MetresPerSecond(it) },
                bearingRad = fix.headingRad?.let { HeadingRadians(wrapHeadingRad(it)) },
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
    ): SensorFrame = SensorFrame(
        sourceId = SOURCE_ID,
        sequence = sequence,
        timestamp = timestamp,
        clockDomain = ClockDomain.ANDROID_ELAPSED_REALTIME,
        kind = kind,
        quality = Quality(available = true, accuracyCode = 2),
        payload = VectorPayload(
            Vector3Payload(x, y, z, unit, VectorFrame.ANDROID_DEVICE),
        ),
    )
}
