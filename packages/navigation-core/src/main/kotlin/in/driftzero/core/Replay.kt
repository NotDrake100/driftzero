package `in`.driftzero.core

import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

data class ReplayRunSummary(
    val statesWritten: Int,
    val maskedGnssDropped: Int,
    val consumedFrames: Int,
    val modes: Map<NavigationMode, Int>,
    val firstTimestampNs: Long?,
    val lastTimestampNs: Long?,
)

data class ReplayExecution(
    val states: List<NavigationState>,
    val load: ReplayLoadStats,
    val run: ReplayRunSummary,
)

/**
 * JVM replay entry. Drives [DeadReckoningFilter] at recorded integer-nanosecond
 * timestamps and writes 10 Hz [NavigationState] JSONL on that clock.
 *
 * Usage:
 * ```
 * JAVA_HOME="$(/usr/libexec/java_home -v 17)" ./gradlew :navigation-core:replay --args="--input frames.jsonl --output states.jsonl"
 * ```
 *
 * Optional: `--mask-start-ns` and `--mask-end-ns` drop GNSS kinds in that
 * inclusive interval before the filter sees them. `--declared-rate-hz` overrides
 * the file header. `--config name=value` overrides an [InsConfig] Double field
 * (camelCase or snake_case). Same input bytes yield the same output bytes.
 * No wall clock and no randomness are used in the loop.
 *
 * IO-VNBD frames are produced by `ml/` as aligned, unit-checked SensorFrame
 * JSONL. This adapter is dataset-agnostic. Hidden truth GNSS is never in the
 * input during a mask. Scoring is Python (`ml/src/driftzero_ml/eval_navstate.py`).
 */
object Replay {
    @JvmStatic
    fun main(args: Array<String>) {
        val code = run(args)
        if (code != 0) {
            exitProcess(code)
        }
    }

    fun run(
        args: Array<String>,
        stdout: PrintStream = System.out,
        stderr: PrintStream = System.err,
    ): Int {
        val parsed = try {
            parseArgs(args)
        } catch (error: IllegalArgumentException) {
            stderr.println(error.message)
            return 2
        }
        val execution = try {
            execute(parsed)
        } catch (error: ReplayFailedException) {
            stderr.println(error.message)
            return 1
        } catch (error: IllegalArgumentException) {
            stderr.println(error.message)
            return 2
        }
        writeStates(parsed.output, execution.states)
        stdout.println(formatSummary(execution.load, execution.run))
        return 0
    }

    fun execute(args: ReplayCliArgs): ReplayExecution {
        val loaded = ReplayJsonl.load(args.input, args.declaredRateHz)
        val ready = when (loaded) {
            is ReplayLoadResult.Ready -> loaded
            is ReplayLoadResult.Failed -> throw ReplayFailedException(loaded.error.toString())
        }
        val filter = DeadReckoningFilter(args.config)
        val consumed = ArrayList<SensorFrame>()
        val states = runFilter(
            frames = ready.source.frames,
            filter = filter,
            mask = args.mask,
            onConsume = { consumed.add(it) },
        )
        val run = summaryOf(states, consumed.size, countMasked(ready.source.frames, args.mask))
        return ReplayExecution(states, ready.stats, run)
    }

    fun runFilter(
        frames: List<SensorFrame>,
        filter: DeadReckoningFilter = DeadReckoningFilter(),
        mask: GnssMaskInterval? = null,
        onConsume: ((SensorFrame) -> Unit)? = null,
    ): List<NavigationState> {
        val states = ArrayList<NavigationState>()
        var lastEmitNs = -1L
        for (frame in frames) {
            if (mask != null && mask.drops(frame)) {
                continue
            }
            onConsume?.invoke(frame)
            filter.consume(frame)
            val timestampNs = frame.timestamp.value
            if (lastEmitNs >= 0L && timestampNs - lastEmitNs < PERIOD_NS) {
                continue
            }
            val pose = filter.poseAt(frame.timestamp) ?: continue
            lastEmitNs = timestampNs
            states.add(pose)
        }
        return states
    }

