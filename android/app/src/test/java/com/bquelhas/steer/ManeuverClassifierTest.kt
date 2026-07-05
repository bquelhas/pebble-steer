package com.bquelhas.steer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManeuverClassifierTest {

    private val N = IconConverter.SIZE
    private val STRIDE = IconConverter.ROW_BYTES

    private fun blank() = ByteArray(IconConverter.BYTE_COUNT)
    private fun set(p: ByteArray, x: Int, y: Int) {
        if (x in 0 until N && y in 0 until N) {
            val i = y * STRIDE + (x / 8)
            p[i] = (p[i].toInt() or (1 shl (x % 8))).toByte()
        }
    }
    private fun vline(p: ByteArray, x0: Int, x1: Int, y0: Int, y1: Int) {
        for (y in y0..y1) for (x in x0..x1) set(p, x, y)
    }

    @Test fun straightArrowIsStraightAndHigh() {
        val p = blank()
        vline(p, 23, 25, 8, 40)            // vertical stem, tip at top
        val r = ManeuverClassifier.classify(p)
        assertEquals(Direction.STRAIGHT, r.direction)
        assertEquals(ManeuverClassifier.Confidence.HIGH, r.confidence)
    }

    @Test fun leftBendClassifiesToLeftSide() {
        val p = blank()
        vline(p, 23, 25, 24, 40)           // stem rising from the bottom
        vline(p, 6, 24, 23, 25)            // elbow turning left, tip at the left
        val r = ManeuverClassifier.classify(p)
        assertTrue(
            "expected a left-side maneuver, got ${r.direction}",
            r.direction in setOf(Direction.LEFT, Direction.SLIGHT_LEFT, Direction.SHARP_LEFT),
        )
        assertTrue(r.angle < 0)            // head is to the left of the tail
    }

    @Test fun bulkyRoundaboutShapeStaysLow() {
        val p = blank()
        // A hollow square ring: bulky in both axes, low coverage -> not a simple arrow.
        for (x in 10..38) { set(p, x, 6); set(p, x, 7); set(p, x, 33); set(p, x, 34) }
        for (y in 6..34) { set(p, 10, y); set(p, 11, y); set(p, 37, y); set(p, 38, y) }
        val r = ManeuverClassifier.classify(p)
        assertEquals(ManeuverClassifier.Confidence.LOW, r.confidence)
    }

    @Test fun emptyGlyphIsLow() {
        val r = ManeuverClassifier.classify(blank())
        assertEquals(ManeuverClassifier.Confidence.LOW, r.confidence)
    }

    @Test fun fingerprintIsDeterministicAndShapeSensitive() {
        val straight = blank().also { vline(it, 23, 25, 8, 40) }
        val left = blank().also { vline(it, 23, 25, 24, 40); vline(it, 6, 24, 23, 25) }
        assertEquals(
            ManeuverClassifier.fingerprint(straight),
            ManeuverClassifier.fingerprint(straight.copyOf()),
        )
        assertNotEquals(
            ManeuverClassifier.fingerprint(straight),
            ManeuverClassifier.fingerprint(left),
        )
    }
}
