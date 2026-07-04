# Contributing to Steer

Thanks for your interest! Steer is a small, community-driven Pebble app.

## Getting set up

1. Install the [Pebble SDK](https://developer.repebble.com) so `pebble` is on
   your `PATH`.
2. `pebble build` — should produce a clean build for all target platforms.
3. `pebble install --emulator emery` to try it, or `--phone <ip>` for hardware.

## Guidelines

- Keep code and comments in **English**.
- Match the existing style in `src/c/navme.c` (small static helpers, clear
  comments explaining *why*).
- If you change the phone ↔ watch protocol, update the `messageKeys` block in
  `package.json` **and** the companion app in lock-step.
- Do not commit third-party binaries, decompiled apps, or map-provider artwork
  (the `.gitignore` already excludes the known cases).

## Pull requests

- One logical change per PR, with a short description of what and why.
- Mention which platforms/emulators you tested on.

By contributing you agree your contributions are licensed under the project's
MIT licence.
