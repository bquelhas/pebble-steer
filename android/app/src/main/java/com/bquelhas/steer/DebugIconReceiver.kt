package com.bquelhas.steer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * adb-driven helper to show ONE specific maneuver icon on the watch, for testing
 * the PDC set one at a time (instead of the random [DebugCycler]).
 *
 *   adb shell am broadcast -a com.bquelhas.steer.SHOW_ICON --ei id 18
 *   adb shell am broadcast -a com.bquelhas.steer.SHOW_ICON --es name ROUNDABOUT_6_LEFT
 *
 * Either `id` (Direction.id, 0..40) or `name` (Direction enum name) selects the
 * maneuver; `name` wins when both are given. Sends a fixed test instruction so the
 * watch draws the chosen NAV_TURN icon directly (no Maps glyph forwarding).
 *
 * Special: `--es name __LAUNCH__` fires [PebbleEmitter.launchWatchApp] (the PebbleKit2
 * autolaunch path) so the watchapp can be opened from adb to test autolaunch in isolation.
 */
class DebugIconReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (!NavPrefs.isDebugTests(context.applicationContext)) {
            Log.i(TAG, "debug tests OFF — ignoring $ACTION")
            return
        }
        // Showing a specific icon means we want manual control: stop the random
        // cycler so it doesn't overwrite the chosen maneuver every few seconds.
        if (DebugCycler.isRunning()) DebugCycler.stop(context.applicationContext)

        // Autolaunch test hook: open the watchapp via PebbleKit2 without a real nav session.
        if (intent.getStringExtra("name")?.trim()?.uppercase() == "__LAUNCH__") {
            Log.i(TAG, "DIAG: launchWatchApp() via PebbleKit2")
            PebbleEmitter.launchWatchApp(context.applicationContext)
            return
        }

        val dir = resolveDirection(intent) ?: run {
            Log.w(TAG, "no valid id/name extra in $ACTION")
            return
        }
        val pretty = dir.name.lowercase().replace('_', ' ')
            .replaceFirstChar { it.uppercase() }
        val data = NaviData(dir, "200 m — $pretty • Teste", gpsAccuracy = "Good", eta = "12:00")
        PebbleEmitter.sendNav(context.applicationContext, data)
        Log.i(TAG, "show icon id=${dir.id} (${dir.name})")
    }

    private fun resolveDirection(intent: Intent): Direction? {
        val name = intent.getStringExtra("name")?.trim()?.uppercase()
        if (!name.isNullOrEmpty()) {
            return Direction.entries.firstOrNull { it.name == name }
        }
        if (intent.hasExtra("id")) {
            val id = intent.getIntExtra("id", -1)
            return Direction.entries.firstOrNull { it.id == id }
        }
        return null
    }

    companion object {
        private const val TAG = "NavMe/DebugIcon"
        const val ACTION = "com.bquelhas.steer.SHOW_ICON"
    }
}
