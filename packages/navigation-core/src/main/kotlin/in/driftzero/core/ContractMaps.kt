package `in`.driftzero.core

/** Map domain types onto the JSON contract keys used by Python and Android. */
object ContractMaps {
    fun sensorFrame(frame: SensorFrame): Map<String, Any?> {
        val payload: Map<String, Any?> = when (val body = frame.payload) {
            is VectorPayload -> vectorMap(body.vector)
            is FixPayload -> fixMap(body.fix)
            is StatusPayload -> statusMap(body.status)
            is RawPayload -> rawMap(body.raw)
        }
        return linkedMapOf(
            "schema_version" to frame.schemaVersion,
            "source_id" to frame.sourceId,
            "sequence" to frame.sequence,
            "timestamp_ns" to frame.timestamp.value,
            "clock_domain" to frame.clockDomain.contractName(),
            "kind" to frame.kind.contractName(),
            "quality" to linkedMapOf(
                "available" to frame.quality.available,
                "accuracy_code" to frame.quality.accuracyCode,
                "flags" to frame.quality.flags.toList(),
            ),
            "payload" to payload,
        )
    }

    fun navigationState(state: NavigationState): Map<String, Any?> = linkedMapOf(
        "schema_version" to state.schemaVersion,
        "sequence" to state.sequence,
        "timestamp_ns" to state.timestamp.value,
        "mode" to state.mode.name,
        "position" to linkedMapOf<String, Any?>(
            "latitude_deg" to state.position.latitude.value,
            "longitude_deg" to state.position.longitude.value,
        ).apply {
            state.position.altitudeM?.let { put("altitude_m", it) }
        },
        "motion" to linkedMapOf<String, Any?>(
            "speed_mps" to state.motion.speed.value,
            "heading_rad" to state.motion.heading.value,
        ).apply {
            state.motion.yawRateRadps?.let { put("yaw_rate_radps", it) }
        },
        "uncertainty" to linkedMapOf(
            "horizontal_95_m" to state.uncertainty.horizontal95.value,
            "heading_95_rad" to state.uncertainty.heading95Rad,
            "is_calibrated" to state.uncertainty.isCalibrated,
        ),
        "gnss_health" to linkedMapOf(
            "score" to state.gnssHealth.score,
            "last_trusted_fix_age_s" to state.gnssHealth.lastTrustedFixAgeS,
            "risk_flags" to state.gnssHealth.riskFlags.toList(),
        ),
        "map_match" to linkedMapOf<String, Any?>(
            "status" to state.mapMatch.status.name,
            "confidence" to state.mapMatch.confidence,
        ).apply {
            state.mapMatch.roadSegmentId?.let { put("road_segment_id", it) }
        },
        "health" to linkedMapOf(
            "sensor_ok" to state.health.sensorOk,
            "model_ok" to state.health.modelOk,
            "filter_ok" to state.health.filterOk,
            "map_ok" to state.health.mapOk,
            "flags" to state.health.flags.toList(),
        ),
        "provenance" to linkedMapOf<String, Any?>(
            "core_version" to state.provenance.coreVersion,
            "config_hash" to state.provenance.configHash,
        ).apply {
            state.provenance.modelVersion?.let { put("model_version", it) }
            state.provenance.mapPackageId?.let { put("map_package_id", it) }
        },
    )

    /**
     * Parse one `contracts/sensor_frame.schema.json` object.
     * Unknown top-level keys other than `metadata` are rejected.
     */
    fun sensorFrameFrom(obj: Map<String, Any?>): SensorFrame {
        val extra = obj.keys - SENSOR_FRAME_KEYS
        require(extra.isEmpty()) { "SensorFrame unknown fields: ${extra.sorted().joinToString()}" }
        require(ContractJson.asString(obj["schema_version"], "schema_version") == SCHEMA_VERSION) {
            "schema_version must be $SCHEMA_VERSION"
        }
        val kind = parseKind(ContractJson.asString(obj["kind"], "kind"))
        return SensorFrame(
            sourceId = ContractJson.asString(obj["source_id"], "source_id"),
            sequence = ContractJson.asLong(obj["sequence"], "sequence"),
            timestamp = Nanoseconds(ContractJson.asLong(obj["timestamp_ns"], "timestamp_ns")),
            clockDomain = parseClock(ContractJson.asString(obj["clock_domain"], "clock_domain")),
            kind = kind,
            quality = parseQuality(ContractJson.asObject(obj["quality"], "quality")),
            payload = parsePayload(kind, ContractJson.asObject(obj["payload"], "payload")),
            schemaVersion = SCHEMA_VERSION,
        )
    }

