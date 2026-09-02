package `in`.driftzero.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * File replay of `SensorFrame` JSONL for [DeadReckoningFilter].
 *
 * Units, axes, clock, and rate are taken from an optional first-line header
 * object (`declared_rate_hz`, `clock_domain`, `frame`, `source_id`) or from
 * the first IMU frame. Accel unit is `m/s^2`, gyro unit is `rad/s`, mag unit
 * is `uT`. Timestamps are integer nanoseconds in the declared [ClockDomain].
 * The adapter does not resample. 10 Hz, 100 Hz, and 200 Hz files are all
 * legal. Android APIs are not used.
 *
 * IO-VNBD (and any other dataset) must already be aligned and unit-checked
 * SensorFrame JSONL from `ml/`. This class does not know dataset column names.
 */
class ReplaySensorSource(
    override val descriptor: SensorSourceDescriptor,
    val header: ReplayFileHeader,
    private val loaded: List<SensorFrame>,
) : SensorSource {
    val frames: List<SensorFrame> get() = loaded

    override fun frames(): Flow<SensorFrame> = loaded.asFlow()
}

/**
 * Declared stream metadata. [imuFrame] is the first IMU payload frame
 * (`android_device`, `vehicle_flu`, or `unspecified`). [accelUnit] and
 * [gyroUnit] are the contract strings from that first IMU row.
 */
data class ReplayFileHeader(
    val sourceId: String,
    val clockDomain: ClockDomain,
    val declaredRateHz: Double,
    val imuFrame: VectorFrame,
    val accelUnit: String,
    val gyroUnit: String,
    val fromFileHeader: Boolean,
)

data class ReplayLoadStats(
    val framesRead: Int,
    val gapCount: Int,
    val droppedLines: Int,
    val firstTimestampNs: Long?,
    val lastTimestampNs: Long?,
)

sealed class ReplayLoadResult {
    data class Ready(
        val source: ReplaySensorSource,
        val stats: ReplayLoadStats,
    ) : ReplayLoadResult()

    data class Failed(
        val error: ReplayLoadError,
    ) : ReplayLoadResult()
}

sealed class ReplayLoadError {
    data class NonMonotonicTimestamp(
        val lineNumber: Int,
        val previousNs: Long,
        val timestampNs: Long,
    ) : ReplayLoadError() {
        override fun toString(): String =
            "line $lineNumber: timestamp_ns $timestampNs is before previous $previousNs"
    }

    data class EmptyInput(
        val path: String,
    ) : ReplayLoadError() {
        override fun toString(): String = "$path has no SensorFrame rows"
    }

    data class MissingDeclaredRate(
        val path: String,
    ) : ReplayLoadError() {
        override fun toString(): String =
            "$path has no declared_rate_hz header and rate cannot be estimated from IMU timestamps"
    }

    data class Unreadable(
        val path: String,
        val message: String,
    ) : ReplayLoadError() {
        override fun toString(): String = "$path: $message"
    }
}

/**
 * Inclusive mask on the recorded clock. GNSS kinds inside the interval are
 * dropped in the adapter so [DeadReckoningFilter.consume] never sees them.
 * This is the file equivalent of Android `setGnssHeld`: the filter gets no
 * fix, status, or raw GNSS update in the window. IMU frames still pass.
 */
data class GnssMaskInterval(
    val startNs: Long,
    val endNs: Long,
) {
    init {
        require(startNs >= 0L && endNs >= startNs) { "mask interval must be non-negative and ordered" }
    }

    fun contains(timestampNs: Long): Boolean = timestampNs in startNs..endNs

    fun drops(frame: SensorFrame): Boolean {
        if (frame.kind != SensorKind.GNSS_FIX &&
            frame.kind != SensorKind.GNSS_STATUS &&
            frame.kind != SensorKind.RAW_GNSS
        ) {
            return false
        }
        return contains(frame.timestamp.value)
    }
}

object ReplayJsonl {
    /**
     * Read one JSON object per line. Blank lines and `#` comments are ignored
     * without counting. Malformed or schema-invalid lines increment
     * [ReplayLoadStats.droppedLines] and are not fed to the filter. A backward
     * timestamp is [ReplayLoadResult.Failed], not a silent skip.
     *
     * A gap is a forward jump larger than two declared IMU periods.
     */
    fun load(
        path: Path,
        declaredRateHz: Double? = null,
    ): ReplayLoadResult {
        val lines = try {
            Files.readAllLines(path, StandardCharsets.UTF_8)
        } catch (error: Exception) {
            return ReplayLoadResult.Failed(ReplayLoadError.Unreadable(path.toString(), error.message ?: "read failed"))
        }
        return loadLines(lines, path.toString(), declaredRateHz)
    }

