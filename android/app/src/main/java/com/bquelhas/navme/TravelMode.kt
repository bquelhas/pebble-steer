package com.bquelhas.navme

/**
 * How the user will travel to a destination. Chosen on the watch when a favourite is launched
 * (sent back as [NavKeys.NAV_ROUTE_MODE]); each navigator expresses the mode differently, so the
 * per-app deep-link parameters live here and are consumed by [NavLauncher].
 *
 * The [id] values are the wire contract with the watchapp: 0 car, 1 bicycle, 2 pedestrian,
 * 3 transit — keep them in sync with the mode-picker rows on the C side.
 */
enum class TravelMode(
    val id: Int,
    /** Google Maps `google.navigation:...&mode=` letter, or null when unsupported (transit). */
    val mapsNavMode: String?,
    /** Google Maps `maps/dir/?api=1&...&travelmode=` word (used for transit). */
    val mapsTravelMode: String,
    /** OsmAnd `osmand.api://navigate?...&profile=` value. */
    val osmandProfile: String,
    /** Organic Maps / CoMaps `route?...&type=` value. */
    val osmType: String,
) {
    CAR(0, "d", "driving", "car", "vehicle"),
    BICYCLE(1, "b", "bicycling", "bicycle", "bicycle"),
    PEDESTRIAN(2, "w", "walking", "pedestrian", "pedestrian"),
    TRANSIT(3, null, "transit", "public_transport", "transit");

    companion object {
        fun fromId(id: Int): TravelMode = entries.firstOrNull { it.id == id } ?: CAR
    }
}
