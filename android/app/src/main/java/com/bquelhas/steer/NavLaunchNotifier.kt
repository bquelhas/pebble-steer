package com.bquelhas.steer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Fallback for launching a watch-triggered navigation when Steer can't start the activity
 * directly (no "display over other apps" permission, so Android's BAL policy blocks the
 * background `startActivity`). Posts a high-priority, tap-to-launch notification carrying the
 * navigator intent; a full-screen intent is attached so it opens straight away on a locked
 * screen where the platform allows it. Granting the overlay permission avoids this hop.
 */
object NavLaunchNotifier {
    private const val CHANNEL_ID = "steer_nav_launch"
    private const val NOTIF_ID = 4711

    fun post(context: Context, label: String, navIntent: Intent) {
        ensureChannel(context)

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getActivity(context, NOTIF_ID, navIntent, flags)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(context.getString(R.string.nav_launch_title, label))
            .setContentText(context.getString(R.string.nav_launch_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .build()

        // notify() no-ops (doesn't throw) if POST_NOTIFICATIONS isn't granted — acceptable
        // graceful degradation for a fallback path.
        NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.nav_launch_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }
    }
}
