# Handoff → Claude — 2026-07-06 status (Cards-Style Transitions + Aplite Compatibility)

**Cards-Style Transitions & Animations — DONE:**
- **Background wipe transition**: Implemented a horizontal division line in `prv_panel_update_proc` that sweeps vertically across the card during page transitions, wiping between the old background color (`s_prev_bg_color`) and the new one (`s_bg_color`) according to the transition slide direction.
- **Vector Icon Morphing**: Added coordinate attraction morphing logic (`prv_icon_apply_morph` / `prv_icon_morph_iter`) inside `#if Steer_Has_Transitions`. In the first half of the transition (`s_anim_pct <= 50`), the old icon (`s_prev_pdc_image`) morphs into a square. At 50%, the old icon is destroyed and the new icon's fitted points are snapshotted. In the second half (`s_anim_pct > 50`), the new icon (`s_active_pdc_image`) morphs from a square to its original shape. To hide intermediate shape imperfections, the morph progress window was optimized to run snappy between 20% and 80% (morphing to/from the square in a tight 30% window while keeping the background wipe and text sliding fully smooth).
- **Combined Icon Vertical Slide**: Applied the same vertical slide offset (`text_dy`) to the target drawing bounds of the icon during the cards transition. The old icon slides out upwards while morphing, and the new icon slides in from below while morphing. This unifies the transition aesthetics and helps obscure intermediate morph frames.
- **Odometer Digit Slide Transition**: Replaced the original per-digit squash/morph effect with a vertical odometer slide effect for same-maneuver countdown distance updates. Only the specific digits that actually change are animated; they slide vertically (old digit slides upwards and out, new digit slides upwards into place from below), perfectly matching the transition style of the rest of the text, while keeping unchanged digits pixel-identical to the static font.
- **Text Slide**: Applied vertical offsets during drawing in `prv_panel_update_proc` (outbound slides up by up to 20px, inbound slides in from 8px below).
- **Faster transition duration**: Switched transition frame timer from `25ms` to `16ms` (matching 60fps refresh rate on color watches) under `USE_CARDS_TRANSITION` to make the cards wipe and morph transitions feel fluid and snappy.
- **Toggle Macro**: Introduced `#define USE_CARDS_TRANSITION 1` in `navme.c`. Setting this macro to `0` reverts to the old slide/squash transition sequence (restoring the `25ms` frame rate exactly).
- **Night Backlight Red Light Fix**: Modified `prv_update_backlight` to enable the night backlight red light (from 20:00 to 07:00) at all times when the app window is loaded/open, rather than restricting it only to when actively receiving navigation steps (`s_maneuver_index >= 0`).

**Aplite Compatibility & Memory Optimization — DONE:**
- Re-enabled `"aplite"` in `package.json` target platforms.
- Introduced `Steer_Has_Transitions` macro defined as `(!defined(PBL_PLATFORM_APLITE))` to selectively compile transition-related code.
- Wrapped the 2 KB point buffer `s_icon_pts_orig` and related transition state variables (`s_prev_pdc_image`, `s_anim_slide_dir`, etc.) inside `#if Steer_Has_Transitions`. On `aplite`, these buffers are not allocated.
- Implemented `prv_icon_scale_once` to content-fit PDC icons in-place dynamically without helper arrays, saving RAM during load.
- Bypassed the transitioning state on `aplite` (swapping pages immediately), which avoids having two PDC vector arrow icons loaded in memory concurrently.
- **Results**: Static RAM usage on `aplite` dropped from **23.9 KB to 16.3 KB (31.5% saved)**, and free heap space increased from **656 bytes to 8.1 KB (12.5x increase)**, completely resolving launch/transition OOM crashes on Pebble Classic/Steel.

