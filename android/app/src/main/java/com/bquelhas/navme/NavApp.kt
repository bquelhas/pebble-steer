package com.bquelhas.navme

/**
 * Which navigator handles a watch-triggered favorite launch.
 *
 * [AUTO] picks the first installed supported app (Maps preferred, matching the Favorites
 * chooser order). The others force a specific package when it is installed and can handle the
 * destination — Waze and Maps take a free-text query; OsmAnd needs a "lat,lng" pair — otherwise
 * the launch falls back to whatever is available.
 */
enum class NavApp {
    AUTO,
    GOOGLE_MAPS,
    WAZE,
    OSMAND,
}
