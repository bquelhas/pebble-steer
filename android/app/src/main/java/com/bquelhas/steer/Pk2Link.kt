package com.bquelhas.steer

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.rebble.pebblekit2.PebbleKitProviderContract
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The PebbleKit 2 half of the watch link; [PebbleEmitter] falls back to classic PebbleKit
 * whenever this doesn't deliver.
 *
 * Why two transports: Core (and the other libpebble3-based Pebble apps) picks the protocol PER
 * WATCHAPP from the installed .pbw. A watchapp whose package.json lists an Android package under
 * `companionApp` is served over PebbleKit 2 only; any other watchapp over classic only. Steer
 * watchapp builds that declare `companionApp` therefore need PK2, older builds and the original
 * Pebble app need classic, and PK2-only Pebble apps (e.g. Gravel) refuse classic altogether.
 *
 * Session state comes from [SteerPebbleListenerService]: Core binds it and calls onAppOpened /
 * onAppClosed around each run of the watchapp. A PK2 send while Steer has no PK2 session fails
 * fast (FailedDifferentAppOpen, no watch round trip), so trying PK2 first costs one quick IPC
 * even when classic ends up carrying the message.
 */
object Pk2Link {
    private const val TAG = "NavMe/PK2"

    /**
     * Cap on one PK2 call. Core waits up to 10 s for the watch's ACK; the send queue shouldn't
     * stall that long (a slow ACK still lands — we only stop waiting for it). The library's own
     * binding wait is also 10 s, and a binding that never connects would otherwise cost that on
     * every message.
     */
    private const val CALL_TIMEOUT_MS = 4000L

    /**
     * Runs the PK2 calls themselves, apart from the caller: in library 1.2.0 a request ignores
     * cancellation and never returns if the Pebble app dies after receiving it. Waiting on a
     * Deferred instead lets the caller give up after [CALL_TIMEOUT_MS] without the send queue
     * hanging on it.
     */
    private val callScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** After PK2 proved unreachable, skip it this long (unless a session opens) so every frame
     *  doesn't pay for the failed attempt. */
    private const val BACKOFF_MS = 60_000L

    /** How often to re-check which Pebble app to target when more than one is installed. */
    private const val TARGET_CHECK_MS = 30_000L

    enum class Outcome {
        /** Delivered (send) / launched (start) on at least one watch. */
        DELIVERED,
        /** A Pebble app answered but failed on every watch — typically FailedDifferentAppOpen
         *  (Steer isn't running with a PK2 session) or no watch connected. */
        FAILED,
        /** No PK2 answer: no PK2-capable Pebble app installed, binding failed, or backing off. */
        UNAVAILABLE,
        /** No verdict within [CALL_TIMEOUT_MS]. */
        TIMEOUT,
    }

    /** Whether Steer currently runs on the watch with a PK2 session (see [SteerPebbleListenerService]). */
    @Volatile var sessionOpen = false
        private set

    @Volatile private var sender: DefaultPebbleSender? = null
    @Volatile private var backoffUntil = 0L
    @Volatile private var lastTargetCheck = 0L

    fun onSessionOpened(watch: WatchIdentifier) {
        sessionOpen = true
        backoffUntil = 0L
        Log.i(TAG, "session opened on ${watch.value}")
    }

    fun onSessionClosed(watch: WatchIdentifier) {
        sessionOpen = false
        Log.i(TAG, "session closed on ${watch.value}")
    }

    suspend fun send(context: Context, data: Map<UInt, PebbleDictionaryItem>): Outcome =
        call(context, "send") { it.sendDataToPebble(NavKeys.WATCH_UUID, data) }

    suspend fun startApp(context: Context): Outcome =
        call(context, "startApp") { it.startAppOnTheWatch(NavKeys.WATCH_UUID) }

    /** Whether any PK2-capable Pebble app reports a connected watch. */
    fun isWatchConnected(context: Context): Boolean = try {
        DefaultPebbleAndroidAppPicker.getInstance(context).getAllEligibleApps()
            .any { hasConnectedWatch(context, it) }
    } catch (e: Exception) {
        false
    }

    /** Wraps a completed call's results (null = the library couldn't reach a Pebble app). */
    private class Reply(val results: Map<WatchIdentifier, TransmissionResult>?)

    private suspend fun call(
        context: Context,
        what: String,
        block: suspend (DefaultPebbleSender) -> Map<WatchIdentifier, TransmissionResult>?,
    ): Outcome {
        val appCtx = context.applicationContext
        if (!sessionOpen && SystemClock.elapsedRealtime() < backoffUntil) return Outcome.UNAVAILABLE
        ensureTarget(appCtx)
        val s = sender ?: synchronized(this) {
            sender ?: DefaultPebbleSender(appCtx).also { sender = it }
        }

        val pending = callScope.async { Reply(block(s)) }
        val reply = try {
            withTimeoutOrNull(CALL_TIMEOUT_MS) { pending.await() }
        } catch (e: Exception) {
            Log.w(TAG, "$what threw: ${e.message}")
            Reply(null)
        }
        if (reply == null) pending.cancel()
        val results = reply?.results
        val outcome = when {
            reply == null -> Outcome.TIMEOUT
            results == null -> Outcome.UNAVAILABLE
            results.values.any { it == TransmissionResult.Success } -> Outcome.DELIVERED
            else -> Outcome.FAILED
        }
        // Unreachable, or silent while no session is known: back off and drop the binding so the
        // next attempt starts a fresh one. A timeout DURING a session is just a slow watch.
        if (outcome == Outcome.UNAVAILABLE || (outcome == Outcome.TIMEOUT && !sessionOpen)) {
            backoffUntil = SystemClock.elapsedRealtime() + BACKOFF_MS
            resetSender()
        }
        if (outcome != Outcome.DELIVERED) Log.d(TAG, "$what -> $outcome ${results ?: ""}")
        return outcome
    }

    private fun resetSender() {
        val old = synchronized(this) { sender.also { sender = null } } ?: return
        try {
            old.close()
        } catch (_: Exception) {
            // Never bound (bindService refused): nothing to unbind.
        }
    }

    /**
     * With more than one PK2-capable Pebble app installed (e.g. Core AND Gravel), the library's
     * auto-select simply takes the first one — possibly not the one the watch is paired with, and
     * then PK2 goes nowhere in either direction. Point it at the app that reports a connected
     * watch. No-op with zero or one such app, the common case.
     */
    private suspend fun ensureTarget(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTargetCheck < TARGET_CHECK_MS) return
        lastTargetCheck = now
        try {
            val picker = DefaultPebbleAndroidAppPicker.getInstance(context)
            val apps = picker.getAllEligibleApps()
            if (apps.size < 2) return
            val current = picker.getCurrentlySelectedApp()
            val withWatch = apps.filter { hasConnectedWatch(context, it) }
            if (withWatch.isEmpty() || current in withWatch) return
            picker.selectApp(withWatch.first())
            resetSender()
            Log.i(TAG, "target Pebble app $current -> ${withWatch.first()} (the one with the watch)")
        } catch (e: Exception) {
            Log.w(TAG, "target check failed: ${e.message}")
        }
    }

    private fun hasConnectedWatch(context: Context, pkg: String): Boolean = try {
        context.contentResolver.query(
            PebbleKitProviderContract.ConnectedWatch.getContentUri(pkg),
            arrayOf(PebbleKitProviderContract.ConnectedWatch.ID), null, null, null,
        )?.use { it.count > 0 } ?: false
    } catch (e: Exception) {
        false
    }
}
