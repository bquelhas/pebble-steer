package com.bquelhas.steer

import androidx.annotation.DrawableRes

/**
 * Maneuver taxonomy shared with the Pebble watchapp. The integer id is the value
 * sent in NAV_TURN and indexes the watch's `direction_resources[]` icon table.
 *
 * Ids 0..40 all have a dedicated icon, drawn by Bruno (mockup) and shared between
 * the watch (PDC) and this app (VectorDrawable `ic_maneuver_<id>`). The Android
 * vectors are generated from the same source SVGs so the preview matches the watch.
 *
 * NEVER reorder existing ids; only append. The Pebble C enum must match exactly.
 */
enum class Direction(val id: Int) {
    ARRIVE(0), ARRIVE_LEFT(1), ARRIVE_RIGHT(2), DEPART(3),
    FORK_LEFT(4), FORK_RIGHT(5), GENERIC_MERGE(6),
    GENERIC_ROUNDABOUT_LEFT(7), GENERIC_ROUNDABOUT_RIGHT(8),
    LEFT(9), RIGHT(10), RAMP_LEFT(11), RAMP_RIGHT(12),
    ROUNDABOUT_1_LEFT(13), ROUNDABOUT_2_LEFT(14), ROUNDABOUT_3_LEFT(15), ROUNDABOUT_4_LEFT(16),
    ROUNDABOUT_5_LEFT(17), ROUNDABOUT_6_LEFT(18), ROUNDABOUT_7_LEFT(19), ROUNDABOUT_8_LEFT(20),
    ROUNDABOUT_1_RIGHT(21), ROUNDABOUT_2_RIGHT(22), ROUNDABOUT_3_RIGHT(23), ROUNDABOUT_4_RIGHT(24),
    ROUNDABOUT_5_RIGHT(25), ROUNDABOUT_6_RIGHT(26), ROUNDABOUT_7_RIGHT(27), ROUNDABOUT_8_RIGHT(28),
    ROUNDABOUT_EXIT_LEFT(29), ROUNDABOUT_EXIT_RIGHT(30),
    SHARP_LEFT(31), SHARP_RIGHT(32), SLIGHT_LEFT(33), SLIGHT_RIGHT(34),
    STRAIGHT(35), UTURN_LEFT(36), UTURN_RIGHT(37),
    // --- additions (no dedicated watch icon yet) ---
    FERRY(38), KEEP_LEFT(39), KEEP_RIGHT(40);

    /** VectorDrawable that mirrors the watch's PDC icon for this maneuver. */
    @get:DrawableRes
    val iconRes: Int
        get() = ICON_RES[id]

    companion object {
        fun fromId(id: Int): Direction = entries.firstOrNull { it.id == id } ?: STRAIGHT

        // Indexed by Direction.id (0..40). Generated set: ic_maneuver_<id>.xml
        private val ICON_RES = intArrayOf(
            R.drawable.ic_maneuver_0, R.drawable.ic_maneuver_1, R.drawable.ic_maneuver_2,
            R.drawable.ic_maneuver_3, R.drawable.ic_maneuver_4, R.drawable.ic_maneuver_5,
            R.drawable.ic_maneuver_6, R.drawable.ic_maneuver_7, R.drawable.ic_maneuver_8,
            R.drawable.ic_maneuver_9, R.drawable.ic_maneuver_10, R.drawable.ic_maneuver_11,
            R.drawable.ic_maneuver_12, R.drawable.ic_maneuver_13, R.drawable.ic_maneuver_14,
            R.drawable.ic_maneuver_15, R.drawable.ic_maneuver_16, R.drawable.ic_maneuver_17,
            R.drawable.ic_maneuver_18, R.drawable.ic_maneuver_19, R.drawable.ic_maneuver_20,
            R.drawable.ic_maneuver_21, R.drawable.ic_maneuver_22, R.drawable.ic_maneuver_23,
            R.drawable.ic_maneuver_24, R.drawable.ic_maneuver_25, R.drawable.ic_maneuver_26,
            R.drawable.ic_maneuver_27, R.drawable.ic_maneuver_28, R.drawable.ic_maneuver_29,
            R.drawable.ic_maneuver_30, R.drawable.ic_maneuver_31, R.drawable.ic_maneuver_32,
            R.drawable.ic_maneuver_33, R.drawable.ic_maneuver_34, R.drawable.ic_maneuver_35,
            R.drawable.ic_maneuver_36, R.drawable.ic_maneuver_37, R.drawable.ic_maneuver_38,
            R.drawable.ic_maneuver_39, R.drawable.ic_maneuver_40,
        )
    }
}
