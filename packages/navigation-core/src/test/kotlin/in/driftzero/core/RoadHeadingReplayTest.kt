package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.math.abs

/**
 * Synthetic road replay: v5-style YAW_SPEED_HOLD versus the same coast with
 * MATCHED heading. Official IO-VNBD is not scored here (no pinned extract
 * for those roads). Cross-track is east of a northbound centreline.
 */
class RoadHeadingReplayTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun replayCliAcceptsRoadGraph() {
        val parsed = Replay.parseArgs(
            arrayOf(
                "--input",
                "in.jsonl",
                "--output",
                "out.jsonl",
                "--coast-mode=yaw_speed_hold",
                "--road-graph=pack/graph.bin",
            ),
        )
        assertEquals(CoastMode.YAW_SPEED_HOLD, parsed.config.coastMode)
        assertEquals("pack/graph.bin", parsed.roadGraph.toString())
        val defaults = Replay.parseArgs(arrayOf("--input", "in.jsonl", "--output", "out.jsonl"))
        assertNull(defaults.roadGraph)
    }

    @Test
    fun mismatchedHeadingSingleRoadDropsCrossTrackVersusV5() {
        val trip = biasedNorthTrip(graph = RoadFixtures.singleRoad(lengthM = ROAD_M))
        val v5 = Replay.runFilter(trip.frames, DeadReckoningFilter(V5_CONFIG), trip.mask)
        val aided = Replay.runFilter(
            trip.frames,
            DeadReckoningFilter(V5_CONFIG),
            trip.mask,
            roadGraph = trip.graph,
        )
        val v5Last = lastCoast(v5, trip)
        val aidedLast = lastCoast(aided, trip)
        val v5East = eastM(trip, v5Last)
        val aidedEast = eastM(trip, aidedLast)
        assertTrue("v5 cross-track must grow, east=$v5East", abs(v5East) > 15.0)
        assertTrue(
            "MATCHED heading should cut cross-track vs v5: aided=$aidedEast v5=$v5East",
            abs(aidedEast) < 0.45 * abs(v5East),
        )
        assertTrue(
            "aided heading should stay nearer north, aided=${aidedLast.motion.heading.value} v5=${v5Last.motion.heading.value}",
            abs(signedHeadingDeltaRad(aidedLast.motion.heading.value, 0.0)) <
                abs(signedHeadingDeltaRad(v5Last.motion.heading.value, 0.0)),
        )
        assertEquals(MapMatchStatus.MATCHED, aidedLast.mapMatch.status)
        assertTrue(aided.any { it.health.flags.contains(DeadReckoningFilter.FLAG_ROAD_HEADING) })
        assertTrue(v5.none { it.health.flags.contains(DeadReckoningFilter.FLAG_ROAD_HEADING) })
        assertNoCentrelineSnap(trip, aided)
    }

    @Test
    fun osmXmlExtractAppliesHeadingWhenMatched() {
        val xml = folder.newFile("single.osm.xml").toPath()
        Files.writeString(xml, RoadFixtures.singleRoadOsmXml(lengthM = ROAD_M), StandardCharsets.UTF_8)
        val graph = OsmGraphLoader.load(xml, packageId = "fixture-osm-single")
        assertTrue(graph.edges.isNotEmpty())
        val trip = biasedNorthTrip(graph = graph)
        val v5 = Replay.runFilter(trip.frames, DeadReckoningFilter(V5_CONFIG), trip.mask)
        val aided = Replay.runFilter(
            trip.frames,
            DeadReckoningFilter(V5_CONFIG),
            trip.mask,
            roadGraph = graph,
        )
        val v5East = abs(eastM(trip, lastCoast(v5, trip)))
        val aidedEast = abs(eastM(trip, lastCoast(aided, trip)))
        assertTrue("OSM extract v5 east=$v5East", v5East > 15.0)
        assertTrue("OSM extract aided=$aidedEast v5=$v5East", aidedEast < 0.45 * v5East)
        assertEquals(MapMatchStatus.MATCHED, lastCoast(aided, trip).mapMatch.status)
        assertNoCentrelineSnap(trip, aided)

        val input = folder.newFile("frames.jsonl").toPath()
        writeTrip(input, trip)
        val out = folder.newFile("states.jsonl").toPath()
        val sink = java.io.PrintStream(java.io.ByteArrayOutputStream())
        val code = Replay.run(
            arrayOf(
                "--input",
                input.toString(),
                "--output",
                out.toString(),
                "--mask-start-ns",
                trip.mask.startNs.toString(),
                "--mask-end-ns",
                trip.mask.endNs.toString(),
                "--coast-mode=yaw_speed_hold",
                "--config",
                "nhcMinSpeedMps=100",
                "--config",
                "lowConfidenceRadiusM=10000",
                "--road-graph",
                xml.toString(),
            ),
            stdout = sink,
            stderr = sink,
        )
        assertEquals(0, code)
        assertTrue(Files.size(out) > 0L)
    }

    @Test
    fun parallelRoadsDoNotApplyHeading() {
        val graph = RoadFixtures.parallelRoads(lengthM = ROAD_M, sepM = 30.0)
        val trip = biasedNorthTrip(graph = graph, eastM = 15.0)
        val matcher = object : RoadMatcher {
            override fun update(state: FilterSnapshot, graph: RoadGraph): MapMatchResult =
                MapMatchResult(
                    match = MapMatch(MapMatchStatus.AMBIGUOUS, 0.48, roadSegmentId = "west"),
                    bestPosterior = 0.48,
                    secondPosterior = 0.42,
                    secondRoadSegmentId = "east",
                )
            override fun reset() = Unit
        }
        val v5 = Replay.runFilter(trip.frames, DeadReckoningFilter(V5_CONFIG), trip.mask)
        val mapped = Replay.runFilter(
            trip.frames,
            DeadReckoningFilter(V5_CONFIG),
            trip.mask,
            roadGraph = graph,
            roadMatcher = matcher,
        )
        val last = lastCoast(mapped, trip)
        assertEquals(MapMatchStatus.AMBIGUOUS, last.mapMatch.status)
        assertTrue(mapped.none { it.health.flags.contains(DeadReckoningFilter.FLAG_ROAD_HEADING) })
        val v5East = eastM(trip, lastCoast(v5, trip))
        val mappedEast = eastM(trip, last)
        assertTrue(
            "AMBIGUOUS must not pull heading; mapped=$mappedEast v5=$v5East",
            abs(mappedEast - v5East) < 8.0,
        )
    }

    @Test
    fun firstHeadingUpdateDoesNotMovePosition() {
        val graph = RoadFixtures.singleRoad(lengthM = ROAD_M)
        val filter = DeadReckoningFilter(V5_CONFIG)
        val trip = biasedNorthTrip(graph = graph)
        var lastBefore: NavigationState? = null
        var compared = 0
        val matcher = HmmRoadMatcher()
        for (frame in trip.frames) {
            filter.setGnssHeld(trip.mask.contains(frame.timestamp.value))
            if (trip.mask.drops(frame)) {
                continue
            }
            filter.consume(frame)
            val raw = filter.poseAt(frame.timestamp) ?: continue
            if (filter.isCoastingAt(frame.timestamp) && raw.motion.speed.value >= 1.0) {
                val beforeLat = raw.position.latitude.value
                val beforeLon = raw.position.longitude.value
                val tick = RoadHeadingFeedback.apply(
                    filter = filter,
                    pose = raw,
                    matcher = matcher,
                    graph = graph,
                    coasting = true,
                    now = frame.timestamp,
                )
                if (tick.decision?.prior != null) {
                    assertEquals(beforeLat, tick.pose.position.latitude.value, 1e-9)
                    assertEquals(beforeLon, tick.pose.position.longitude.value, 1e-9)
                    compared += 1
                    lastBefore = raw
                }
            } else {
                matcher.update(FilterSnapshot(raw), graph)
            }
        }
        assertTrue("expected heading applies, last=$lastBefore compared=$compared", compared >= 1)
    }

    private fun lastCoast(states: List<NavigationState>, trip: BiasedTrip): NavigationState {
        val during = states.filter { it.timestamp.value >= trip.mask.startNs }
        assertTrue(during.isNotEmpty())
        return during.last()
    }

    private fun eastM(trip: BiasedTrip, state: NavigationState): Double {
        return Wgs84.northEastMetres(
            trip.originLat,
            trip.originLon,
            state.position.latitude.value,
            state.position.longitude.value,
        ).second
    }

    private fun assertNoCentrelineSnap(trip: BiasedTrip, states: List<NavigationState>) {
        var prevEast: Double? = null
        for (state in states.filter { it.timestamp.value >= trip.mask.startNs }) {
            val east = eastM(trip, state)
            val prev = prevEast
            if (prev != null) {
                assertTrue(
                    "east jumped ${east - prev} m; heading must not snap lat/lon",
                    abs(east - prev) < 5.0,
                )
            }
            prevEast = east
        }
    }

    private fun writeTrip(path: java.nio.file.Path, trip: BiasedTrip) {
        val header = linkedMapOf<String, Any?>(
            "declared_rate_hz" to trip.imuHz,
            "clock_domain" to ClockDomain.DATASET_DECLARED.contractName(),
            "frame" to VectorFrame.VEHICLE_FLU.contractName(),
            "source_id" to "synthetic-road-heading",
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

    private fun biasedNorthTrip(graph: RoadGraph, eastM: Double = 0.0): BiasedTrip {
        val originLat = RoadFixtures.ORIGIN_LAT
        val originLon = RoadFixtures.ORIGIN_LON
        val fusedS = 8.0
        val maskS = 20.0
        val endS = fusedS + maskS
        val imuHz = 10.0
        val imuPeriodNs = (1_000_000_000.0 / imuHz).toLong()
        val gnssPeriodNs = 1_000_000_000L
        val maskStartNs = (fusedS * 1_000_000_000.0).toLong()
        val endNs = (endS * 1_000_000_000.0).toLong()
        val gravity = Wgs84.gravityMps2(originLat)
        val frames = ArrayList<SensorFrame>()
        var sequence = 0L
        var t = 0L
        while (t <= endNs) {
            val gz = if (t >= maskStartNs) GYRO_BIAS_RADPS else 0.0
            frames.add(gyroFrame(sequence++, t, gz))
            frames.add(accelFrame(sequence++, t, gravity))
            if (t % gnssPeriodNs == 0L && t < maskStartNs) {
                val northM = SPEED_MPS * (t / 1_000_000_000.0)
                val (lat, lon) = Wgs84.offsetMetres(originLat, originLon, northM, eastM)
                frames.add(gnssFrame(sequence++, t, lat, lon))
            }
            t += imuPeriodNs
        }
        return BiasedTrip(
            frames = frames,
            graph = graph,
            originLat = originLat,
            originLon = originLon,
            imuHz = imuHz,
            mask = GnssMaskInterval(maskStartNs, endNs),
        )
    }

    private fun accelFrame(sequence: Long, timestampNs: Long, gravity: Double): SensorFrame =
        SensorFrame(
            sourceId = "synthetic-road-heading",
            sequence = sequence,
            timestamp = Nanoseconds(timestampNs),
            clockDomain = ClockDomain.DATASET_DECLARED,
            kind = SensorKind.ACCELEROMETER,
            quality = Quality(available = true, accuracyCode = 3),
            payload = VectorPayload(
                Vector3Payload(0.0, 0.0, gravity, "m/s^2", VectorFrame.VEHICLE_FLU),
            ),
        )

    private fun gyroFrame(sequence: Long, timestampNs: Long, gz: Double): SensorFrame = SensorFrame(
        sourceId = "synthetic-road-heading",
        sequence = sequence,
        timestamp = Nanoseconds(timestampNs),
        clockDomain = ClockDomain.DATASET_DECLARED,
        kind = SensorKind.GYROSCOPE,
        quality = Quality(available = true, accuracyCode = 3),
        payload = VectorPayload(
            Vector3Payload(0.0, 0.0, gz, "rad/s", VectorFrame.VEHICLE_FLU),
        ),
    )

    private fun gnssFrame(
        sequence: Long,
        timestampNs: Long,
        latitudeDeg: Double,
        longitudeDeg: Double,
    ): SensorFrame = SensorFrame(
        sourceId = "synthetic-road-heading",
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
                speedMps = MetresPerSecond(SPEED_MPS),
                bearingRad = HeadingRadians(0.0),
            ),
        ),
    )

    private data class BiasedTrip(
        val frames: List<SensorFrame>,
        val graph: RoadGraph,
        val originLat: Double,
        val originLon: Double,
        val imuHz: Double,
        val mask: GnssMaskInterval,
    )

    companion object {
        private const val ROAD_M: Double = 800.0
        private const val SPEED_MPS: Double = 12.0
        /** About 1.15 deg/s. 20 s of v5 coast is ~0.4 rad, ~48 m east at 12 m/s. */
        private const val GYRO_BIAS_RADPS: Double = 0.02
        private val V5_CONFIG: InsConfig = InsConfig(
            coastMode = CoastMode.YAW_SPEED_HOLD,
            nhcMinSpeedMps = 100.0,
            lowConfidenceRadiusM = 10_000.0,
        )
    }
}