    /**
     * Parse one `contracts/navigation_state.schema.json` object.
     * Unknown top-level keys are rejected.
     */
    fun navigationStateFrom(obj: Map<String, Any?>): NavigationState {
        val extra = obj.keys - NAVIGATION_STATE_KEYS
        require(extra.isEmpty()) { "NavigationState unknown fields: ${extra.sorted().joinToString()}" }
        require(ContractJson.asString(obj["schema_version"], "schema_version") == SCHEMA_VERSION) {
            "schema_version must be $SCHEMA_VERSION"
        }
        val position = ContractJson.asObject(obj["position"], "position")
        val motion = ContractJson.asObject(obj["motion"], "motion")
        val uncertainty = ContractJson.asObject(obj["uncertainty"], "uncertainty")
        val gnssHealth = ContractJson.asObject(obj["gnss_health"], "gnss_health")
        val mapMatch = ContractJson.asObject(obj["map_match"], "map_match")
        val health = ContractJson.asObject(obj["health"], "health")
        val provenance = ContractJson.asObject(obj["provenance"], "provenance")
        return NavigationState(
            sequence = ContractJson.asLong(obj["sequence"], "sequence"),
            timestamp = Nanoseconds(ContractJson.asLong(obj["timestamp_ns"], "timestamp_ns")),
            mode = NavigationMode.valueOf(ContractJson.asString(obj["mode"], "mode")),
            position = GeoPoint(
                latitude = LatitudeDeg(ContractJson.asDouble(position["latitude_deg"], "latitude_deg")),
                longitude = LongitudeDeg(ContractJson.asDouble(position["longitude_deg"], "longitude_deg")),
                altitudeM = position["altitude_m"]?.let { ContractJson.asDouble(it, "altitude_m") },
            ),
            motion = Motion(
                speed = MetresPerSecond(ContractJson.asDouble(motion["speed_mps"], "speed_mps")),
                heading = HeadingRadians(ContractJson.asDouble(motion["heading_rad"], "heading_rad")),
                yawRateRadps = motion["yaw_rate_radps"]?.let { ContractJson.asDouble(it, "yaw_rate_radps") },
            ),
            uncertainty = Uncertainty(
                horizontal95 = Metres(ContractJson.asDouble(uncertainty["horizontal_95_m"], "horizontal_95_m")),
                heading95Rad = ContractJson.asDouble(uncertainty["heading_95_rad"], "heading_95_rad"),
                isCalibrated = ContractJson.asBoolean(uncertainty["is_calibrated"], "is_calibrated"),
            ),
            gnssHealth = GnssHealth(
                score = ContractJson.asDouble(gnssHealth["score"], "score"),
                lastTrustedFixAgeS = ContractJson.asDouble(
                    gnssHealth["last_trusted_fix_age_s"],
                    "last_trusted_fix_age_s",
                ),
                riskFlags = ContractJson.asStringList(gnssHealth["risk_flags"], "risk_flags").toCollection(LinkedHashSet()),
            ),
            mapMatch = MapMatch(
                status = MapMatchStatus.valueOf(ContractJson.asString(mapMatch["status"], "status")),
                confidence = ContractJson.asDouble(mapMatch["confidence"], "confidence"),
                roadSegmentId = mapMatch["road_segment_id"]?.let { ContractJson.asString(it, "road_segment_id") },
            ),
            health = ComponentHealth(
                sensorOk = ContractJson.asBoolean(health["sensor_ok"], "sensor_ok"),
                modelOk = ContractJson.asBoolean(health["model_ok"], "model_ok"),
                filterOk = ContractJson.asBoolean(health["filter_ok"], "filter_ok"),
                mapOk = ContractJson.asBoolean(health["map_ok"], "map_ok"),
                flags = ContractJson.asStringList(health["flags"], "flags").toCollection(LinkedHashSet()),
            ),
            provenance = Provenance(
                coreVersion = ContractJson.asString(provenance["core_version"], "core_version"),
                configHash = ContractJson.asString(provenance["config_hash"], "config_hash"),
                modelVersion = provenance["model_version"]?.let { ContractJson.asString(it, "model_version") },
                mapPackageId = provenance["map_package_id"]?.let { ContractJson.asString(it, "map_package_id") },
            ),
            schemaVersion = SCHEMA_VERSION,
        )
    }

