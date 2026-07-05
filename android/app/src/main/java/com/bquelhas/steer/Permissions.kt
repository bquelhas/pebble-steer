package com.bquelhas.steer

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * Central checks for the runtime access Steer needs. The one hard requirement is
 * notification-listener access — without it the app can't read the map's turn-by-turn
 * notification and nothing reaches the watch. Location is optional (only the speed features).
 */
object Permissions {

    /**
     * True when the user has enabled Steer's [NavNotificationListenerService] under
     * Settings → Notification access. Read from the system's flat list of enabled
     * listeners and matched by exact ComponentName (not just package) to be precise.
     */
    fun isNotificationAccessGranted(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        val self = ComponentName(context, NavNotificationListenerService::class.java)
        return flat.split(":").any { entry ->
            ComponentName.unflattenFromString(entry) == self
        }
    }

    /**
     * True when the user granted "display over other apps" (SYSTEM_ALERT_WINDOW). Steer uses it
     * only for the BAL exemption that lets a favorite picked on the watch open the navigator
     * directly from the background (see [NavLauncher.launchForWatch]); without it that launch
     * degrades to a tap-to-launch notification.
     */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)
}
