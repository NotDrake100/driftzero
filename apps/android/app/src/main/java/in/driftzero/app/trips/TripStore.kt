package `in`.driftzero.app.trips

import `in`.driftzero.core.ContractWrite
import `in`.driftzero.core.GnssMaskInterval
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class TripSummary(
    val id: String,
    val dir: File,
    val startWallMs: Long,
    val startNs: Long,
    val endNs: Long,
    val distanceM: Double,
    val stateCount: Int,
    val drCount: Int,
    val holds: List<GnssMaskInterval>,
    val requestedSensorDelay: String = TripRecorder.REQUESTED_SENSOR_DELAY,
    val measuredAccelHz: Double? = null,
    val measuredGyroHz: Double? = null,
    val accelSampleCount: Int = 0,
    val gyroSampleCount: Int = 0,
    val accelMinDtNs: Long? = null,
    val accelMaxDtNs: Long? = null,
    val gyroMinDtNs: Long? = null,
    val gyroMaxDtNs: Long? = null,
    val droppedSensorFrames: Int = 0,
    val droppedStateFrames: Int = 0,
) {
    val durationS: Double
        get() = ((endNs - startNs).coerceAtLeast(0L)) / 1_000_000_000.0

    val drPercent: Int
        get() = if (stateCount <= 0) {
            0
        } else {
            ((drCount * 100.0) / stateCount.toDouble()).toInt().coerceIn(0, 100)
        }

    val sensorsFile: File get() = File(dir, TripRecorder.SENSORS)

    val statesFile: File get() = File(dir, TripRecorder.STATES)

    fun toManifest(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "start_wall_ms" to startWallMs,
        "start_ns" to startNs,
        "end_ns" to endNs,
        "distance_m" to distanceM,
        "state_count" to stateCount.toLong(),
        "dr_count" to drCount.toLong(),
        "hold_intervals" to holds.map { interval ->
            linkedMapOf(
                "start_ns" to interval.startNs,
                "end_ns" to interval.endNs,
            )
        },
        "requested_sensor_delay" to requestedSensorDelay,
        "declared_rate_hz" to TripRecorder.DECLARED_RATE_HZ,
        "measured_accel_hz" to measuredAccelHz,
        "measured_gyro_hz" to measuredGyroHz,
        "accel_sample_count" to accelSampleCount.toLong(),
        "gyro_sample_count" to gyroSampleCount.toLong(),
        "accel_min_dt_ns" to accelMinDtNs,
        "accel_max_dt_ns" to accelMaxDtNs,
        "gyro_min_dt_ns" to gyroMinDtNs,
        "gyro_max_dt_ns" to gyroMaxDtNs,
        "dropped_sensor_frames" to droppedSensorFrames.toLong(),
        "dropped_state_frames" to droppedStateFrames.toLong(),
    )
}

/** Per-trip directories under app files. List and delete are local only. */
class TripStore(private val root: File) {
    private val lock = Any()
    private var current: TripRecorder? = null

    fun start(wallMs: Long = System.currentTimeMillis()): TripRecorder {
        synchronized(lock) {
            current?.stop()
            val id = tripId(wallMs)
            val dir = File(root, id)
            dir.mkdirs()
            val recorder = TripRecorder(dir = dir, id = id, startWallMs = wallMs)
            recorder.start()
            current = recorder
            return recorder
        }
    }

    fun stop(): TripSummary? {
        synchronized(lock) {
            val recorder = current ?: return null
            current = null
            return recorder.stop()
        }
    }

    fun list(): List<TripSummary> {
        if (!root.isDirectory) {
            return emptyList()
        }
        return root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { readSummary(it) }
            ?.sortedByDescending { it.startWallMs }
            ?: emptyList()
    }

    fun delete(id: String): Boolean {
        synchronized(lock) {
            if (current?.id == id) {
                current?.stop()
                current = null
            }
        }
        val dir = File(root, id)
        if (!dir.isDirectory) {
            return false
        }
        return dir.deleteRecursively()
    }