    fun loadLines(
        lines: List<String>,
        pathLabel: String = "<memory>",
        declaredRateHz: Double? = null,
    ): ReplayLoadResult {
        var fileHeader: Map<String, Any?>? = null
        val frames = ArrayList<SensorFrame>()
        var dropped = 0
        var previousNs: Long? = null
        for ((index, raw) in lines.withIndex()) {
            val lineNumber = index + 1
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) {
                continue
            }
            val obj = try {
                ContractJson.parseObject(line)
            } catch (error: IllegalArgumentException) {
                dropped += 1
                continue
            }
            if (fileHeader == null && frames.isEmpty() && isFileHeader(obj)) {
                fileHeader = obj
                continue
            }
            val frame = try {
                ContractMaps.sensorFrameFrom(obj)
            } catch (_: IllegalArgumentException) {
                dropped += 1
                continue
            } catch (_: IllegalStateException) {
                dropped += 1
                continue
            }
            val timestampNs = frame.timestamp.value
            val previous = previousNs
            if (previous != null && timestampNs < previous) {
                return ReplayLoadResult.Failed(
                    ReplayLoadError.NonMonotonicTimestamp(lineNumber, previous, timestampNs),
                )
            }
            previousNs = timestampNs
            frames.add(frame)
        }
        if (frames.isEmpty()) {
            return ReplayLoadResult.Failed(ReplayLoadError.EmptyInput(pathLabel))
        }
        val rate = try {
            declaredRateHz
                ?: headerRateHz(fileHeader)
                ?: estimateImuRateHz(frames)
                ?: return ReplayLoadResult.Failed(ReplayLoadError.MissingDeclaredRate(pathLabel))
        } catch (error: IllegalArgumentException) {
            return ReplayLoadResult.Failed(ReplayLoadError.Unreadable(pathLabel, error.message ?: "header"))
        }
        if (!rate.isFinite() || rate <= 0.0) {
            return ReplayLoadResult.Failed(
                ReplayLoadError.Unreadable(pathLabel, "declared_rate_hz must be positive and finite"),
            )
        }
        val firstImu = frames.firstOrNull { frame ->
            frame.kind == SensorKind.ACCELEROMETER || frame.kind == SensorKind.GYROSCOPE
        }
        val vector = (firstImu?.payload as? VectorPayload)?.vector
        val header = try {
            ReplayFileHeader(
                sourceId = headerString(fileHeader, "source_id") ?: frames.first().sourceId,
                clockDomain = headerClock(fileHeader) ?: frames.first().clockDomain,
                declaredRateHz = rate,
                imuFrame = headerFrame(fileHeader) ?: vector?.frame ?: VectorFrame.UNSPECIFIED,
                accelUnit = "m/s^2",
                gyroUnit = "rad/s",
                fromFileHeader = fileHeader != null,
            )
        } catch (error: IllegalArgumentException) {
            return ReplayLoadResult.Failed(ReplayLoadError.Unreadable(pathLabel, error.message ?: "header"))
        }
        val source = ReplaySensorSource(
            descriptor = SensorSourceDescriptor(
                sourceId = header.sourceId,
                declaredRateHz = header.declaredRateHz,
                clockDomain = header.clockDomain,
            ),
            header = header,
            loaded = frames,
        )
        val stats = ReplayLoadStats(
            framesRead = frames.size,
            gapCount = countGaps(frames, rate),
            droppedLines = dropped,
            firstTimestampNs = frames.first().timestamp.value,
            lastTimestampNs = frames.last().timestamp.value,
        )
        return ReplayLoadResult.Ready(source, stats)
    }

    internal fun countGaps(frames: List<SensorFrame>, declaredRateHz: Double): Int {
        val periodNs = (1_000_000_000.0 / declaredRateHz)
        val gapNs = 2.0 * periodNs
        var gaps = 0
        for (index in 1 until frames.size) {
            val dt = (frames[index].timestamp.value - frames[index - 1].timestamp.value).toDouble()
            if (dt > gapNs) {
                gaps += 1
            }
        }
        return gaps
    }

    private fun isFileHeader(obj: Map<String, Any?>): Boolean {
        return obj.containsKey("declared_rate_hz") && !obj.containsKey("kind")
    }

    private fun headerRateHz(header: Map<String, Any?>?): Double? {
        val raw = header?.get("declared_rate_hz") ?: return null
        return ContractJson.asDouble(raw, "declared_rate_hz")
    }

    private fun headerString(header: Map<String, Any?>?, key: String): String? {
        val raw = header?.get(key) ?: return null
        return ContractJson.asString(raw, key)
    }

    private fun headerClock(header: Map<String, Any?>?): ClockDomain? {
        val raw = header?.get("clock_domain") ?: return null
        val name = ContractJson.asString(raw, "clock_domain")
        return ClockDomain.entries.firstOrNull { it.contractName() == name }
            ?: throw IllegalArgumentException("unknown clock_domain: $name")
    }

    private fun headerFrame(header: Map<String, Any?>?): VectorFrame? {
        val raw = header?.get("frame") ?: return null
        val name = ContractJson.asString(raw, "frame")
        return VectorFrame.entries.firstOrNull { it.contractName() == name }
            ?: throw IllegalArgumentException("unknown frame: $name")
    }

    private fun estimateImuRateHz(frames: List<SensorFrame>): Double? {
        val kinds = listOf(SensorKind.ACCELEROMETER, SensorKind.GYROSCOPE)
        for (kind in kinds) {
            val times = frames.asSequence()
                .filter { it.kind == kind }
                .map { it.timestamp.value }
                .take(2)
                .toList()
            if (times.size < 2) {
                continue
            }
            val dt = times[1] - times[0]
            if (dt > 0L) {
                return 1_000_000_000.0 / dt.toDouble()
            }
        }
        return null
    }
}
