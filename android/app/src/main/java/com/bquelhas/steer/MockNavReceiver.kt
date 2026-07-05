package com.bquelhas.steer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * adb-driven front-end for [MockNavEmitter.sendMapsGlyph], so Claude (or any tester)
 * can emulate a Google Maps notification with Maps' OWN maneuver glyph and check how
 * the classifier reads it — one icon at a time, headless.
 *
 * NOTE: target the receiver explicitly with `-n` — One UI (Samsung) drops implicit
 * background broadcasts to manifest receivers, so a bare `-a` action never arrives.
 *
 *   # emulate one Maps glyph (asset name, e.g. turn_normal_left, roundabout_exit_cw):
 *   adb shell am broadcast -n com.bquelhas.steer/.MockNavReceiver \
 *       -a com.bquelhas.steer.MOCK_NAV \
 *       --es glyph turn_normal_left --es dist "300 m" --es street "R. da Paz" --es eta "20:09"
 *
 *   # run through EVERY bundled Maps glyph (logs read result per glyph):
 *   adb shell am broadcast -n com.bquelhas.steer/.MockNavReceiver \
 *       -a com.bquelhas.steer.MOCK_NAV --ez all true
 *
 *   # end the route (clears notification + cancels on watch):
 *   adb shell am broadcast -n com.bquelhas.steer/.MockNavReceiver \
 *       -a com.bquelhas.steer.MOCK_NAV_CANCEL
 *
 * The classifier verdict for each glyph is logged under tag NavMe/MockNav.
 */
class MockNavReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            // Always allowed — turning the route off must work even with debug OFF.
            ACTION_CANCEL -> MockNavEmitter.cancel(app)
            // Flip the master debug switch headless, so adb audits stay possible:
            //   adb shell am broadcast -n com.bquelhas.steer/.MockNavReceiver \
            //       -a com.bquelhas.steer.SET_DEBUG --ez on true
            ACTION_SET_DEBUG -> {
                val on = intent.getBooleanExtra("on", false)
                NavPrefs.setDebugTests(app, on)
                if (!on) MockNavEmitter.cancel(app)
                Log.i(TAG, "debug tests set to $on (adb)")
            }
            ACTION -> {
                if (!NavPrefs.isDebugTests(app)) {
                    Log.w(TAG, "debug tests OFF — ignoring $ACTION " +
                        "(enable: SET_DEBUG --ez on true)")
                    return
                }
                val dist = intent.getStringExtra("dist") ?: "300 m"
                val street = intent.getStringExtra("street").orEmpty()
                val eta = intent.getStringExtra("eta")

                if (intent.getBooleanExtra("all", false)) {
                    auditAll(app)
                    return
                }
                val glyph = intent.getStringExtra("glyph")?.trim()
                if (glyph.isNullOrEmpty()) {
                    Log.w(TAG, "no 'glyph' extra (or --ez all true) in $ACTION")
                    return
                }
                MockNavEmitter.sendMapsGlyph(app, glyph, dist, street, eta)
            }
        }
    }

    /**
     * Classifies every bundled Maps glyph in sequence and scores each read against the
     * ground-truth label ([MapsGlyphs.expected]). Logs `OK`/`FAIL exp=…` per glyph and a
     * final accuracy summary, so a build can be judged headless from `adb logcat`.
     */
    private fun auditAll(context: Context) {
        val names = MapsGlyphs.names(context)
        Log.i(TAG, "audit: ${names.size} Maps glyphs")
        var scored = 0; var ok = 0
        for (name in names) {
            val r = MockNavEmitter.sendMapsGlyph(context, name, "300 m", "", null)
            val read = r?.direction
            val exp = MapsGlyphs.expected(name)
            // CAL line drives the fingerprint-table generator (/tmp/gen_fp_table.py).
            if (exp != null && r != null) Log.i(TAG, "CAL ${name} ${exp.name} ${r.sigHex}")
            if (exp == null) {
                Log.i(TAG, "audit ${name} -> ${read} (${r?.confidence}) [no label]")
            } else {
                scored++
                val hit = read == exp
                if (hit) ok++
                Log.i(TAG, "audit ${name} -> ${read} (${r?.confidence} d=${r?.matchDist}) ${if (hit) "OK" else "FAIL exp=$exp"}")
            }
        }
        Log.i(TAG, "audit DONE: $ok/$scored correct (${if (scored > 0) ok * 100 / scored else 0}%)")
    }

    companion object {
        private const val TAG = "NavMe/MockNav"
        const val ACTION = "com.bquelhas.steer.MOCK_NAV"
        const val ACTION_CANCEL = "com.bquelhas.steer.MOCK_NAV_CANCEL"
        const val ACTION_SET_DEBUG = "com.bquelhas.steer.SET_DEBUG"
    }
}
