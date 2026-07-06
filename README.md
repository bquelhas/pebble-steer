# Pebble Steer

Turn-by-turn navigation on your **Pebble** watch, mirrored from your phone's
map app. Steer shows the next-maneuver icon, distance, street/instruction and
ETA on the watch — no proprietary cloud, just a notification listener on the
phone talking to a watchapp over Bluetooth.

## Download the phone app

Grab the companion Android app (`.apk`) from the
[**latest release**](https://github.com/bquelhas/pebble-steer/releases/latest),
then sideload it: open the file on your phone and allow "install unknown apps"
when prompted. The watchapp itself is bundled inside the phone app — install it
to your watch from the app's Developer screen (or get it from the Rebble app
store). The watchapp `.pbw` is also attached to the release.

This is a monorepo with two halves:

| Directory | What it is |
|-----------|------------|
| [`watch/`](watch) | The Pebble watchapp (C, Pebble SDK). Builds for aplite, basalt, chalk, diorite and emery — Pebble Time 2 is the main target. |
| [`android/`](android) | The Android companion app (Kotlin). Reads navigation from your map app's notifications and forwards each maneuver to the watch. |

Each half has its own README with build instructions:
[watch/README.md](watch/README.md) · [android/README.md](android/README.md).

## Compatible navigation apps

The companion reads turn-by-turn guidance from **Google Maps**, **OsmAnd**
(Play and free/F-Droid builds), **CoMaps** and **Organic Maps**. Waze can be
*launched* to a favourite, but its notifications don't expose the maneuver, so
a live Waze route can't be mirrored to the watch.

## How it fits together

```
Map app (Google Maps, OsmAnd, …)
    │  posts a navigation notification
    ▼
android/  — NavNotificationListenerService → NaviParser → PebbleEmitter
    │  AppMessage over Bluetooth (PebbleKit classic)
    ▼
watch/    — Steer watchapp: maneuver icon, distance, street, ETA
```

## Status

Working: maneuver mirroring, ETA, per-turn vibration, configurable background
colour, a speed-limit alert, an automatic night backlight (red-tinted on
Pebble Time 2), and English/Portuguese UI chosen from the device language.

Planned / in progress: an on-watch speedometer and launching a favourite
destination directly from the watch.

## Credits & licence

Built for personal use and open-sourced as-is — contributions welcome.
Heavily AI-assisted ("vibecoded"). The maneuver-glyph-over-Bluetooth approach
is inspired by the third-party **PebbleNavi** app; the maneuver icon set is
derived from Pebble's own artwork (see the per-directory `CREDITS.md`).

MIT — see [LICENSE](LICENSE).