    fun formatSummary(load: ReplayLoadStats, run: ReplayRunSummary): String {
        val modes = NavigationMode.entries
            .mapNotNull { mode -> run.modes[mode]?.let { count -> "${mode.name}:$count" } }
            .joinToString(",")
        return buildString {
            append("frames_read=").append(load.framesRead).append('\n')
            append("gaps=").append(load.gapCount).append('\n')
            append("dropped_lines=").append(load.droppedLines).append('\n')
            append("states_written=").append(run.statesWritten).append('\n')
            append("masked_gnss_dropped=").append(run.maskedGnssDropped).append('\n')
            append("consumed_frames=").append(run.consumedFrames).append('\n')
            append("modes=").append(modes).append('\n')
            append("first_timestamp_ns=").append(run.firstTimestampNs ?: "").append('\n')
            append("last_timestamp_ns=").append(run.lastTimestampNs ?: "")
        }
    }

    internal fun parseArgs(args: Array<String>): ReplayCliArgs {
        var input: Path? = null
        var output: Path? = null
        var maskStart: Long? = null
        var maskEnd: Long? = null
        var declaredRateHz: Double? = null
        val overrides = LinkedHashMap<String, Double>()
        var index = 0
        while (index < args.size) {
            val token = args[index]
            val (key, inline) = splitFlag(token)
            when (key) {
                "--input" -> input = Path.of(readValue(args, index, inline).also { if (inline == null) index++ })
                "--output" -> output = Path.of(readValue(args, index, inline).also { if (inline == null) index++ })
                "--mask-start-ns" -> maskStart = readValue(args, index, inline).also { if (inline == null) index++ }.toLong()
                "--mask-end-ns" -> maskEnd = readValue(args, index, inline).also { if (inline == null) index++ }.toLong()
                "--declared-rate-hz" -> declaredRateHz = readValue(args, index, inline).also { if (inline == null) index++ }.toDouble()
                "--config" -> {
                    val spec = readValue(args, index, inline).also { if (inline == null) index++ }
                    val eq = spec.indexOf('=')
                    require(eq > 0) { "--config needs name=value" }
                    overrides[spec.substring(0, eq)] = spec.substring(eq + 1).toDouble()
                }
                else -> throw IllegalArgumentException("unknown argument: $token")
            }
            index++
        }
        val inputPath = input ?: throw IllegalArgumentException("missing --input")
        val outputPath = output ?: throw IllegalArgumentException("missing --output")
        val mask = when {
            maskStart == null && maskEnd == null -> null
            maskStart != null && maskEnd != null -> GnssMaskInterval(maskStart, maskEnd)
            else -> throw IllegalArgumentException("--mask-start-ns and --mask-end-ns must be given together")
        }
        return ReplayCliArgs(
            input = inputPath,
            output = outputPath,
            mask = mask,
            declaredRateHz = declaredRateHz,
            config = InsConfig().withOverrides(overrides),
        )
    }

    internal fun writeStates(path: Path, states: List<NavigationState>) {
        path.parent?.let { Files.createDirectories(it) }
        Files.newBufferedWriter(path, StandardCharsets.UTF_8).use { writer ->
            for (state in states) {
                writer.write(ContractJson.stringify(ContractMaps.navigationState(state)))
                writer.write("\n")
            }
        }
    }

    private fun summaryOf(
        states: List<NavigationState>,
        consumedFrames: Int,
        maskedGnssDropped: Int,
    ): ReplayRunSummary {
        val modes = LinkedHashMap<NavigationMode, Int>()
        for (state in states) {
            modes[state.mode] = (modes[state.mode] ?: 0) + 1
        }
        return ReplayRunSummary(
            statesWritten = states.size,
            maskedGnssDropped = maskedGnssDropped,
            consumedFrames = consumedFrames,
            modes = modes,
            firstTimestampNs = states.firstOrNull()?.timestamp?.value,
            lastTimestampNs = states.lastOrNull()?.timestamp?.value,
        )
    }

