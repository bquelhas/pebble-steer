package com.bquelhas.steer

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.getpebble.android.kit.PebbleKit
import com.getpebble.android.kit.util.PebbleDictionary

/**
 * Receives messages sent *from* the NavMe watchapp. Currently the only inbound command is
 * [NavKeys.NAV_TRIGGER_ROUTE]: when nav is idle the watch SELECT button picks a favorite and
 * sends back its index, and the phone starts navigation to that destination.
 *
 * Registered dynamically (see [NavNotificationListenerService]); ack every transaction so the
 * watch's AppMessage outbox drains.
 */
class WatchCommandReceiver : PebbleKit.PebbleDataReceiver(NavKeys.WATCH_UUID) {

    override fun receiveData(context: Context, transactionId: Int, data: PebbleDictionary) {
        // Always ack first so the watch isn't left waiting.
        PebbleKit.sendAckToPebble(context, transactionId)

        // Launch-time sync: the watchapp only keeps favorites in RAM, so a fresh launch shows an
        // empty menu until the phone re-pushes. When the watch asks (NAV_REQUEST_FAVS) we reply with
        // a full favorites burst. Handled here because this receiver is alive whenever the
        // notification listener is (i.e. essentially always), no foreground app needed.
        if (data.getUnsignedIntegerAsLong(NavKeys.NAV_REQUEST_FAVS) != null) {
            Log.i(TAG, "watch requested favorites -> resyncing")
            PebbleEmitter.sendFavorites(context)
            return
        }

        val idx = data.getUnsignedIntegerAsLong(NavKeys.NAV_TRIGGER_ROUTE)?.toInt() ?: return
        // Debounce: the watch may resend on a flaky link; ignore repeats of the same pick.
        val now = SystemClock.elapsedRealtime()
        if (idx == lastIndex && now - lastAt < DEBOUNCE_MS) return
        lastIndex = idx; lastAt = now

        // Travel mode chosen on the watch (0 car by default if an older watchapp omits it).
        val mode = TravelMode.fromId(
            data.getUnsignedIntegerAsLong(NavKeys.NAV_ROUTE_MODE)?.toInt() ?: 0
        )
        // Remember it for the session so SpeedProvider can gate the speedometer per mode.
        NavPrefs.setActiveMode(context, mode)

        val favs = FavoritesStore.all(context)
        val fav = favs.getOrNull(idx)
        if (fav == null) {
            Log.w(TAG, "trigger for unknown favorite index $idx (have ${favs.size})")
            return
        }
        Log.i(TAG, "watch triggered favorite #$idx '${fav.label}' (${mode.name}) -> ${fav.query}")
        launchFavorite(context, fav, mode)
    }

    private fun launchFavorite(context: Context, fav: Favorite, mode: TravelMode) {
        // Started from a background context (the watch press). Android's BAL policy blocks a plain
        // background startActivity, so launchForWatch honors the preferred navigator, uses the
        // overlay (SYSTEM_ALERT_WINDOW) BAL exemption when granted, and otherwise falls back to a
        // tap-to-launch notification.
        NavLauncher.launchForWatch(context, fav.label, fav.query, mode)
    }

    companion object {
        private const val TAG = "NavMe/WatchCmd"
        private const val DEBOUNCE_MS = 3000L
        private var lastIndex = -1
        private var lastAt = 0L
    }
}
