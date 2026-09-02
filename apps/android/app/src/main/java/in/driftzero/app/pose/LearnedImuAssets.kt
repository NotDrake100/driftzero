package `in`.driftzero.app.pose

import android.content.Context
import `in`.driftzero.core.LinearDisplacementStudent
import java.io.FileNotFoundException

/**
 * Optional on-device linear Δp student. Copied from
 * `models/learned_imu_v1/linear_dp.json` at assemble time when that file
 * exists. Missing or invalid JSON leaves the heuristic speed/ZUPT path.
 * No ONNX Runtime and no TimesFM.
 */
object LearnedImuAssets {
    const val ASSET_PATH: String = "learned_imu_v1/linear_dp.json"

    fun load(context: Context): LinearDisplacementStudent? {
        return try {
            context.assets.open(ASSET_PATH).bufferedReader().use { reader ->
                LinearDisplacementStudent.parse(reader.readText())
            }
        } catch (_: FileNotFoundException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
