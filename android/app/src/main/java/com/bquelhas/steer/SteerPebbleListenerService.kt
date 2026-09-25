package com.bquelhas.steer

import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * PebbleKit 2 endpoint for the Steer watchapp (the classic twin is [WatchCommandReceiver]).
 *
 * Core — or another PK2 Pebble app — binds this service while the watchapp runs, which also keeps
 * this process alive for the drive, and calls [onAppOpened] / [onAppClosed] around each run.
 * Messages from the watch arrive in [onMessageReceived] and go through [WatchCommands], the same
 * as on the classic path. Only watchapp builds that declare `companionApp` get a PK2 session.
 */
class SteerPebbleListenerService : BasePebbleListenerService() {

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: Map<UInt, PebbleDictionaryItem>,
        watch: WatchIdentifier,
    ): ReceiveResult {
        if (watchappUUID != NavKeys.WATCH_UUID) return ReceiveResult.Nack
        // A message proves the session is live even if onAppOpened was missed (e.g. this
        // process was restarted while the watchapp stayed open).
        if (!Pk2Link.sessionOpen) Pk2Link.onSessionOpened(watch)
        WatchCommands.handle(applicationContext, "pk2") { key -> data[key.toUInt()]?.asLong() }
        // Ack everything, as the classic receiver does, so the watch's outbox drains.
        return ReceiveResult.Ack
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID != NavKeys.WATCH_UUID) return
        Pk2Link.onSessionOpened(watch)
        NavLog.add("watch: Steer opened (PebbleKit 2 session)")
        // The watchapp asks for its data (NAV_REQUEST_FAVS) as soon as it starts — possibly
        // before Core has finished binding this service, and then the request is lost. Resync
        // once shortly after the session opens, unless that request was served meanwhile.
        coroutineScope.launch {
            delay(OPEN_RESYNC_DELAY_MS)
            if (Pk2Link.sessionOpen && WatchCommands.msSinceResync() > OPEN_RESYNC_DELAY_MS + 1000) {
                WatchCommands.resync(applicationContext, "PK2 session opened")
            }
        }
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID != NavKeys.WATCH_UUID) return
        Pk2Link.onSessionClosed(watch)
        NavLog.add("watch: Steer closed (PebbleKit 2 session)")
    }

    companion object {
        /** Lets the watchapp finish starting (open its AppMessage inbox) before the fallback push. */
        private const val OPEN_RESYNC_DELAY_MS = 800L
    }
}
