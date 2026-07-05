package com.bquelhas.steer

import android.content.Context
import android.util.Log
import com.getpebble.android.kit.PebbleKit
import com.getpebble.android.kit.util.PebbleDictionary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends a [NaviData] to the NavMe watchapp via the Core Devices bridge.
 *
 * Data send uses the **legacy/classic PebbleKit** (`PebbleKit.sendDataToPebble`, the
 * `com.getpebble.action.app.SEND` broadcast). This was re-adopted after PebbleKit2 was found
 * UNUSABLE for our case: PK2's `DefaultPebbleSender.sendDataToPebble` is a pure relay that returns
 * whatever verdict Core puts in the reply bundle, and Core rejects every send with
 * `FailedDifferentAppOpen` whenever its tracked "active app" (`content://coredevices.coreapp.pebblekit/
 * activeApp/<serial>`) isn't our UUID — which it routinely isn't, because Core's `startAppOnTheWatch`
 * only flips that record optimistically and does NOT actually foreground the watchapp (verified
 * on-device 2026-06-26: startAppOnTheWatch=Success yet the very next send still NACKs
 * FailedDifferentAppOpen). The classic path has NO active-app gate — it broadcasts the AppMessage and
 * Core forwards it regardless. PebbleNavi (which works reliably on Core) uses ONLY classic PebbleKit
 * and no PK2 at all; we now mirror that.
 *
 * Core handles the classic `SEND` broadcast through its runtime-registered receiver
 * (`io.rebble.libpebblecommon.pebblekit.classic`); it is dynamically registered so it does not show
 * up in `pm query-receivers` (manifest-only) — the earlier "zero recipients" reading was that
 * artifact, not an absent receiver.
 *
 * Sends are serialized through a [Mutex] off the caller's thread so concurrent maneuver frames
 * don't race while building/broadcasting the shared dictionary.
 */
object PebbleEmitter {
    private const val TAG = "NavMe/Emitter"

    /**
     * Gap between the consecutive AppMessages of a favorites sync. Classic PebbleKit
     * `sendDataToPebble` is fire-and-forget (no ACK wait), and the Core bridge / watch inbox
     * silently DROPS messages fired back-to-back — which is why favorites defined on the phone
     * never showed up on the watch. Pacing the burst lets each message land before the next.
     */
    private const val FAV_SEND_GAP_MS = 250L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sendMutex = Mutex()

    /** Legacy connection probe — Core re-exposes the basalt content provider it reads. */
    fun isWatchConnected(context: Context): Boolean =
        try { PebbleKit.isWatchConnected(context) } catch (e: Exception) { false }

    /** Serializes one classic PebbleKit send, off the caller's thread. */
    private fun send(context: Context, label: String, build: (PebbleDictionary) -> Unit) {
        val appCtx = context.applicationContext
        scope.launch {
            sendMutex.withLock {
                try {
                    val dict = PebbleDictionary()
                    build(dict)
                    PebbleKit.sendDataToPebble(appCtx, NavKeys.WATCH_UUID, dict)
                    Log.i(TAG, "$label -> sent(classic)")
                } catch (e: Exception) {
                    Log.e(TAG, "$label send failed: ${e.message}")
                }
            }
        }
    }

    fun sendNav(context: Context, data: NaviData, iconBytes: ByteArray? = null) {
        val label = "sent turn=${data.direction} text='${data.instructionText}'" +
            (if (data.eta != null) " +eta(${data.eta})" else "") +
            (if (iconBytes != null) " +icon(${iconBytes.size}B)" else "")
        send(context, label) { dict ->
            dict.addInt32(NavKeys.NAV_TURN, data.directionId)
            dict.addUint8(NavKeys.NAV_TEXT_BEGIN, 1.toByte())
            dict.addString(NavKeys.NAV_TEXT, data.instructionText.take(120))
            dict.addUint8(NavKeys.NAV_TEXT_END, 1.toByte())
            data.gpsAccuracy?.let { dict.addString(NavKeys.NAV_GPS_ACCURACY, it) }
            data.eta?.let { dict.addString(NavKeys.NAV_ETA, it) }
            iconBytes?.let { dict.addBytes(NavKeys.NAV_ICON_BITMAP, it) }
            // Watch background color (0xRRGGBB); watch adapts text contrast by luminance.
            dict.addUint32(NavKeys.NAV_BG_COLOR, NavPrefs.getBgColor(context) and 0xFFFFFF)
            // Settings sync: let the watch know whether to buzz on each new maneuver.
            dict.addUint8(NavKeys.NAV_VIBE_ON_TURN, (if (NavPrefs.isVibeOnTurn(context)) 1 else 0).toByte())
        }
    }

