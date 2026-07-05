package com.bquelhas.steer

import android.content.Context

/**
 * How the watch speedometer decides when to show the current speed.
 *  - [OFF]: never stream speed.
 *  - [ALWAYS]: show speed whenever a route is active, regardless of how/where it started.
 *  - [BICYCLE]: only show when the active route was launched from the watch in bicycle mode.
 */
enum class SpeedometerMode { OFF, ALWAYS, BICYCLE }

/** Small persisted settings store shared by the UI and the listener service. */
object NavPrefs {
    private const val FILE = "navme_prefs"
    private const val KEY_AUTOLAUNCH = "autolaunch"
    private const val KEY_BG_COLOR = "bg_color"
    private const val KEY_VIBE_ON_TURN = "vibe_on_turn"
    private const val KEY_DEBUG_TESTS = "debug_tests"
    private const val KEY_SPEEDOMETER_MODE = "speedometer_mode"
    private const val KEY_ACTIVE_MODE = "active_mode"
    private const val KEY_SPEED_ALERT = "speed_alert"
    private const val KEY_SPEED_LIMIT = "speed_limit"
    private const val KEY_SPEED_LIMIT_OSM = "speed_limit_osm"
    private const val KEY_UNITS = "units"
    private const val KEY_NAV_APP = "nav_app"
    private const val KEY_DETECT_APPS = "detect_apps"

    /** Default watch background = the red used on-watch (NAV_SCREEN_BG #ff4b49). */
    const val DEFAULT_BG_COLOR = 0xFF4B49

    /** Default manual speed limit (km/h) for the speed-limit alert. */
    const val DEFAULT_SPEED_LIMIT = 120

    /** Quick presets for the manual limit — the common highway maximums. */
    val SPEED_LIMIT_PRESETS = listOf(100, 120, 130)

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isAutolaunch(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTOLAUNCH, true)

