package com.bquelhas.steer

import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * What the phone does with a message FROM the Steer watchapp, whichever transport delivered it —
 * [WatchCommandReceiver] (classic PebbleKit) or [SteerPebbleListenerService] (PebbleKit 2):
 *  - [NavKeys.NAV_REQUEST_FAVS]: the watchapp just launched and wants its data — [resync].
 *  - [NavKeys.NAV_TRIGGER_ROUTE] (+ [NavKeys.NAV_ROUTE_MODE]): with nav idle, the SELECT button
 *    picked a favorite (and a travel mode); start navigation to it.
 */
object WatchCommands {
    private const val TAG = "NavMe/WatchCmd"
    private const val DEBOUNCE_MS = 3000L

    private var lastIndex = -1
    private var lastAt = 0L
    @Volatile private var lastResyncAt = 0L

    /**
     * Handles one inbound message. [readInt] returns the integer at a message key, or null when
     * the key is absent, so each transport plugs in its own dictionary type.
     */
    @Synchronized
    fun handle(context: Context, via: String, readInt: (Int) -> Long?) {
        // Launch-time sync: the watchapp only keeps favorites in RAM, so a fresh launch shows an
        // empty menu until the phone re-pushes. The request is handled by a receiver/service that
        // is alive whenever the notification listener is (i.e. essentially always), no foreground
        // app needed.
        if (readInt(NavKeys.NAV_REQUEST_FAVS) != null) {
            resync(context, "watch requested favorites ($via)")
            return
        }

        val idx = readInt(NavKeys.NAV_TRIGGER_ROUTE)?.toInt() ?: return
        // Debounce: the watch may resend on a flaky link; ignore repeats of the same pick.
        val now = SystemClock.elapsedRealtime()
        if (idx == lastIndex && now - lastAt < DEBOUNCE_MS) return
        lastIndex = idx; lastAt = now

        // Travel mode chosen on the watch (0 car by default if an older watchapp omits it).
        val rawMode = readInt(NavKeys.NAV_ROUTE_MODE)
        val mode = TravelMode.fromId(rawMode?.toInt() ?: 0)
        // Remember it for the session so SpeedProvider can gate the speedometer per mode.
        NavPrefs.setActiveMode(context, mode)
        // Diagnostic: a null rawMode means the watchapp didn't include NAV_ROUTE_MODE (19) in the
        // trigger message, so we fall back to CAR — for which the speedometer is off by default.
        Log.i(TAG, "NAV_ROUTE_MODE raw=$rawMode -> ${mode.name}" +
            (if (rawMode == null) " (MISSING: watch sent no mode, defaulting CAR)" else ""))

        val favs = FavoritesStore.all(context)
        val fav = favs.getOrNull(idx)
        if (fav == null) {
            Log.w(TAG, "trigger for unknown favorite index $idx (have ${favs.size})")
            return
        }
        Log.i(TAG, "watch triggered favorite #$idx '${fav.label}' (${mode.name}, $via) -> ${fav.query}")
        // Started from a background context (the watch press). Android's BAL policy blocks a plain
        // background startActivity, so launchForWatch honors the preferred navigator, uses the
        // overlay (SYSTEM_ALERT_WINDOW) BAL exemption when granted, and otherwise falls back to a
        // tap-to-launch notification.
        NavLauncher.launchForWatch(context, fav.label, fav.query, mode)
    }

    /**
     * Brings a freshly (re)launched watchapp up to date. The current nav frame goes FIRST (the
     * favorites burst is paced and would delay it), so a mid-route relaunch shows the maneuver
     * right away instead of "Waiting for signal..." until the next map update; then the
     * favorites are re-pushed.
     */
    fun resync(context: Context, reason: String) {
        lastResyncAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "$reason -> resyncing")
        if (NavSession.active) {
            NavSession.lastData?.let {
                Log.i(TAG, "nav active -> replaying last frame to the watch")
                PebbleEmitter.sendNav(context, it, null)
            }
        }
        PebbleEmitter.sendFavorites(context)
    }

    /** Milliseconds since the last [resync] — lets a launch-time fallback skip a duplicate. */
    fun msSinceResync(): Long = SystemClock.elapsedRealtime() - lastResyncAt
}
