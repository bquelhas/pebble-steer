# Steer — Android companion app

Steer's phone half. It reads turn-by-turn navigation from your map app's
notifications and forwards each maneuver to a **Pebble** watch running the
[Steer watchapp](https://github.com/bquelhas/steer): next-turn icon,
distance, street/instruction, ETA and an over-limit speed alert.

- **Package / app id:** `com.bquelhas.navme` (display name "Steer").
- **Min SDK 24**, Material You (dynamic colours, follows system theme).
- **Interface** in English or Portuguese (chosen from the device language).
- **Licence:** MIT (see [LICENSE](LICENSE)); attribution in [CREDITS.md](CREDITS.md).

### Compatible navigation apps

Turn-by-turn guidance is read from **Google Maps**, **OsmAnd** (Play and
free/F-Droid builds), **CoMaps** and **Organic Maps**. Waze can be *launched*
to a favourite, but its notifications don't expose the maneuver, so a live Waze
route can't be mirrored to the watch.

### Planned / in progress

- On-watch speedometer (the speed-limit alert already works).
- Launching a favourite destination directly from the watch.

## How it works

```
Map app (Google Maps, …)
    │  posts a navigation notification
    ▼
NavNotificationListenerService     ← requires "Notification access" permission
    │  NaviParser: extract distance / street / maneuver icon, normalise units
    ▼
PebbleEmitter                       ← PebbleKit CLASSIC (not PebbleKit 2)
    │  AppMessage keyed by NavKeys (mirrors the watch's package.json messageKeys)
    ▼
Steer watchapp on the Pebble
```

Key pieces (all under `app/src/main/java/com/bquelhas/navme/`):

| File | Role |
|------|------|
| `NavNotificationListenerService.kt` | Listens to nav notifications, drives the pipeline |
| `NaviParser.kt` | Parses distance/street from notification text; metric/imperial units |
| `ManeuverClassifier.kt` / `ManeuverFingerprints.kt` | Classifies a maneuver from the notification's icon |
| `PebbleEmitter.kt` | Sends AppMessages to the watch (classic PebbleKit) |
| `NavKeys.kt` | Message-key constants — must match the watch's `package.json` |
| `SpeedProvider.kt` | GPS speed for the watch speedometer / speed alert |
| `Favorites*.kt`, `NavLauncher.kt`, `WatchCommandReceiver.kt` | Favourite destinations + launch-from-watch |
| `DeveloperActivity.kt`, `MockNav*`, `DebugCycler.kt` | Debug tools (gated behind a master switch) |
| `PbwInstaller.kt` | Installs the bundled watchapp `.pbw` onto the watch |

### Why PebbleKit *classic* and not PebbleKit 2

On the Core Devices app, PebbleKit 2's `sendDataToPebble` fails with
`FailedDifferentAppOpen` whenever the app it tracks as "active" isn't our UUID,
and `startAppOnTheWatch` only flips that registration without actually focusing
the app — a deadlock. The classic PebbleKit path
(`PebbleKit.sendDataToPebble` / `startAppOnPebble`) works reliably. Keep using
it.

## Building

Requires a JDK **with a compiler** (`javac`). The system `java-21` on the
original dev machine was JRE-only; Android Studio's bundled JBR works:

```sh
export JAVA_HOME=/path/to/a/jdk-with-javac      # e.g. Android Studio's jbr
./gradlew assembleDebug
```

Install to a phone (wireless adb example):

```sh
adb connect <phone-ip>:<port>
adb -s <phone-ip>:<port> install -r app/build/outputs/apk/debug/app-debug.apk
```

### Bundling the watchapp into the APK

The APK can ship the watch build so users install both together. The
`bundleWatchPbw` Gradle task copies the watch's compiled `.pbw` into
`app/src/main/assets/steer.pbw` before assets are merged. It looks for the
watch project as a **sibling checkout**:

```
../../projetos/Nav-app/build/Nav-app.pbw     (relative to this repo)
```

Adjust that path in `app/build.gradle.kts` to wherever you cloned the
[watch repo](https://github.com/bquelhas/steer), run `pebble build`
there, then rebuild the APK. If the `.pbw` is absent the task is skipped and
the "install watchapp" button simply reports it's not bundled — the phone app
still builds and runs.

## First run

1. Grant **Notification access** (the app shows a setup card until you do).
2. Grant **Location** if you want the speedometer / speed alert.
3. Pair a Pebble via the Pebble / Core Devices app and start navigating.

## What's **not** in this repo

- `app/src/main/assets/steer.pbw` — build artifact (regenerate as above).
- `app/src/debug/assets/gmaps_maneuvers/` — Google Maps' own maneuver artwork,
  used only by the debug MockNav auditing tool. Not redistributed. Without it,
  that debug tool degrades gracefully.

## Contributing

Code and comments in English. If you change the phone↔watch protocol, update
`NavKeys.kt` **and** the watch's `package.json` in lock-step. See
[CONTRIBUTING.md](CONTRIBUTING.md).
