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
 * inclusive interval before the filter sees them. Replay also calls
 * [DeadReckoningFilter.setGnssHeld] for every timestamp in the interval so
 * still-ZUPT does not zero a moving coast. `--declared-rate-hz` overrides
 * the file header. `--config name=value` overrides an [InsConfig] Double field
 * (camelCase or snake_case). `--coast-mode=strapdown|yaw_speed_hold` selects
 * [InsConfig.coastMode]. `--persist-speed-pseudo` injects last accepted GNSS
 * speed through [DeadReckoningFilter.ingestMotionPseudo] while GNSS is held
 * (R std 1 m/s). Off by default. `--coast-stop-detect` enables the vibration
 * ZUPT during yaw-speed-hold. `--coast-restart=held_speed|accel_burst` selects
 * resume after that stop. `--weak-heading-policy=integrate|hold_course` selects
 * yaw behaviour when the heading-gyro pick is weak. `--heading-pick-quality=`
 * `weak|accepted|<file>` overrides gyro quality flags. `--coast-latch-gnss-speed`
 * latches last reported GNSS speed if it is within 2 s of coast start.
 * `--gnss-reseed-after-s=T` re-seeds pose after a unique-fix gap of at least T seconds,
 * while coasting. `--gnss-reseed-min-median-unique-s=M` also requires the trip's
 * median unique spacing so far to be at least M seconds. `--gnss-reseed-while-fused`
 * also allows that reseed during fused GNSS.
 * `--coast-honest-p` grows coast position P from held-speed uncertainty.
 * `--coast-speed-decay` decays held speed toward `coastSpeedDecayTargetMps`.
 * `--student-forward-speed` enables the gated learned forward-speed update. Default off.
 * Default [InsConfig.coastMode] remains [CoastMode.STRAPDOWN] so v1/v2 hashes stay.
 * Persist-like skip of a 0 m/s mask-start unique lives in [PersistCoastSeed]
 * and is not wired into Replay after the seed_premask named-interval abort.
 * `--engine` routes consume through
 * [DeadReckoningEngine] so a later student can be injected. Default remains
 * consume-only. Same input bytes yield the same output bytes.
 * `--road-graph=extract.osm|extract.pbf|graph.bin` uses the shared map path.
 * `--road-feedback=false` retains only its display overlay for ablations.
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
        args.headingPickWeak?.let { filter.setHeadingPickWeak(it, forced = true) }
        val consumed = ArrayList<SensorFrame>()
        val states = runFilter(
            frames = ready.source.frames,
            filter = filter,
            mask = args.mask,
            persistSpeedPseudo = args.persistSpeedPseudo,
            useEngine = args.useEngine,
            roadGraph = args.roadGraph?.let { path ->
                if (path.fileName.toString().endsWith(".bin")) RoadGraphBin.load(path).first
                else OsmGraphLoader.load(path)
            },
            roadFeedback = args.roadFeedback,
            onConsume = { consumed.add(it) },
        )
        val run = summaryOf(states, consumed.size, countMasked(ready.source.frames, args.mask))
        return ReplayExecution(states, ready.stats, run)
    }

    fun runFilter(
        frames: List<SensorFrame>,
        filter: DeadReckoningFilter = DeadReckoningFilter(),
        mask: GnssMaskInterval? = null,
        persistSpeedPseudo: Boolean = false,
        persistSpeedStdMps: Double = PERSIST_SPEED_PSEUDO_STD_MPS,
        useEngine: Boolean = false,
        onConsume: ((SensorFrame) -> Unit)? = null,
        roadGraph: RoadGraph? = null,
        roadMatcher: RoadMatcher? = null,
        roadFeedback: Boolean = true,
    ): List<NavigationState> {
        val states = ArrayList<NavigationState>()
        var lastEmitNs = -1L
        var lastGnssSpeedMps: Double? = null
        val matcher = roadMatcher ?: roadGraph?.let { HmmRoadMatcher() }
        val mapCoast = MapCoastSession().also { it.setGraph(roadGraph) }
        val engine = if (useEngine) DeadReckoningEngine(
            filter = filter, matcher = matcher, graph = roadGraph,
            mapCoast = mapCoast, mapFeedback = roadFeedback,
        ) else null
        fun injectPersist(timestamp: Nanoseconds) {
            val speed = lastGnssSpeedMps
            if (persistSpeedPseudo && filter.isGnssHeld() && speed != null) {
                filter.ingestMotionPseudo(persistSpeedMeasurement(speed, persistSpeedStdMps), timestamp)
            }
        }
        for (frame in frames) {
            if (mask != null) {
                filter.setGnssHeld(mask.contains(frame.timestamp.value))
                if (mask.drops(frame)) {
                    continue
                }
            }
            if (frame.kind == SensorKind.GNSS_FIX && frame.quality.available) {
                val speed = (frame.payload as FixPayload).fix.speedMps?.value
                if (speed != null) {
                    lastGnssSpeedMps = speed
                }
            }
            onConsume?.invoke(frame)
            if (engine != null) {
                val pose = engine.ingestForReplay(frame, ::injectPersist) ?: continue
                states.add(pose)
                continue
            }
            filter.consume(frame)
            val timestampNs = frame.timestamp.value
            if (lastEmitNs >= 0L && timestampNs - lastEmitNs < PERIOD_NS) {
                continue
            }
            injectPersist(frame.timestamp)
            val pose = filter.poseAt(frame.timestamp) ?: continue
            lastEmitNs = timestampNs
            val match = mapCoast.match(pose, matcher, roadGraph)
            val coasting = filter.isGnssHeld() || pose.gnssHealth.lastTrustedFixAgeS > filter.gnssStaleAfterS()
            if (match != null && roadFeedback && coasting) {
                mapCoast.apply(filter, match, pose, timestampNs)
            }
            val after = filter.poseAt(frame.timestamp) ?: pose
            states.add(if (match != null) after.withMapMatch(match) else after)
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
        var coastMode = CoastMode.STRAPDOWN
        var persistSpeedPseudo = false
        var coastStopDetect = false
        var coastRestart = CoastRestart.HELD_SPEED
        var useEngine = false
        var weakHeadingPolicy = WeakHeadingPolicy.INTEGRATE
        var headingPickWeak: Boolean? = null
        var coastLatchGnssSpeed = false
        var gnssReseedAfterS = 0.0
        var gnssReseedWhileFused = false
        var gnssReseedMinMedianUniqueS = 0.0
        var coastHonestP = false
        var coastSpeedDecay = false
        var studentForwardSpeed = false
        var roadGraph: Path? = null
        var roadFeedback = true
        var index = 0
        while (index < args.size) {
            val token = args[index]
            val (key, inline) = splitFlag(token)
            when (key) {
                "--input" -> input = Path.of(readValue(args, index, inline).also { if (inline == null) index++ })
                "--road-graph" -> roadGraph = Path.of(readValue(args, index, inline).also { if (inline == null) index++ })
                "--road-feedback" -> roadFeedback = readValue(args, index, inline).also { if (inline == null) index++ }.toBooleanStrict()
                "--output" -> output = Path.of(readValue(args, index, inline).also { if (inline == null) index++ })
                "--mask-start-ns" -> maskStart = readValue(args, index, inline).also { if (inline == null) index++ }.toLong()
                "--mask-end-ns" -> maskEnd = readValue(args, index, inline).also { if (inline == null) index++ }.toLong()
                "--declared-rate-hz" -> declaredRateHz = readValue(args, index, inline).also { if (inline == null) index++ }.toDouble()
                "--coast-mode" -> coastMode = parseCoastMode(readValue(args, index, inline).also { if (inline == null) index++ })
                "--persist-speed-pseudo" -> persistSpeedPseudo = inline?.toBoolean() ?: true
                "--coast-stop-detect" -> coastStopDetect = inline?.toBoolean() ?: true
                "--coast-restart" -> coastRestart = parseCoastRestart(readValue(args, index, inline).also { if (inline == null) index++ })
                "--engine" -> useEngine = inline?.toBoolean() ?: true
                "--weak-heading-policy" -> weakHeadingPolicy = parseWeakHeadingPolicy(
                    readValue(args, index, inline).also { if (inline == null) index++ },
                )
                "--heading-pick-quality" -> headingPickWeak = parseHeadingPickQuality(
                    readValue(args, index, inline).also { if (inline == null) index++ },
                )
                "--coast-latch-gnss-speed" -> coastLatchGnssSpeed = inline?.toBoolean() ?: true
                "--gnss-reseed-after-s" -> gnssReseedAfterS = readValue(args, index, inline).also { if (inline == null) index++ }.toDouble()
                "--gnss-reseed-while-fused" -> gnssReseedWhileFused = inline?.toBoolean() ?: true
                "--gnss-reseed-min-median-unique-s" -> gnssReseedMinMedianUniqueS = readValue(args, index, inline).also { if (inline == null) index++ }.toDouble()
                "--coast-honest-p" -> coastHonestP = inline?.toBoolean() ?: true
                "--coast-speed-decay" -> coastSpeedDecay = inline?.toBoolean() ?: true
                "--student-forward-speed" -> studentForwardSpeed = inline?.toBoolean() ?: true
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
            config = InsConfig(
                coastMode = coastMode,
                coastStopDetect = coastStopDetect,
                coastRestart = coastRestart,
                weakHeadingPolicy = weakHeadingPolicy,
                coastLatchGnssSpeed = coastLatchGnssSpeed,
                gnssReseedAfterS = gnssReseedAfterS,
                gnssReseedWhileFused = gnssReseedWhileFused,
                gnssReseedMinMedianUniqueS = gnssReseedMinMedianUniqueS,
                coastHonestP = coastHonestP,
                coastSpeedDecay = coastSpeedDecay,
                studentForwardSpeed = studentForwardSpeed,
            ).withOverrides(overrides),
            persistSpeedPseudo = persistSpeedPseudo,
            useEngine = useEngine,
            headingPickWeak = headingPickWeak,
            roadGraph = roadGraph,
            roadFeedback = roadFeedback,
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
    const val PERSIST_SPEED_PSEUDO_STD_MPS: Double = 1.0
}

data class ReplayCliArgs(
    val input: Path,
    val output: Path,
    val mask: GnssMaskInterval? = null,
    val declaredRateHz: Double? = null,
    val config: InsConfig = InsConfig(),
    val persistSpeedPseudo: Boolean = false,
    val useEngine: Boolean = false,
    val headingPickWeak: Boolean? = null,
    val roadGraph: Path? = null,
    val roadFeedback: Boolean = true,
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
        "zuptheldskipmps" -> copy(zuptHeldSkipMps = value)
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
        "gnssgaterejectsbeforeinflate" -> copy(gnssGateRejectsBeforeInflate = value.toInt().also { n ->
            require(n >= 1 && kotlin.math.abs(value - n) < 1e-9) { "gnssGateRejectsBeforeInflate must be an integer >= 1" }
        })
        "gnssgateinflatemaxsigmam" -> copy(gnssGateInflateMaxSigmaM = value)
        "gnssgateinflate" -> copy(gnssGateInflate = value != 0.0)
        "coaststopdetect" -> copy(coastStopDetect = value != 0.0)
        "coaststopaccelvar" -> copy(coastStopAccelVar = value)
        "coaststopgyroradps" -> copy(coastStopGyroRadps = value)
        "coaststopholds" -> copy(coastStopHoldS = value)
        "coaststoprestartdebounces" -> copy(coastStopRestartDebounceS = value)
        "coastrestart" -> copy(
            coastRestart = if (value == 0.0) CoastRestart.HELD_SPEED else CoastRestart.ACCEL_BURST,
        )
        "coastrestartbursts" -> copy(coastRestartBurstS = value)
        "coastrestartaccelclipmps2" -> copy(coastRestartAccelClipMps2 = value)
        "roadheadingchi2gate" -> copy(roadHeadingChi2Gate = value)
        "weakheadingpolicy" -> copy(
            weakHeadingPolicy = if (value == 0.0) WeakHeadingPolicy.INTEGRATE else WeakHeadingPolicy.HOLD_COURSE,
        )
        "weakheadinggrowradps" -> copy(weakHeadingGrowRadps = value)
        "coastlatchgnssspeed" -> copy(coastLatchGnssSpeed = value != 0.0)
        "coastlatchgnssmaxs" -> copy(coastLatchGnssMaxS = value)
        "coaststoprequirestoppedprefix" -> copy(coastStopRequireStoppedPrefix = value != 0.0)
        "coaststopmovingk" -> copy(coastStopMovingK = value)
        "coaststopmovingminmps" -> copy(coastStopMovingMinMps = value)
        "coaststopstoppedmaxmps" -> copy(coastStopStoppedMaxMps = value)
        "gnssreseedafters" -> copy(gnssReseedAfterS = value)
        "gnssreseedwhilefused" -> copy(gnssReseedWhileFused = value != 0.0)
        "gnssreseedminmedianuniques" -> copy(gnssReseedMinMedianUniqueS = value)
        "gnssreseedheadingmotionm" -> copy(gnssReseedHeadingMotionM = value)
        "gnssreseedslowmps" -> copy(gnssReseedSlowMps = value)
        "gnssreseedslowyawstdrad" -> copy(gnssReseedSlowYawStdRad = value)
        "coasthonestp" -> copy(coastHonestP = value != 0.0)
        "coastspeedrwmps" -> copy(coastSpeedRwMps = value)
        "coastheadingrwradps" -> copy(coastHeadingRwRadps = value)
        "studentforwardspeed" -> copy(studentForwardSpeed = value != 0.0)
        "studentspeedchi2gate" -> copy(studentSpeedChi2Gate = value)
        "studentspeedsigmafloormps" -> copy(studentSpeedSigmaFloorMps = value)
        "coastspeeddecay" -> copy(coastSpeedDecay = value != 0.0)
        "coastspeeddecaytaus" -> copy(coastSpeedDecayTauS = value)
        "coastspeeddecaytargetmps" -> copy(coastSpeedDecayTargetMps = value)
        "staleafters" -> copy(staleAfterS = value)
        "gnssquarantine" -> copy(gnssQuarantine = value != 0.0)
        "alongtrackchi2gate" -> copy(alongTrackChi2Gate = value)
        "alongtrackmaxabsm" -> copy(alongTrackMaxAbsM = value)
        else -> throw IllegalArgumentException("unknown InsConfig field: $name")
    }
}

internal fun parseCoastMode(raw: String): CoastMode {
    return when (raw.replace("-", "_").uppercase()) {
        "STRAPDOWN" -> CoastMode.STRAPDOWN
        "YAW_SPEED_HOLD" -> CoastMode.YAW_SPEED_HOLD
        else -> throw IllegalArgumentException("unknown coast mode: $raw")
    }
}

internal fun parseCoastRestart(raw: String): CoastRestart {
    return when (raw.replace("-", "_").uppercase()) {
        "HELD_SPEED", "A" -> CoastRestart.HELD_SPEED
        "ACCEL_BURST", "B" -> CoastRestart.ACCEL_BURST
        else -> throw IllegalArgumentException("unknown coast restart: $raw")
    }
}

internal fun parseWeakHeadingPolicy(raw: String): WeakHeadingPolicy {
    return when (raw.replace("-", "_").uppercase()) {
        "INTEGRATE" -> WeakHeadingPolicy.INTEGRATE
        "HOLD_COURSE" -> WeakHeadingPolicy.HOLD_COURSE
        else -> throw IllegalArgumentException("unknown weak heading policy: $raw")
    }
}

internal fun parseHeadingPickQuality(raw: String): Boolean {
    val trimmed = raw.trim()
    val key = trimmed.replace("-", "_").lowercase()
    when (key) {
        "weak", "fallback", "few_hops", "insufficient_fixes", "weak_corr" -> return true
        "accepted", "strong", "ok", "integrate" -> return false
    }
    val path = Path.of(trimmed)
    require(Files.isRegularFile(path)) { "unknown heading pick quality: $raw" }
    val text = Files.readString(path, StandardCharsets.UTF_8).trim()
    if (text.startsWith("{")) {
        val fallback = Regex(""""fallback"\s*:\s*(true|false)""").find(text)?.groupValues?.get(1)
        if (fallback != null) {
            return fallback == "true"
        }
        val reason = Regex(""""reason"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
        if (reason != null) {
            return parseHeadingPickQuality(reason)
        }
    }
    return parseHeadingPickQuality(text.trim('"'))
}

private fun persistSpeedMeasurement(speedMps: Double, stdMps: Double): MotionPseudoMeasurement {
    val sigma = if (stdMps.isFinite() && stdMps > 0.0) stdMps else Replay.PERSIST_SPEED_PSEUDO_STD_MPS
    return MotionPseudoMeasurement(
        forwardSpeed = MetresPerSecond(speedMps),
        yawRateRadps = 0.0,
        stopProbability = 0.0,
        logSpeedVariance = kotlin.math.ln(sigma * sigma),
    )
}

private fun normalizeConfigName(name: String): String =
    name.replace("_", "").lowercase()
