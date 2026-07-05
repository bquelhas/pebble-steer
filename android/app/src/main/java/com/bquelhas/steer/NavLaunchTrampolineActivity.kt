package com.bquelhas.steer

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager

/**
 * Keyguard trampoline for watch-triggered navigation.
 *
 * When a favorite is picked on the watch while the phone is LOCKED, the overlay
 * (SYSTEM_ALERT_WINDOW) BAL exemption lets us start an activity, but Android still won't put a
 * third-party navigator on top of a secure lock screen — the launch just queues behind the
 * keyguard until the user unlocks manually, so "nothing happens" until then.
 *
 * This tiny, invisible activity fixes the UX: it declares show-when-locked + turn-screen-on, so it
 * comes up over the lock screen and wakes the display, then asks the platform to dismiss the
 * keyguard. On a non-secure lock (swipe) that dismisses instantly; on a secure lock (PIN/biometric)
 * the user is prompted right away. Once the keyguard is gone we launch the navigator and finish.
 *
 * On an already-unlocked phone [NavLauncher] launches the navigator directly and never routes here.
 */
class NavLaunchTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Appear over the lock screen and turn the display on.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        val navIntent = extractNavIntent(intent)
        if (navIntent == null) {
            Log.w(TAG, "no nav intent supplied; nothing to launch")
            finish()
            return
        }

        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km != null && km.isKeyguardLocked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.i(TAG, "keyguard locked; requesting dismiss before launch")
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = launchAndFinish(navIntent)
                override fun onDismissCancelled() {
                    Log.i(TAG, "keyguard dismiss cancelled; aborting launch")
                    finish()
                }
                // If the platform can't run the dismiss flow, still try to launch.
                override fun onDismissError() = launchAndFinish(navIntent)
            })
        } else {
            launchAndFinish(navIntent)
        }
    }

    private fun launchAndFinish(navIntent: Intent) {
        try {
            navIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(navIntent)
            Log.i(TAG, "navigator launched after keyguard handling")
        } catch (e: Exception) {
            Log.e(TAG, "launch after keyguard failed: ${e.message}")
        }
        finish()
    }

    @Suppress("DEPRECATION")
    private fun extractNavIntent(host: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            host.getParcelableExtra(EXTRA_NAV_INTENT, Intent::class.java)
        } else {
            host.getParcelableExtra(EXTRA_NAV_INTENT)
        }

    companion object {
        private const val TAG = "NavMe/Trampoline"
        private const val EXTRA_NAV_INTENT = "com.bquelhas.steer.extra.NAV_INTENT"

        /** Wraps [navIntent] into a trampoline-launch intent startable from a background context. */
        fun intentFor(context: Context, navIntent: Intent): Intent =
            Intent(context, NavLaunchTrampolineActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_NAV_INTENT, navIntent)
    }
}
