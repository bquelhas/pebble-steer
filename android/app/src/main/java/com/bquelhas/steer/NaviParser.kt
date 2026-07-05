package com.bquelhas.steer

import kotlin.math.roundToInt

/** How distances shown on the watch are expressed. */
enum class UnitSystem {
    /** Keep whatever the nav app emitted (metric stays metric, imperial stays imperial). */
    AUTO,
    /** Always metres / kilometres, converting imperial notifications. */
    METRIC,
    /** Always feet / miles, converting metric notifications. */
    IMPERIAL,
}

/**
 * Turns a navigation notification (title + text) into a [NaviData].
 *
 * Strategy: the maneuver keyword matching and distance extraction are shared
 * across apps (they all speak the same human vocabulary); only the package
 * dispatch differs. Real-world notification strings vary by app version and must
 * be tuned on-device — keep the keyword tables here as the single tuning point.
 */
object NaviParser {

    const val PKG_GOOGLE_MAPS = "com.google.android.apps.maps"
    const val PKG_WAZE = "com.waze"
    const val PKG_OSMAND = "net.osmand.plus"
    const val PKG_OSMAND_FREE = "net.osmand"
    const val PKG_COMAPS = "app.comaps.google"
    const val PKG_ORGANIC = "app.organicmaps"
    const val PKG_HERE = "com.here.app.maps"
    const val PKG_SYGIC = "com.sygic.aura"

    // CoMaps / Organic Maps share one notification format: title = distance, text =
    // street name, and the maneuver lives ONLY as the engine-rendered largeIcon bitmap
    // (no maneuver text anywhere). They are handled like Google Maps — the glyph is
    // classified into NAV_TURN. Critically we must NOT keyword-match their text: the
    // "text" is a street name and PT streets like "Rua Direita"/"Rua da Esquerda" would
    // be misread as a turn. [ICON_ONLY] forces the glyph path (maneuverFromText=false).
    private val ICON_ONLY = setOf(PKG_COMAPS, PKG_ORGANIC)

    // Waze is deliberately NOT in SUPPORTED: its nav notification is only
    // "Waze - Running. Tap to open." — it never exposes the maneuver (no text, no glyph
    // in the notification). The only programmatic turn-by-turn feed Waze offers is the
    // partner/Transport SDK (rideshare), which needs a commercial agreement. We keep
    // [PKG_WAZE] for the favorites deep-link launcher only ([NavLauncher]).
    val SUPPORTED = setOf(PKG_GOOGLE_MAPS, PKG_OSMAND, PKG_OSMAND_FREE, PKG_COMAPS, PKG_ORGANIC)

    fun parse(
        pkg: String,
        title: String?,
        text: String?,
        subText: String? = null,
        units: UnitSystem = UnitSystem.AUTO,
    ): NaviData? {
        if (pkg !in SUPPORTED) return null
        val eta = extractEta(subText)
        if (pkg == PKG_OSMAND || pkg == PKG_OSMAND_FREE) return parseOsmand(title, text, eta, units)
        if (pkg in ICON_ONLY) return parseIconOnly(title, text, eta, units)

        val t = listOfNotNull(title, text).joinToString(" ").trim()
        if (t.isEmpty()) return null

        val direction = mapManeuver(t)
        val distance = extractDistance(t, units)
        val instruction = pickInstruction(title, text, t)
        val composed = compose(distance, instruction)
        return NaviData(direction, composed, maneuverFromText = hasManeuverKeyword(t), eta = eta)
    }

    /**
     * Extracts the arrival clock time from the notification subText, e.g.
     * "6 min · 1.7 km · 20:09 ETA" -> "20:09" (English) or
     * "6 min · 1,7 km · Chegada às 20:09" -> "20:09" (Portuguese). Locale-agnostic:
     * the ETA is the only clock-time token (HH:MM) in the subText, so we just grab the
     * last one. Returns null when there's no time token (e.g. "6 min · 1.7 km").
     */
    fun extractEta(subText: String?): String? {
        val s = subText?.trim().orEmpty()
        if (s.isEmpty()) return null
        return CLOCK_RE.findAll(s).lastOrNull()?.value?.trim()
    }

