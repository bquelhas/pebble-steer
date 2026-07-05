# Publishing Steer 1.0.0 to the Rebble App Store

Everything needed to submit the watchapp. Portal: **https://dev-portal.rebble.io**
(sign in with a Rebble account). If the portal export needs manual review, email the
generated bundle to **support@rebble.io** — see help.rebble.io/appstore-submission.

---

## 1. Release file (.pbw)

- **File:** `watch/build/watch.pbw`
- Version **1.0.0**, UUID `1bdfe435-6a34-42d5-aed7-ace29fec1260`
- Bundles all 5 platforms: aplite, basalt, chalk, diorite, emery.

Rebuild if needed with `pebble build` (run in `watch/`).

## 2. Assets (drafts in `watch/store/`)

| Asset | Spec | File | Status |
|---|---|---|---|
| Large icon | 144×144 PNG | `store/large_icon_144.png` | ✅ draft (from `docs/icon_car_red.svg`) |
| Small icon | 48×48 PNG | `store/small_icon_48.png` | ✅ draft |
| Banner / header | 720×320 PNG | `store/banner_720x320.png` | ✅ draft |
| Screenshots | native res, ≤5 per platform, PNG/GIF | `docs/screenshots/*.png` | ✅ 5× emery (200×228) |

Notes:
- Screenshots default to *all* platforms. The 200×228 emery maneuver shots are the
  store default. The round (chalk) layout is now fixed and verified on the emulator
  (`store/chalk_round_idle.png` — idle "waiting" screen). Maneuver shots on chalk
  still need the app fed with nav data (mock feed / Gemini's emulator workflow);
  the `pebble` CLI alone only launches the app, it can't inject AppMessages.
- Icons/banner are editable drafts — banner source `store/banner.svg` (wordmark in
  the watch's LECO font), icon source `docs/icon_car_red.svg`. Swap for other art.

## 3. Listing text

**Type:** Watchapp
**Title:** `Steer`
**Category:** `Tools & Utilities`
**Website:** `https://github.com/bquelhas/pebble-steer`
**Source code:** `https://github.com/bquelhas/pebble-steer`
**Support email:** optional (defaults to your Rebble account email)

**Description** (≤1600 chars):

```
Steer mirrors your phone's turn-by-turn navigation onto your Pebble: the next
maneuver icon, distance, street name and ETA — glanceable on your wrist, so your
phone can stay in your pocket.

It's the watch half of a two-part project. Install the free Steer companion app
on your Android phone; it reads navigation from your map app and forwards each
maneuver to the watch.

Features:
- Clear next-maneuver arrow (41 hand-drawn maneuver icons) with distance and street
- ETA display
- Per-turn vibration, so you feel each turn without looking
- Custom background colour with automatic text contrast
- Automatic night backlight (red-tinted on Pebble Time 2) to protect night vision
- Launch a saved favourite destination straight from the watch
- Interface in English or Portuguese, following your watch language

Reads navigation from Google Maps, OsmAnd, CoMaps and Organic Maps. (Waze can be
launched to a favourite, but doesn't expose its maneuvers.)

Works on Pebble, Pebble Steel, Pebble Time / Time Steel, Pebble Time Round,
Pebble 2 and Pebble Time 2.

Open source (MIT). Companion app and source: github.com/bquelhas/pebble-steer
```

**Release notes (1.0.0):**

```
First public release. Turn-by-turn maneuvers with distance, ETA and per-turn
vibration; automatic night backlight; favourites you can launch from the watch;
and support for Google Maps, OsmAnd, CoMaps and Organic Maps.
```

## 4. Submission steps

1. Sign in at https://dev-portal.rebble.io.
2. New submission → **Watchapp**. Enter title, website and source URLs.
3. Pick category **Tools & Utilities**.
4. Upload **large icon**, **small icon** and **banner**.
5. Upload **screenshots**, paste the **description** and **release notes**
   (per-platform asset collection if you add round shots).
6. Upload the **.pbw** (`watch/build/watch.pbw`).
7. Submit. If prompted, download the bundle and email it to support@rebble.io.
   Review is normally quick; you get an email when it's live.

## 5. Before you ship — status of open items (watch side, Gemini's)

- 🔴 **BLOCKER — stale version label.** The wait screen shows `[Steer v2.0]`
  (hardcoded in `watch/src/c/navme.c` lines 87 & 113). Change to `v1.0` (or drop
  the label) and rebuild the pbw before publishing a 1.0.0 release.
- ✅ Favourites-empty-on-open — fixed.
- ✅ Round (chalk / Pebble Time Round) layout — fixed; verified on the emulator today.
- ❓ Backlight turns off when opening the app — unconfirmed; re-check.