    fun deleteAll(): Int {
        synchronized(lock) {
            current?.stop()
            current = null
        }
        val trips = list()
        var removed = 0
        for (trip in trips) {
            if (trip.dir.deleteRecursively()) {
                removed += 1
            }
        }
        return removed
    }

    fun exportZip(trip: TripSummary, dest: File): File {
        dest.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(dest)).use { zip ->
            for (name in listOf(TripRecorder.SENSORS, TripRecorder.STATES, TripRecorder.MANIFEST)) {
                val file = File(trip.dir, name)
                if (!file.isFile) {
                    continue
                }
                zip.putNextEntry(ZipEntry("${trip.id}/$name"))
                FileInputStream(file).use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return dest
    }

    companion object {
        fun tripId(wallMs: Long): String = "trip-${wallMs.coerceAtLeast(0L)}"

        internal fun readSummary(dir: File): TripSummary? {
            val manifest = File(dir, TripRecorder.MANIFEST)
            if (manifest.isFile) {
                return parseManifest(dir, manifest.readText())
            }
            if (!File(dir, TripRecorder.SENSORS).isFile && !File(dir, TripRecorder.STATES).isFile) {
                return null
            }
            return TripSummary(
                id = dir.name,
                dir = dir,
                startWallMs = dir.lastModified().coerceAtLeast(0L),
                startNs = 0L,
                endNs = 0L,
                distanceM = 0.0,
                stateCount = 0,
                drCount = 0,
                holds = emptyList(),
            )
        }

        internal fun parseManifest(dir: File, text: String): TripSummary? {
            val obj = try {
                ContractWrite.parseObject(text)
            } catch (_: IllegalArgumentException) {
                return null
            }
            val id = obj["id"] as? String ?: dir.name
            val holds = (obj["hold_intervals"] as? List<*>)?.mapNotNull { row ->
                val item = row as? Map<*, *> ?: return@mapNotNull null
                val start = longField(item["start_ns"]) ?: return@mapNotNull null
                val end = longField(item["end_ns"]) ?: return@mapNotNull null
                if (end < start) {
                    null
                } else {
                    GnssMaskInterval(start, end)
                }
            } ?: emptyList()
            return TripSummary(
                id = id,
                dir = dir,
                startWallMs = longField(obj["start_wall_ms"]) ?: 0L,
                startNs = longField(obj["start_ns"]) ?: 0L,
                endNs = longField(obj["end_ns"]) ?: 0L,
                distanceM = doubleField(obj["distance_m"]) ?: 0.0,
                stateCount = longField(obj["state_count"])?.toInt() ?: 0,
                drCount = longField(obj["dr_count"])?.toInt() ?: 0,
                holds = holds,
                requestedSensorDelay = obj["requested_sensor_delay"] as? String
                    ?: TripRecorder.REQUESTED_SENSOR_DELAY,
                measuredAccelHz = doubleField(obj["measured_accel_hz"]),
                measuredGyroHz = doubleField(obj["measured_gyro_hz"]),
                accelSampleCount = longField(obj["accel_sample_count"])?.toInt() ?: 0,
                gyroSampleCount = longField(obj["gyro_sample_count"])?.toInt() ?: 0,
                accelMinDtNs = longField(obj["accel_min_dt_ns"]),
                accelMaxDtNs = longField(obj["accel_max_dt_ns"]),
                gyroMinDtNs = longField(obj["gyro_min_dt_ns"]),
                gyroMaxDtNs = longField(obj["gyro_max_dt_ns"]),
                droppedSensorFrames = longField(obj["dropped_sensor_frames"])?.toInt() ?: 0,
                droppedStateFrames = longField(obj["dropped_state_frames"])?.toInt() ?: 0,
            )
        }

        private fun longField(value: Any?): Long? = when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Double -> if (value.isFinite() && value % 1.0 == 0.0) value.toLong() else null
            else -> null
        }

        private fun doubleField(value: Any?): Double? = when (value) {
            is Double -> value.takeIf { it.isFinite() }
            is Long -> value.toDouble()
            is Int -> value.toDouble()
            else -> null
        }
    }
}
