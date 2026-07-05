package com.bquelhas.steer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * GPS-based current-speed source for the watch speedometer and the speed-limit alert.
 *
 * The navigator app (Google Maps / OsmAnd) already holds the GPS; we just subscribe to the
 * phone's own location updates and read [Location.getSpeed] (m/s), converting to km/h. This
 * needs no Play Services — plain [LocationManager] with the GPS provider (fused if the OEM
 * routes it there) works while a route is active.
 *
 * Lifecycle is tied to a navigation session: [start] when nav begins, [stop] when it ends.
 * Each fix pushes the current speed to the watch (NAV_SPEED) and, when a manual speed limit is
 * enabled, raises/clears the speed-limit warning (NAV_SPEED_ALERT) with a small hysteresis so a
 * momentary GPS spike doesn't flap the banner.
 */
object SpeedProvider {
    private const val TAG = "NavMe/Speed"

    // Ignore fixes without a real speed reading or slower than this (parked / GPS noise).
    private const val MIN_VALID_SPEED_KMH = 2

    // Hysteresis (km/h) so the alert doesn't flap right at the limit boundary.
    private const val ALERT_HYSTERESIS_KMH = 3

    private var manager: LocationManager? = null
    private var listener: LocationListener? = null
    private var running = false

    private var lastSentSpeed = -1
    private var lastGateBlockedLog = -1
    private var alertActive = false
    private var lastLimit = NavPrefs.DEFAULT_SPEED_LIMIT

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Begins listening for GPS fixes and relaying speed. No-op if already running. */
    fun start(context: Context) {
        if (running) return
        if (!NavPrefs.isSpeedometerEnabled(context) && !NavPrefs.isSpeedAlert(context)) {
            Log.d(TAG, "start skipped: speedometer + alert both off")
            return
        }
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "start skipped: no location permission")
            return
        }

        val appCtx = context.applicationContext
        val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = handleFix(appCtx, location)
            // Deprecated no-op overrides kept for older API levels.
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            val provider = when {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> LocationManager.GPS_PROVIDER
            }
            lm.requestLocationUpdates(provider, 1000L, 0f, l, Looper.getMainLooper())
            manager = lm
            listener = l
            running = true
            lastSentSpeed = -1
            alertActive = false
            Log.i(TAG, "started on $provider")
        } catch (e: SecurityException) {
            Log.e(TAG, "start failed (permission): ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}")
        }
    }

    /** Stops listening and clears any active speed warning on the watch. */
    fun stop(context: Context) {
        if (!running) return
        try { listener?.let { manager?.removeUpdates(it) } } catch (_: Exception) {}
        manager = null
        listener = null
        running = false
        if (alertActive) {
            PebbleEmitter.sendSpeedAlert(context, false, lastLimit)
            alertActive = false
        }
        OsmSpeedLimit.reset()
        lastSentSpeed = -1
        Log.i(TAG, "stopped")
    }

    private fun handleFix(context: Context, location: Location) {
        if (!location.hasSpeed()) return
        val limit = effectiveLimit(context, location)
        val kmh = Math.round(location.speed * 3.6f)
        if (kmh < MIN_VALID_SPEED_KMH) {
            // Treat as stationary: report 0 once so the watch face zeroes out.
            maybeSendSpeed(context, 0)
            updateAlert(context, 0, limit)
            return
        }
        val clamped = kmh.coerceIn(0, 255)
        maybeSendSpeed(context, clamped)
        updateAlert(context, clamped, limit)
    }

    /**
     * The limit to enforce right now: the road's real OSM maxspeed in OSM mode (falling back to the
     * manual limit when OSM has no answer yet / offline), or the manual limit in Manual mode.
     */
    private fun effectiveLimit(context: Context, location: Location): Int {
        val manual = NavPrefs.getSpeedLimit(context)
        if (!NavPrefs.isOsmSpeedLimit(context)) return manual
        OsmSpeedLimit.maybeRefresh(location.latitude, location.longitude)
        return OsmSpeedLimit.currentLimit() ?: manual
    }

    private fun maybeSendSpeed(context: Context, kmh: Int) {
        // Gate: Off never streams; Always always does; Bicycle only for a watch-launched bike route.
        val activeMode = NavPrefs.getActiveMode(context)
        if (!NavPrefs.shouldShowSpeed(context, activeMode)) {
            if (kmh != lastGateBlockedLog) {
                Log.d(TAG, "speed $kmh km/h suppressed: mode=${NavPrefs.getSpeedometerMode(context)}" +
                    " activeRoute=${activeMode.name}")
                lastGateBlockedLog = kmh
            }
            return
        }
        if (kmh == lastSentSpeed) return
        lastSentSpeed = kmh
        Log.d(TAG, "speed $kmh km/h -> watch (mode ${activeMode.name})")
        PebbleEmitter.sendSpeed(context, kmh)
    }

    private fun updateAlert(context: Context, kmh: Int, limit: Int) {
        if (!NavPrefs.isSpeedAlert(context)) {
            if (alertActive) {
                PebbleEmitter.sendSpeedAlert(context, false, limit)
                alertActive = false
            }
            return
        }
        lastLimit = limit
        val exceeded = if (alertActive) kmh > (limit - ALERT_HYSTERESIS_KMH)
                       else kmh > limit
        if (exceeded != alertActive) {
            alertActive = exceeded
            PebbleEmitter.sendSpeedAlert(context, exceeded, limit)
        }
    }
}
