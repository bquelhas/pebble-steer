package com.bquelhas.steer

/**
 * One navigation update ready to send to the watch. [instructionText] is the
 * finished display string (distance already embedded) — the watch does no parsing.
 */
data class NaviData(
    val direction: Direction,
    val instructionText: String,
    val gpsAccuracy: String? = null,
    /**
     * Whether [direction] came from an actual maneuver keyword in the notification text.
     * When false the text had no turn info (e.g. Google Maps) and [direction] is just the
     * STRAIGHT fallback — the caller should forward the notification glyph bitmap instead.
     */
    val maneuverFromText: Boolean = true,
    /** Arrival clock time ("20:09") parsed from the notification subText, or null. */
    val eta: String? = null,
) {
    val directionId: Int get() = direction.id
}
