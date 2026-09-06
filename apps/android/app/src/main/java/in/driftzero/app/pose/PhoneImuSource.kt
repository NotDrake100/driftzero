package `in`.driftzero.app.pose

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import `in`.driftzero.core.Nanoseconds

/**
 * Copies accelerometer, gyroscope, and magnetometer [SensorEvent]s into
 * [PoseStore.offerImu]. Accel is m/s^2, gyro is rad/s, mag is microtesla (uT).
 * Gravity, linear acceleration, and uncalibrated gyro are copied when the
 * device exposes them. The ESKF does not consume those three. All vectors
 * are [in.driftzero.core.VectorFrame.ANDROID_DEVICE]. Mag is captured
 * and unused in fusion. The callback only enqueues. Delivery is [dz-imu], not
 * the main thread.
 *
 * Registers [SensorManager.SENSOR_DELAY_FASTEST] so a phone that can do
 * 100 Hz may deliver it. Android does not guarantee that rate. After 40
 * samples this class logs the measured Hz from [SensorEvent.timestamp]
 * deltas. Trip JSONL timestamps are the persistent measurement. Do not cite
 * 100 Hz until that log exists on a device.
 */
class PhoneImuSource(
    context: Context,
    private val store: PoseStore,
) : SensorEventListener {
    private val manager = context.applicationContext.getSystemService(SensorManager::class.java)
    private val accel = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val mag = manager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gravity = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val linearAccel = manager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyroUncal = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
    private var running: Boolean = false
    private var imuThread: HandlerThread? = null
    private var accelCount: Int = 0
    private var accelFirstNs: Long = -1L
    private var accelLastNs: Long = -1L
    private var gyroCount: Int = 0
    private var gyroFirstNs: Long = -1L
    private var gyroLastNs: Long = -1L
    private var accelRateLogged: Boolean = false
    private var gyroRateLogged: Boolean = false

    fun start() {
        if (running) {
            return
        }
        val sensors = manager ?: return
        val thread = HandlerThread("dz-imu")
        thread.start()
        imuThread = thread
        val handler = Handler(thread.looper)
        running = true
        accelCount = 0
        accelFirstNs = -1L
        accelLastNs = -1L
        gyroCount = 0
        gyroFirstNs = -1L
        gyroLastNs = -1L
        accelRateLogged = false
        gyroRateLogged = false
        accel?.let { sensors.registerListener(this, it, REQUESTED_DELAY, handler) }
        gyro?.let { sensors.registerListener(this, it, REQUESTED_DELAY, handler) }
        mag?.let { sensors.registerListener(this, it, REQUESTED_DELAY, handler) }
        gravity?.let { sensors.registerListener(this, it, REQUESTED_DELAY, handler) }
        linearAccel?.let { sensors.registerListener(this, it, REQUESTED_DELAY, handler) }
        gyroUncal?.let { sensors.registerListener(this, it, REQUESTED_DELAY, handler) }
    }

    fun stop() {
        if (!running) {
            return
        }
        running = false
        manager?.unregisterListener(this)
        imuThread?.quitSafely()
        imuThread = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) {
            return
        }
        val values = event.values
        if (values.size < 3) {
            return
        }
        val stamp = Nanoseconds(event.timestamp.coerceAtLeast(0L))
        val x = values[0].toDouble()
        val y = values[1].toDouble()
        val z = values[2].toDouble()
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                noteRate(QueuedImuKind.ACCEL, stamp.value)
                store.offerImu(QueuedImuKind.ACCEL, stamp, x, y, z, accuracyCode = event.accuracy)
            }
            Sensor.TYPE_GYROSCOPE -> {
                noteRate(QueuedImuKind.GYRO, stamp.value)
                store.offerImu(QueuedImuKind.GYRO, stamp, x, y, z, accuracyCode = event.accuracy)
            }
            Sensor.TYPE_MAGNETIC_FIELD ->
                store.offerImu(QueuedImuKind.MAG, stamp, x, y, z, accuracyCode = event.accuracy)
            Sensor.TYPE_GRAVITY ->
                store.offerImu(QueuedImuKind.GRAVITY, stamp, x, y, z, accuracyCode = event.accuracy)
            Sensor.TYPE_LINEAR_ACCELERATION ->
                store.offerImu(QueuedImuKind.LINEAR, stamp, x, y, z, accuracyCode = event.accuracy)
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                if (values.size < 6) {
                    return
                }
                store.offerImu(
                    QueuedImuKind.GYRO_UNCAL,
                    stamp,
                    x,
                    y,
                    z,
                    accuracyCode = event.accuracy,
                    biasX = values[3].toDouble(),
                    biasY = values[4].toDouble(),
                    biasZ = values[5].toDouble(),
                )
            }
            else -> Unit
        }
    }

    private fun noteRate(kind: QueuedImuKind, timestampNs: Long) {
        when (kind) {
            QueuedImuKind.ACCEL -> {
                if (accelFirstNs < 0L) {
                    accelFirstNs = timestampNs
                }
                accelLastNs = timestampNs
                accelCount += 1
                if (!accelRateLogged && accelCount >= RATE_LOG_SAMPLES) {
                    accelRateLogged = true
                    logMeasuredRate("accel", accelCount, accelFirstNs, accelLastNs)
                }
            }
            QueuedImuKind.GYRO -> {
                if (gyroFirstNs < 0L) {
                    gyroFirstNs = timestampNs
                }
                gyroLastNs = timestampNs
                gyroCount += 1
                if (!gyroRateLogged && gyroCount >= RATE_LOG_SAMPLES) {
                    gyroRateLogged = true
                    logMeasuredRate("gyro", gyroCount, gyroFirstNs, gyroLastNs)
                }
            }
            QueuedImuKind.MAG,
            QueuedImuKind.GRAVITY,
            QueuedImuKind.LINEAR,
            QueuedImuKind.GYRO_UNCAL,
            -> Unit
        }
    }

    companion object {
        const val REQUESTED_DELAY: Int = SensorManager.SENSOR_DELAY_FASTEST
        const val REQUESTED_DELAY_NAME: String = "SENSOR_DELAY_FASTEST"
        private const val TAG: String = "dz-imu"
        private const val RATE_LOG_SAMPLES: Int = 40

        internal fun measuredHz(sampleCount: Int, firstNs: Long, lastNs: Long): Double? {
            if (sampleCount < 2 || lastNs <= firstNs) {
                return null
            }
            return (sampleCount - 1).toDouble() / ((lastNs - firstNs) / 1_000_000_000.0)
        }

        private fun logMeasuredRate(sensor: String, sampleCount: Int, firstNs: Long, lastNs: Long) {
            val hz = measuredHz(sampleCount, firstNs, lastNs)
            if (hz == null) {
                Log.i(TAG, "requested $REQUESTED_DELAY_NAME; $sensor rate not yet measurable")
                return
            }
            Log.i(
                TAG,
                "requested $REQUESTED_DELAY_NAME; measured $sensor $hz Hz over $sampleCount samples from timestamp deltas. Not a 100 Hz claim.",
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
