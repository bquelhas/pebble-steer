package com.bquelhas.steer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VibePlannerTest {

    private val STRAIGHT = Direction.STRAIGHT.id
    private val LEFT = Direction.LEFT.id

    @Before fun clean() = VibePlanner.reset()

    /** Simulates an approach at a constant speed; returns the distance (m) at which it buzzed. */
    private fun approachBuzzDistance(startM: Double, speedKmh: Double, gps: Int, stepS: Double): Double? {
        VibePlanner.reset()
        val mps = speedKmh / 3.6
        var d = startM
        var t = 0L
        while (d > 0) {
            if (VibePlanner.onUpdate(LEFT, "${d.toInt()} m — Rua X", d, gps, t)) return d
            d -= mps * stepS
            t += (stepS * 1000).toLong()
        }
        return null
    }

    // DERIVED speed (no GPS): a motorway approach (distance shrinking fast) buzzes far out,
    // ~800 m — not the 200/400 m the old distance-buckets gave.
    @Test fun motorwayViaDerivedSpeedBuzzesFarOut() {
        val d = approachBuzzDistance(startM = 2500.0, speedKmh = 120.0, gps = -1, stepS = 2.0)
        assertNotNull("should buzz", d)
        assertTrue("motorway buzz should be far out (~800 m), was $d", d!! in 700.0..950.0)
    }

    // DERIVED speed (no GPS): a town approach buzzes close, ~280 m.
    @Test fun cityViaDerivedSpeedBuzzesClose() {
        val d = approachBuzzDistance(startM = 500.0, speedKmh = 40.0, gps = -1, stepS = 1.0)
        assertNotNull(d)
        assertTrue("city buzz ~280 m, was $d", d!! in 220.0..340.0)
    }

    // GPS speed, when present, drives the lead directly.
    @Test fun gpsSpeedDrivesLead() {
        val d = approachBuzzDistance(startM = 1500.0, speedKmh = 5.0, gps = 100, stepS = 2.0)
        assertNotNull(d)
        assertTrue("100 km/h -> ~695 m, was $d", d!! in 620.0..760.0)
    }

    // A maneuver that appears already within the lead buzzes immediately (Bruno's ask).
    @Test fun newManeuverAppearingCloseBuzzesAtOnce() {
        assertTrue(VibePlanner.onUpdate(LEFT, "80 m — Rua Z", 80.0, -1, 0L))
    }

    // A reroute (distance jumps back up on the same road) re-arms and buzzes again.
    @Test fun rerouteBuzzesAgain() {
        assertFalse(VibePlanner.onUpdate(LEFT, "300 m — Rua A", 300.0, -1, 0L))
        assertTrue(VibePlanner.onUpdate(LEFT, "250 m — Rua A", 250.0, -1, 2_000L))   // buzz #1
        assertFalse(VibePlanner.onUpdate(LEFT, "600 m — Rua A", 600.0, -1, 4_000L))  // reroute, re-arm
        assertTrue(VibePlanner.onUpdate(LEFT, "550 m — Rua A", 550.0, -1, 6_000L))   // buzz #2
    }

    // A brand-new maneuver (different street) buzzes even right after the previous one.
    @Test fun newManeuverRightAfterPreviousBuzzes() {
        assertTrue(VibePlanner.onUpdate(LEFT, "120 m — Rua A", 120.0, -1, 0L))
        assertTrue(VibePlanner.onUpdate(STRAIGHT, "90 m — Rua B", 90.0, -1, 1_000L))
    }

    // The same maneuver counting down buzzes exactly once.
    @Test fun sameManeuverBuzzesOnce() {
        var buzzes = 0
        var d = 400
        var t = 0L
        while (d >= 20) {
            if (VibePlanner.onUpdate(LEFT, "$d m — Rua X", d.toDouble(), 40, t)) buzzes++
            d -= 10; t += 1_000L
        }
        assertEquals(1, buzzes)
    }

    // The user's report: the distance lives INSIDE the instruction text and changes each
    // tick. Identity must ignore it -> exactly one buzz, not one per 10 m.
    @Test fun distanceInsideInstructionStillOneBuzz() {
        var buzzes = 0
        var d = 300
        var t = 0L
        while (d >= 20) {
            if (VibePlanner.onUpdate(LEFT, "In $d m turn left onto Rua X", d.toDouble(), 40, t)) buzzes++
            d -= 10; t += 1_000L
        }
        assertEquals(1, buzzes)
    }

    // Legacy path (no parseable distance): one buzz per new instruction.
    @Test fun noDistanceBuzzesOncePerInstruction() {
        assertTrue(VibePlanner.onUpdate(LEFT, "Turn left", null, -1, 0L))
        assertFalse(VibePlanner.onUpdate(LEFT, "Turn left", null, -1, 1_000L))
        assertTrue(VibePlanner.onUpdate(STRAIGHT, "Turn right", null, -1, 2_000L))
    }
}
