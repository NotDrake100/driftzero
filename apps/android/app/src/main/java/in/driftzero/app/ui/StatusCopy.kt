package `in`.driftzero.app.ui

import `in`.driftzero.app.maps.AreaPack
import `in`.driftzero.app.pose.LocationGrant
import `in`.driftzero.app.pose.NavicSnapshot
import `in`.driftzero.app.pose.PoseStore
import `in`.driftzero.core.DeadReckoningFilter
import `in`.driftzero.core.MapMatchStatus
import `in`.driftzero.core.MountQuality
import `in`.driftzero.core.MountSession
import `in`.driftzero.core.NavigationState
import `in`.driftzero.core.RoadHeadingDecision
import `in`.driftzero.core.RoadHeadingSkipReason

/** Sheet value strings. Labels stay in strings.xml. */
object StatusCopy {
    /** Label column min width so "Area pack" stays one line, not single letters. */
    const val SHEET_LABEL_MIN_DP: Int = 112

    /** Collapse whitespace. Never insert breaks that would paint a label as letters. */
    fun sheetLabel(label: String): String = label.trim().replace(Regex("\\s+"), " ")

    /**
     * Road heading aid for the sheet. Null hides the row until PoseStore wires it.
     * [ON] is an accepted heading prior. [OFF_NEAR_JUNCTION] is gated at a junction.
     */
    enum class RoadAidState {
        ON,
        OFF_NEAR_JUNCTION,
    }

    fun gnssAgeS(lastSeenNs: Long?, nowNs: Long): Double? {
        if (lastSeenNs == null || nowNs < lastSeenNs) {
            return null
        }
        return (nowNs - lastSeenNs) / 1_000_000_000.0
    }

    fun lastTrustedAgo(ageS: Double): String = "${InstrumentFormat.formatSeconds(ageS)} ago"

    fun noFix(ageS: Double): String = "No fix ${InstrumentFormat.formatSeconds(ageS)}"

    fun preciseLocationOff(): String = "Precise location is off"

    fun noGpsYet(): String = "No GPS yet. Turn on Precise location. Wait outdoors."

    fun noLocationReason(grant: LocationGrant, hasPose: Boolean): String? {
        if (hasPose) {
            return null
        }
        return when (grant) {
            LocationGrant.NONE -> null
            LocationGrant.COARSE -> preciseLocationOff()
            LocationGrant.FINE -> noGpsYet()
        }
    }

    fun coasted(metres: Double): String = "${InstrumentFormat.formatDistance(metres)} coasted"

    fun radius95(metres: Double): String = "${InstrumentFormat.formatRadius(metres)} 95%"

    fun heading(headingRad: Double, heading95Rad: Double): String =
        "${InstrumentFormat.formatHeadingDeg(headingRad)}, ${InstrumentFormat.formatAngleDeg(heading95Rad)} 95%"

    fun mapMatch(status: MapMatchStatus, confidence: Double): String = when (status) {
        MapMatchStatus.MATCHED -> "Matched ${InstrumentFormat.formatConfidence(confidence)}"
        MapMatchStatus.AMBIGUOUS -> "Ambiguous ${InstrumentFormat.formatConfidence(confidence)}"
        MapMatchStatus.UNMATCHED -> "Off road"
        MapMatchStatus.NO_MAP -> "No area pack"
    }

    /**
     * IMU status, plus magnetometer when health carries
     * [PoseStore.FLAG_MAG_CAPTURED_UNUSED]. Mag is stored, not fused. Mag copy
     * is omitted when that flag is absent.
     */
    fun sensors(flags: Set<String>): String {
        val imu = when {
            flags.contains(DeadReckoningFilter.FLAG_NO_IMU) -> "No IMU"
            flags.contains(DeadReckoningFilter.FLAG_ZUPT) && flags.contains(DeadReckoningFilter.FLAG_NHC) -> "ZUPT, NHC"
            flags.contains(DeadReckoningFilter.FLAG_ZUPT) -> "ZUPT"
            flags.contains(DeadReckoningFilter.FLAG_NHC) -> "NHC"
            else -> "Accel, gyro ok"
        }
        if (!flags.contains(PoseStore.FLAG_MAG_CAPTURED_UNUSED)) {
            return imu
        }
        return if (imu == "Accel, gyro ok") {
            "Mag: captured, unused"
        } else {
            "$imu. Mag: captured, unused"
        }
    }

