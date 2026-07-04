package com.bquelhas.navme

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Mock Google-Maps navigation generator, for auditing how we READ Maps' maneuver icons.
 *
 * [sendMapsGlyph] takes one of the real Google Maps glyphs bundled in assets (decoded
 * from the Maps APK, white-on-transparent like the notification largeIcon), runs it
 * through the exact on-device path — [IconConverter.pack] then [ManeuverClassifier] —
 * and sends the maneuver WE READ to the watch, while posting a Maps-style notification
 * carrying the authentic glyph. So the phone shows Maps' own icon and the watch shows
 * our decoded arrow: a misread is immediately visible, one maneuver at a time.
 */
object MockNavEmitter {
    private const val TAG = "NavMe/MockNav"
    private const val CHANNEL_ID = "navme_mock"
    private const val NOTIF_ID = 0x6D6F // 'mo'
    /** largeIcon side in px; Maps' turn glyph sits around this size on a phone. */
    private const val GLYPH_PX = 128

    /**
     * Emulates a Google Maps notification using Maps' OWN maneuver glyph and tests how
     * we read it: the real glyph (asset [glyphName]) is packed through [IconConverter]
     * and run through [ManeuverClassifier] — exactly the on-device path — then the
     * CLASSIFIED [Direction] is sent to the watch. The phone notification shows the
     * authentic Maps glyph; the watch shows what we decoded, so a misread is obvious.
     * Returns the classifier result (or null if the asset is missing) so the caller can
     * surface "Maps icon X → read as Y".
     */
    fun sendMapsGlyph(
        context: Context,
        glyphName: String,
        distance: String,
        street: String,
        eta: String?,
    ): ManeuverClassifier.Result? {
        val app = context.applicationContext
        if (!NavPrefs.isDebugTests(app)) {
            Log.i(TAG, "debug tests OFF — ignoring mock glyph '$glyphName'")
            return null
        }
        ensureChannel(app)

        val glyph = MapsGlyphs.load(app, glyphName) ?: run {
            Log.w(TAG, "maps glyph not found: $glyphName"); return null
        }
        val packed = IconConverter.pack(glyph)
        val result = ManeuverClassifier.classify(packed)

        val dist = distance.trim()
        val road = street.trim()
        val arrival = eta?.trim().orEmpty()

        // phone: authentic Maps glyph as largeIcon
        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(dist.ifBlank { "—" })
            .setContentText(road.ifBlank { glyphName })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setColor(Color.parseColor("#1A73E8"))
            .setColorized(true)
            .setLargeIcon(scaleForNotif(glyph))
        if (arrival.isNotEmpty()) builder.setSubText(arrival)
        try {
            NotificationManagerCompatPost(app, builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "notify failed: ${e.message}")
        }

        // watch: the maneuver WE READ from the glyph (not a pre-chosen Direction)
        val composed = NaviParser.compose(dist.ifBlank { null }, road.ifBlank { result.direction.pretty() })
        PebbleEmitter.sendNav(app, NaviData(result.direction, composed, gpsAccuracy = "Good", eta = arrival.ifBlank { null }))

        Log.i(TAG, "maps glyph '$glyphName' -> read=${result.direction} (${result.confidence}" +
            " angle=${result.angle} cov=${"%.3f".format(result.coverage)}) fp=${result.fpHex}")
        return result
    }

    /** Up-scales the 96px asset to a crisp notification largeIcon without blurring. */
    private fun scaleForNotif(src: Bitmap): Bitmap =
        Bitmap.createScaledBitmap(src, GLYPH_PX, GLYPH_PX, true)

    /** Clears the fake notification and tells the watch the route ended. */
    fun cancel(context: Context) {
        val app = context.applicationContext
        try {
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIF_ID)
        } catch (_: Exception) {}
        PebbleEmitter.sendCancel(app)
        Log.i(TAG, "mock nav cancelled")
    }

    private fun NotificationManagerCompatPost(context: Context, notif: Notification) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, notif)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID, "Navigation (mock)", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Mock navigation notification for debugging" }
        mgr.createNotificationChannel(ch)
    }

    private fun Direction.pretty(): String =
        name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}
