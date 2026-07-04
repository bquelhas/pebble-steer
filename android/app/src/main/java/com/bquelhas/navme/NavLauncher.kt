package com.bquelhas.navme

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Starts turn-by-turn navigation to a destination in one of the supported navigators, in the
 * [TravelMode] the user picked on the watch. Directions start from the current location.
 *
 * The [query] may be a free-text address ("Rua da Paz, Lisboa") or a "lat,lng" pair. Each app
 * takes the travel mode differently:
 *  - Google Maps: `google.navigation:q=...&mode=d|b|w` (transit falls back to the directions URL,
 *    which `google.navigation:` doesn't support). Handles both text and coordinates.
 *  - OsmAnd: `osmand.api://navigate?...&profile=car|bicycle|pedestrian|public_transport`, which
 *    needs destination coordinates; an address-only query drops to plain (car) navigation.
 *  - Organic Maps / CoMaps: `om://route` / `cm://route?...&type=vehicle|bicycle|pedestrian|transit`,
 *    which need BOTH the current location and destination coordinates (no "from here" shorthand).
 */
object NavLauncher {

    private const val TAG = "NavMe/Launcher"
    private const val PKG_MAPS = NaviParser.PKG_GOOGLE_MAPS
    private const val PKG_OSMAND = NaviParser.PKG_OSMAND
    private const val PKG_OSMAND_FREE = NaviParser.PKG_OSMAND_FREE
    private const val PKG_ORGANIC = NaviParser.PKG_ORGANIC
    private const val PKG_COMAPS = NaviParser.PKG_COMAPS

    private val LATLNG = Regex("""^\s*-?\d+(\.\d+)?\s*,\s*-?\d+(\.\d+)?\s*$""")

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    private fun parseLatLon(query: String): Pair<Double, Double>? {
        if (!LATLNG.matches(query)) return null
        val parts = query.trim().split(",")
        val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return null
        val lon = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
        return lat to lon
    }

    /** Best-effort current position for the OSM-based apps that need an explicit route start. */
    private fun lastKnownLatLon(context: Context): Pair<Double, Double>? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) ?: return null
            loc.latitude to loc.longitude
        } catch (e: SecurityException) {
            null
        }
    }

    private fun mapsIntent(context: Context, query: String, mode: TravelMode): Intent? {
        if (!isInstalled(context, PKG_MAPS)) return null
        val encoded = Uri.encode(query.trim())
        val uri = if (mode.mapsNavMode != null) {
            "google.navigation:q=$encoded&mode=${mode.mapsNavMode}"
        } else {
            // google.navigation: has no transit mode; use the universal directions URL instead.
            "https://www.google.com/maps/dir/?api=1&destination=$encoded&travelmode=${mode.mapsTravelMode}"
        }
        return Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(PKG_MAPS)
    }

    private fun osmandIntent(context: Context, query: String, mode: TravelMode): Intent? {
        val pkg = when {
            isInstalled(context, PKG_OSMAND) -> PKG_OSMAND
            isInstalled(context, PKG_OSMAND_FREE) -> PKG_OSMAND_FREE
            else -> return null
        }
        val dest = parseLatLon(query)
        if (dest != null) {
            val (lat, lon) = dest
            val sb = StringBuilder(
                "osmand.api://navigate?dest_lat=$lat&dest_lon=$lon&dest_name=&profile=${mode.osmandProfile}"
            )
            lastKnownLatLon(context)?.let { (slat, slon) ->
                sb.append("&start_lat=$slat&start_lon=$slon&start_name=")
            }
            return Intent(Intent.ACTION_VIEW, Uri.parse(sb.toString())).setPackage(pkg)
        }
        // Address-only: the OsmAnd navigate API needs coordinates, so fall back to plain
        // navigation (defaults to OsmAnd's current profile — no mode control on this path).
        val encoded = Uri.encode(query.trim())
        return Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encoded")).setPackage(pkg)
    }

    /** Organic Maps (`om://`) and CoMaps (`cm://`) share the same `route?...&type=` structure. */
    private fun omRouteIntent(
        context: Context, query: String, mode: TravelMode, pkg: String, scheme: String,
    ): Intent? {
        if (!isInstalled(context, pkg)) return null
        val dest = parseLatLon(query) ?: return null    // route link needs destination coordinates
        val start = lastKnownLatLon(context) ?: return null  // and an explicit start
        val (dlat, dlon) = dest
        val (slat, slon) = start
        val uri = "$scheme://route?sll=$slat,$slon&saddr=&dll=$dlat,$dlon&daddr=&type=${mode.osmType}"
        return Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg)
    }

    /** All navigators that can handle [query] in [mode] on this device, in preference order. */
    fun intentsFor(context: Context, query: String, mode: TravelMode): List<Pair<String, Intent>> {
        val out = mutableListOf<Pair<String, Intent>>()
        mapsIntent(context, query, mode)?.let { out += "Google Maps" to it }
        osmandIntent(context, query, mode)?.let { out += "OsmAnd" to it }
        omRouteIntent(context, query, mode, PKG_ORGANIC, "om")?.let { out += "Organic Maps" to it }
        omRouteIntent(context, query, mode, PKG_COMAPS, "cm")?.let { out += "CoMaps" to it }
        return out
    }

    /** Intent for the user's forced navigator, or null when it can't handle [query] in [mode]. */
    fun intentForApp(context: Context, query: String, app: NavApp, mode: TravelMode): Intent? =
        when (app) {
            NavApp.GOOGLE_MAPS -> mapsIntent(context, query, mode)
            NavApp.OSMAND -> osmandIntent(context, query, mode)
            NavApp.AUTO -> null
        }

    /**
     * Resolves the intent for a watch-triggered launch honoring the user's preferred navigator
     * ([NavPrefs.getNavApp]); falls back to the first available navigator and finally a generic
     * geo: intent so the destination always opens *somewhere*.
     */
    fun resolveForWatch(context: Context, query: String, mode: TravelMode): Intent {
        intentForApp(context, query, NavPrefs.getNavApp(context), mode)?.let { return it }
        return intentsFor(context, query, mode).firstOrNull()?.second ?: genericGeoIntent(query)
    }

    /**
     * Launches navigation to [query] in [mode] in response to a favorite picked on the watch — i.e.
     * from a background context with no visible activity. Android's Background Activity Launch (BAL)
     * policy blocks `startActivity` there UNLESS the app holds the "display over other apps"
     * (SYSTEM_ALERT_WINDOW) permission, which grants a BAL exemption. When it's granted we open the
     * navigator directly; otherwise we fall back to a tap-to-launch notification (see
     * [NavLaunchNotifier]) so the destination is never silently dropped.
     */
    fun launchForWatch(context: Context, label: String, query: String, mode: TravelMode) {
        val intent = resolveForWatch(context, query, mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Settings.canDrawOverlays(context)) {
            try {
                context.startActivity(intent)
                Log.i(TAG, "launched '$label' (${mode.name}) directly (overlay-exempt)")
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
