# Steer — Android companion app

Steer's phone half. It reads turn-by-turn navigation from your map app's
notifications and forwards each maneuver to a **Pebble** watch running the
[Steer watchapp](../watch): next-turn icon,
distance, street/instruction and ETA.

- **Package / app id:** `com.bquelhas.steer` (display name "Steer").
- **Min SDK 24**, Material You (dynamic colours, follows system theme).
- **Interface** in English or Portuguese (chosen from the device language).
- **Licence:** MIT (see [LICENSE](LICENSE)); attribution in [CREDITS.md](CREDITS.md).

### Compatible navigation apps

Turn-by-turn guidance is read from **Google Maps**, **OsmAnd** (Play and
free/F-Droid builds), **CoMaps**, **Organic Maps** and **komoot**.

### Planned / in progress

- On-watch speedometer and speed-limit alert (both built, switched off for now).

## How it works

```
Map app (Google Maps, …)
    │  posts a navigation notification
    ▼
NavNotificationListenerService     ← requires "Notification access" permission
    │  NaviParser: extract distance / street / maneuver icon, normalise units
    ▼
PebbleEmitter                       ← PebbleKit 2 first, classic PebbleKit as fallback
    │  AppMessage keyed by NavKeys (mirrors the watch's package.json messageKeys)
    ▼
Steer watchapp on the Pebble
```

Key pieces (all under `app/src/main/java/com/bquelhas/steer/`):

| File | Role |
|------|------|
| `NavNotificationListenerService.kt` | Listens to nav notifications, drives the pipeline |
| `NaviParser.kt` | Parses distance/street from notification text; metric/imperial units |
| `ManeuverClassifier.kt` / `ManeuverFingerprints.kt` | Classifies a maneuver from the notification's icon |
| `PebbleEmitter.kt` | Sends AppMessages to the watch (PebbleKit 2, classic fallback) |
| `Pk2Link.kt` | PebbleKit 2 transport: call timeouts, back-off, picks the Pebble app that has the watch |
| `SteerPebbleListenerService.kt` | Receives watch messages over PebbleKit 2; tracks the watchapp session |
| `WatchMessage.kt` | One AppMessage, rendered for either PebbleKit |
| `NavKeys.kt` | Message-key constants — must match the watch's `package.json` |
| `SpeedProvider.kt` | GPS speed for the watch speedometer / speed alert |
| `Favorites*.kt`, `NavLauncher.kt`, `WatchCommands.kt`, `WatchCommandReceiver.kt` | Favourite destinations + launch-from-watch |
| `DeveloperActivity.kt`, `MockNav*`, `DebugCycler.kt` | Debug tools (gated behind a master switch) |
| `PbwInstaller.kt` | Installs the bundled watchapp `.pbw` onto the watch |

### PebbleKit 2 first, classic PebbleKit as fallback

The Pebble / Core Devices app picks the protocol per watchapp from the
installed `.pbw`: a watchapp whose `package.json` lists an Android package under
`companionApp` is served over **PebbleKit 2 only**, any other watchapp over
**classic PebbleKit only**. The Steer watchapp declares `companionApp` (since
1.6.0), so the phone sends over PebbleKit 2 and falls back to classic PebbleKit
whenever PebbleKit 2 didn't deliver — the original Pebble app, and older
watchapp builds. Core only listens for the classic broadcast during a classic
session, so the fallback never delivers a message twice. PebbleKit 2 is also
what Pebble apps that block classic PebbleKit (e.g. Gravel) need.

**Rollout rule:** never publish a `.pbw` with `companionApp` before the APK that
speaks PebbleKit 2 — an older APK only speaks classic, which Core ignores for
such a watchapp.

Before 1.6.0 the watchapp had no `companionApp`, so Core never opened a
PebbleKit 2 session for it and every PebbleKit 2 send failed with
`FailedDifferentAppOpen` — which is why Steer used classic PebbleKit only.

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
watch build output in the sibling `watch/` directory of the monorepo:

```
../watch/build/Nav-app.pbw     (relative to the android/ project root)
```

Adjust that path in `app/build.gradle.kts` if your layout differs, run
`pebble build` in [`../watch`](../watch), then rebuild the APK. If the `.pbw` is absent the task is skipped and
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
