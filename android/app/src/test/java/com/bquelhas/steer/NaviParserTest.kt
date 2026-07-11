package com.bquelhas.steer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NaviParserTest {

    private fun gmaps(title: String?, text: String? = null) =
        NaviParser.parse(NaviParser.PKG_GOOGLE_MAPS, title, text)

    private fun osmand(title: String?, text: String? = null) =
        NaviParser.parse(NaviParser.PKG_OSMAND_FREE, title, text)

    @Test fun unsupportedPackageIgnored() {
        assertNull(NaviParser.parse("com.unknown.app", "Turn left", "300 m"))
    }

    // --- CoMaps flavours (Play / F-Droid / IzzyOnDroid) are all supported ---

    @Test fun everyComapsFlavourIsSupported() {
        assertTrue(NaviParser.isSupported("app.comaps.google"))
        assertTrue(NaviParser.isSupported("app.comaps.fdroid"))
        assertTrue(NaviParser.isSupported("app.comaps.izzyondroid"))
        assertFalse(NaviParser.isSupported("app.comapsx.other"))
        assertFalse(NaviParser.isSupported("com.example.comaps"))
    }

    @Test fun comapsFdroidReadsAsIconOnly() {
        // Icon-only: distance from title, street from text, direction left to the classifier.
        val d = NaviParser.parse("app.comaps.fdroid", "300 m", "Rua de São Dinis")
        assertEquals("300 m — Rua de São Dinis", d!!.instructionText)
        assertFalse(d.maneuverFromText)   // must NOT keyword-match the street
    }

    @Test fun detectMatchesComapsFlavourByPrefix() {
        // Enabling CoMaps stores the canonical package; any flavour must still be detected.
        val detect = setOf(NaviParser.PKG_COMAPS, NaviParser.PKG_GOOGLE_MAPS)
        assertTrue(NaviParser.isDetected("app.comaps.fdroid", detect))
        assertTrue(NaviParser.isDetected(NaviParser.PKG_GOOGLE_MAPS, detect))
        // CoMaps unchecked -> no comaps package in the set -> not detected.
        assertFalse(NaviParser.isDetected("app.comaps.fdroid", setOf(NaviParser.PKG_GOOGLE_MAPS)))
    }

    @Test fun turnLeftEn() {
        assertEquals(Direction.LEFT, gmaps("Turn left onto Main St", "300 m")!!.direction)
    }

    @Test fun turnRightPt() {
        assertEquals(Direction.RIGHT, gmaps("Vire à direita na Rua A", "250 m")!!.direction)
    }

    @Test fun slightBeatsPlainLeft() {
        assertEquals(Direction.SLIGHT_LEFT, gmaps("Slight left onto A1", "1.2 km")!!.direction)
    }

    @Test fun sharpRight() {
        assertEquals(Direction.SHARP_RIGHT, gmaps("Sharp right", "80 m")!!.direction)
    }

    @Test fun uTurn() {
        assertEquals(Direction.UTURN_LEFT, gmaps("Make a U-turn", "120 m")!!.direction)
    }

    @Test fun roundaboutThirdExit() {
        assertEquals(Direction.ROUNDABOUT_3_RIGHT, gmaps("At the roundabout, take the 3rd exit", "200 m")!!.direction)
    }

    @Test fun roundaboutPtSecondExit() {
        assertEquals(Direction.ROUNDABOUT_2_RIGHT, gmaps("Na rotunda, saída 2", "150 m")!!.direction)
    }

    @Test fun keepLeft() {
        assertEquals(Direction.KEEP_LEFT, gmaps("Keep left", "500 m")!!.direction)
    }

    @Test fun ferry() {
        assertEquals(Direction.FERRY, gmaps("Take the ferry", "1 km")!!.direction)
    }

    @Test fun arrival() {
        assertEquals(Direction.ARRIVE, gmaps("You have arrived", null)!!.direction)
    }

    @Test fun distanceMeters() {
        assertEquals("400 m", NaviParser.extractDistance("In 400 m, turn left"))
    }

    @Test fun distanceKmComma() {
        assertEquals("2.5 km", NaviParser.extractDistance("Continue por 2,5 km"))
    }

    // --- numeric distance for the smart-vibration planner ---

    @Test fun numericDistanceMetersExposed() {
        assertEquals(400.0, NaviParser.distanceMetersOf("In 400 m, turn left")!!, 0.01)
        assertEquals(2500.0, NaviParser.distanceMetersOf("Continue por 2,5 km")!!, 0.01)
        assertEquals(482.8, NaviParser.distanceMetersOf("In 0.3 mi, turn left")!!, 0.1)
        assertEquals(null, NaviParser.distanceMetersOf("Turn left"))
    }

    @Test fun parsedNaviDataCarriesDistanceMeters() {
        assertEquals(300.0, gmaps("300 m", "R. de São Dinis")!!.distanceMeters!!, 0.01)
        assertEquals(null, gmaps("Turn left", null)!!.distanceMeters)
    }

    // --- ETA: "time left" honoured even when the notification only has the arrival clock ---

    @Test fun remainingUsesExplicitDurationWhenPresent() {
        assertEquals("6 min",
            NaviParser.extractEtaField("6 min · 1.7 km · 20:09", EtaMode.REMAINING, nowMinutes = 20 * 60))
    }

    @Test fun remainingDerivedFromArrivalClockWhenNoDurationToken() {
        // subText has the arrival clock but no "X min" token; at 19:51 -> 18 min left.
        assertEquals("18 min",
            NaviParser.extractEtaField("20:09 ETA · 1.7 km", EtaMode.REMAINING, nowMinutes = 19 * 60 + 51))
    }

    @Test fun remainingDerivedAcrossTheHour() {
        // now 10:12, arrival 11:30 -> 1 h 18 min.
        assertEquals("1 h 18 min",
            NaviParser.remainingFromArrival("11:30", 10 * 60 + 12))
    }

    @Test fun remainingHandlesAfterMidnightArrival() {
        // now 23:50, arrival 00:10 -> 20 min (wraps past midnight).
        assertEquals("20 min", NaviParser.remainingFromArrival("00:10", 23 * 60 + 50))
    }

    @Test fun arrivalModeStillShowsClock() {
        assertEquals("20:09",
            NaviParser.extractEtaField("6 min · 1.7 km · 20:09", EtaMode.ARRIVAL, nowMinutes = 20 * 60))
    }

    // --- international units ---

    @Test fun autoKeepsImperialSource() {
        // A US-locale nav app emits miles/feet; AUTO must pass them through, not drop them.
        assertEquals("0.3 mi", NaviParser.extractDistance("In 0.3 mi, turn left", UnitSystem.AUTO))
        assertEquals("500 ft", NaviParser.extractDistance("In 500 ft, turn right", UnitSystem.AUTO))
    }

    @Test fun autoKeepsMetricSource() {
        assertEquals("400 m", NaviParser.extractDistance("In 400 m", UnitSystem.AUTO))
        assertEquals("2.5 km", NaviParser.extractDistance("2,5 km", UnitSystem.AUTO))
    }

    @Test fun forceMetricConvertsImperial() {
        // 1 mi = 1609.344 m -> 1.6 km ; 500 ft = 152.4 m -> 152 m
        assertEquals("1.6 km", NaviParser.extractDistance("1 mi ahead", UnitSystem.METRIC))
        assertEquals("152 m", NaviParser.extractDistance("500 ft ahead", UnitSystem.METRIC))
    }

    @Test fun forceImperialConvertsMetric() {
        // 1.6 km = 1600 m -> 0.994 mi ~ 1 mi ; 100 m -> 328 ft -> rounded to nearest 10 = 330 ft
        assertEquals("1 mi", NaviParser.extractDistance("1,6 km ahead", UnitSystem.IMPERIAL))
        assertEquals("330 ft", NaviParser.extractDistance("100 m ahead", UnitSystem.IMPERIAL))
    }

    @Test fun miNotReadAsMetres() {
        // "mi" must not be mis-parsed as the bare "m" unit.
        assertEquals("0.5 mi", NaviParser.extractDistance("0.5 mi", UnitSystem.AUTO))
    }

    @Test fun composeWithDistance() {
        val d = gmaps("Turn left onto Rua da Paz", "300 m")!!
        assertEquals("300 m — Turn left onto Rua da Paz", d.instructionText)
    }

    @Test fun fallbackStraight() {
        assertEquals(Direction.STRAIGHT, gmaps("Continue on A5", "3 km")!!.direction)
    }

    @Test fun directionRoundTripsThroughId() {
        Direction.entries.forEach { assertEquals(it, Direction.fromId(it.id)) }
    }

    // --- OsmAnd (distance in title, maneuver+street in bigText) ---

    @Test fun osmandTurnLeftDirection() {
        val d = osmand("60 m • Turn left and go", "Turn left and go Rua de São Dinis 150 m")!!
        assertEquals(Direction.LEFT, d.direction)
    }

    @Test fun osmandUsesTitleDistanceAndStreet() {
        val d = osmand("60 m • Turn left and go", "Turn left and go Rua de São Dinis 150 m")!!
        assertEquals("60 m — Turn left and go Rua de São Dinis", d.instructionText)
    }

    @Test fun osmandKeepRight() {
        assertEquals(Direction.KEEP_RIGHT, osmand("400 m • Keep right", "Keep right onto A1 2 km")!!.direction)
    }

    @Test fun osmandTitleOnlyFallback() {
        val d = osmand("200 m • Go straight", null)!!
        assertEquals(Direction.STRAIGHT, d.direction)
        assertEquals("200 m — Go straight", d.instructionText)
    }

    // --- Google Maps real ongoing-nav format: title = distance, text = street ---

    @Test fun gmapsDistanceInTitleStreetInText() {
        val d = gmaps("60 m", "R. de São Dinis")!!
        assertEquals("60 m — R. de São Dinis", d.instructionText)
    }

    @Test fun gmapsTowardsStreet() {
        val d = gmaps("0 m", "towards R. de São Dinis")!!
        assertEquals("0 m — towards R. de São Dinis", d.instructionText)
    }

    // --- maneuver-from-text confidence (drives whether we forward the glyph bitmap) ---

    @Test fun gmapsIconOnlyIsNotFromText() {
        // distance + street, no turn word → caller should forward the notification glyph
        assertEquals(false, gmaps("60 m", "R. de São Dinis")!!.maneuverFromText)
        assertEquals(false, gmaps("0 m", "towards R. de São Dinis")!!.maneuverFromText)
    }

    @Test fun keywordManeuverIsFromText() {
        assertEquals(true, gmaps("Turn left onto Main St", "300 m")!!.maneuverFromText)
        assertEquals(true, osmand("60 m • Turn left and go", "Turn left and go Rua X 150 m")!!.maneuverFromText)
    }

    @Test fun hasManeuverKeywordDirectly() {
        assertEquals(true, NaviParser.hasManeuverKeyword("Turn right onto A1"))
        assertEquals(false, NaviParser.hasManeuverKeyword("towards R. de São Dinis"))
        assertEquals(false, NaviParser.hasManeuverKeyword("60 m"))
    }

    // --- ETA extraction from notification subText ---

    @Test fun etaEnglishSubText() {
        assertEquals("20:09", NaviParser.extractEta("6 min · 1.7 km · 20:09 ETA"))
    }

    @Test fun etaPortugueseSubText() {
        assertEquals("20:09", NaviParser.extractEta("6 min · 1,7 km · Chegada às 20:09"))
    }

    @Test fun etaArriveFormat() {
        assertEquals("14:30", NaviParser.extractEta("Arrive 14:30"))
    }

    @Test fun etaTwelveHour() {
        assertEquals("8:09 PM", NaviParser.extractEta("6 min · 1.7 km · 8:09 PM ETA"))
    }

    @Test fun etaAbsentReturnsNull() {
        assertNull(NaviParser.extractEta("6 min · 1.7 km"))
        assertNull(NaviParser.extractEta(""))
        assertNull(NaviParser.extractEta(null))
    }

    @Test fun etaFlowsIntoNaviData() {
        val d = NaviParser.parse(NaviParser.PKG_GOOGLE_MAPS, "220 m", "Largo da Ramada Alta",
            "6 min · 1.7 km · 20:09 ETA")!!
        assertEquals("20:09", d.eta)
    }
}
