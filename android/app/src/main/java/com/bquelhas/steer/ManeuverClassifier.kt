package com.bquelhas.steer

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Best-effort classification of an icon-only maneuver glyph (e.g. Google Maps'
 * notification largeIcon) into a [Direction], so the watch can draw its own
 * Pebble-style PDC arrow from NAV_TURN instead of blitting the foreign raster glyph.
 *
 * Input is the packed 1-bpp 48x48 mask produced by [IconConverter]. We model the
 * arrow as a stem rising from the bottom with a head pointing toward the turn,
 * estimate the head direction geometrically, and bin the angle to a maneuver.
 *
 * Deliberately conservative: [Confidence.HIGH] is reported only for clean,
 * unambiguous simple turns. Anything circular/branching (roundabouts, forks) or
 * with odd coverage stays [Confidence.LOW] so the caller keeps forwarding the raw
 * glyph (no regression vs. today). Every glyph is also reduced to a coarse
 * [fingerprint] so a labelled lookup table can be built from real drives later.
 */
object ManeuverClassifier {
    enum class Confidence { HIGH, LOW }

    data class Result(
        val direction: Direction,
        val confidence: Confidence,
        val fingerprint: Long,
        val angle: Int,
        val coverage: Float = 0f,
        /** Fine 16x16 occupancy signature of the glyph (256 bits), for the lookup table. */
        val sig: ByteArray = ByteArray(SIG_BYTES),
        /** Hamming distance to the matched table entry, or -1 if matched by geometry. */
        val matchDist: Int = -1,
    ) {
        /** Stable, log-friendly id of the glyph shape, e.g. "3f0a…". */
        val fpHex: String get() = java.lang.Long.toHexString(fingerprint)
        /** Hex of the fine signature, 64 chars — the key baked into [ManeuverFingerprints]. */
        val sigHex: String get() = sig.joinToString("") { "%02x".format(it) }
    }

    private const val N = IconConverter.SIZE      // 48
    private const val STRIDE = 8                   // IconConverter.ROW_BYTES for 48px
    const val SIG_GRID = 16                        // fine fingerprint is 16x16 cells
    const val SIG_BYTES = SIG_GRID * SIG_GRID / 8  // 32 bytes = 256 bits

    private fun bit(packed: ByteArray, x: Int, y: Int): Int =
        (packed[y * STRIDE + (x / 8)].toInt() shr (x % 8)) and 1

    /**
     * Fine 16x16 occupancy signature (256 bits). Each cell is 3x3 px; a cell bit is set
     * when at least 2 of its 9 pixels are foreground (thin strokes survive). Far finer
     * than the legacy 8x8 [fingerprint], so the 64 Maps glyphs become separable for a
     * nearest-neighbour lookup table ([ManeuverFingerprints]).
     */
    fun signature(packed: ByteArray): ByteArray {
        val cell = N / SIG_GRID    // 3
        val out = ByteArray(SIG_BYTES)
        for (gy in 0 until SIG_GRID) {
            for (gx in 0 until SIG_GRID) {
                var on = 0
                for (yy in 0 until cell) for (xx in 0 until cell) {
                    if (bit(packed, gx * cell + xx, gy * cell + yy) == 1) on++
                }
                if (on >= 2) {
                    val idx = gy * SIG_GRID + gx
                    out[idx / 8] = (out[idx / 8].toInt() or (1 shl (idx % 8))).toByte()
                }
            }
        }
        return out
    }

    /** Bit-difference between two equal-length signatures. */
    fun hamming(a: ByteArray, b: ByteArray): Int {
        var d = 0
        for (i in a.indices) d += Integer.bitCount((a[i].toInt() xor b[i].toInt()) and 0xFF)
        return d
    }

    // NOTE: the old exact-match FINGERPRINTS lookup was REMOVED. The 8x8 hash is far too
    // coarse — it collapses the 64 distinct Maps glyphs into ~30 buckets, so e.g.
    // turn_normal_left hashed identically to a captured "straight" glyph and was returned
    // as STRAIGHT with HIGH confidence (a dangerous wrong read). Classification is now
    // geometry-only; a finer, collision-free fingerprint table is the planned follow-up.

