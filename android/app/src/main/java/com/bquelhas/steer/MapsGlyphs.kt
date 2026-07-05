package com.bquelhas.steer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Loads the real Google Maps maneuver glyphs bundled under `assets/gmaps_maneuvers/`.
 *
 * These are the actual `maneuver_*` vector drawables decoded out of the Google Maps
 * APK, rendered white-on-transparent exactly like Maps puts them in a notification
 * `largeIcon`. They exist ONLY to drive [ManeuverClassifier] in the mock-nav debug
 * tool — i.e. to test, one maneuver at a time, whether we read Maps' own icons
 * correctly. They are reference assets for interop testing and must NOT ship in a
 * public release (Google's artwork).
 */
object MapsGlyphs {
    const val DIR = "gmaps_maneuvers"

    /** Asset names (without extension), sorted, e.g. "turn_normal_left". */
    fun names(context: Context): List<String> =
        try {
            context.assets.list(DIR)?.filter { it.endsWith(".png") }
                ?.map { it.removeSuffix(".png") }?.sorted().orEmpty()
        } catch (_: Exception) { emptyList() }

    /** Loads the white-on-transparent glyph bitmap for [name], or null. */
    fun load(context: Context, name: String): Bitmap? =
        try {
            context.assets.open("$DIR/$name.png").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null }

    /** Ground-truth label for a Maps glyph asset, or null if unmapped. */
    fun expected(name: String): Direction? = LABELS[name]

    /**
     * Intentional mapping from each Maps maneuver glyph (asset name) to OUR [Direction].
     * This is the ground truth the classifier audit scores against, and the source for a
     * baked fingerprint table later. Several Maps variants are deliberately COLLAPSED onto
     * one of our maneuvers (we don't draw every Maps gradation):
     *  - all on/off-ramp_*  -> RAMP_LEFT / RAMP_RIGHT (we have no sharp/slight/u-turn ramp)
     *  - all merge*         -> GENERIC_MERGE
     *  - roundabout rotation -> LEFT (ccw) / RIGHT (cw) family. The angle-encoded
     *    `roundabout_enter_and_exit_<ccw|cw>_<suffix>` glyphs now read the EXIT ANGLE from
     *    the icon into ROUNDABOUT_1..8 (Bruno-confirmed 2026-06-25, CONFIRMA_rotunda_RBT.png):
     *    the suffix names the exit-arrow direction, mapped to a 45deg bucket numbered by
     *    increasing angle. For ccw the angle grows right->straight->left:
     *      sharp_right=45=RBT1, normal_right=90=RBT2, slight_right=135=RBT3, straight=180=RBT4,
     *      slight_left=215=RBT5, normal_left=270=RBT6, sharp_left=315=RBT7, u_turn=360=RBT8.
     *    cw is the mirror (swap left<->right in the suffix) -> ROUNDABOUT_n_RIGHT. The plain
     *    `enter`/`enter_and_exit` (no angle suffix) and `exit` glyphs stay generic/exit.
     *
     * ASSUMPTION (flag to Bruno): ccw = LEFT-circulating (Portugal/right-hand traffic). If
     * the watch shows the wrong rotation, flip ccw<->cw here — it is a one-line change.
     */
    private val LABELS: Map<String, Direction> = mapOf(
        "depart" to Direction.DEPART,
        "destination" to Direction.ARRIVE,
        "destination_left" to Direction.ARRIVE_LEFT,
        "destination_right" to Direction.ARRIVE_RIGHT,
        "destination_straight" to Direction.ARRIVE,
        "fork_left" to Direction.FORK_LEFT,
        "fork_right" to Direction.FORK_RIGHT,
        "keep_left" to Direction.KEEP_LEFT,
        "keep_right" to Direction.KEEP_RIGHT,
        "merge" to Direction.GENERIC_MERGE,
        "merge_left" to Direction.GENERIC_MERGE,
        "merge_right" to Direction.GENERIC_MERGE,
        "name_change" to Direction.STRAIGHT,
        "straight" to Direction.STRAIGHT,
        "turn_normal_left" to Direction.LEFT,
        "turn_normal_right" to Direction.RIGHT,
        "turn_sharp_left" to Direction.SHARP_LEFT,
        "turn_sharp_right" to Direction.SHARP_RIGHT,
        "turn_slight_left" to Direction.SLIGHT_LEFT,
        "turn_slight_right" to Direction.SLIGHT_RIGHT,
        "u_turn_left" to Direction.UTURN_LEFT,
        "u_turn_right" to Direction.UTURN_RIGHT,
        // on/off ramps -> RAMP_LEFT / RAMP_RIGHT (all gradations collapsed)
        "off_ramp_keep_left" to Direction.RAMP_LEFT,
        "off_ramp_keep_right" to Direction.RAMP_RIGHT,
        "off_ramp_normal_left" to Direction.RAMP_LEFT,
        "off_ramp_normal_right" to Direction.RAMP_RIGHT,
        "off_ramp_sharp_left" to Direction.RAMP_LEFT,
        "off_ramp_sharp_right" to Direction.RAMP_RIGHT,
        "off_ramp_slight_left" to Direction.RAMP_LEFT,
        "off_ramp_slight_right" to Direction.RAMP_RIGHT,
        "off_ramp_u_turn_left" to Direction.RAMP_LEFT,
        "off_ramp_u_turn_right" to Direction.RAMP_RIGHT,
        "on_ramp_keep_left" to Direction.RAMP_LEFT,
        "on_ramp_keep_right" to Direction.RAMP_RIGHT,
        "on_ramp_normal_left" to Direction.RAMP_LEFT,
        "on_ramp_normal_right" to Direction.RAMP_RIGHT,
        "on_ramp_sharp_left" to Direction.RAMP_LEFT,
        "on_ramp_sharp_right" to Direction.RAMP_RIGHT,
        "on_ramp_slight_left" to Direction.RAMP_LEFT,
        "on_ramp_slight_right" to Direction.RAMP_RIGHT,
        "on_ramp_u_turn_left" to Direction.RAMP_LEFT,
        "on_ramp_u_turn_right" to Direction.RAMP_RIGHT,
        // roundabouts -> generic by rotation, plus the plain exit glyph
        "roundabout_enter_ccw" to Direction.GENERIC_ROUNDABOUT_LEFT,
        "roundabout_enter_cw" to Direction.GENERIC_ROUNDABOUT_RIGHT,
        "roundabout_exit_ccw" to Direction.ROUNDABOUT_EXIT_LEFT,
        "roundabout_exit_cw" to Direction.ROUNDABOUT_EXIT_RIGHT,
        "roundabout_enter_and_exit_ccw" to Direction.GENERIC_ROUNDABOUT_LEFT,
        "roundabout_enter_and_exit_cw" to Direction.GENERIC_ROUNDABOUT_RIGHT,
        // angle-encoded exits: suffix = exit-arrow direction -> 45deg bucket by angle.
        // ccw (LEFT family): angle grows right -> straight -> left.
        "roundabout_enter_and_exit_ccw_sharp_right" to Direction.ROUNDABOUT_1_LEFT,   // 45
        "roundabout_enter_and_exit_ccw_normal_right" to Direction.ROUNDABOUT_2_LEFT,  // 90
        "roundabout_enter_and_exit_ccw_slight_right" to Direction.ROUNDABOUT_3_LEFT,  // 135
        "roundabout_enter_and_exit_ccw_straight" to Direction.ROUNDABOUT_4_LEFT,      // 180
        "roundabout_enter_and_exit_ccw_slight_left" to Direction.ROUNDABOUT_5_LEFT,   // 215
        "roundabout_enter_and_exit_ccw_normal_left" to Direction.ROUNDABOUT_6_LEFT,   // 270
        "roundabout_enter_and_exit_ccw_sharp_left" to Direction.ROUNDABOUT_7_LEFT,    // 315
        "roundabout_enter_and_exit_ccw_u_turn" to Direction.ROUNDABOUT_8_LEFT,        // 360
        // cw (RIGHT family): mirror of ccw (swap left<->right in the suffix).
        "roundabout_enter_and_exit_cw_sharp_left" to Direction.ROUNDABOUT_1_RIGHT,    // 45
        "roundabout_enter_and_exit_cw_normal_left" to Direction.ROUNDABOUT_2_RIGHT,   // 90
        "roundabout_enter_and_exit_cw_slight_left" to Direction.ROUNDABOUT_3_RIGHT,   // 135
        "roundabout_enter_and_exit_cw_straight" to Direction.ROUNDABOUT_4_RIGHT,      // 180
        "roundabout_enter_and_exit_cw_slight_right" to Direction.ROUNDABOUT_5_RIGHT,  // 215
        "roundabout_enter_and_exit_cw_normal_right" to Direction.ROUNDABOUT_6_RIGHT,  // 270
        "roundabout_enter_and_exit_cw_sharp_right" to Direction.ROUNDABOUT_7_RIGHT,   // 315
        "roundabout_enter_and_exit_cw_u_turn" to Direction.ROUNDABOUT_8_RIGHT,        // 360
    )
}
