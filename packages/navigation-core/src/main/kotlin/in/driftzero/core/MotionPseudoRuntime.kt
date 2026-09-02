package `in`.driftzero.core

/**
 * Causal IMU buffer plus [MotionModel] and optional [DisplacementModel].
 * The ESKF does not own the student. Call [inferAt] / [inferDisplacementAt]
 * on the 10 Hz worker, then ingest into [DeadReckoningFilter].
 */
class MotionPseudoRuntime(
    private val model: MotionModel = ZuptAccelMotionModel(),
    private val displacement: DisplacementModel? = null,
    private val buffer: CausalImuBuffer = CausalImuBuffer(),
) {
    fun ingestAccel(timestamp: Nanoseconds, x: Double, y: Double, z: Double) {
        buffer.pushAccel(timestamp, x, y, z)
    }

    fun ingestGyro(timestamp: Nanoseconds, x: Double, y: Double, z: Double) {
        buffer.pushGyro(timestamp, x, y, z)
    }

    fun ingestFrame(frame: SensorFrame) {
        if (!frame.quality.available) {
            return
        }
        when (frame.kind) {
            SensorKind.ACCELEROMETER -> {
                val vector = (frame.payload as VectorPayload).vector
                ingestAccel(frame.timestamp, vector.x, vector.y, vector.z)
            }
            SensorKind.GYROSCOPE -> {
                val vector = (frame.payload as VectorPayload).vector
                ingestGyro(frame.timestamp, vector.x, vector.y, vector.z)
            }
            else -> Unit
        }
    }

    fun inferAt(now: Nanoseconds): MotionPseudoMeasurement? {
        val window = buffer.windowEndingAt(now)
        if (window.samples.size < ImuMotionConstants.MIN_SAMPLES) {
            return null
        }
        return model.infer(window)
    }

    fun inferDisplacementAt(now: Nanoseconds): DisplacementPseudoMeasurement? {
        val student = displacement ?: return null
        val window = buffer.windowEndingAt(now)
        if (window.samples.size < ImuMotionConstants.MIN_SAMPLES) {
            return null
        }
        return student.infer(window)
    }
}