    private fun vectorMap(vector: Vector3Payload): Map<String, Any?> = linkedMapOf<String, Any?>(
        "x" to vector.x,
        "y" to vector.y,
        "z" to vector.z,
        "unit" to vector.unit,
        "frame" to vector.frame.contractName(),
    ).apply {
        vector.biasX?.let { put("bias_x", it) }
        vector.biasY?.let { put("bias_y", it) }
        vector.biasZ?.let { put("bias_z", it) }
    }

    private fun fixMap(fix: GnssFixPayload): Map<String, Any?> = linkedMapOf<String, Any?>(
        "latitude_deg" to fix.latitude.value,
        "longitude_deg" to fix.longitude.value,
        "horizontal_accuracy_m" to fix.horizontalAccuracyM.value,
        "provider_time_ms" to fix.providerTimeMs,
    ).apply {
        fix.altitudeM?.let { put("altitude_m", it) }
        fix.speedMps?.let { put("speed_mps", it.value) }
        fix.bearingRad?.let { put("bearing_rad", it.value) }
        fix.isMock?.let { put("is_mock", it) }
        fix.speedAccuracyMps?.let { put("speed_accuracy_mps", it.value) }
        fix.bearingAccuracyRad?.let { put("bearing_accuracy_rad", it) }
        fix.verticalAccuracyM?.let { put("vertical_accuracy_m", it.value) }
    }

    private fun statusMap(status: GnssStatusPayload): Map<String, Any?> = linkedMapOf(
        "satellites_visible" to status.satellitesVisible,
        "satellites_used" to status.satellitesUsed,
        "constellations" to status.constellations.toList(),
    )

    private fun rawMap(raw: RawGnssPayload): Map<String, Any?> = linkedMapOf(
        "constellation" to raw.constellation,
        "svid" to raw.svid,
    )

    private fun parseQuality(obj: Map<String, Any?>): Quality {
        val extra = obj.keys - QUALITY_KEYS
        require(extra.isEmpty()) { "quality unknown fields: ${extra.sorted().joinToString()}" }
        return Quality(
            available = ContractJson.asBoolean(obj["available"], "available"),
            accuracyCode = ContractJson.asLong(obj["accuracy_code"], "accuracy_code").toInt(),
            flags = ContractJson.asStringList(obj["flags"], "flags").toCollection(LinkedHashSet()),
        )
    }

    private fun parsePayload(kind: SensorKind, obj: Map<String, Any?>): SensorPayload = when (kind) {
        SensorKind.ACCELEROMETER,
        SensorKind.GYROSCOPE,
        SensorKind.MAGNETOMETER,
        SensorKind.GRAVITY,
        SensorKind.LINEAR_ACCELERATION,
        SensorKind.GYROSCOPE_UNCALIBRATED,
        ->
            VectorPayload(parseVector(obj))
        SensorKind.GNSS_FIX -> FixPayload(parseFix(obj))
        SensorKind.GNSS_STATUS -> StatusPayload(parseStatus(obj))
        SensorKind.RAW_GNSS -> RawPayload(parseRaw(obj))
    }

    private fun parseVector(obj: Map<String, Any?>): Vector3Payload {
        val extra = obj.keys - VECTOR_KEYS
        require(extra.isEmpty()) { "vector payload unknown fields: ${extra.sorted().joinToString()}" }
        return Vector3Payload(
            x = ContractJson.asDouble(obj["x"], "x"),
            y = ContractJson.asDouble(obj["y"], "y"),
            z = ContractJson.asDouble(obj["z"], "z"),
            unit = ContractJson.asString(obj["unit"], "unit"),
            frame = parseFrame(ContractJson.asString(obj["frame"], "frame")),
            biasX = obj["bias_x"]?.let { ContractJson.asDouble(it, "bias_x") },
            biasY = obj["bias_y"]?.let { ContractJson.asDouble(it, "bias_y") },
            biasZ = obj["bias_z"]?.let { ContractJson.asDouble(it, "bias_z") },
        )
    }