    /**
     * Picks the human instruction from title/text. Google Maps' ongoing-navigation
     * notification puts the *distance* in the title ("60 m") and the street /
     * instruction in the text ("towards R. de São Dinis"); using the title verbatim
     * would yield "60 m — 60 m". When the title is nothing but a distance token, the
     * instruction lives in the text. Apps whose title already holds the instruction
     * (title="Turn left onto X", text="300 m") keep using the title.
     */
    private fun pickInstruction(title: String?, text: String?, combined: String): String {
        val ti = title?.trim().orEmpty()
        val tx = text?.trim().orEmpty()
        if (ti.isNotEmpty() && isPureDistance(ti)) return tx.ifBlank { ti }
        return ti.ifBlank { tx.ifBlank { combined } }
    }

    private fun isPureDistance(s: String): Boolean = PURE_DISTANCE_RE.matches(s)

    /**
     * OsmAnd format: title = "60 m • Turn left and go" (distance + maneuver),
     * bigText = "Turn left and go Rua de São Dinis 150 m" (maneuver + street +
     * segment length). The distance-to-maneuver lives in the title; the street is
     * only in bigText. We take the distance from the title and use bigText (minus
     * its trailing segment distance) as the instruction so the street is shown.
     */
    private fun parseOsmand(title: String?, text: String?, eta: String? = null, units: UnitSystem = UnitSystem.AUTO): NaviData? {
        val titleStr = title?.trim().orEmpty()
        val big = text?.trim().orEmpty()
        val combined = listOf(titleStr, big).filter { it.isNotEmpty() }.joinToString(" ")
        if (combined.isEmpty()) return null

        val direction = mapManeuver(combined)
        val distance = extractDistance(titleStr, units) ?: extractDistance(big, units)
        val instruction = (if (big.isNotEmpty()) stripTrailingDistance(big)
            else titleStr.substringAfter('•', titleStr).trim())
            .ifBlank { combined }
        return NaviData(direction, compose(distance, instruction), maneuverFromText = hasManeuverKeyword(combined), eta = eta)
    }

    /**
     * CoMaps / Organic Maps format: title = distance ("76 m"), text = street name
     * ("Rua de São Dinis"). The maneuver is NOT in any text field — only in the
     * largeIcon glyph — so we deliberately force the glyph path (maneuverFromText =
     * false) and never run keyword matching on the street (a "Rua Direita" must not
     * become a RIGHT turn). [direction] is a placeholder the classifier overwrites.
     */
    private fun parseIconOnly(title: String?, text: String?, eta: String? = null, units: UnitSystem = UnitSystem.AUTO): NaviData? {
        val dist = title?.trim()?.let { extractDistance(it, units) }
        val street = text?.trim().orEmpty()
        val instruction = street.ifBlank { title?.trim().orEmpty() }
        if (dist == null && instruction.isEmpty()) return null
        return NaviData(Direction.STRAIGHT, compose(dist, instruction), maneuverFromText = false, eta = eta)
    }

    private fun stripTrailingDistance(s: String): String =
        s.replace(TRAILING_DISTANCE_RE, "").trim()

    fun compose(distance: String?, instruction: String): String =
        if (distance != null) "$distance — $instruction" else instruction

    /**
     * Extracts a human distance token like "400 m", "1.2 km", "0,5 km", "0.3 mi", "500 ft"
     * and re-expresses it in the requested [units]. In [UnitSystem.AUTO] the source system is
     * preserved (metric notifications stay metric, imperial stay imperial); [UnitSystem.METRIC]
     * / [UnitSystem.IMPERIAL] force a conversion. Returns null when no distance token is present.
     */
    fun extractDistance(s: String, units: UnitSystem = UnitSystem.AUTO): String? {
        val d = parseDistanceMeters(s) ?: return null
        return when (units) {
            UnitSystem.METRIC -> formatMetric(d.meters)
            UnitSystem.IMPERIAL -> formatImperial(d.meters)
            UnitSystem.AUTO -> if (d.imperialSource) formatImperial(d.meters) else formatMetric(d.meters)
        }
    }

    private data class Dist(val meters: Double, val imperialSource: Boolean)

    /** Parses the first distance token in [s] into metres + whether the source unit was imperial. */
    private fun parseDistanceMeters(s: String): Dist? {
        val m = DISTANCE_RE.find(s) ?: return null
        val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return when (m.groupValues[2].lowercase()) {
            "km" -> Dist(value * 1000.0, false)
            "m" -> Dist(value, false)
            "mi" -> Dist(value * 1609.344, true)
            "ft" -> Dist(value * 0.3048, true)
            "yd" -> Dist(value * 0.9144, true)
            else -> null
        }
    }

