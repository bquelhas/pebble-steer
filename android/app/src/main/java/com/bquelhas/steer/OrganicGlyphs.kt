package com.bquelhas.steer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Ground-truth labels for the real Organic Maps maneuver glyphs bundled under
 * `assets/om_maneuvers/` (the `ic_turn_*` / `ic_roundabout_exit_*` drawables that Organic
 * — and CoMaps, its fork — put in the navigation notification `largeIcon`, per
 * CarDirection.getTurnRes). They drive [ManeuverFingerprints.ORGANIC_TABLE] and its test.
 * Reference assets for interop testing; they must NOT ship in a public release.
 *
 * Roundabouts encode only the EXIT NUMBER (a circle + digit), which the watch can't render
 * yet, so every roundabout glyph maps to the generic roundabout (known limitation — see
 * docs/organic_mapping).
 */
object OrganicGlyphs {
    const val DIR = "om_maneuvers"

    fun names(context: Context): List<String> =
        try {
            context.assets.list(DIR)?.filter { it.endsWith(".png") }
                ?.map { it.removeSuffix(".png") }?.sorted().orEmpty()
        } catch (_: Exception) { emptyList() }

    fun load(context: Context, name: String): Bitmap? =
        try {
            context.assets.open("$DIR/$name.png").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null }

    fun expected(name: String): Direction? = LABELS[name]

    private val LABELS: Map<String, Direction> = buildMap {
        put("straight", Direction.STRAIGHT)
        put("left", Direction.LEFT)
        put("right", Direction.RIGHT)
        put("slight_left", Direction.SLIGHT_LEFT)
        put("slight_right", Direction.SLIGHT_RIGHT)
        put("sharp_left", Direction.SHARP_LEFT)
        put("sharp_right", Direction.SHARP_RIGHT)
        put("uturn_left", Direction.UTURN_LEFT)
        put("uturn_right", Direction.UTURN_RIGHT)
        put("arrive", Direction.ARRIVE)
        put("exit_left", Direction.RAMP_LEFT)     // ExitHighwayToLeft
        put("exit_right", Direction.RAMP_RIGHT)   // ExitHighwayToRight
        // Roundabouts: generic (no exit number on the watch yet).
        put("roundabout", Direction.GENERIC_ROUNDABOUT_RIGHT)
        for (n in 1..8) put("roundabout_exit_$n", Direction.GENERIC_ROUNDABOUT_RIGHT)
    }
}
