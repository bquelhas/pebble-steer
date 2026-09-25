package com.bquelhas.steer

import android.content.Context
import android.util.Log
import com.getpebble.android.kit.PebbleKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Sends everything the phone pushes to the Steer watchapp: nav frames, settings, favorites and
 * the autolaunch.
 *
 * Every message tries two transports in order:
 *  1. **PebbleKit 2** ([Pk2Link]) — required when the installed watchapp declares `companionApp`
 *     in its package.json: Core then serves it over PK2 only, and PK2-only Pebble apps (e.g.
 *     Gravel) don't accept classic PebbleKit at all.
 *  2. **Classic PebbleKit** (the `com.getpebble.action.app.SEND` broadcast) whenever PK2 didn't
 *     deliver — the original Pebble app, and Core running a watchapp build without
 *     `companionApp`, which it serves over classic only.
 * Core runs each watchapp session over exactly one of the two, and only registers its classic
 * SEND receiver for a classic session, so the fallback never delivers a message twice.
 *
 * History: in June 2026 PK2 looked unusable — every send came back FailedDifferentAppOpen, and
 * data moved to classic only. The cause was the watchapp's package.json lacking `companionApp`,
 * so Core never opened a PK2 session for Steer (Core source: CompanionAppLifecycleManager.android.kt,
 * PebbleKit2.kt, PebbleSenderReceiver.kt).
 *
 * Sends are serialized through a [Mutex] off the caller's thread so frames go out in order. A PK2
 * send waits for the watch's ACK, so newer nav frames can queue behind one in flight; a queued
 * frame that a newer one already superseded is dropped instead of being shown late.
 */
object PebbleEmitter {
    private const val TAG = "NavMe/Emitter"

    /**
     * Gap between the consecutive AppMessages of a favorites sync sent over CLASSIC PebbleKit,
     * whose `sendDataToPebble` is fire-and-forget (no ACK wait): the Core bridge / watch inbox
     * silently DROPS messages fired back-to-back — which is why favorites defined on the phone
     * never showed up on the watch. Pacing the burst lets each message land before the next.
     * A PK2 send already waits for the watch's ACK, so it needs no extra gap.
     */
    private const val FAV_SEND_GAP_MS = 250L

    private enum class Transport { PK2, CLASSIC }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sendMutex = Mutex()

    /** Bumped per nav frame; a queued frame holding an older number has been superseded. */
    private val navFrameSeq = AtomicLong()

    /** Transport of the previous message, to note in [NavLog] only when it changes. */
    @Volatile private var lastTransport: Transport? = null

    /**
     * Whether a Pebble watch is connected: the classic provider (the original Pebble app, and
     * Core, which re-exposes it), else any PK2-capable Pebble app that reports a connected watch.
     */
    fun isWatchConnected(context: Context): Boolean =
        (try { PebbleKit.isWatchConnected(context) } catch (e: Exception) { false }) ||
            Pk2Link.isWatchConnected(context)

    /** Delivers one message — PK2 first, classic when PK2 didn't deliver. Call under [sendMutex]. */
    private suspend fun deliver(appCtx: Context, label: String, msg: WatchMessage): Transport {
        val pk2 = Pk2Link.send(appCtx, msg.toPk2())
        val transport = if (pk2 == Pk2Link.Outcome.DELIVERED) {
            Transport.PK2
        } else {
            PebbleKit.sendDataToPebble(appCtx, NavKeys.WATCH_UUID, msg.toClassic())
            Transport.CLASSIC
        }
        Log.i(TAG, "$label -> sent(${transport.name.lowercase()})" +
            (if (transport == Transport.CLASSIC) " pk2=$pk2" else ""))
        if (transport != lastTransport) {
            lastTransport = transport
            NavLog.add("link: sending to the watch via " +
                (if (transport == Transport.PK2) "PebbleKit 2" else "classic PebbleKit (PebbleKit 2: $pk2)"))
        }
        return transport
    }