    private fun parseFix(obj: Map<String, Any?>): GnssFixPayload {
        val extra = obj.keys - FIX_KEYS
        require(extra.isEmpty()) { "gnss_fix unknown fields: ${extra.sorted().joinToString()}" }
        return GnssFixPayload(
            latitude = LatitudeDeg(ContractJson.asDouble(obj["latitude_deg"], "latitude_deg")),
            longitude = LongitudeDeg(ContractJson.asDouble(obj["longitude_deg"], "longitude_deg")),
            horizontalAccuracyM = Metres(
                ContractJson.asDouble(obj["horizontal_accuracy_m"], "horizontal_accuracy_m"),
            ),
            providerTimeMs = ContractJson.asLong(obj["provider_time_ms"], "provider_time_ms"),
            altitudeM = obj["altitude_m"]?.let { ContractJson.asDouble(it, "altitude_m") },
            speedMps = obj["speed_mps"]?.let { MetresPerSecond(ContractJson.asDouble(it, "speed_mps")) },
            bearingRad = obj["bearing_rad"]?.let { HeadingRadians(ContractJson.asDouble(it, "bearing_rad")) },
            isMock = obj["is_mock"]?.let { ContractJson.asBoolean(it, "is_mock") },
            speedAccuracyMps = obj["speed_accuracy_mps"]?.let {
                MetresPerSecond(ContractJson.asDouble(it, "speed_accuracy_mps"))
            },
            bearingAccuracyRad = obj["bearing_accuracy_rad"]?.let {
                ContractJson.asDouble(it, "bearing_accuracy_rad")
            },
            verticalAccuracyM = obj["vertical_accuracy_m"]?.let {
                Metres(ContractJson.asDouble(it, "vertical_accuracy_m"))
            },
        )
    }

    private fun parseStatus(obj: Map<String, Any?>): GnssStatusPayload {
        val extra = obj.keys - STATUS_KEYS
        require(extra.isEmpty()) { "gnss_status unknown fields: ${extra.sorted().joinToString()}" }
        val constellations = obj["constellations"]?.let {
            ContractJson.asStringList(it, "constellations").toCollection(LinkedHashSet())
        } ?: emptySet()
        return GnssStatusPayload(
            satellitesVisible = ContractJson.asLong(
                obj["satellites_visible"],
                "satellites_visible",
            ).toInt(),
            satellitesUsed = ContractJson.asLong(obj["satellites_used"], "satellites_used").toInt(),
            constellations = constellations,
        )
    }

    private fun parseRaw(obj: Map<String, Any?>): RawGnssPayload {
        return RawGnssPayload(
            constellation = ContractJson.asString(obj["constellation"], "constellation"),
            svid = ContractJson.asLong(obj["svid"], "svid").toInt(),
        )
    }

    private fun parseClock(name: String): ClockDomain = ClockDomain.entries.firstOrNull {
        it.contractName() == name
    } ?: throw IllegalArgumentException("unknown clock_domain: $name")

    private fun parseKind(name: String): SensorKind = SensorKind.entries.firstOrNull {
        it.contractName() == name
    } ?: throw IllegalArgumentException("unknown kind: $name")

    private fun parseFrame(name: String): VectorFrame = VectorFrame.entries.firstOrNull {
        it.contractName() == name
    } ?: throw IllegalArgumentException("unknown frame: $name")

    private val SENSOR_FRAME_KEYS = setOf(
        "schema_version",
        "source_id",
        "sequence",
        "timestamp_ns",
        "clock_domain",
        "kind",
        "quality",
        "payload",
        "metadata",
    )
    private val NAVIGATION_STATE_KEYS = setOf(
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
    private val QUALITY_KEYS = setOf("available", "accuracy_code", "flags")
    private val VECTOR_KEYS = setOf("x", "y", "z", "unit", "frame", "bias_x", "bias_y", "bias_z")
    private val FIX_KEYS = setOf(
        "latitude_deg",
        "longitude_deg",
        "horizontal_accuracy_m",
        "provider_time_ms",
        "altitude_m",
        "vertical_accuracy_m",
        "speed_mps",
        "speed_accuracy_mps",
        "bearing_rad",
        "bearing_accuracy_rad",
        "is_mock",
    )
    private val STATUS_KEYS = setOf(
        "satellites_visible",
        "satellites_used",
        "constellations",
        "mean_cn0_dbhz",
    )
}
