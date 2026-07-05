package com.bquelhas.steer

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Resolves the current road's legal speed limit from OpenStreetMap via the Overpass API.
 *
 * Deliberately light: no client library, just a small HTTP POST + `org.json` parse (both already
 * on Android). The road's `maxspeed` tag is looked up for the nearest `highway=*` way around the
 * GPS fix. Results are cached and the lookup is throttled hard (time + distance gated) so a route
 * costs only a trickle of mobile data — one small request every ~10 s while moving, not per fix.
 *
 * [currentLimit] never blocks: it returns the last cached value immediately (or null if we don't
 * have one yet). [maybeRefresh] kicks off the actual network call on a background thread when the
 * cache is stale enough to bother. The caller ([SpeedProvider]) falls back to the manual limit
 * whenever this returns null, so OSM gaps / offline never break the alert.
 */
object OsmSpeedLimit {
    private const val TAG = "NavMe/OSM"

    // A couple of mirrors; we try them in order until one answers.
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    private const val QUERY_RADIUS_M = 30      // nearest way within this radius carries the limit
    private const val MIN_REFRESH_MS = 10_000L // never hit the API more often than this
    private const val MIN_MOVE_M = 40.0        // ...unless we've moved at least this far
    private const val STALE_AFTER_MS = 60_000L // drop a cached limit older than this (turned off route)
    private const val CONNECT_TIMEOUT_MS = 6000
    private const val READ_TIMEOUT_MS = 8000

    private data class Cached(val lat: Double, val lon: Double, val limitKmh: Int, val atMs: Long)

    @Volatile private var cache: Cached? = null
    @Volatile private var inFlight = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Last resolved limit (km/h) if still fresh, else null. Non-blocking. */
    fun currentLimit(): Int? {
        val c = cache ?: return null
        if (System.currentTimeMillis() - c.atMs > STALE_AFTER_MS) return null
        return c.limitKmh
    }

    /** Clears the cache (call when navigation stops). */
    fun reset() {
        cache = null
    }

    /**
     * Triggers a background refresh if enough time/distance has passed since the last one.
     * Cheap and safe to call on every GPS fix from the main thread.
     */
    fun maybeRefresh(lat: Double, lon: Double) {
        if (inFlight) return
        val now = System.currentTimeMillis()
        val c = cache
        if (c != null) {
            val movedEnough = distanceMeters(c.lat, c.lon, lat, lon) >= MIN_MOVE_M
            val agedEnough = now - c.atMs >= MIN_REFRESH_MS
            if (!movedEnough && !agedEnough) return
        }
        inFlight = true
        scope.launch {
            val limit = try {
                query(lat, lon)
            } catch (e: Exception) {
                Log.w(TAG, "lookup failed: ${e.message}")
                null
            }
            if (limit != null) {
                cache = Cached(lat, lon, limit, System.currentTimeMillis())
                Log.i(TAG, "maxspeed=$limit km/h @ $lat,$lon")
            }
            inFlight = false
        }
    }

    private fun query(lat: Double, lon: Double): Int? {
        // Nearest highway way that actually carries a maxspeed tag.
        val ql = "[out:json][timeout:8];way(around:$QUERY_RADIUS_M,$lat,$lon)" +
            "[highway][maxspeed];out tags 1;"
        val body = "data=" + URLEncoder.encode(ql, "UTF-8")
        for (endpoint in ENDPOINTS) {
            try {
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
                conn.outputStream.use { it.write(body.toByteArray()) }
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    continue
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val elements = JSONObject(text).optJSONArray("elements") ?: return null
                for (i in 0 until elements.length()) {
                    val tags = elements.getJSONObject(i).optJSONObject("tags") ?: continue
                    val raw = tags.optString("maxspeed", "")
                    parseMaxspeed(raw)?.let { return it }
                }
                return null
            } catch (e: Exception) {
                Log.w(TAG, "endpoint $endpoint failed: ${e.message}")
                // try the next mirror
            }
        }
        return null
    }

    /**
     * Parses an OSM `maxspeed` value into km/h. Handles "50", "120", "50 mph", and ignores
     * non-numeric zone codes ("RO:urban", "walk", "none", "signals") by returning null.
     */
    fun parseMaxspeed(raw: String): Int? {
        val v = raw.trim().lowercase()
        if (v.isEmpty()) return null
        val num = Regex("""\d+""").find(v)?.value?.toIntOrNull() ?: return null
        return if (v.contains("mph")) (num * 1.60934).roundToInt() else num
    }

    /** Rough equirectangular distance in metres — plenty accurate at these tiny ranges. */
    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val meanLat = Math.toRadians((lat1 + lat2) / 2)
        val x = dLon * cos(meanLat)
        return Math.sqrt(dLat * dLat + x * x) * 6_371_000.0
    }
}
