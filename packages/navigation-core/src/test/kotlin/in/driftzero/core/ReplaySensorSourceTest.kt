package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class ReplaySensorSourceTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun writtenStateSchemaRoundTrip() {
        val trip = constantVelocityNorth(imuHz = 100.0)
        val filter = DeadReckoningFilter(SYNTHETIC_CONFIG)
        val states = Replay.runFilter(trip.frames, filter, trip.mask)
        assertTrue(states.isNotEmpty())
        val json = ContractJson.stringify(ContractMaps.navigationState(states.first()))
        val parsed = ContractMaps.navigationStateFrom(ContractJson.parseObject(json))
        assertEquals(json, ContractJson.stringify(ContractMaps.navigationState(parsed)))
        val keys = ContractJson.parseObject(json).keys
        NAV_REQUIRED.forEach { key -> assertTrue(key, keys.contains(key)) }
    }

    @Test
    fun nonMonotonicTimestampReturnsError() {
        val good = accelFrame(0L, 0L)
        val late = accelFrame(1L, 20_000_000L)
        val early = accelFrame(2L, 10_000_000L)
        val result = ReplayJsonl.loadLines(
            listOf(
                ContractJson.stringify(ContractMaps.sensorFrame(good)),
                ContractJson.stringify(ContractMaps.sensorFrame(late)),
                ContractJson.stringify(ContractMaps.sensorFrame(early)),
            ),
            pathLabel = "mono.jsonl",
            declaredRateHz = 100.0,
        )
        val failed = result as ReplayLoadResult.Failed
        val error = failed.error as ReplayLoadError.NonMonotonicTimestamp
        assertEquals(3, error.lineNumber)
        assertEquals(20_000_000L, error.previousNs)
        assertEquals(10_000_000L, error.timestampNs)
    }

    @Test
    fun constantVelocity100HzMaskedEndsNearTruth() {
        val trip = constantVelocityNorth(imuHz = 100.0)
        val consumed = ArrayList<SensorFrame>()
        val states = Replay.runFilter(
            trip.frames,
            DeadReckoningFilter(SYNTHETIC_CONFIG),
            trip.mask,
        ) { consumed.add(it) }
        assertModes(states, trip)
        val last = states.last()
        val errorM = Wgs84.distanceMetres(
            last.position.latitude.value,
            last.position.longitude.value,
            trip.endLatitudeDeg,
            trip.endLongitudeDeg,
        )
        assertTrue(
            "endpoint error $errorM m exceeds ${ENDPOINT_TOLERANCE_M}m " +
                "(15 m is 7.5 percent of the 200 m coast, under the SIH 10 percent gate)",
            errorM <= ENDPOINT_TOLERANCE_M,
        )
        assertEquals(trip.endNs, last.timestamp.value)
        assertTrue(consumed.none { trip.mask.drops(it) })
    }

    @Test
    fun twoHundredHzTrajectoryMatchesOneHundredHz() {
        val at100 = constantVelocityNorth(imuHz = 100.0)
        val at200 = constantVelocityNorth(imuHz = 200.0)
        val states100 = Replay.runFilter(
            at100.frames,
            DeadReckoningFilter(SYNTHETIC_CONFIG),
            at100.mask,
        )
        val states200 = Replay.runFilter(
            at200.frames,
            DeadReckoningFilter(SYNTHETIC_CONFIG),
            at200.mask,
        )
        val byTime = states200.associateBy { it.timestamp.value }
        var compared = 0
        var worst = 0.0
        for (state in states100) {
            val other = byTime[state.timestamp.value] ?: continue
            val d = Wgs84.distanceMetres(
                state.position.latitude.value,
                state.position.longitude.value,
                other.position.latitude.value,
                other.position.longitude.value,
            )
            if (d > worst) {
                worst = d
            }
            compared += 1
        }
        assertTrue("expected shared 10 Hz stamps, compared=$compared", compared >= 250)
        assertTrue(
            "100 Hz vs 200 Hz max distance $worst m exceeds ${RATE_INDEPENDENCE_M}m " +
                "(3 m is 1 percent of 300 m and larger than one 10 Hz sample at 10 m/s)",
            worst <= RATE_INDEPENDENCE_M,
        )
    }

    @Test
    fun maskedGnssNeverReachesFilter() {
        val trip = constantVelocityNorth(imuHz = 100.0)
        val consumed = ArrayList<SensorFrame>()
        Replay.runFilter(
            trip.frames,
            DeadReckoningFilter(SYNTHETIC_CONFIG),
            trip.mask,
        ) { consumed.add(it) }
        val maskedGnss = trip.frames.filter { trip.mask.drops(it) }
        assertTrue(maskedGnss.isNotEmpty())
        assertTrue(maskedGnss.any { it.kind == SensorKind.GNSS_FIX })
        assertTrue(consumed.none { it.kind == SensorKind.GNSS_FIX && trip.mask.contains(it.timestamp.value) })
        assertTrue(consumed.none { trip.mask.drops(it) })
        val decoy = maskedGnss.first { frame ->
            frame.kind == SensorKind.GNSS_FIX &&
                (frame.payload as FixPayload).fix.latitude.value > trip.originLatitudeDeg + 0.5
        }
        val states = Replay.runFilter(
            trip.frames,
            DeadReckoningFilter(SYNTHETIC_CONFIG),
            trip.mask,
        )
        val last = states.last()
        val toDecoy = Wgs84.distanceMetres(
            last.position.latitude.value,
            last.position.longitude.value,
            (decoy.payload as FixPayload).fix.latitude.value,
            (decoy.payload as FixPayload).fix.longitude.value,
        )
        assertTrue("filter accepted masked decoy, distance $toDecoy m", toDecoy > 50_000.0)
    }

    @Test
    fun maskSetsGpsHeldAndKeepsSpeed() {
        val trip = constantVelocityNorth(imuHz = 100.0)
        val states = Replay.runFilter(
            trip.frames,
            DeadReckoningFilter(SYNTHETIC_CONFIG),
            trip.mask,
        )
        val during = states.filter { it.timestamp.value >= trip.mask.startNs }
        assertTrue(during.isNotEmpty())
        assertTrue(during.all { it.health.flags.contains(DeadReckoningFilter.FLAG_GPS_HELD) })
        val mid = during[during.size / 2]
        assertEquals(NavigationMode.DEAD_RECKONING, mid.mode)
        assertTrue("held coast should keep seed speed, got ${mid.motion.speed.value}", mid.motion.speed.value > 8.0)
    }

    @Test
    fun summaryCountsMatchInput() {
        val lines = listOf(
            """{"declared_rate_hz":100.0,"clock_domain":"dataset_declared","frame":"vehicle_flu","source_id":"count"}""",
            "",
            "# comment",
            ContractJson.stringify(ContractMaps.sensorFrame(accelFrame(0L, 0L))),
            "not-json",
            ContractJson.stringify(ContractMaps.sensorFrame(gyroFrame(1L, 10_000_000L))),
            ContractJson.stringify(ContractMaps.sensorFrame(gnssFrame(2L, 1_000_000_000L, 0.0, 0.0))),
        )
        val loaded = ReplayJsonl.loadLines(lines, pathLabel = "counts.jsonl") as ReplayLoadResult.Ready
        assertEquals(3, loaded.stats.framesRead)
        assertEquals(1, loaded.stats.droppedLines)
        assertEquals(1, loaded.stats.gapCount)
        assertEquals(0L, loaded.stats.firstTimestampNs)
        assertEquals(1_000_000_000L, loaded.stats.lastTimestampNs)
        assertTrue(loaded.source.header.fromFileHeader)
        assertEquals(100.0, loaded.source.descriptor.declaredRateHz, 0.0)

        val path = folder.newFile("counts.jsonl").toPath()
        Files.write(path, lines, StandardCharsets.UTF_8)
        val mask = GnssMaskInterval(1_000_000_000L, 1_000_000_000L)
        val code = Replay.run(
            arrayOf(
                "--input",
                path.toString(),
                "--output",
                folder.newFile("out.jsonl").toPath().toString(),
                "--mask-start-ns",
                "1000000000",
                "--mask-end-ns",
                "1000000000",
            ),
            stdout = java.io.PrintStream(java.io.ByteArrayOutputStream()),
            stderr = java.io.PrintStream(java.io.ByteArrayOutputStream()),
        )
        assertEquals(0, code)
        val execution = Replay.execute(
            ReplayCliArgs(input = path, output = folder.newFile("out2.jsonl").toPath(), mask = mask),
        )
        assertEquals(loaded.stats.framesRead, execution.load.framesRead)
        assertEquals(loaded.stats.droppedLines, execution.load.droppedLines)
        assertEquals(loaded.stats.gapCount, execution.load.gapCount)
        assertEquals(1, execution.run.maskedGnssDropped)
        assertEquals(execution.load.framesRead - execution.run.maskedGnssDropped, execution.run.consumedFrames)
    }

    @Test
    fun replayCliDefaultCoastModeStaysStrapdown() {
        val defaults = Replay.parseArgs(
            arrayOf("--input", "in.jsonl", "--output", "out.jsonl"),
        )
        assertEquals(CoastMode.STRAPDOWN, defaults.config.coastMode)
        assertTrue(!defaults.config.coastLatchGnssSpeed)
        assertTrue(!defaults.config.coastHonestP)
        assertTrue(!defaults.config.studentForwardSpeed)
    }

    @Test
    fun replayCliAcceptsCoastModeAndPersistSpeedPseudo() {
        val parsed = Replay.parseArgs(
            arrayOf(
                "--input",
                "in.jsonl",
                "--output",
                "out.jsonl",
                "--coast-mode=yaw_speed_hold",
                "--persist-speed-pseudo",
            ),
        )
        assertEquals(CoastMode.YAW_SPEED_HOLD, parsed.config.coastMode)
        assertTrue(parsed.persistSpeedPseudo)
        val defaults = Replay.parseArgs(
            arrayOf("--input", "in.jsonl", "--output", "out.jsonl"),
        )
        assertEquals(CoastMode.STRAPDOWN, defaults.config.coastMode)
        assertEquals(0.0, defaults.config.gnssReseedAfterS, 0.0)
        assertEquals(0.0, defaults.config.gnssReseedMinMedianUniqueS, 0.0)
        assertTrue(!defaults.config.studentForwardSpeed)
        assertTrue(!defaults.config.coastSpeedDecay)
        assertTrue(!defaults.persistSpeedPseudo)
        assertTrue(!defaults.useEngine)
        assertTrue(!defaults.config.coastStopDetect)
        val v4 = Replay.parseArgs(
            arrayOf(
                "--input",
                "in.jsonl",
                "--output",
                "out.jsonl",
                "--coast-mode=yaw_speed_hold",
                "--coast-stop-detect",
                "--coast-restart=accel_burst",
                "--engine",
            ),
        )
        assertTrue(v4.config.coastStopDetect)
        assertEquals(CoastRestart.ACCEL_BURST, v4.config.coastRestart)
        assertTrue(v4.useEngine)
        val v5 = Replay.parseArgs(
            arrayOf(
                "--input",
                "in.jsonl",
                "--output",
                "out.jsonl",
                "--coast-mode=yaw_speed_hold",
                "--weak-heading-policy=hold_course",
                "--heading-pick-quality=weak",
                "--coast-latch-gnss-speed",
                "--config",
                "coastStopRequireStoppedPrefix=1",
            ),
        )
        assertEquals(WeakHeadingPolicy.HOLD_COURSE, v5.config.weakHeadingPolicy)
        assertEquals(true, v5.headingPickWeak)
        assertTrue(v5.config.coastLatchGnssSpeed)
        assertTrue(v5.config.coastStopRequireStoppedPrefix)
        assertEquals(false, Replay.parseArgs(
            arrayOf(
                "--input",
                "in.jsonl",
                "--output",
                "out.jsonl",
                "--heading-pick-quality=accepted",
            ),
        ).headingPickWeak)
        val v6 = Replay.parseArgs(
            arrayOf(
                "--input",
                "in.jsonl",
                "--output",
                "out.jsonl",
                "--coast-mode=yaw_speed_hold",
                "--gnss-reseed-after-s=3",
                "--coast-honest-p",
                "--coast-speed-decay",
                "--config",
                "coastSpeedDecayTargetMps=8",
            ),
        )
        assertEquals(3.0, v6.config.gnssReseedAfterS, 0.0)
        assertTrue(v6.config.coastHonestP)
        assertTrue(v6.config.coastSpeedDecay)
        assertEquals(8.0, v6.config.coastSpeedDecayTargetMps, 0.0)
        assertTrue(!v6.config.studentForwardSpeed)
        assertTrue(!v6.config.gnssReseedWhileFused)
        val v7 = Replay.parseArgs(
            arrayOf(
                "--input",
                "in.jsonl",
                "--output",
                "out.jsonl",
                "--gnss-reseed-after-s=6",
                "--gnss-reseed-while-fused",
                "--coast-honest-p",
            ),
        )
        assertEquals(6.0, v7.config.gnssReseedAfterS, 0.0)
        assertTrue(v7.config.gnssReseedWhileFused)
        assertTrue(v7.config.coastHonestP)
        val v8 = Replay.parseArgs(
            arrayOf(
                "--input",
                "in.jsonl",
                "--output",
                "out.jsonl",
                "--gnss-reseed-after-s=8",
                "--gnss-reseed-min-median-unique-s=8",
                "--coast-honest-p",
            ),
        )
        assertEquals(8.0, v8.config.gnssReseedAfterS, 0.0)
        assertEquals(8.0, v8.config.gnssReseedMinMedianUniqueS, 0.0)
        assertTrue(v8.config.coastHonestP)
        assertTrue(!v8.config.gnssReseedWhileFused)
    }

    @Test
    fun persistSpeedPseudoOffByDefaultAndFlagsWhenEnabled() {
        val trip = constantVelocityNorth(imuHz = 100.0)
        val off = Replay.runFilter(trip.frames, DeadReckoningFilter(SYNTHETIC_CONFIG), trip.mask)
        val duringOff = off.filter { it.timestamp.value >= trip.mask.startNs }
        assertTrue(duringOff.isNotEmpty())
        assertTrue(duringOff.none { it.health.flags.contains(DeadReckoningFilter.FLAG_MOTION_PSEUDO) })
        val on = Replay.runFilter(
            trip.frames,
            DeadReckoningFilter(SYNTHETIC_CONFIG.copy(studentForwardSpeed = true)),
            trip.mask,
            persistSpeedPseudo = true,
        )
        val duringOn = on.filter { it.timestamp.value >= trip.mask.startNs }
        assertTrue(duringOn.isNotEmpty())
        assertTrue(duringOn.any { it.health.flags.contains(DeadReckoningFilter.FLAG_MOTION_PSEUDO) })
        val mid = duringOn[duringOn.size / 2]
        assertTrue("persist speed should hold ~10 m/s, got ${mid.motion.speed.value}", mid.motion.speed.value > 8.0)
    }

    @Test
    fun yawSpeedHoldReplayMatchesNorthCoast() {
        val trip = constantVelocityNorth(imuHz = 100.0)
        val states = Replay.runFilter(
            trip.frames,
            DeadReckoningFilter(
                SYNTHETIC_CONFIG.copy(coastMode = CoastMode.YAW_SPEED_HOLD),
            ),
            trip.mask,
        )
        val last = states.last()
        val errorM = Wgs84.distanceMetres(
            last.position.latitude.value,
            last.position.longitude.value,
            trip.endLatitudeDeg,
            trip.endLongitudeDeg,
        )
        assertTrue("YAW_SPEED_HOLD endpoint $errorM m", errorM <= ENDPOINT_TOLERANCE_M)
        val mid = states.first { it.timestamp.value >= trip.mask.startNs + 2_000_000_000L }
        assertTrue(mid.motion.speed.value > 8.0)
    }

    @Test
    fun engineReplayKeepsNavigationStateSchema() {
        val trip = constantVelocityNorth(imuHz = 100.0)
        val consume = Replay.runFilter(trip.frames, DeadReckoningFilter(SYNTHETIC_CONFIG), trip.mask)
        val engine = Replay.runFilter(
            trip.frames,
            DeadReckoningFilter(SYNTHETIC_CONFIG),
            trip.mask,
            useEngine = true,
        )
        assertTrue(consume.isNotEmpty())
        assertTrue(engine.isNotEmpty())
        val consumeKeys = ContractJson.parseObject(ContractJson.stringify(ContractMaps.navigationState(consume.first()))).keys
        val engineKeys = ContractJson.parseObject(ContractJson.stringify(ContractMaps.navigationState(engine.first()))).keys
        assertEquals(consumeKeys, engineKeys)
        NAV_REQUIRED.forEach { key -> assertTrue(key, engineKeys.contains(key)) }
    }

    @Test
    fun replayCliIsDeterministic() {
        val trip = constantVelocityNorth(imuHz = 100.0)
        val input = folder.newFile("in.jsonl").toPath()
        writeTrip(input, trip)
        val firstOut = folder.newFile("a.jsonl").toPath()
        val secondOut = folder.newFile("b.jsonl").toPath()
        val args = arrayOf(
            "--input",
            input.toString(),
            "--mask-start-ns",
            trip.mask.startNs.toString(),
            "--mask-end-ns",
            trip.mask.endNs.toString(),
            "--config",
            "nhcMinSpeedMps=100",
            "--config",
            "lowConfidenceRadiusM=10000",
        )
        val sink = java.io.PrintStream(java.io.ByteArrayOutputStream())
        assertEquals(0, Replay.run(args + arrayOf("--output", firstOut.toString()), stdout = sink, stderr = sink))
        assertEquals(0, Replay.run(args + arrayOf("--output", secondOut.toString()), stdout = sink, stderr = sink))
        assertEquals(
            Files.readAllBytes(firstOut).toList(),
            Files.readAllBytes(secondOut).toList(),
        )
    }

    private fun assertModes(states: List<NavigationState>, trip: SyntheticTrip) {
        val before = states.filter { it.timestamp.value < trip.mask.startNs }
        val during = states.filter { it.timestamp.value >= trip.mask.startNs + 2_100_000_000L }
        assertTrue(before.isNotEmpty())
        assertTrue(during.isNotEmpty())
        assertTrue(before.all { it.mode == NavigationMode.GNSS_FUSED })
        assertTrue(during.all { it.mode == NavigationMode.DEAD_RECKONING })
    }

    private fun writeTrip(path: java.nio.file.Path, trip: SyntheticTrip) {
        val header = linkedMapOf<String, Any?>(
            "declared_rate_hz" to trip.imuHz,
            "clock_domain" to ClockDomain.DATASET_DECLARED.contractName(),
            "frame" to VectorFrame.VEHICLE_FLU.contractName(),
            "source_id" to "synthetic-cv",
        )
        Files.newBufferedWriter(path, StandardCharsets.UTF_8).use { writer ->
            writer.write(ContractJson.stringify(header))
            writer.write("\n")
            for (frame in trip.frames) {
                writer.write(ContractJson.stringify(ContractMaps.sensorFrame(frame)))
                writer.write("\n")
            }
        }
    }

    private fun constantVelocityNorth(imuHz: Double): SyntheticTrip {
        val originLat = 0.0
        val originLon = 0.0
        val speed = 10.0
        val fusedS = 10.0
        val maskS = 20.0
        val endS = fusedS + maskS
        val imuPeriodNs = (1_000_000_000.0 / imuHz).toLong()
        val gnssPeriodNs = 1_000_000_000L
        val maskStartNs = (fusedS * 1_000_000_000.0).toLong()
        val endNs = (endS * 1_000_000_000.0).toLong()
        val decoyNs = maskStartNs + 10_000_000_000L
        val gravity = Wgs84.gravityMps2(originLat)
        val frames = ArrayList<SensorFrame>()
        var sequence = 0L
        var t = 0L
        while (t <= endNs) {
            frames.add(gyroFrame(sequence++, t))
            frames.add(accelFrame(sequence++, t, gravity))
            if (t % gnssPeriodNs == 0L) {
                val northM = speed * (t / 1_000_000_000.0)
                val (lat, lon, _) = Wgs84.enuToGeodetic(originLat, originLon, 0.0, 0.0, northM, 0.0)
                frames.add(gnssFrame(sequence++, t, lat, lon, speed))
            }
            if (t == decoyNs) {
                frames.add(gnssFrame(sequence++, t, originLat + 1.0, originLon, speed))
            }
            t += imuPeriodNs
        }
        val (endLat, endLon, _) = Wgs84.enuToGeodetic(originLat, originLon, 0.0, 0.0, speed * endS, 0.0)
        return SyntheticTrip(
            frames = frames,
            imuHz = imuHz,
            originLatitudeDeg = originLat,
            originLongitudeDeg = originLon,
            endLatitudeDeg = endLat,
            endLongitudeDeg = endLon,
            mask = GnssMaskInterval(maskStartNs, endNs),
            endNs = endNs,
        )
    }

    private fun accelFrame(sequence: Long, timestampNs: Long, gravity: Double = Wgs84.gravityMps2(0.0)): SensorFrame =
        SensorFrame(
            sourceId = "synthetic-cv",
            sequence = sequence,
            timestamp = Nanoseconds(timestampNs),
            clockDomain = ClockDomain.DATASET_DECLARED,
            kind = SensorKind.ACCELEROMETER,
            quality = Quality(available = true, accuracyCode = 3),
            payload = VectorPayload(
                Vector3Payload(0.0, 0.0, gravity, "m/s^2", VectorFrame.VEHICLE_FLU),
            ),
        )

    private fun gyroFrame(sequence: Long, timestampNs: Long): SensorFrame = SensorFrame(
        sourceId = "synthetic-cv",
        sequence = sequence,
        timestamp = Nanoseconds(timestampNs),
        clockDomain = ClockDomain.DATASET_DECLARED,
        kind = SensorKind.GYROSCOPE,
        quality = Quality(available = true, accuracyCode = 3),
        payload = VectorPayload(
            Vector3Payload(0.0, 0.0, 0.0, "rad/s", VectorFrame.VEHICLE_FLU),
        ),
    )

    private fun gnssFrame(
        sequence: Long,
        timestampNs: Long,
        latitudeDeg: Double,
        longitudeDeg: Double,
        speedMps: Double = 10.0,
    ): SensorFrame = SensorFrame(
        sourceId = "synthetic-cv",
        sequence = sequence,
        timestamp = Nanoseconds(timestampNs),
        clockDomain = ClockDomain.DATASET_DECLARED,
        kind = SensorKind.GNSS_FIX,
        quality = Quality(available = true, accuracyCode = 3),
        payload = FixPayload(
            GnssFixPayload(
                latitude = LatitudeDeg(latitudeDeg),
                longitude = LongitudeDeg(longitudeDeg),
                horizontalAccuracyM = Metres(3.0),
                providerTimeMs = timestampNs / 1_000_000L,
                speedMps = MetresPerSecond(speedMps),
                bearingRad = HeadingRadians(0.0),
            ),
        ),
    )

    private data class SyntheticTrip(
        val frames: List<SensorFrame>,
        val imuHz: Double,
        val originLatitudeDeg: Double,
        val originLongitudeDeg: Double,
        val endLatitudeDeg: Double,
        val endLongitudeDeg: Double,
        val mask: GnssMaskInterval,
        val endNs: Long,
    )

    companion object {
        // 15 m is 7.5 percent of the 200 m masked coast at 10 m/s. Freeze would miss by 200 m.
        private const val ENDPOINT_TOLERANCE_M: Double = 15.0
        // 3 m is 1 percent of the 300 m run and larger than one 10 Hz sample at 10 m/s.
        private const val RATE_INDEPENDENCE_M: Double = 3.0
        // NHC off so the endpoint is strapdown coast. Radius raised so 20 s of P growth
        // stays DEAD_RECKONING instead of the 120 m LOW_CONFIDENCE ceiling.
        private val SYNTHETIC_CONFIG: InsConfig = InsConfig(
            nhcMinSpeedMps = 100.0,
            lowConfidenceRadiusM = 10_000.0,
        )
        private val NAV_REQUIRED = listOf(
            "schema_version",
            "sequence",
            "timestamp_ns",
            "mode",
            "position",
            "motion",
            "uncertainty",
            "gnss_health",
            "map_match",
            "health",
            "provenance",
        )
    }
}
