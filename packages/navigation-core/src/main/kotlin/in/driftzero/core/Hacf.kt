package `in`.driftzero.core

/**
 * RoNIN heading-agnostic coordinate frame (Herath et al., ICRA 2020 §4.1).
 * Z is gravity. Device +X is flattened onto the horizontal plane when it is
 * not parallel to gravity, so a level phone keeps R = I.
 *
 * Keep this aligned with `driftzero_ml.learned_imu.hacf_rotation`.
 */
internal fun hacfRotation(gravity: Vec3): Mat3 {
    val z = gravity.normalized()
        ?: throw IllegalArgumentException("gravity vector is too small to form an HACF")
    var xh = Vec3(1.0 - z.x * z.x, -z.x * z.y, -z.x * z.z)
    if (xh.norm() < 0.05) {
        xh = Vec3(-z.y * z.x, 1.0 - z.y * z.y, -z.y * z.z)
    }
    val x = xh.normalized() ?: throw IllegalArgumentException("HACF x-axis vanished")
    val y = z.cross(x).normalized() ?: throw IllegalArgumentException("HACF y-axis vanished")
    return Mat3(
        x.x, x.y, x.z,
        y.x, y.y, y.z,
        z.x, z.y, z.z,
    )
}

internal fun rotateIntoHacf(vector: Vec3, rotation: Mat3): Vec3 = Vec3(
    rotation.row(0).dot(vector),
    rotation.row(1).dot(vector),
    rotation.row(2).dot(vector),
)

/**
 * TLIO §IV-B: rotate the window by the gravity frame at the first sample.
 * Gyro gaps become explicit zeros. Magnetometer and GNSS are not channels.
 * Each row is ax, ay, az, gx, gy, gz in HACF.
 */
internal fun hacfSixAxis(samples: List<ImuSample>): List<DoubleArray> {
    require(samples.isNotEmpty()) { "IMU window must not be empty" }
    val rotation = hacfRotation(causalGravityVectors(samples)[0])
    return samples.map { sample ->
        val accel = rotateIntoHacf(Vec3(sample.accelX, sample.accelY, sample.accelZ), rotation)
        val gyro = if (sample.gyroX == null || sample.gyroY == null || sample.gyroZ == null) {
            Vec3.ZERO
        } else {
            rotateIntoHacf(Vec3(sample.gyroX, sample.gyroY, sample.gyroZ), rotation)
        }
        doubleArrayOf(accel.x, accel.y, accel.z, gyro.x, gyro.y, gyro.z)
    }
}
