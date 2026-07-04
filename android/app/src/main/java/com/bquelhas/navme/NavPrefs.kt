package com.bquelhas.navme

import android.content.Context

/** Small persisted settings store shared by the UI and the listener service. */
object NavPrefs {
    private const val FILE = "navme_prefs"
    private const val KEY_AUTOLAUNCH = "autolaunch"
    private const val KEY_BG_COLOR = "bg_color"
    private const val KEY_VIBE_ON_TURN = "vibe_on_turn"
    private const val KEY_DEBUG_TESTS = "debug_tests"
    private const val KEY_SPEEDOMETER = "speedometer"
    private const val KEY_SPEED_ALERT = "speed_alert"
    private const val KEY_SPEED_LIMIT = "speed_limit"
    private const val KEY_UNITS = "units"
    private const val KEY_NAV_APP = "nav_app"

    /** Default watch background = the red used on-watch (NAV_SCREEN_BG #ff4b49). */
    const val DEFAULT_BG_COLOR = 0xFF4B49

    /** Default manual speed limit (km/h) for the speed-limit alert. */
    const val DEFAULT_SPEED_LIMIT = 120

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
     * Whether the watch speedometer is on — when true, [SpeedProvider] reads the phone GPS
     * while navigating and streams the current speed (km/h) to the watch (NAV_SPEED).
     * Default OFF: it needs the location permission and only matters once Bruno wires the
     * on-watch speed display (STEER_SHOW_SPEEDOMETER).
     */
    fun isSpeedometer(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPEEDOMETER, false)

    fun setSpeedometer(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPEEDOMETER, enabled).apply()
    }

    /**
     * Whether the speed-limit alert is on — when true, [SpeedProvider] compares the GPS speed
     * against [getSpeedLimit] and raises the watch warning banner (NAV_SPEED_ALERT) when over.
     * Default OFF (opt-in, needs location permission).
     */
    fun isSpeedAlert(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPEED_ALERT, false)

    fun setSpeedAlert(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPEED_ALERT, enabled).apply()
    }

    /** Manual speed limit (km/h) for the speed-limit alert. */
    fun getSpeedLimit(context: Context): Int =
        prefs(context).getInt(KEY_SPEED_LIMIT, DEFAULT_SPEED_LIMIT)

    fun setSpeedLimit(context: Context, kmh: Int) {
        prefs(context).edit().putInt(KEY_SPEED_LIMIT, kmh.coerceIn(1, 255)).apply()
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
}
