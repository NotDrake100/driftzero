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
        "position" to linkedMapOf(
            "latitude_deg" to state.position.latitude.value,
            "longitude_deg" to state.position.longitude.value,
        ),
        "motion" to linkedMapOf(
            "speed_mps" to state.motion.speed.value,
            "heading_rad" to state.motion.heading.value,
        ),
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
        "provenance" to linkedMapOf(
            "core_version" to state.provenance.coreVersion,
            "config_hash" to state.provenance.configHash,
        ),
    )

    private fun vectorMap(vector: Vector3Payload): Map<String, Any?> = linkedMapOf(
        "x" to vector.x,
        "y" to vector.y,
        "z" to vector.z,
        "unit" to vector.unit,
        "frame" to vector.frame.contractName(),
    )

    private fun fixMap(fix: GnssFixPayload): Map<String, Any?> = linkedMapOf(
        "latitude_deg" to fix.latitude.value,
        "longitude_deg" to fix.longitude.value,
        "horizontal_accuracy_m" to fix.horizontalAccuracyM.value,
        "provider_time_ms" to fix.providerTimeMs,
    )

    private fun statusMap(status: GnssStatusPayload): Map<String, Any?> = linkedMapOf(
        "satellites_visible" to status.satellitesVisible,
        "satellites_used" to status.satellitesUsed,
        "constellations" to status.constellations.toList(),
    )

    private fun rawMap(raw: RawGnssPayload): Map<String, Any?> = linkedMapOf(
        "constellation" to raw.constellation,
        "svid" to raw.svid,
    )
}