    fun mount(quality: MountQuality?, yawConfidence: Double? = null): String? {
        if (quality == null) {
            return null
        }
        return when (quality) {
            MountQuality.PENDING -> "pending, drive straight"
            MountQuality.STATIONARY_ONLY -> "still only"
            MountQuality.ALIGNED, MountQuality.ALIGNED_HIGH -> {
                if (yawConfidence != null && yawConfidence.isFinite()) {
                    "aligned ${InstrumentFormat.formatPercent(yawConfidence)}"
                } else {
                    "aligned"
                }
            }
        }
    }

    fun roadHeading(state: RoadAidState?): String? = when (state) {
        null -> null
        RoadAidState.ON -> "on"
        RoadAidState.OFF_NEAR_JUNCTION -> "off (near junction)"
    }

    fun roadAid(decision: RoadHeadingDecision?): RoadAidState? {
        if (decision == null) {
            return null
        }
        if (decision.prior != null) {
            return RoadAidState.ON
        }
        return when (decision.skipReason) {
            RoadHeadingSkipReason.NEAR_JUNCTION -> RoadAidState.OFF_NEAR_JUNCTION
            else -> null
        }
    }

    /**
     * One reason string for the sheet and the lamp. Remount copy wins when
     * [mountReason] is set or health carries [PoseStore.FLAG_MOUNT_REMOUNT].
     * Default copy is driver words. Lab keeps the numbered dump.
     */
    fun reasonLine(
        modeReason: ModeReason?,
        mountReason: String? = null,
        flags: Set<String> = emptySet(),
        lab: Boolean = false,
    ): String? {
        if (!mountReason.isNullOrEmpty()) {
            return mountReason
        }
        if (flags.contains(PoseStore.FLAG_MOUNT_REMOUNT)) {
            return MountSession.REMOUNT_USER_REASON
        }
        if (modeReason == null) {
            return null
        }
        return if (lab) reasonPlain(modeReason) else reasonDriver(modeReason)
    }

    fun model(flags: Set<String>, studentLoaded: Boolean): String = when {
        studentLoaded && flags.contains(DeadReckoningFilter.FLAG_MOTION_PSEUDO) -> "Speed student"
        flags.contains(DeadReckoningFilter.FLAG_MOTION_PSEUDO) -> "Heuristic speed"
        else -> "Filter only"
    }

    fun navic(snapshot: NavicSnapshot): String {
        if (!snapshot.constellations.contains(NavicSnapshotIRNSS) && snapshot.navicVisible == 0) {
            return "Not reported by chipset"
        }
        return "${snapshot.navicVisible} visible, ${snapshot.navicUsed} used"
    }

    fun areaPack(pack: AreaPack?, bytes: Long?): String {
        if (pack == null) {
            return "None. Streets and routing from network"
        }
        val name = pack.manifest.label ?: pack.manifest.id.value
        return if (bytes != null && bytes > 0L) {
            "$name, ${InstrumentFormat.formatBytes(bytes)}"
        } else {
            name
        }
    }

    fun routingStatus(
        network: Boolean,
        localRouter: Boolean = false,
        packReady: Boolean = false,
    ): String? {
        if (localRouter) {
            return null
        }
        if (packReady) {
            return NO_LOCAL_GRAPH
        }
        if (!network) {
            return "Routing needs network"
        }
        return null
    }

    /**
     * This-trip routing source. Only when a Ready pack's graph is live and
     * this route still came from the network.
     */
    fun routeSource(fromLocal: Boolean?, localRouterAvailable: Boolean): String? {
        if (fromLocal == null || fromLocal || !localRouterAvailable) {
            return null
        }
        return NETWORK_ROUTE_LOCAL_MISS
    }