    /** Serializes one send, off the caller's thread. [isStale] lets a superseded message bow out. */
    private fun send(
        context: Context,
        label: String,
        isStale: () -> Boolean = { false },
        build: (WatchMessage) -> Unit,
    ) {
        val appCtx = context.applicationContext
        scope.launch {
            sendMutex.withLock {
                if (isStale()) {
                    Log.d(TAG, "$label -> skipped (superseded by a newer frame)")
                    return@withLock
                }
                try {
                    val msg = WatchMessage()
                    build(msg)
                    deliver(appCtx, label, msg)
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
        // Each frame carries the complete display state, so the newest one makes older queued
        // frames redundant.
        val seq = navFrameSeq.incrementAndGet()
        send(context, label, isStale = { navFrameSeq.get() != seq }) { dict ->
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
            // Settings sync: large-text (accessibility) mode for the street/ETA text.
            dict.addUint8(NavKeys.NAV_TEXT_SIZE, (if (NavPrefs.isLargeWatchText(context)) 1 else 0).toByte())
        }
    }

    /** Pushes the large-text setting on its own (right after the user toggles it). */
    fun sendTextSize(context: Context) {
        send(context, "textSize") {
            it.addUint8(NavKeys.NAV_TEXT_SIZE, (if (NavPrefs.isLargeWatchText(context)) 1 else 0).toByte())
        }
    }

    fun sendCancel(context: Context) {
        send(context, "cancel") { it.addUint8(NavKeys.NAV_CANCEL, 1.toByte()) }
    }

    /**
     * Fires the smart "get ready" buzz on the watch (NAV_VIBE_NOW). [VibePlanner] decides
     * the moment — once per maneuver, at a lead distance adapted to the current speed.
     */
    fun sendVibeNow(context: Context) {
        send(context, "vibeNow") { it.addUint8(NavKeys.NAV_VIBE_NOW, 1.toByte()) }
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
                    var via = deliver(appCtx, "favCount=${favs.size}", WatchMessage().apply {
                        addUint8(NavKeys.NAV_FAV_COUNT, favs.size.toByte())
                    })
                    favs.forEachIndexed { i, fav ->
                        if (via == Transport.CLASSIC) delay(FAV_SEND_GAP_MS)
                        via = deliver(appCtx, "fav[$i]=${fav.label}", WatchMessage().apply {
                            addUint8(NavKeys.NAV_FAV_INDEX, i.toByte())
                            addString(NavKeys.NAV_FAV_NAME, fav.label.take(32))
                            addUint8(NavKeys.NAV_FAV_ICON, fav.icon.coerceIn(0, 255).toByte())
                        })
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "sendFavorites failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Brings the Steer watchapp to the foreground on the Pebble (autolaunch).
     *
     * PK2's startAppOnTheWatch and the classic START broadcast end in the same Core call
     * (launchApp: tell the watch to start the app, then wait until it reports it running), so
     * PK2 is tried first for its verdict and classic covers Pebble apps without PK2. On a PK2
     * TIMEOUT the launch is already under way — firing classic as well would launch it twice.
     */
    fun launchWatchApp(context: Context) {
        val appCtx = context.applicationContext
        scope.launch {
            sendMutex.withLock {
                try {
                    when (val pk2 = Pk2Link.startApp(appCtx)) {
                        Pk2Link.Outcome.DELIVERED -> Log.i(TAG, "startAppOnTheWatch(pk2) -> running")
                        Pk2Link.Outcome.TIMEOUT -> Log.w(TAG, "startAppOnTheWatch(pk2) -> no verdict yet")
                        else -> {
                            PebbleKit.startAppOnPebble(appCtx, NavKeys.WATCH_UUID)
                            Log.i(TAG, "startAppOnPebble(classic) requested (pk2=$pk2)")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "launchWatchApp failed: ${e.message}")
                }
            }
        }
    }
}
