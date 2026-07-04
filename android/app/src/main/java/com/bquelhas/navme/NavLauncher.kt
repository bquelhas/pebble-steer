package com.bquelhas.navme

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * Starts turn-by-turn navigation to a destination in one of the supported
 * navigator apps. Directions always start from the current location.
 *
 * The [query] may be a free-text address ("Rua da Paz, Lisboa") or a
 * "lat,lng" pair. Google Maps and Waze both accept a text query; OsmAnd needs
 * coordinates, so it is only offered when the query looks like "lat,lng".
 */
object NavLauncher {

    private const val TAG = "NavMe/Launcher"
    private const val PKG_MAPS = "com.google.android.apps.maps"
    private const val PKG_WAZE = "com.waze"
    private const val PKG_OSMAND = "net.osmand.plus"
    private const val PKG_OSMAND_FREE = "net.osmand"

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    private val LATLNG = Regex("""^\s*-?\d+(\.\d+)?\s*,\s*-?\d+(\.\d+)?\s*$""")

    /** Builds the list of navigator intents available for [query] on this device. */
    fun intentsFor(context: Context, query: String): List<Pair<String, Intent>> {
        val out = mutableListOf<Pair<String, Intent>>()
        val isCoords = LATLNG.matches(query)
        val encoded = Uri.encode(query.trim())

        if (isInstalled(context, PKG_MAPS)) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encoded"))
                .setPackage(PKG_MAPS)
            out += "Google Maps" to i
        }
        if (isInstalled(context, PKG_WAZE)) {
            val uri = if (isCoords) "https://waze.com/ul?ll=$encoded&navigate=yes"
                      else "https://waze.com/ul?q=$encoded&navigate=yes"
            out += "Waze" to Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(PKG_WAZE)
        }
        if (isCoords) {
            val coords = query.trim()
            val osmPkg = when {
                isInstalled(context, PKG_OSMAND) -> PKG_OSMAND
                isInstalled(context, PKG_OSMAND_FREE) -> PKG_OSMAND_FREE
                else -> null
            }
            if (osmPkg != null) {
                val (lat, lng) = coords.split(",").map { it.trim() }
                val i = Intent(Intent.ACTION_VIEW,
                    Uri.parse("google.navigation:q=$lat,$lng")).setPackage(osmPkg)
                out += "OsmAnd" to i
            }
        }
        return out
    }

    /**
     * Builds the navigator intent for a specific [app], or null when that app can't handle
     * [query] on this device (not installed, or OsmAnd asked for a non-coordinate query).
     */
    fun intentForApp(context: Context, query: String, app: NavApp): Intent? {
        val isCoords = LATLNG.matches(query)
        val encoded = Uri.encode(query.trim())
        return when (app) {
            NavApp.GOOGLE_MAPS ->
                if (isInstalled(context, PKG_MAPS))
                    Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encoded"))
                        .setPackage(PKG_MAPS)
                else null
            NavApp.WAZE ->
                if (isInstalled(context, PKG_WAZE)) {
                    val uri = if (isCoords) "https://waze.com/ul?ll=$encoded&navigate=yes"
                              else "https://waze.com/ul?q=$encoded&navigate=yes"
                    Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(PKG_WAZE)
                } else null
            NavApp.OSMAND -> {
                if (!isCoords) return null // OsmAnd only takes coordinates
                val osmPkg = when {
                    isInstalled(context, PKG_OSMAND) -> PKG_OSMAND
                    isInstalled(context, PKG_OSMAND_FREE) -> PKG_OSMAND_FREE
                    else -> return null
                }
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${query.trim()}"))
                    .setPackage(osmPkg)
            }
            NavApp.AUTO -> null
        }
    }

    /**
     * Resolves the intent for a watch-triggered launch honoring the user's preferred
     * navigator ([NavPrefs.getNavApp]); falls back to the first available navigator and
     * finally a generic geo: intent so the destination always opens *somewhere*.
     */
    fun resolveForWatch(context: Context, query: String): Intent {
        intentForApp(context, query, NavPrefs.getNavApp(context))?.let { return it }
        return intentsFor(context, query).firstOrNull()?.second ?: genericGeoIntent(query)
    }

    /**
     * Launches navigation to [query] in response to a favorite picked on the watch — i.e. from
     * a background context with no visible activity. Android's Background Activity Launch (BAL)
     * policy blocks `startActivity` there UNLESS the app holds the "display over other apps"
     * (SYSTEM_ALERT_WINDOW) permission, which grants a BAL exemption. When it's granted we open
     * the navigator directly; otherwise we fall back to a tap-to-launch notification (see
     * [NavLaunchNotifier]) so the destination is never silently dropped.
     */
    fun launchForWatch(context: Context, label: String, query: String) {
        val intent = resolveForWatch(context, query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Settings.canDrawOverlays(context)) {
            try {
                context.startActivity(intent)
                Log.i(TAG, "launched '$label' directly (overlay-exempt)")
                return
            } catch (e: Exception) {
                Log.e(TAG, "direct launch failed: ${e.message}")
            }
        }
        Log.i(TAG, "no overlay permission; posting launch notification for '$label'")
        NavLaunchNotifier.post(context, label, intent)
    }

    /**
     * Launches navigation. If several navigators are available, the caller is
     * expected to have let the user choose; this fires the given [intent].
     * Returns false (and toasts) when nothing can handle the destination.
     */
    fun launch(context: Context, intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(context, R.string.fav_no_nav_app, Toast.LENGTH_LONG).show()
            false
        }
    }

    /** Generic geo: fallback when no supported navigator is installed. */
    fun genericGeoIntent(query: String): Intent {
        val encoded = Uri.encode(query.trim())
        return Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
