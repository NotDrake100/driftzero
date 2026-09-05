package `in`.driftzero.app.trips

import `in`.driftzero.app.pose.ImuRateMeter
import `in`.driftzero.app.pose.PhoneImuSource
import `in`.driftzero.app.pose.RingBuffer
import `in`.driftzero.core.ClockDomain
import `in`.driftzero.core.CoastFix
import `in`.driftzero.core.ContractWrite
import `in`.driftzero.core.GnssMaskInterval
import `in`.driftzero.core.Nanoseconds
import `in`.driftzero.core.NavigationMode
import `in`.driftzero.core.NavigationState
import `in`.driftzero.core.SensorFrame
import `in`.driftzero.core.SensorKind
import `in`.driftzero.core.VectorFrame
import `in`.driftzero.core.Wgs84
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Copies frames and 10 Hz states into rings, then writes JSONL off the
 * caller thread. Sensor callbacks must only [offerAccel], [offerGyro], or
 * [offerGnss]. IMU rate in the manifest is measured from timestamp deltas.
 * [DECLARED_RATE_HZ] is the preferred request, not a measured Hertz.
 */
class TripRecorder(
    val dir: File,
    val id: String,
    val startWallMs: Long,
    private val schedule: (Runnable) -> Unit = DEFAULT_SCHEDULE,
    sensorCap: Int = SENSOR_CAP,
    stateCap: Int = STATE_CAP,
) {
    private val lock = Any()
    private val sensors = RingBuffer<SensorFrame>(sensorCap)
    private val states = RingBuffer<NavigationState>(stateCap)
    private val sensorFile = File(dir, SENSORS)
    private val stateFile = File(dir, STATES)
    private var active: Boolean = false
    private var headerWritten: Boolean = false
    private var sequence: Long = 0L
    private var holdOpenNs: Long? = null
    private val holds = ArrayList<GnssMaskInterval>()
    private var firstPoint: Pair<Double, Double>? = null
    private var lastPoint: Pair<Double, Double>? = null
    private var stateCount: Int = 0
    private var drCount: Int = 0
    private var firstNs: Long? = null
    private var lastNs: Long? = null
    private val accelRate = ImuRateMeter()
    private val gyroRate = ImuRateMeter()

    fun start() {
        synchronized(lock) {
            if (active) {
                return
            }
            dir.mkdirs()
            active = true
            headerWritten = false
        }
        flush()
    }

    fun offerAccel(timestamp: Nanoseconds, x: Double, y: Double, z: Double) {
        offerSensor(TripFrames.accel(nextSequence(), timestamp, x, y, z))
    }

    fun offerGyro(timestamp: Nanoseconds, x: Double, y: Double, z: Double) {
        offerSensor(TripFrames.gyro(nextSequence(), timestamp, x, y, z))
    }

    fun offerGnss(fix: CoastFix, held: Boolean = false) {
        offerSensor(TripFrames.gnss(nextSequence(), fix, held = held))
    }

    fun offerSensor(frame: SensorFrame) {
        val shouldFlush: Boolean
        synchronized(lock) {
            if (!active) {
                return
            }
            when (frame.kind) {
                SensorKind.ACCELEROMETER -> accelRate.accept(frame.timestamp.value)
                SensorKind.GYROSCOPE -> gyroRate.accept(frame.timestamp.value)
                else -> Unit
            }
            sensors.add(frame)
            shouldFlush = true
        }
        if (shouldFlush) {
            schedule(::flush)
        }
    }

    fun offerState(state: NavigationState) {
        val shouldFlush: Boolean
        synchronized(lock) {
            if (!active) {
                return
            }
            states.add(state)
            noteState(state)
            shouldFlush = true
        }
        if (shouldFlush) {
            schedule(::flush)
        }
    }

    fun holdStart(timestampNs: Long) {
        synchronized(lock) {
            if (!active) {
                return
            }
            holdOpenNs = timestampNs.coerceAtLeast(0L)
        }
    }

    fun holdEnd(timestampNs: Long) {
        synchronized(lock) {
            val start = holdOpenNs ?: return
            holdOpenNs = null
            val end = timestampNs.coerceAtLeast(start)
            holds += GnssMaskInterval(start, end)
        }
    }

    fun stop(): TripSummary {
        synchronized(lock) {
            val open = holdOpenNs
            if (open != null) {
                val end = lastNs ?: open
                holds += GnssMaskInterval(open, end.coerceAtLeast(open))
                holdOpenNs = null
            }
            active = false
        }
        flush()
        val summary = snapshot()
        File(dir, MANIFEST).writeText(ContractWrite.stringify(summary.toManifest()))
        return summary
    }

    fun snapshot(): TripSummary {
        synchronized(lock) {
            val start = firstNs ?: 0L
            val end = lastNs ?: start
            val distance = distanceM()
            val accel = accelRate.snapshot()
            val gyro = gyroRate.snapshot()
            return TripSummary(
                id = id,
                dir = dir,
                startWallMs = startWallMs,
                startNs = start,
                endNs = end,
                distanceM = distance,
                stateCount = stateCount,
                drCount = drCount,
                holds = holds.toList(),
                requestedSensorDelay = REQUESTED_SENSOR_DELAY,
                measuredAccelHz = accel.medianHz,
                measuredGyroHz = gyro.medianHz,
                accelSampleCount = accel.sampleCount,
                gyroSampleCount = gyro.sampleCount,
            )
        }
    }

    fun flush() {
        val sensorBatch: List<SensorFrame>
        val stateBatch: List<NavigationState>
        val writeHeader: Boolean
        synchronized(lock) {
            writeHeader = !headerWritten
            if (writeHeader) {
                headerWritten = true
            }
            sensorBatch = sensors.drain()
            stateBatch = states.drain()
        }
        if (writeHeader && !sensorFile.exists()) {
            sensorFile.appendText(headerLine() + "\n")
        }
        if (sensorBatch.isNotEmpty()) {
            val body = StringBuilder()
            for (frame in sensorBatch) {
                body.append(ContractWrite.sensorLine(frame)).append('\n')
            }
            sensorFile.appendText(body.toString())
        }
        if (stateBatch.isNotEmpty()) {
            val body = StringBuilder()
            for (state in stateBatch) {
                body.append(ContractWrite.stateLine(state)).append('\n')
            }
            stateFile.appendText(body.toString())
        }
    }

    private fun noteState(state: NavigationState) {
        stateCount += 1
        if (state.mode != NavigationMode.GNSS_FUSED) {
            drCount += 1
        }
        val lat = state.position.latitude.value
        val lon = state.position.longitude.value
        if (firstPoint == null) {
            firstPoint = lat to lon
        }
        lastPoint = lat to lon
        val t = state.timestamp.value
        if (firstNs == null) {
            firstNs = t
        }
        lastNs = t
    }

    private fun distanceM(): Double {
        val start = firstPoint ?: return 0.0
        val end = lastPoint ?: return 0.0
        return Wgs84.distanceMetres(start.first, start.second, end.first, end.second)
    }

    private fun nextSequence(): Long {
        synchronized(lock) {
            val next = sequence
            sequence += 1L
            return next
        }
    }

    private fun headerLine(): String = ContractWrite.stringify(
        linkedMapOf(
            "declared_rate_hz" to DECLARED_RATE_HZ,
            "requested_sensor_delay" to REQUESTED_SENSOR_DELAY,
            "clock_domain" to ClockDomain.ANDROID_ELAPSED_REALTIME.contractName(),
            "frame" to VectorFrame.ANDROID_DEVICE.contractName(),
            "source_id" to TripFrames.SOURCE_ID,
            "accel_unit" to "m/s^2",
            "gyro_unit" to "rad/s",
            "gnss_speed_unit" to "m/s",
            "gnss_course_unit" to "rad",
            "timestamp_unit" to "ns",
        ),
    )

    companion object {
        const val SENSORS: String = "sensors.jsonl"
        const val STATES: String = "states.jsonl"
        const val MANIFEST: String = "manifest.json"
        const val SENSOR_CAP: Int = 256
        const val STATE_CAP: Int = 32
        /** Preferred request. Not a measured Hertz. See manifest measured_*_hz. */
        const val DECLARED_RATE_HZ: Double = 100.0
        const val REQUESTED_SENSOR_DELAY: String = PhoneImuSource.REQUESTED_DELAY_NAME
        private val WRITER: Executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "driftzero-trip").apply { isDaemon = true }
        }
        val DEFAULT_SCHEDULE: (Runnable) -> Unit = { task -> WRITER.execute(task) }
    }
}
