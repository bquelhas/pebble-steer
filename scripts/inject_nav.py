#!/usr/bin/env python3
"""Inject a NavMe/Steer maneuver AppMessage into a running Pebble emulator.

Usage: inject_nav.py <platform> <scenario_index 0-4>
Sends NAV_TURN + NAV_TEXT + NAV_ETA to the Steer app so we can screenshot a
realistic turn-by-turn frame per platform (the CLI alone can't push AppMessages).
"""
import sys, time, uuid
from pebble_tool.sdk.emulator import ManagedEmulatorTransport
from pebble_tool.sdk import sdk_version
from libpebble2.communication import PebbleConnection
from libpebble2.services.appmessage import AppMessageService, CString, Int32

APP_UUID = uuid.UUID('5e0abdd0-dc23-434f-b01b-20bcbb816542')

# key ids from watch/package.json messageKeys
NAV_TURN, NAV_TEXT, NAV_ETA = 0, 2, 7

EM = u"\u2014"  # em-dash; watch splits "<dist unit> \u2014 <street>"
SCENARIOS = [
    (9,  u"250 M %s Av. da Liberdade" % EM, u"09:58"),   # DIR_LEFT
    (10, u"1.2 KM %s Rua de Santarem" % EM, u"10:05"),   # DIR_RIGHT
    (15, u"400 M %s Exit 3, N114" % EM,     u"10:07"),   # DIR_ROUNDABOUT_3_LEFT
    (35, u"2.8 KM %s Continue straight" % EM, u"10:12"), # DIR_STRAIGHT
    (0,  u"80 M %s You have arrived" % EM,  u"10:15"),   # DIR_ARRIVE
]

platform = sys.argv[1]
scenario = int(sys.argv[2])
turn, text, eta = SCENARIOS[scenario]

transport = ManagedEmulatorTransport(platform, sdk_version())
conn = PebbleConnection(transport)
conn.connect()
conn.run_async()
time.sleep(1.0)

app = AppMessageService(conn)
app.send_message(APP_UUID, {
    NAV_TURN: Int32(turn),
    NAV_TEXT: CString(text),
    NAV_ETA:  CString(eta),
})
time.sleep(1.5)
print("sent scenario %d (turn=%d) to %s" % (scenario, turn, platform))