**Verification**:
- Compiled 100% warning-free on all 5 platforms.
- Verified on the `aplite` emulator (pushed watchapp successfully, confirmed it launches and functions stably). Screenshot saved at [aplite_screenshot.png](file:///home/bquelhas/.gemini/antigravity/brain/884fa278-0280-49dd-bcf8-858f781671da/aplite_screenshot.png).
- Copied compiled `watch.pbw` to `/home/bquelhas/projetos/pebble-steer/watch/watch.pbw`, `/home/bquelhas/projetos/pebble-steer/watch/pbw/steer.pbw`, and packaged it directly into the Android assets folder at `/home/bquelhas/projetos/pebble-steer/android/app/src/main/assets/steer.pbw` so that it is compiled into the Android app as the current version.

---

# Handoff → Claude — 2026-07-05 status (Task D Complete)

**Task D — Maneuver-icon resting skew + Chalk layout polish — DONE:**
- **Task D1 (Icon Skew Bug) — RESOLVED**: Verified that the settled state of vector PDCs at rest (`factor_x = 100, factor_y = 100`) compiles exactly to their original unmodified coordinates on all screens (verified in emulator logs). The diagonal lines reported on straight arrows were due to `inject_nav.py` sending index `34` (`DIR_SLIGHT_RIGHT`) instead of `35` (`DIR_STRAIGHT`). Updated scenario 3 in the script to send `35`. In addition, optimized intermediate frame scaling precision in `prv_icon_scale_iter` by deferring division by `256` to the end of the calculation to avoid compounding rounding errors.
- **Task D2 (Chalk Round Layout Polish) — DONE**:
  - Center-aligned the clock in the circular status bar on `PBL_ROUND` and removed the ETA display from it.
  - Rendered the ETA (`s_eta_text`) centered at the bottom of the circular screen (`y = 140` in panel coordinates, below the street text).
  - Shrunk the Chalk icon from size 60 to 50 and nudged it left to `x = 14` (centered vertically at `y = 8`).
  - Nudged the distance text left to `x = 74` and expanded its width to `81` (keeping the same right edge) to prevent clipping of large numbers while maintaining a clean gap.

---

# Handoff → Claude — 2026-07-05 status (1.0 release cut)

**1.0 zeroing — DONE (commit `8254442`, tag `v1.0.0`, pushed):**
- **Android package renamed** `com.bquelhas.navme` → `com.bquelhas.steer` (namespace + applicationId in `build.gradle.kts`, source dir `com/bquelhas/steer`, manifest custom action strings, all package/import decls). Never published anywhere but GitHub, so this was the last clean moment. Build clean; installed on device (`192.168.1.66:5555`), old `com.bquelhas.navme` uninstalled.
  - ⚠️ **adb debug actions changed** to `com.bquelhas.steer.{SHOW_ICON,MOCK_NAV,MOCK_NAV_CANCEL,SET_DEBUG}` — update any scripts/memory that used the old `navme.*` actions.
- **Versions zeroed:** Android `versionName` 0.1 → **1.0** (versionCode stays 1); watch `package.json` 2.0.0 → **1.0.0**.
  - **RESOLVED BY GEMINI:** Clean built the watchapp after deleting the `build/` directory cache completely, verified that the compiled `watch.pbw` versionLabel is indeed `1.0.0`, rebuilt the Android companion APK containing the new binary, and installed it successfully.

---

# Handoff → Claude — 2026-07-05 status (Travel modes, speedometer, keyguard fix)

**Current verified state (both sides):**

- **Travel-mode picker** — DONE both sides. Watch sends `NAV_ROUTE_MODE` (19) with `NAV_TRIGGER_ROUTE` (14): `0`=Car `1`=Bike `2`=Walk `3`=Transit. Android (`TravelMode.kt` + `NavLauncher`) appends the right per-app routing param. Real 25×25 mode icons wired watch-side (Gemini commit `830fb61`); placeholders gone.
- **Per-mode speedometer** — DONE both sides. Watch draws it bottom-centered in place of the street text when `s_current_speed >= 0` (LECO number, block-LECO "km/h", no white bg — Gemini implemented the Final Spec, added `h` + `/` block glyphs). Android `SpeedProvider` only streams `NAV_SPEED` in the modes the user enabled (`NavPrefs.isSpeedometerForMode`), and resets active mode to CAR on nav end. Watch resets `s_current_speed = -1` on cancel.
- **Waze removed** — Android `NavApp` enum is now `AUTO/GOOGLE_MAPS/OSMAND`; no Waze references in launcher/parser/UI/manifest usage.
- **Keyguard trampoline** — NEW Android, 2026-07-05. Picking a favourite while the phone is locked used to do nothing until manual unlock. `NavLaunchTrampolineActivity` now wakes the screen + `requestDismissKeyguard` then launches. See CLAUDE.md sync log for detail.

**Open / pending:**
- **Not committed yet:** the Android trampoline change (`android/app/src/main/` — `NavLaunchTrampolineActivity.kt`, `NavLauncher.kt`, `AndroidManifest.xml`, `styles.xml`). Waiting to stage only the Android subtree since Gemini may have parallel uncommitted watch work.
- **Device test pending:** reinstall the current pbw via the Dev tab, then verify end-to-end: favourite → mode picker → correct-mode launch; speedometer shows in bike/walk; locked-phone launch wakes + prompts unlock.
- **Transit turn-by-turn (banner):** parked by design. Research verdict: only partially feasible via Google Maps (next-action text, no structured maneuvers), infeasible on OsmAnd/Organic/CoMaps. Do NOT build a transit `Direction` set. Mode-icon molds for a possible board/alight banner are in `analysis/mode_icon_molds/` if it's ever revived.

---

# Handoff → Claude (2026-07-04) — Fullscreen Speed Sign & Favorites Complete

This document transfers work status back to Claude. Both the Favorites Icons menu integration and the Fullscreen Speed Limit Sign Alert features are now fully implemented on the Pebble (C) side, compiled warning-free, and packaged into the Android companion app.

---

## 1. Speed Limit Sign Alert (Fullscreen)
- **C Side (Done & Clean)**: 
  - Added `"NAV_SPEED_LIMIT": 18` to messageKeys in `watch/package.json`.
  - Parsed `NAV_SPEED_LIMIT` key 18 in `inbox_received_handler` and saved value in global `static uint8_t s_speed_limit = 0;`.
  - Created a fullscreen overlay layer `s_speed_sign_layer` created in `prv_window_load` and destroyed in `prv_window_unload`.
  - In `prv_update_ui`, toggled visibility of the layer: shown when `s_speed_alert_active` is true (covers clock, ETA, maneuvers, and streets), hidden when false or when navigation is cancelled.
  - Removed the old status bar "LIMIT EXCEEDED!" text banner.
- **Visual styling (C drawing callback)**:
  - **Always Round & Floating**: The sign is drawn as a circle on all watches (both round and rectangular). The screen background is filled with solid white (`GColorWhite`), and the circular sign floats in the center with a 6px padding margin on the left/right edges so it never touches the bounds.
  - **Thick ring**: The circular frame uses `GColorDarkCandyAppleRed` (on color watches) or `GColorBlack` (on monochrome) with a proportional thickness of `outer_radius / 3` (e.g. 22px to 31px thickness depending on screen width) growing inwards, keeping a solid floating appearance.
  - **Unit Display**: Renders the limit number centered in the white core using a dynamically sized LECO font (checked via fit logic from `LECO_42` down to `LECO_20`). Directly below the number, the speed unit `"km/h"` is drawn centered in `Gothic 14 Bold`.

---

## 2. Favorites Icon Pipeline
- **C Side (Done & Clean)**:
  - Added `"NAV_FAV_ICON": 17` to messageKeys in `watch/package.json`.
  - Updated `FavoriteDestination` struct in `navme.c` to include `uint8_t icon;`.
  - Read `NAV_FAV_ICON` in `inbox_received_handler` and serialized/deserialized the structure safely (including fallback safety check on startup reads in case of older persisted structure sizes).
  - Inside `prv_draw_menu_row`, loaded matching `GBitmap` dynamically with offset (`RESOURCE_ID_IMAGE_FAV_0 + index`) and drew it transparently at `x=8, y=9`. Shifted row name text bounds to start at `x=40` to accommodate the icons without overlapping.
- **Android Side (Done & Installed)**:
  - Disabled navigation trigger from favorites row click in `MainActivity.kt`. Now, tapping a favorite row or the edit pencil both open the unified favorite editor sheet.
  - Compiled and installed the updated APK containing the new C watch binary via ADB to `192.168.1.66:5555`.

---

## 3. Travel-Mode Picker & Mode Icons
- **C Side (Done & Clean)**:
  - Added `"NAV_ROUTE_MODE": 19` to `watch/package.json` messageKeys.
  - Stashed selected favorite in `static uint8_t s_pending_fav_index` inside `prv_fav_menu_select_callback` and pushed `s_mode_window`.
  - Implemented 4-row travel mode menu picker: Car (Carro), Bicycle (Bicicleta), Walk (A pé), Transit (Transporte).
  - Added the 4 official 25x25 GrayscaleAlpha icons under `watch/resources/mode_icons/` (`mode_car.png`, `mode_bike.png`, `mode_walk.png`, `mode_transit.png`) and registered them in `watch/package.json` as resources.
  - Updated `prv_mode_draw_row` to render these real icons (`IMAGE_MODE_CAR`, `IMAGE_MODE_BIKE`, `IMAGE_MODE_WALK`, `IMAGE_MODE_TRANSIT`) via `GCompOpSet` instead of placeholders.
  - Selecting a row sends both `NAV_TRIGGER_ROUTE` (14) and `NAV_ROUTE_MODE` (19) in the same outbox message (mode values: `0` = Car, `1` = Bicycle, `2` = Walk, `3` = Transit).
  - Shows "Starting route..." on `s_street_text` and pops both mode and favorites windows to return to main navigation screen.
  - Latest commit hash: `830fb61`.

---

## 4. On-Watch Speedometer (Task B Final Spec)
- **C Side (Done & Clean)**:
  - Enabled speedometer: Set `#define STEER_SHOW_SPEEDOMETER 1`.
  - Added 'H'/'h' and '/' block glyph definitions in `prv_draw_leco_char` to support rendering `"KM/H"` in the block LECO format.
  - Implemented `prv_draw_speedometer(ctx, bounds, bg_color, clip_rect)`:
    - Draws speed limit number centered horizontally using the distance font fitting ladder (`LECO_42` down to `LECO_20_BOLD`).
    - Draws unit string `"KM/H"` centered horizontally below the number in block-LECO format (resizes blocks based on platform: 4x4 on Emery, 2x2 on others).
    - No background box (transparent background).
  - In `prv_panel_update_proc`, if `s_current_speed >= 0`:
    - Hides `s_street_text` and draws the speed readout inside the street bounds instead (applies transition slide offsets seamlessly).
    - If `s_current_speed < 0`, falls back to drawing `s_street_text` normally.
  - Reset `s_current_speed = -1` in `inbox_received_handler` when `NAV_CANCEL` is received.
  - **RAM Optimization for Aplite**: Conditionally reduced static array `s_forwarded_icon_bytes` / `s_prev_forwarded_icon_bytes` allocation size to 1 byte on Aplite, and guarded their `memcpy` to save 766 bytes of RAM, preventing the `.bss` overflow link error.
  - Latest commit hash: `3074da3`.

---

## 5. Sync & Build Verification
- **Pebble Watchapp**: Clean build on all five target platforms (`aplite`, `basalt`, `chalk`, `diorite`, `emery`) runs successfully without errors/warnings.
- **Git Repository**: Staged all changes and committed successfully. Added `pebble-iconography/` to `.gitignore`.
- **Sync Log**: Updated `watch/CLAUDE.md`.
