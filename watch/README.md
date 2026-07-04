# Steer — Pebble turn-by-turn navigation watchapp

Steer mirrors turn-by-turn navigation from your phone onto a Pebble watch:
the next maneuver icon, distance, street/instruction text and ETA. It is the
watch half of a two-part project — the
[Steer companion Android app](../android) reads navigation from your map app
and forwards it here.

Built with the Pebble SDK for **aplite, basalt, chalk, diorite and emery**
(Pebble Time 2 is the main target).

## Screenshots

Captured on the emery (Pebble Time 2) emulator, driving a simulated trip:

| Turn left | Turn right | Roundabout exit |
|:---:|:---:|:---:|
| ![left](docs/screenshots/01_left.png) | ![right](docs/screenshots/02_right.png) | ![roundabout](docs/screenshots/03_roundabout.png) |

| Continue straight | Arrival |
|:---:|:---:|
| ![straight](docs/screenshots/04_straight.png) | ![arrive](docs/screenshots/05_arrive.png) |

## Features

- Next-maneuver icon (a 41-entry maneuver set drawn as Pebble Draw Commands,
  plus raw-icon passthrough for map apps that ship their own glyphs).
- Distance + street/instruction line with an animated digit "squash" morph.
- ETA display.
- Configurable background colour, per-turn vibration.
- Speed-limit alert (fed by the phone's GPS).
- Automatic night backlight: during a maneuver between 20:00 and 07:00 the
  screen lights itself, tinted **red** on Pebble Time 2 to protect night vision.
- Interface in English or Portuguese, chosen automatically from the watch
  language.

### Compatible navigation apps

The companion app reads turn-by-turn guidance from:

- **Google Maps**
- **OsmAnd** (both the Play and free/F-Droid builds)
- **CoMaps**
- **Organic Maps**

Waze can be *launched* to a favourite from the phone, but its notifications
don't expose the maneuver, so Steer can't mirror a Waze route to the watch.

### Planned / in progress

- On-watch speedometer (the speed-limit alert above already works).
- Launching a favourite destination directly from the watch.

## Building

Requires the [Pebble SDK](https://developer.repebble.com) (`pebble` on your
`PATH`).

```sh
pebble build                       # build for every platform in package.json
pebble install --emulator emery    # run on the emery emulator
pebble install --phone <ip>        # install to a paired phone
```

## Project layout

```
src/c/           C source for the watchapp (navme.c)
resources/img/         menu icon
resources/pebble_icons/pdc/   maneuver icons (Pebble Draw Command format)
package.json     UUID, target platforms, message keys, bundled resources
wscript          Pebble build rules
```

## Phone ↔ watch protocol

The watch receives AppMessages keyed by the `messageKeys` block in
`package.json` (`NAV_TURN`, `NAV_TEXT`, `NAV_ICON_BITMAP`, `NAV_ETA`,
`NAV_SPEED`, favourites, …). See the companion app for the sending side.

## Licence

MIT — see [LICENSE](LICENSE). Third-party credits in [CREDITS.md](CREDITS.md).