    /** Formats metres as the nav apps do: "60 m" under 1 km, else "1.2 km" (integer at >=10 km). */
    private fun formatMetric(meters: Double): String {
        if (meters < 1000.0) return "${meters.roundToInt()} m"
        val km = meters / 1000.0
        return if (km >= 10.0) "${km.roundToInt()} km" else "${trimZero(Math.round(km * 10.0) / 10.0)} km"
    }

    /** Formats metres imperially: "500 ft" under ~0.19 mi, else "0.3 mi" (integer at >=10 mi). */
    private fun formatImperial(meters: Double): String {
        if (meters < 300.0) {
            val ft = (Math.round(meters / 0.3048 / 10.0) * 10).toInt()
            return "$ft ft"
        }
        val mi = meters / 1609.344
        return if (mi >= 10.0) "${mi.roundToInt()} mi" else "${trimZero(Math.round(mi * 10.0) / 10.0)} mi"
    }

    /** "2.0" -> "2", "2.5" -> "2.5". */
    private fun trimZero(v: Double): String {
        val s = v.toString()
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    /** Maps a free-text instruction to a [Direction], falling back to STRAIGHT. */
    fun mapManeuver(raw: String): Direction = classifyManeuver(raw.lowercase()) ?: Direction.STRAIGHT

    /**
     * True when [raw] actually contains a maneuver keyword. When false, the text carries
     * no turn information (e.g. Google Maps' "60 m" / "towards X") and the caller should
     * forward the notification's glyph bitmap instead of trusting a STRAIGHT fallback.
     */
    fun hasManeuverKeyword(raw: String): Boolean = classifyManeuver(raw.lowercase()) != null

    /** Returns the matched [Direction], or null when no keyword matched (caller decides fallback). */
    private fun classifyManeuver(s: String): Direction? {
        // Arrival / departure first (they often also contain a side word).
        if (s.containsAny("arrive", "arriving", "destination", "chega", "chegou", "destino")) return Direction.ARRIVE
        if (s.containsAny("head ", "depart", "siga pela", "comece")) {
            // a bare "depart" with no later maneuver
            if (!s.containsAny("turn", "vire", "roundabout", "rotunda")) return Direction.DEPART
        }

        // Ferry
        if (s.containsAny("ferry", "ferry-boat", "barco", "cacilheiro")) return Direction.FERRY

        // Roundabout. Notifications only ever carry the exit NUMBER ("2nd exit"), never
        // the departure ANGLE, and the two are independent — so we cannot pick an angle
        // icon (ids 13..28) from the text without drawing a turn that doesn't exist.
        // Always use the generic roundabout (7/8); the explicit "exit the roundabout"
        // phrasing maps to the dedicated exit icons (29/30).
        if (s.containsAny("roundabout", "rotunda", "rotatória", "exit and go", "exit of")) {
            val left = isLeft(s)
            if (s.containsAny("exit the", "saia da", "sair da", "exit and go"))
                return if (left) Direction.ROUNDABOUT_EXIT_LEFT else Direction.ROUNDABOUT_EXIT_RIGHT
            val exit = roundaboutExit(s)
            if (exit in 1..8) {
                return if (left) ROUNDABOUT_LEFT[exit - 1] else ROUNDABOUT_RIGHT[exit - 1]
            }
            return if (left) Direction.GENERIC_ROUNDABOUT_LEFT else Direction.GENERIC_ROUNDABOUT_RIGHT
        }

        // Keep / fork / ramp / merge
        if (s.containsAny("keep left", "mantenha-se à esquerda", "mantenha à esquerda")) return Direction.KEEP_LEFT
        if (s.containsAny("keep right", "mantenha-se à direita", "mantenha à direita")) return Direction.KEEP_RIGHT
        if (s.containsAny("fork")) return if (isLeft(s)) Direction.FORK_LEFT else Direction.FORK_RIGHT
        if (s.containsAny("ramp", "rampa", "acesso à", "saída para a")) return if (isLeft(s)) Direction.RAMP_LEFT else Direction.RAMP_RIGHT
        if (s.containsAny("merge", "junte-se", "entroncamento")) return Direction.GENERIC_MERGE

        // U-turn — default left (right-hand traffic, PT); right only if explicit.
        if (s.containsAny("u-turn", "uturn", "inverta", "meia-volta", "inversão"))
            return if (isRight(s)) Direction.UTURN_RIGHT else Direction.UTURN_LEFT

        // Slight / sharp turns
        if (s.containsAny("slight left", "slightly left", "ligeiramente à esquerda")) return Direction.SLIGHT_LEFT
        if (s.containsAny("slight right", "slightly right", "ligeiramente à direita")) return Direction.SLIGHT_RIGHT
        if (s.containsAny("sharp left", "acentuada à esquerda", "apertada à esquerda")) return Direction.SHARP_LEFT
        if (s.containsAny("sharp right", "acentuada à direita", "apertada à direita")) return Direction.SHARP_RIGHT

        // Plain left / right
        if (s.containsAny("turn left", "vire à esquerda", "à esquerda", "esquerda")) return Direction.LEFT
        if (s.containsAny("turn right", "vire à direita", "à direita", "direita")) return Direction.RIGHT

        // Straight / continue
        if (s.containsAny("straight", "continue", "em frente", "siga em frente", "siga")) return Direction.STRAIGHT

        return null
    }

    // --- helpers ---

    private val ROUNDABOUT_LEFT = arrayOf(
        Direction.ROUNDABOUT_1_LEFT, Direction.ROUNDABOUT_2_LEFT, Direction.ROUNDABOUT_3_LEFT, Direction.ROUNDABOUT_4_LEFT,
        Direction.ROUNDABOUT_5_LEFT, Direction.ROUNDABOUT_6_LEFT, Direction.ROUNDABOUT_7_LEFT, Direction.ROUNDABOUT_8_LEFT
    )
    private val ROUNDABOUT_RIGHT = arrayOf(
        Direction.ROUNDABOUT_1_RIGHT, Direction.ROUNDABOUT_2_RIGHT, Direction.ROUNDABOUT_3_RIGHT, Direction.ROUNDABOUT_4_RIGHT,
        Direction.ROUNDABOUT_5_RIGHT, Direction.ROUNDABOUT_6_RIGHT, Direction.ROUNDABOUT_7_RIGHT, Direction.ROUNDABOUT_8_RIGHT
    )

    // Distance units we recognise from notifications: metric (km/m) + imperial (mi/ft/yd).
    // Order matters — longer/imperial tokens before the bare "m" so "mi" isn't read as "m".
    private const val UNIT_ALT = "km|mi|ft|yd|m"
    private val DISTANCE_RE = Regex("""(\d+(?:[.,]\d+)?)\s*($UNIT_ALT)\b""", RegexOption.IGNORE_CASE)
    // The whole string is nothing but a distance token (Google Maps title: "60 m" / "0.3 mi").
    private val PURE_DISTANCE_RE = Regex("""\d+(?:[.,]\d+)?\s*(?:$UNIT_ALT)\b""", RegexOption.IGNORE_CASE)
    // A distance token at the very end of a string (OsmAnd bigText: "... 150 m").
    private val TRAILING_DISTANCE_RE = Regex("""\s*\d+(?:[.,]\d+)?\s*(?:$UNIT_ALT)\b\s*$""", RegexOption.IGNORE_CASE)
    // A clock-time token (the arrival/ETA time in the subText): "20:09", "8:09 PM".
    private val CLOCK_RE = Regex("""\d{1,2}:\d{2}(?:\s?[AaPp][Mm])?""")
    private val EXIT_RE = Regex("""(?:(\d+)\s*(?:st|nd|rd|th|º|ª|a)?\s*(?:exit|saída|saida)|(?:exit|saída|saida)\s*(?:n[º.]?\s*|número\s*)?(\d+))""", RegexOption.IGNORE_CASE)

    private fun isLeft(s: String): Boolean = s.containsAny("left", "esquerda")
    private fun isRight(s: String): Boolean = s.containsAny("right", "direita")
    private fun String.containsAny(vararg needles: String) = needles.any { this.contains(it) }

    private fun roundaboutExit(s: String): Int {
        val m = EXIT_RE.find(s) ?: return -1
        val numStr = m.groupValues[1].ifEmpty { m.groupValues[2] }
        return numStr.toIntOrNull() ?: -1
    }
}
