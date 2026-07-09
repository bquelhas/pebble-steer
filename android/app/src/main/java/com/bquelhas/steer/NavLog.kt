package com.bquelhas.steer

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small in-memory ring buffer of navigation-diagnostic lines, so a user without adb can
 * capture what Steer read from the map notifications and share it from the Test tab.
 *
 * [NavNotificationListenerService] appends one line per nav update (raw notification text
 * plus the direction / distance / ETA / vibration decision we derived). Holds the most
 * recent [MAX_LINES]; process-lived only (cleared on app restart), which is plenty to
 * capture "it just did the wrong thing on this turn".
 */
object NavLog {
    private const val MAX_LINES = 300
    private val lines = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(line: String) {
        lines.addLast("${stamp.format(Date())}  $line")
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    /** The captured lines as one shareable blob (oldest first). The caller adds a header. */
    @Synchronized
    fun dump(): String =
        if (lines.isEmpty()) "(no navigation captured yet — start a route first)"
        else lines.joinToString("\n")

    @Synchronized
    fun isEmpty(): Boolean = lines.isEmpty()
}
