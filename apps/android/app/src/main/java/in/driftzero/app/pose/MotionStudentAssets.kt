package `in`.driftzero.app.pose

import android.content.Context
import `in`.driftzero.core.LinearMotionStudent
import java.io.FileNotFoundException

/**
 * On-device linear speed student. Copied from
 * `models/motion_student_v1/linear.json` at assemble time when that file
 * exists. Missing or invalid JSON leaves [in.driftzero.core.ZuptAccelMotionModel]
 * on the vibration/ZUPT heuristic. No ONNX Runtime and no TimesFM.
 */
object MotionStudentAssets {
    const val ASSET_PATH: String = "motion_student_v1/linear.json"

    fun load(context: Context): LinearMotionStudent? {
        return try {
            context.assets.open(ASSET_PATH).bufferedReader().use { reader ->
                LinearMotionStudent.parse(reader.readText())
            }
        } catch (_: FileNotFoundException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