    fun sendCancel(context: Context) {
        send(context, "cancel") { it.addUint8(NavKeys.NAV_CANCEL, 1.toByte()) }
    }

    /** Pushes the vibrate-on-turn setting on its own (e.g. right after the user toggles it). */
    fun sendVibeOnTurn(context: Context) {
        send(context, "vibeOnTurn") {
            it.addUint8(NavKeys.NAV_VIBE_ON_TURN, (if (NavPrefs.isVibeOnTurn(context)) 1 else 0).toByte())
        }
    }

    /**
     * Raises (or clears) the speed-limit warning on the watch. When [exceeded] is true the watch
     * takes over the whole screen with a speed-limit sign showing [limitKmh] + one long vibration;
     * when false it returns to the normal nav layout. Driven live by [SpeedProvider] from the GPS
     * speed vs. the effective limit (manual preset or OSM road maxspeed). The limit is sent on both
     * edges so the sign always has a value to draw.
     */
    fun sendSpeedAlert(context: Context, exceeded: Boolean, limitKmh: Int) {
        send(context, "speedAlert=$exceeded limit=$limitKmh") {
            it.addUint8(NavKeys.NAV_SPEED_ALERT, (if (exceeded) 1 else 0).toByte())
            it.addUint8(NavKeys.NAV_SPEED_LIMIT, limitKmh.coerceIn(0, 255).toByte())
        }
    }

    /**
     * Pushes the current GPS speed (km/h, 0..255) to the watch speedometer (NAV_SPEED).
     * Called per GPS fix by [SpeedProvider] while a route is active; [SpeedProvider] de-dups
     * so this only fires when the value actually changes.
     */
    fun sendSpeed(context: Context, kmh: Int) {
        send(context, "speed=$kmh") {
            it.addUint8(NavKeys.NAV_SPEED, kmh.coerceIn(0, 255).toByte())
        }
    }

    /**
     * Syncs the saved favorites to the watch so the SELECT button can pick one when nav is
     * idle. Sends the count first, then one message per favorite carrying its index + name.
     * The watch echoes a selection back via [NavKeys.NAV_TRIGGER_ROUTE].
     */
    fun sendFavorites(context: Context) {
        val appCtx = context.applicationContext
        val favs = FavoritesStore.all(appCtx)
        // One coroutine for the whole sync so the messages are strictly ordered (count MUST
        // arrive first — the watch clears its list on NAV_FAV_COUNT) and paced (see
        // FAV_SEND_GAP_MS). Holding the mutex across the burst also stops a maneuver frame
        // from interleaving; favorites sync only runs when idle, so the brief hold is fine.
        scope.launch {
            sendMutex.withLock {
                try {
                    PebbleDictionary()
                        .apply { addUint8(NavKeys.NAV_FAV_COUNT, favs.size.toByte()) }
                        .also { PebbleKit.sendDataToPebble(appCtx, NavKeys.WATCH_UUID, it) }
                    Log.i(TAG, "favCount=${favs.size} -> sent(classic)")
                    favs.forEachIndexed { i, fav ->
                        delay(FAV_SEND_GAP_MS)
                        PebbleDictionary().apply {
                            addUint8(NavKeys.NAV_FAV_INDEX, i.toByte())
                            addString(NavKeys.NAV_FAV_NAME, fav.label.take(32))
                            addUint8(NavKeys.NAV_FAV_ICON, fav.icon.coerceIn(0, 255).toByte())
                        }.also { PebbleKit.sendDataToPebble(appCtx, NavKeys.WATCH_UUID, it) }
                        Log.i(TAG, "fav[$i]=${fav.label} -> sent(classic)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "sendFavorites failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Brings the NavMe watchapp to the foreground on the Pebble (autolaunch).
     *
     * Uses the classic `PebbleKit.startAppOnPebble`, the same launch path PebbleNavi uses on Core.
     * (PK2's `startAppOnTheWatch` returns Success but only flips Core's active-app record without
     * actually foregrounding the app on the watch, so it never satisfied the send gate.)
     */
    fun launchWatchApp(context: Context) {
        val appCtx = context.applicationContext
        scope.launch {
            sendMutex.withLock {
                try {
                    PebbleKit.startAppOnPebble(appCtx, NavKeys.WATCH_UUID)
                    Log.i(TAG, "startAppOnPebble(classic) requested")
                } catch (e: Exception) {
                    Log.e(TAG, "startAppOnPebble failed: ${e.message}")
                }
            }
        }
    }
}