    private fun countMasked(frames: List<SensorFrame>, mask: GnssMaskInterval?): Int {
        if (mask == null) {
            return 0
        }
        return frames.count { mask.drops(it) }
    }

    private fun splitFlag(token: String): Pair<String, String?> {
        val eq = token.indexOf('=')
        return if (eq > 0) {
            token.substring(0, eq) to token.substring(eq + 1)
        } else {
            token to null
        }
    }

    private fun readValue(args: Array<String>, index: Int, inline: String?): String {
        if (inline != null) {
            return inline
        }
        require(index + 1 < args.size) { "missing value for ${args[index]}" }
        return args[index + 1]
    }

    private val PERIOD_NS: Long = (1_000_000_000.0 / DeadReckoningFilter.OUTPUT_HZ).toLong()
}

data class ReplayCliArgs(
    val input: Path,
    val output: Path,
    val mask: GnssMaskInterval? = null,
    val declaredRateHz: Double? = null,
    val config: InsConfig = InsConfig(),
)

internal class ReplayFailedException(message: String) : Exception(message)

internal fun InsConfig.withOverrides(overrides: Map<String, Double>): InsConfig {
    var config = this
    for ((name, value) in overrides) {
        require(value.isFinite()) { "InsConfig $name must be finite" }
        config = config.overrideField(name, value)
    }
    return config
}

private fun InsConfig.overrideField(name: String, value: Double): InsConfig {
    return when (normalizeConfigName(name)) {
        "accelnoise" -> copy(accelNoise = value)
        "gyronoise" -> copy(gyroNoise = value)
        "accelbiasrw" -> copy(accelBiasRw = value)
        "gyrobiasrw" -> copy(gyroBiasRw = value)
        "maxintegrates" -> copy(maxIntegrateS = value)
        "maxgnssaccuracym" -> copy(maxGnssAccuracyM = value)
        "initvelstdmps" -> copy(initVelStdMps = value)
        "inittiltstdrad" -> copy(initTiltStdRad = value)
        "inityawstdrad" -> copy(initYawStdRad = value)
        "initaccelbiasstd" -> copy(initAccelBiasStd = value)
        "initgyrobiasstd" -> copy(initGyroBiasStd = value)
        "zuptaccelmps2" -> copy(zuptAccelMps2 = value)
        "zuptgyroradps" -> copy(zuptGyroRadps = value)
        "zuptaccelvar" -> copy(zuptAccelVar = value)
        "zuptstopprobability" -> copy(zuptStopProbability = value)
        "zuptvelstdmps" -> copy(zuptVelStdMps = value)
        "nhcminspeedmps" -> copy(nhcMinSpeedMps = value)
        "nhcdroplateralmps2" -> copy(nhcDropLateralMps2 = value)
        "nhcvelstdmps" -> copy(nhcVelStdMps = value)
        "speedpseudostdmps" -> copy(speedPseudoStdMps = value)
        "maxaccelbias" -> copy(maxAccelBias = value)
        "maxgyrobias" -> copy(maxGyroBias = value)
        "reanchorm" -> copy(reanchorM = value)
        "coastposgrowmps" -> copy(coastPosGrowMps = value)
        "lowconfidenceradiusm" -> copy(lowConfidenceRadiusM = value)
        "displacementchi2gate" -> copy(displacementChi2Gate = value)
        "displacementoverlaprscale" -> copy(displacementOverlapRScale = value)
        "displacementgategrowm" -> copy(displacementGateGrowM = value)
        else -> throw IllegalArgumentException("unknown InsConfig field: $name")
    }
}

private fun normalizeConfigName(name: String): String =
    name.replace("_", "").lowercase()