    fun setAutolaunch(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOLAUNCH, enabled).apply()
    }

    /** Watch card background color, packed 0xRRGGBB (no alpha). */
    fun getBgColor(context: Context): Int =
        prefs(context).getInt(KEY_BG_COLOR, DEFAULT_BG_COLOR)

    fun setBgColor(context: Context, rgb: Int) {
        prefs(context).edit().putInt(KEY_BG_COLOR, rgb and 0xFFFFFF).apply()
    }

    /** Whether the watch should buzz on each new maneuver (NAV_VIBE_ON_TURN). */
    fun isVibeOnTurn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VIBE_ON_TURN, true)

    fun setVibeOnTurn(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VIBE_ON_TURN, enabled).apply()
    }

    /**
     * Master switch for the debug test tools (mock-nav emitter, icon cycler, adb
     * helpers). Default OFF so a normal user never sees a fake navigation notification
     * — every debug emit path checks this before doing anything.
     */
    fun isDebugTests(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEBUG_TESTS, false)

    fun setDebugTests(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DEBUG_TESTS, enabled).apply()
    }

    /**
     * How the watch speedometer behaves (Off / Always / Bicycle-only). When not Off, [SpeedProvider]
     * reads the phone GPS while navigating and streams the current speed (km/h) to the watch
     * (NAV_SPEED). Default [SpeedometerMode.BICYCLE] — speed matters most while cycling.
     */
    fun getSpeedometerMode(context: Context): SpeedometerMode {
        val ord = prefs(context).getInt(KEY_SPEEDOMETER_MODE, SpeedometerMode.BICYCLE.ordinal)
        return SpeedometerMode.entries.getOrElse(ord) { SpeedometerMode.BICYCLE }
    }

    fun setSpeedometerMode(context: Context, mode: SpeedometerMode) {
        prefs(context).edit().putInt(KEY_SPEEDOMETER_MODE, mode.ordinal).apply()
    }

    /** True when the speedometer is on in any form (gates GPS streaming). */
    fun isSpeedometerEnabled(context: Context): Boolean =
        getSpeedometerMode(context) != SpeedometerMode.OFF

    /**
     * Whether the current speed should be streamed for a route in [activeMode]: always in
     * [SpeedometerMode.ALWAYS]; only for bicycle routes in [SpeedometerMode.BICYCLE]; never when Off.
     */
    fun shouldShowSpeed(context: Context, activeMode: TravelMode): Boolean =
        when (getSpeedometerMode(context)) {
            SpeedometerMode.OFF -> false
            SpeedometerMode.ALWAYS -> true
            SpeedometerMode.BICYCLE -> activeMode == TravelMode.BICYCLE
        }

    /**
     * The travel mode of the current navigation session, set when a favourite is launched from the
     * watch (NAV_ROUTE_MODE) and reset to [TravelMode.CAR] when navigation ends. Drives the per-mode
     * speedometer gate — a route started manually (no watch mode) is treated as CAR.
     */
    fun getActiveMode(context: Context): TravelMode =
        TravelMode.fromId(prefs(context).getInt(KEY_ACTIVE_MODE, TravelMode.CAR.id))

    fun setActiveMode(context: Context, mode: TravelMode) {
        prefs(context).edit().putInt(KEY_ACTIVE_MODE, mode.id).apply()
    }

    /**
     * Whether the speed-limit alert is on — when true, [SpeedProvider] compares the GPS speed
     * against the effective limit (manual or OSM) and raises the watch full-screen speed-limit
     * sign (NAV_SPEED_ALERT + NAV_SPEED_LIMIT) when over. Default OFF (opt-in, needs location).
     */
    fun isSpeedAlert(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPEED_ALERT, false)

    fun setSpeedAlert(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPEED_ALERT, enabled).apply()
    }

    /**
     * Manual speed limit (km/h). Used directly in Manual mode, and as the fallback in OSM mode
     * when the road's real maxspeed can't be resolved (no coverage / offline).
     */
    fun getSpeedLimit(context: Context): Int =
        prefs(context).getInt(KEY_SPEED_LIMIT, DEFAULT_SPEED_LIMIT)

    fun setSpeedLimit(context: Context, kmh: Int) {
        prefs(context).edit().putInt(KEY_SPEED_LIMIT, kmh.coerceIn(1, 255)).apply()
    }

    /**
     * Speed-limit source. When true the alert uses the current road's real limit fetched from
     * OpenStreetMap (Overpass `maxspeed`) — this costs a little mobile data — instead of the fixed
     * manual limit above. Default false (Manual, zero network).
     */
    fun isOsmSpeedLimit(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPEED_LIMIT_OSM, false)

    fun setOsmSpeedLimit(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPEED_LIMIT_OSM, enabled).apply()
    }

    /**
     * Distance units shown on the watch. Default [UnitSystem.AUTO] — keep whatever the nav app
     * emits (so a phone set to miles shows miles, a phone set to km shows km). METRIC/IMPERIAL
     * force a conversion regardless of the nav app's own unit setting.
     */
    fun getUnitSystem(context: Context): UnitSystem {
        val ord = prefs(context).getInt(KEY_UNITS, UnitSystem.AUTO.ordinal)
        return UnitSystem.entries.getOrElse(ord) { UnitSystem.AUTO }
    }

    fun setUnitSystem(context: Context, units: UnitSystem) {
        prefs(context).edit().putInt(KEY_UNITS, units.ordinal).apply()
    }

    /**
     * Which navigator opens when a favorite is picked on the watch. Default [NavApp.AUTO] —
     * the first installed supported app (Maps preferred). See [NavLauncher.launchForWatch].
     */
    fun getNavApp(context: Context): NavApp {
        val ord = prefs(context).getInt(KEY_NAV_APP, NavApp.AUTO.ordinal)
        return NavApp.entries.getOrElse(ord) { NavApp.AUTO }
    }

    fun setNavApp(context: Context, app: NavApp) {
        prefs(context).edit().putInt(KEY_NAV_APP, app.ordinal).apply()
    }

    /**
     * Which navigator packages the notification listener is allowed to read. Defaults to the full
     * supported set, so behaviour is unchanged until the user narrows it in the Customization tab.
     * OsmAnd's free + paid packages are treated as one entry keyed on [NaviParser.PKG_OSMAND].
     */
    fun getDetectApps(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_DETECT_APPS, null) ?: NaviParser.SUPPORTED

    fun setDetectApps(context: Context, apps: Set<String>) {
        // Store a copy: SharedPreferences must not be handed a set it may later mutate.
        prefs(context).edit().putStringSet(KEY_DETECT_APPS, HashSet(apps)).apply()
    }
}
