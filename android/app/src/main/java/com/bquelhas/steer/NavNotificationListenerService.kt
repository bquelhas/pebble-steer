package com.bquelhas.steer

import android.app.Notification
import android.content.BroadcastReceiver
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.getpebble.android.kit.PebbleKit

/**
 * Listens to ongoing navigation notifications from supported map apps, parses them,
 * and relays a NaviData to the watch. Requires the user to grant notification
 * access (Settings → Notifications → Notification access).
 */
class NavNotificationListenerService : NotificationListenerService() {
    private val TAG = "NavMe/Listener"
    private var lastSent: String? = null
    private var navActive = false

    // Session-end debounce: Google Maps cancels + re-posts its ongoing nav notification mid-route
    // (reroute, or swapping the startup notification for the live one). Treating each removal as a
    // hard session end would wipe the watch-chosen travel mode (reset to CAR, killing the per-mode
    // speedometer) and flap the watch UI. So a removal only *schedules* the end; a nav notification
    // coming back within the grace window cancels it.
    private val endHandler = Handler(Looper.getMainLooper())
    private val endSessionRunnable = Runnable { finalizeSessionEnd() }

    // Inbound handler for watch->phone commands (favorite SELECT -> NAV_TRIGGER_ROUTE).
    private var watchReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        val filter = android.content.IntentFilter("com.getpebble.action.app.RECEIVE")
        val receiver = WatchCommandReceiver()
        watchReceiver = receiver
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            applicationContext.registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            applicationContext.registerReceiver(receiver, filter)
        }
    }

    override fun onDestroy() {
        endHandler.removeCallbacks(endSessionRunnable)
        watchReceiver?.let { try { applicationContext.unregisterReceiver(it) } catch (_: Exception) {} }
        watchReceiver = null
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Best-effort: push the favorites list so the watch SELECT menu is populated.
        PebbleEmitter.sendFavorites(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg !in NaviParser.SUPPORTED) return
        // User can narrow which navigators Steer reads (Customization tab). Default = all supported.
        if (pkg !in NavPrefs.getDetectApps(applicationContext)) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        Log.d(TAG, "notif from $pkg title='$title' text='$text' subText='$subText'")

        var data = NaviParser.parse(pkg, title, text, subText, NavPrefs.getUnitSystem(applicationContext)) ?: return

        // A live nav update arrived: cancel any pending session-end so a Maps notification
        // cancel+repost doesn't tear down the session (and its travel mode) between frames.
        endHandler.removeCallbacks(endSessionRunnable)

        // When the maneuver IS named in the text (OsmAnd/keyword apps), trust NAV_TURN —
        // the watch draws its own PDC arrow. When it's icon-only (Google Maps), classify
        // the notification glyph into a maneuver and send that in NAV_TURN so the watch
        // always draws its own clean PDC arrow. We deliberately no longer forward the raw
        // glyph (key 6): a stray wrong-but-clean arrow beats the chunky raster fallback.
        // The classification is logged so a Lockito calibration pass can tighten it later.
        if (!data.maneuverFromText) {
            val packed = IconConverter.extractManeuverBitmap(applicationContext, extras)
            if (packed != null) {
                // The baked fingerprint table is Google-Maps artwork only. CoMaps / Organic
                // Maps draw their own glyphs, so for them we classify by geometry alone and
                // log the signature so a CoMaps-specific table can be built from a real drive.
                val useTable = pkg == NaviParser.PKG_GOOGLE_MAPS
                val r = ManeuverClassifier.classify(packed, useTable)
                Log.i(TAG, "glyph[$pkg] -> ${r.direction} (${r.confidence} angle=${r.angle}" +
                    " cov=${"%.3f".format(r.coverage)}) fp=${r.fpHex} sig=${r.sigHex}")
                data = data.copy(direction = r.direction)
            }
        }

        // First nav update of a session: optionally bring the watchapp to the front so
        // the user doesn't have to open it by hand when a route starts.
        if (!navActive) {
            navActive = true
            if (NavPrefs.isAutolaunch(applicationContext)) {
                PebbleEmitter.launchWatchApp(applicationContext)
            }
            // Start streaming GPS speed to the watch (speedometer + speed-limit alert).
            // No-ops internally if both features are off or the location permission is denied.
            SpeedProvider.start(applicationContext)
        }

        // De-dup identical consecutive updates (nav notifications refresh often).
        val sig = "${data.directionId}|${data.instructionText}|${data.eta}"
        if (sig == lastSent) return
        lastSent = sig

        PebbleEmitter.sendNav(applicationContext, data, null)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName !in NaviParser.SUPPORTED) return
        // Don't end the session yet — Maps may re-post within moments. Schedule the teardown; a
        // fresh nav update (onNotificationPosted) cancels it. Only a real, sustained removal wins.
        endHandler.removeCallbacks(endSessionRunnable)
        endHandler.postDelayed(endSessionRunnable, SESSION_END_DELAY_MS)
    }

    /** Genuine end of a navigation session (no nav notification came back within the grace window). */
    private fun finalizeSessionEnd() {
        if (!navActive) return
        lastSent = null
        navActive = false
        SpeedProvider.stop(applicationContext)
        // Session over: forget the watch-chosen travel mode so a manual route defaults to CAR.
        NavPrefs.setActiveMode(applicationContext, TravelMode.CAR)
        PebbleEmitter.sendCancel(applicationContext)
        Log.i(TAG, "navigation session ended")
    }

    companion object {
        // Grace window covering Google Maps' cancel+repost churn before a removal counts as an end.
        private const val SESSION_END_DELAY_MS = 4000L
    }
}
