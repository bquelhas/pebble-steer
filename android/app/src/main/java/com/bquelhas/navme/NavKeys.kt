package com.bquelhas.navme

import java.util.UUID

/**
 * Shared AppMessage contract between this Android app and the NavMe Pebble watchapp.
 * The integer key values MUST match `messageKeys` in the Pebble package.json.
 */
object NavKeys {
    val WATCH_UUID: UUID = UUID.fromString("1bdfe435-6a34-42d5-aed7-ace29fec1260")

    const val NAV_TURN = 0          // int: maneuver index (Direction.id)
    const val NAV_TEXT_BEGIN = 1    // uint8: start of instruction frame
    const val NAV_TEXT = 2          // cstring: full instruction (distance embedded)
    const val NAV_TEXT_END = 3      // uint8: commit instruction to display
    const val NAV_INVERT_COLOR = 4  // int: 0 normal, 1 inverted theme
    const val NAV_GPS_ACCURACY = 5  // cstring: Unknown|Poor|Medium|Good|Excellent
    const val NAV_ICON_BITMAP = 6   // bytes: 48x48 1bpp maneuver glyph forwarded from
                                    // the nav app's notification largeIcon (8 bytes/row,
                                    // 384 bytes, LSB-first, bit=1 -> foreground pixel).
                                    // Sent only when the maneuver is icon-only (e.g. Google
                                    // Maps); when present the watch draws it instead of the
                                    // native NAV_TURN icon.
    const val NAV_ETA = 7           // cstring: arrival clock time, e.g. "20:09" (no prefix;
                                    // the watch formats it as "ETA: 20:09"). Extracted from
                                    // the notification subText ("6 min · 1.7 km · 20:09 ETA").
    const val NAV_BG_COLOR = 8      // uint32: watch background color packed 0xRRGGBB. The watch
                                    // (GColorFromRGB) sets it as the card background and auto-picks
                                    // black/white text by luminance. Sent with each nav frame.
    const val NAV_VIBE_ON_TURN = 9  // uint8: 0 disabled / 1 enabled. Settings sync — the watch
                                    // only buzzes on a new maneuver when this is 1. Sent with each
                                    // nav frame and immediately when the user toggles the setting.
    const val NAV_SPEED_ALERT = 10  // uint8: 0 normal / 1 speed limit exceeded. When 1 the watch
                                    // shows an inverted warning banner in the status bar + vibrates.
    const val NAV_FAV_COUNT = 11    // uint8: total number of favorites being synced to the watch.
    const val NAV_FAV_INDEX = 12    // uint8: index (0..count-1) of the favorite in the message.
    const val NAV_FAV_NAME = 13     // cstring: display name of the favorite at NAV_FAV_INDEX.
    const val NAV_TRIGGER_ROUTE = 14 // uint8 (watch->phone): the SELECT button picked favorite X;
                                    // the phone starts navigation to that favorite.
    const val NAV_SPEED = 15        // uint8: current speed in km/h from the phone GPS (0..255),
                                    // for the watch speedometer. Sent while navigating.
    const val NAV_REQUEST_FAVS = 16 // uint8 (watch->phone): the watchapp just launched and wants
                                    // its favorites; the phone replies with a full sendFavorites
                                    // burst. Fixes the empty-menu-until-next-edit sync gap: the
                                    // watch only holds favorites in RAM, so a fresh launch starts
                                    // empty unless the phone re-pushes. Watch also persists them
                                    // locally (persist_write) for instant offline display.
    const val NAV_CANCEL = 99       // uint8: navigation stopped / clear display
}
