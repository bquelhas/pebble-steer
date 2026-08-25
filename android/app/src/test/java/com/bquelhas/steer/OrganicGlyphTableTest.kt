package com.bquelhas.steer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Regression net for the Organic Maps / CoMaps table ([ManeuverFingerprints.ORGANIC_TABLE]):
 * every entry must map its source Organic glyph to the ground-truth [Direction] in
 * [OrganicGlyphs.expected], every glyph asset must have an entry, and every glyph's baked
 * signature must resolve to the right Direction by nearest-neighbour (turns must not collide
 * with each other or with the roundabouts).
 */
class OrganicGlyphTableTest {

    // Gradle runs unit tests with the module dir (android/app) as the working directory.
    private val glyphDir = File("src/debug/assets/om_maneuvers")
    private val table = ManeuverFingerprints.ORGANIC_TABLE

    @Test
    fun everyTableEntryMatchesTheGroundTruthLabel() {
        val failures = table.mapNotNull { entry ->
            val expected = OrganicGlyphs.expected(entry.glyph)
                ?: return@mapNotNull "${entry.glyph}: not in OrganicGlyphs.LABELS"
            if (entry.direction != expected)
                "${entry.glyph}: table says ${entry.direction}, ground truth is $expected"
            else null
        }
        assertTrue("table/ground-truth mismatches:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun everyLabelledGlyphHasATableEntry() {
        val inTable = table.map { it.glyph }.toSet()
        assumeTrue(glyphDir.isDirectory)
        val missing = pngNames().filter { it !in inTable }
        assertTrue("glyph assets with no table entry: $missing", missing.isEmpty())
    }

    @Test
    fun everyGlyphAssetHasAGroundTruthLabel() {
        assumeTrue(glyphDir.isDirectory)
        val unlabelled = pngNames().filter { OrganicGlyphs.expected(it) == null }
        assertTrue("glyphs missing from OrganicGlyphs.LABELS: $unlabelled", unlabelled.isEmpty())
    }

    @Test
    fun signaturesAreWellFormed() {
        for (entry in table) {
            assertEquals("${entry.glyph}: bad signature length",
                ManeuverClassifier.SIG_BYTES, entry.sig.size)
        }
    }

    @Test
    fun everyGlyphResolvesToTheCorrectDirection() {
        // Each baked signature must nearest-neighbour to an entry with its OWN Direction. For
        // turns this means self (distance 0); for the near-identical roundabout glyphs it means
        // any roundabout entry — all generic — so the Direction still matches. Catches a turn
        // glyph landing closer to a different-direction entry.
        for (entry in table) {
            var best: Direction? = null
            var bestDist = Int.MAX_VALUE
            for ((tsig, tdir) in table) {
                val d = ManeuverClassifier.hamming(entry.sig, tsig)
                if (d < bestDist) { bestDist = d; best = tdir }
            }
            assertEquals("${entry.glyph}: resolves to the wrong Direction", entry.direction, best)
        }
    }

    private fun pngNames(): List<String> =
        glyphDir.listFiles { f -> f.name.endsWith(".png") }!!
            .map { it.name.removeSuffix(".png") }
            .sorted()
}
