package com.bquelhas.navme

/**
 * Which navigator handles a watch-triggered favorite launch.
 *
 * [AUTO] picks the first installed supported app (Maps preferred, then OsmAnd, Organic Maps,
 * CoMaps). The others force a specific package when it is installed and can handle the
 * destination — Maps takes a free-text query; OsmAnd/Organic/CoMaps need coordinates for full
 * travel-mode routing — otherwise the launch falls back to whatever is available.
 */
enum class NavApp {
    AUTO,
    GOOGLE_MAPS,
    OSMAND,
}
