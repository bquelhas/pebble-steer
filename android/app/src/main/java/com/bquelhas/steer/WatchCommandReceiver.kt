package com.bquelhas.steer

import android.content.Context
import com.getpebble.android.kit.PebbleKit
import com.getpebble.android.kit.util.PebbleDictionary

/**
 * Receives messages sent *from* the Steer watchapp over classic PebbleKit — the original Pebble
 * app, and Core running a watchapp build without `companionApp`. The PebbleKit 2 twin is
 * [SteerPebbleListenerService]; both hand the message to [WatchCommands].
 *
 * Registered dynamically (see [NavNotificationListenerService]); ack every transaction so the
 * watch's AppMessage outbox drains.
 */
class WatchCommandReceiver : PebbleKit.PebbleDataReceiver(NavKeys.WATCH_UUID) {

    override fun receiveData(context: Context, transactionId: Int, data: PebbleDictionary) {
        // Always ack first so the watch isn't left waiting.
        PebbleKit.sendAckToPebble(context, transactionId)
        WatchCommands.handle(context, "classic") { key -> data.getUnsignedIntegerAsLong(key) }
    }
}
