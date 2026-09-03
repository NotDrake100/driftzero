package `in`.driftzero.app.pose

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import `in`.driftzero.core.Nanoseconds

/**
 * Copies accelerometer, gyroscope, and magnetometer [SensorEvent]s into
 * [PoseStore]. Accel is m/s^2, gyro is rad/s, mag is microtesla (uT). All
 * three are [in.driftzero.core.VectorFrame.ANDROID_DEVICE]. Mag is captured
 * and unused in fusion. No inference, disk, or UI work runs in the callback.
 */
class PhoneImuSource(
    context: Context,
    private val store: PoseStore,
) : SensorEventListener {
    private val manager = context.applicationContext.getSystemService(SensorManager::class.java)
    private val accel = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val mag = manager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private var running: Boolean = false

    fun start() {
        if (running) {
            return
        }
        val sensors = manager ?: return
        running = true
        accel?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyro?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        mag?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        if (!running) {
            return
        }
        running = false
        manager?.unregisterListener(this)
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
            Sensor.TYPE_ACCELEROMETER -> store.ingestAccel(stamp, x, y, z)
            Sensor.TYPE_GYROSCOPE -> store.ingestGyro(stamp, x, y, z)
            Sensor.TYPE_MAGNETIC_FIELD ->
                store.ingestMagnetometer(stamp, x, y, z, accuracyCode = event.accuracy)
            else -> Unit
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
