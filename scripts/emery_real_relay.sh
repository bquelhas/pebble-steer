#!/usr/bin/env bash
# Capture the 5 Steer maneuver frames on the REAL emery watch via the
# CloudPebble relay (new 2025/26 Pebble app). Run this in YOUR terminal
# (the one where `pebble login` succeeded).
#
# Prereq: `pebble login` done (Firebase/GitHub), Dev Connection ON in the app.
#
# Two fixes vs the first attempt:
#   1) GEVENT_RESOLVER=block  -> gevent's DNS resolver fails with EAI_AGAIN on
#      the relay host; the blocking stdlib resolver resolves it fine.
#   2) use --cloudpebble (a flag) instead of `--phone` with no IP: `--phone`
#      has nargs='?' and greedily eats the next arg (the pbw path), producing
#      an invalid ws:// URL. --cloudpebble has no such ambiguity.
set -u
export GEVENT_RESOLVER=block
PEBBLE=/home/bquelhas/.local/share/uv/tools/pebble-tool/bin/pebble
WATCH=/home/bquelhas/projetos/pebble-steer/watch
OUT=/home/bquelhas/projetos/pebble-steer/docs/screenshots
UUID=1bdfe435-6a34-42d5-aed7-ace29fec1260
cd "$WATCH" || exit 1
mkdir -p "$OUT"
noise() { grep -viE "new SDK|Update with|new pebble-tool"; }

echo "=== installing pbw on real emery via relay ==="
timeout 150 "$PEBBLE" install --cloudpebble build/watch.pbw 2>&1 | noise | tail -10
sleep 4

# name | NAV_TURN(0,int) | NAV_TEXT(2,string) | NAV_ETA(7,string)
send_shot () {
  local name="$1" turn="$2" text="$3" eta="$4"
  echo "--- $name (turn=$turn) ---"
  timeout 60 "$PEBBLE" send-app-message --cloudpebble --app-uuid "$UUID" \
    --int 0="$turn" --string 2="$text" 7="$eta" 2>&1 | noise | tail -2
  sleep 3
  timeout 60 "$PEBBLE" screenshot --cloudpebble --no-open "$OUT/emery_${name}.png" 2>&1 | noise | tail -2
  sleep 1
}

send_shot 01_left       9  "250 M — Av. da Liberdade"   "09:58"
send_shot 02_right      10 "1.2 KM — Rua de Santarem"   "10:05"
send_shot 03_roundabout 15 "400 M — Exit 3, N114"       "10:07"
send_shot 04_straight   35 "2.8 KM — Continue straight" "10:12"
send_shot 05_arrive     0  "80 M — You have arrived"    "10:15"

echo "=== DONE — check $OUT/emery_0*.png ==="
ls -la "$OUT"/emery_0*.png
