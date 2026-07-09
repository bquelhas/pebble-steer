package com.bquelhas.steer

/**
 * Decides WHEN the watch should buzz for the upcoming maneuver — a single "get ready"
 * warning per maneuver, fired at a lead distance that adapts to how fast you're going.
 *
 * Speed source, best first:
 *  1. GPS speed from [SpeedProvider], when location permission is granted.
 *  2. DERIVED speed — how fast the announced distance is shrinking between notification
 *     updates. Needs no permission and no map data: on a motorway the distance drops
 *     ~33 m/s so the lead grows to ~800 m; in town it shrinks slowly so the lead is short.
 *  3. When neither is available yet (the very first frame of a maneuver), a coarse bucket
 *     by the distance at which the maneuver was first announced.
 *
 * With a usable speed the lead is speed × [PREPARE_SECONDS] (≈ 830 m at 120 km/h, ≈ 280 m
 * at 40 km/h), clamped to [[MIN_LEAD_M], [MAX_LEAD_M]].
 *
 * One buzz per maneuver: a latch arms when the maneuver changes and re-arms on a reroute
 * (the announced distance jumps back up) OR a new maneuver — both of which SHOULD buzz
 * again, including a maneuver that appears already within the lead distance. The maneuver
 * identity strips distances ([NaviParser.instructionKey]) so a live countdown embedded in
 * the text isn't mistaken for a new maneuver. State is process-wide, reset per session.
 */
object VibePlanner {

    /** Lead time of the "get ready" buzz. */
    private const val PREPARE_SECONDS = 25.0

    /** Below this a speed reading is parked/noise. */
    private const val MIN_SPEED_KMH = 3.0

    /** Clamp of the speed-derived lead. */
    private const val MIN_LEAD_M = 50.0
    private const val MAX_LEAD_M = 1500.0

    /** A distance increase this large on the SAME instruction means a reroute / new leg. */
    private const val REARM_JUMP_M = 150.0

    // Derived-speed window: ignore deltas measured over an implausibly short/long gap.
    private const val MIN_DT_S = 0.5
    private const val MAX_DT_S = 90.0

    // First-frame fallback: coarse lead by how far out the maneuver was first announced,
    // used only until a speed estimate exists (or if a maneuver pops up already close).
    private const val SEG_MOTORWAY_M = 3000.0
    private const val SEG_ROAD_M = 1000.0
    private const val LEAD_MOTORWAY_M = 700.0
    private const val LEAD_ROAD_M = 400.0
    private const val LEAD_STREET_M = 200.0

    private var maneuverKey: String? = null
    private var buzzed = false
    private var lastDistance = 0.0
    private var anchorDistance = 0.0   // distance when the current approach started
    private var anchorTimeMs = 0L      // time when the current approach started
    private var derivedSpeedKmh = -1.0 // speed inferred from the shrinking distance

    /** Clears all per-session state. Call at navigation session start and end. */
    @Synchronized
    fun reset() {
        maneuverKey = null
        buzzed = false
        lastDistance = 0.0
        anchorDistance = 0.0
        anchorTimeMs = 0L
        derivedSpeedKmh = -1.0
    }

    /**
     * Feed one navigation update; returns true when the watch should buzz NOW.
     *
     * @param directionId the maneuver id (Direction.id) of this update.
     * @param instructionText the composed display text ("300 m — Rua X"); distances are
     *   stripped for identity so the live countdown doesn't look like a new maneuver.
     * @param distanceMeters parsed numeric distance to the maneuver, or null.
     * @param speedKmh current GPS speed, or -1 when unknown/stale.
     */
    @Synchronized
    fun onUpdate(
        directionId: Int,
        instructionText: String,
        distanceMeters: Double?,
        speedKmh: Int,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val key = "$directionId|${NaviParser.instructionKey(instructionText)}"

        if (distanceMeters == null) {
            // Legacy fallback: no distance to reason about -> one buzz per new instruction.
            val isNew = key != maneuverKey
            maneuverKey = key
            buzzed = true
            lastDistance = Double.MAX_VALUE
            anchorDistance = 0.0
            anchorTimeMs = now
            derivedSpeedKmh = -1.0
            return isNew
        }

        val newManeuver = key != maneuverKey
        val reroute = !newManeuver && distanceMeters > lastDistance + REARM_JUMP_M
        if (newManeuver || reroute) {
            // A genuinely new approach: re-arm the buzz and restart the speed estimate.
            maneuverKey = key
            buzzed = false
            anchorDistance = distanceMeters
            anchorTimeMs = now
            derivedSpeedKmh = -1.0
        } else {
            // Same maneuver getting closer: refine the derived approach speed.
            val dtS = (now - anchorTimeMs) / 1000.0
            val drop = anchorDistance - distanceMeters
            if (drop > 0 && dtS in MIN_DT_S..MAX_DT_S) {
                derivedSpeedKmh = drop / dtS * 3.6
            } else if (dtS > MAX_DT_S) {
                // Slow crawl: slide the window so the estimate stays recent.
                anchorDistance = distanceMeters
                anchorTimeMs = now
            }
        }
        lastDistance = distanceMeters

        if (buzzed) return false
        if (distanceMeters <= leadMeters(speedKmh)) {
            buzzed = true
            return true
        }
        return false
    }

    /** The lead distance (m) at which the buzz should fire, given the best speed we have. */
    private fun leadMeters(gpsSpeedKmh: Int): Double {
        val speed = if (gpsSpeedKmh >= MIN_SPEED_KMH) gpsSpeedKmh.toDouble() else derivedSpeedKmh
        if (speed >= MIN_SPEED_KMH) {
            return (speed / 3.6 * PREPARE_SECONDS).coerceIn(MIN_LEAD_M, MAX_LEAD_M)
        }
        // No usable speed yet: coarse bucket by how far out the maneuver was announced.
        return when {
            anchorDistance >= SEG_MOTORWAY_M -> LEAD_MOTORWAY_M
            anchorDistance >= SEG_ROAD_M -> LEAD_ROAD_M
            else -> LEAD_STREET_M
        }
    }
}
