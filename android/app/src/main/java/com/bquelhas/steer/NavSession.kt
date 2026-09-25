package com.bquelhas.steer

/**
 * Process-wide snapshot of the live navigation session. Written only by
 * [NavNotificationListenerService]; read by components that run beside it — namely
 * [WatchCommands.resync], which replays [lastData] when the watchapp (re)launches
 * mid-route so the watch shows the current maneuver immediately instead of sitting
 * on "Waiting for signal..." until the next notification update.
 */
object NavSession {
    @Volatile var active = false
        private set

    @Volatile var lastData: NaviData? = null
        private set

    /** Marks the session live and stores the newest frame. */
    fun update(data: NaviData) {
        active = true
        lastData = data
    }

    /** Session over: nothing to replay any more. */
    fun clear() {
        active = false
        lastData = null
    }
}