    fun outputRate(p95Ms: Double?): String? {
        if (p95Ms == null || !p95Ms.isFinite() || p95Ms < 0.0) {
            return null
        }
        return "p95 gap ${p95Ms.toInt()} ms"
    }

    fun of(
        state: NavigationState,
        lastGnssSeenNs: Long?,
        nowNs: Long,
        studentLoaded: Boolean,
        navic: NavicSnapshot,
        pack: AreaPack?,
        packBytes: Long?,
        p95Ms: Double?,
        mountQuality: MountQuality? = null,
        mountYawConfidence: Double? = null,
        mountReason: String? = null,
        roadAid: RoadAidState? = null,
        lab: Boolean = false,
        speedText: String? = null,
    ): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>(14)
        reasonLine(modeReason(state), mountReason, state.health.flags, lab)?.let { reason ->
            rows += sheetLabel("Reason") to reason
        }
        if (!speedText.isNullOrEmpty()) {
            rows += sheetLabel("Speed") to speedText
        }
        gnssAgeS(lastGnssSeenNs, nowNs)?.let { age ->
            rows += sheetLabel("GNSS age") to InstrumentFormat.formatSeconds(age)
        }
        rows += sheetLabel("Heading") to heading(state.motion.heading.value, state.uncertainty.heading95Rad)
        rows += sheetLabel("Confidence radius") to radius95(state.uncertainty.horizontal95.value)
        mountIfUseful(mountQuality, mountYawConfidence)?.let { rows += sheetLabel("Mount") to it }
        rows += sheetLabel("Area pack") to areaPack(pack, packBytes)
        if (!lab) {
            return rows
        }
        rows += sheetLabel("Last trusted fix") to lastTrustedAgo(state.gnssHealth.lastTrustedFixAgeS)
        rows += sheetLabel("Map match") to mapMatch(state.mapMatch.status, state.mapMatch.confidence)
        rows += sheetLabel("Sensors") to sensors(state.health.flags)
        roadHeading(roadAid)?.let { rows += sheetLabel("Road heading") to it }
        rows += sheetLabel("Model") to model(state.health.flags, studentLoaded)
        rows += sheetLabel("NavIC") to navic(navic)
        outputRate(p95Ms)?.let { rows += sheetLabel("Output rate") to it }
        return rows
    }

    /** Mount row on the default sheet. Pending is first-run noise, not a driver fact. */
    fun mountIfUseful(quality: MountQuality?, yawConfidence: Double? = null): String? {
        if (quality == null || quality == MountQuality.PENDING) {
            return null
        }
        return mount(quality, yawConfidence)
    }

    private fun reasonDriver(reason: ModeReason): String = when (reason) {
        is ModeReason.NoFix,
        is ModeReason.RadiusLimit,
        is ModeReason.FixAccuracyOver,
        ModeReason.GatedFix,
        ModeReason.Reacquiring,
        -> WEAK_GPS
        ModeReason.Held -> "GNSS held"
        ModeReason.ImuGap -> "No IMU data"
    }

    private fun reasonPlain(reason: ModeReason): String = when (reason) {
        is ModeReason.NoFix -> "No fix for ${InstrumentFormat.formatSeconds(reason.ageS)}"
        ModeReason.Held -> "GNSS held"
        is ModeReason.RadiusLimit ->
            "Radius ${InstrumentFormat.formatRadius(reason.radiusM)} over ${InstrumentFormat.formatRadius(reason.limitM)} limit"
        is ModeReason.FixAccuracyOver ->
            "Fix accuracy over ${InstrumentFormat.formatRadius(reason.limitM)}"
        ModeReason.GatedFix -> "Fix disagreed with estimate"
        ModeReason.Reacquiring -> "Fix back, confirming"
        ModeReason.ImuGap -> "No IMU data"
    }

    const val WEAK_GPS: String = "Weak GPS. Wait outdoors."
    const val NO_LOCAL_GRAPH: String = "No local graph. Routing from network"
    const val NETWORK_ROUTE_LOCAL_MISS: String = "Network route. Local graph missed."
}

private const val NavicSnapshotIRNSS: String = "IRNSS"