    /**
     * @param useTable when false, the baked [ManeuverFingerprints] nearest-neighbour
     *   lookup is skipped and classification is geometry-only. The table holds the
     *   signatures of GOOGLE MAPS artwork; CoMaps / Organic Maps draw their own glyphs,
     *   so matching them against the Maps table would mis-bin them. Geometry (arrow
     *   shaft + head heading) is artwork-agnostic and safe for any arrow glyph. A
     *   CoMaps-specific table can be added later from captured signatures.
     */
    fun classify(packed: ByteArray, useTable: Boolean = true): Result {
        val pts = ArrayList<IntArray>(512)
        var minX = N; var maxX = 0; var minY = N; var maxY = 0
        for (y in 0 until N) {
            for (x in 0 until N) {
                if (bit(packed, x, y) == 1) {
                    pts.add(intArrayOf(x, y))
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        val fp = fingerprint(packed)
        val sig = signature(packed)
        val coverage = pts.size.toFloat() / (N * N)
        if (pts.size < 40 || coverage > 0.55f)
            return Result(Direction.STRAIGHT, Confidence.LOW, fp, 0, coverage, sig)

        // 1) Fingerprint table first. The baked table ([ManeuverFingerprints]) holds the
        //    256-bit signature of every real Maps glyph -> our Direction. Nearest-neighbour
        //    by Hamming distance recognises the non-arrow maneuvers geometry can't model
        //    (ramps, forks, keeps, merges, roundabouts, destinations) and pins exact turns.
        if (useTable) {
            var best: Direction? = null
            var bestDist = Int.MAX_VALUE
            for ((tsig, tdir) in ManeuverFingerprints.TABLE) {
                val d = hamming(sig, tsig)
                if (d < bestDist) { bestDist = d; best = tdir }
            }
            if (best != null && bestDist <= MATCH_MAX) {
                val conf = if (bestDist <= MATCH_TIGHT) Confidence.HIGH else Confidence.LOW
                return Result(best, conf, fp, 0, coverage, sig, bestDist)
            }
        }

        // Maps maneuver arrows are L-SHAPED: a vertical shaft rises from the bottom
        // centre, then an elbow and an arrowhead point the way to turn. The OLD model
        // measured tail -> farthest-foreground-point, but the long shaft dragged every
        // turn back toward vertical, so turn_normal_left read as STRAIGHT (HIGH) — wrong
        // and dangerous. Instead we locate the arrowhead TIP and read the heading from
        // the HEAD ALONE: the vector from the local neighbourhood centroid around the tip
        // to the tip itself. That ignores the shaft, so a 90° turn reads ~90°, a slight
        // bend reads slight, a hairpin reads u-turn.
        // Entry/tail = the bottom END OF THE SHAFT, taken as the centroid of the lowest
        // few rows of foreground. Maps arrows do NOT all start bottom-centre: a left turn's
        // shaft rises on the RIGHT and bends left at the top, so a fixed centre anchor made
        // the farthest-point flip between head and elbow and read as STRAIGHT.
        var tbx = 0.0; var tbn = 0
        for (p in pts) if (p[1] >= maxY - 2) { tbx += p[0]; tbn++ }
        val tailX = if (tbn > 0) (tbx / tbn) else 24.0
        val tailY = maxY.toDouble()

        // Tip: the foreground point farthest from the shaft entry (the arrowhead apex).
        var tip = pts[0]
        var bestD = -1.0
        for (p in pts) {
            val d = hypot(p[0] - tailX, p[1] - tailY)
            if (d > bestD) { bestD = d; tip = p }
        }

        // Local head heading: centroid of foreground within R of the tip, pointing to tip.
        val r = N * 0.30                                 // head neighbourhood radius (~14px)
        var hx = 0.0; var hy = 0.0; var hn = 0
        for (p in pts) {
            if (hypot((p[0] - tip[0]).toDouble(), (p[1] - tip[1]).toDouble()) <= r) {
                hx += p[0]; hy += p[1]; hn++
            }
        }
        val baseX = if (hn > 0) hx / hn else tailX.toDouble()
        val baseY = if (hn > 0) hy / hn else tailY.toDouble()
        val dx = tip[0] - baseX
        val dy = baseY - tip[1]                          // up is positive
        val angle = Math.toDegrees(atan2(dx, dy)).roundToInt()   // 0 = up, + = right, - = left
        val dir = binSimple(angle)

        // Circularity guard: a roundabout glyph fills a wide near-square box with an
        // empty centre. If the box is bulky in both axes, the simple-turn geometry is
        // unreliable — keep it LOW so the caller can fall back.
        val bulky = (maxX - minX + 1) > N * 0.6f && (maxY - minY + 1) > N * 0.6f && coverage < 0.30f
        if (bulky) return Result(dir, Confidence.LOW, fp, angle, coverage, sig)

        // The head-local heading is reliable across the simple-turn family; trust it HIGH
        // when the tip is clearly displaced from the tail (a real arrow, not a blob).
        val trustworthy = dir in HIGH_SET && bestD > N * 0.30f
        return Result(dir, if (trustworthy) Confidence.HIGH else Confidence.LOW, fp, angle, coverage, sig)
    }

    /** Max Hamming distance to accept a table match; tighter band reports HIGH. */
    private const val MATCH_MAX = 40      // ~16% of 256 bits
    private const val MATCH_TIGHT = 14    // ~5% of 256 bits

    private val HIGH_SET = setOf(
        Direction.STRAIGHT, Direction.LEFT, Direction.RIGHT,
        Direction.SLIGHT_LEFT, Direction.SLIGHT_RIGHT,
        Direction.SHARP_LEFT, Direction.SHARP_RIGHT,
        Direction.UTURN_LEFT, Direction.UTURN_RIGHT,
    )

    private fun binSimple(angle: Int): Direction {
        val a = abs(angle)
        val left = angle < 0
        return when {
            a <= 18 -> Direction.STRAIGHT
            a <= 50 -> if (left) Direction.SLIGHT_LEFT else Direction.SLIGHT_RIGHT
            a <= 115 -> if (left) Direction.LEFT else Direction.RIGHT
            a <= 155 -> if (left) Direction.SHARP_LEFT else Direction.SHARP_RIGHT
            else -> if (left) Direction.UTURN_LEFT else Direction.UTURN_RIGHT
        }
    }

    /** Coarse 8x8 perceptual hash of the glyph, for a labelled lookup table later. */
    fun fingerprint(packed: ByteArray): Long {
        val cell = N / 8   // 6
        var bits = 0L
        for (gy in 0 until 8) {
            for (gx in 0 until 8) {
                var on = 0
                for (yy in 0 until cell) {
                    val y = gy * cell + yy
                    for (xx in 0 until cell) {
                        if (bit(packed, gx * cell + xx, y) == 1) on++
                    }
                }
                // ~1/6 of the cell is enough: arrow strokes are thin, a majority test
                // would wipe them out and collapse every glyph to an empty hash.
                if (on * 6 >= cell * cell) bits = bits or (1L shl (gy * 8 + gx))
            }
        }
        return bits
    }
}
