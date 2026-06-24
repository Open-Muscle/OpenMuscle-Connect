# OpenMuscle Wire Format (canonical spec)

This is the shared, decoded specification for how OpenMuscle devices put data on the
wire. It is derived by reading the actual source, not the loose descriptions in the
briefing docs. It is the reference for three independent efforts:

1. The **Android app** (this repo) consuming sensor and label frames.
2. The **firmware BLE work** that will add a Bluetooth transport to the FlexGrid.
3. Any new device or tool that wants to be parsed by the PC app unchanged.

Sources this was decoded from:
- Firmware: `FlexGridV3-Firmware/lib/network_manager.py`, `lib/sensor_matrix.py`, `lib/settings_manager.py`, `flexgrid.py`
- PC app: `OpenMuscle-Software/pc/src/openmuscle/protocol/parser.py`, `receiver/udp_listener.py`, `receiver/matcher.py`, `data/dataset.py`, `web/state.py`

> Important correction to the briefing docs: the on-the-wire payload is **JSON text**,
> not packed binary integers. There is no endianness concern for the UDP path. The
> "packed integers" phrasing in `docs/ECOSYSTEM.md` and `docs/TECH-DECISIONS.md` is
> stale; this document supersedes it for the UDP transport.

---

## 1. Transport summary

| Transport | Port | Used by | Status |
|---|---|---|---|
| Wi-Fi UDP (JSON) | 3141 | FlexGrid, LASK5 sensor/label streams -> hubs | Live today |
| Wi-Fi mDNS | 5353 | Device discovery on Wi-Fi (primary) | Live (V4, best-effort) |
| Wi-Fi UDP broadcast beacon | 3141 | Device discovery fallback when multicast is blocked | Live (V4) |
| Wi-Fi TCP control (cmd channel) | 8001 (announce `services.cmd`) | Commands + heartbeat hub <-> device, newline-delimited JSON | Live (V4) |
| HTTP + WebSocket | 8000 | VR headset and browser UI <-> PC | Live today |
| Robot-hand UDP | 3145 | PC -> OpenHand robot (inference output) | Live today |
| BLE advertising + GATT | n/a | Discovery, sensor, label, command, status -> phone | Draft, V4 firmware onward |

The PC UDP listener binds `0.0.0.0:3141` and reads datagrams up to 8192 bytes
(`receiver/udp_listener.py`). The phone should do the same.

Devices are distinguished by the **`type` field inside the JSON**, not by sender IP or
port (`protocol/parser.py`). Do not route by source address.

**Direction update 2026-06-17.** Devices stop assuming a hardcoded hub IP; discovery
becomes part of the protocol (section 8.1) and both Wi-Fi and BLE are first-class
transports. The data-plane sections (2 through 6) are live and authoritative. The
control-plane sections (8) are draft, to be ratified with the firmware effort. Note that
adding BLE GATT to the MicroPython firmware is V4-onward work that is not yet scheduled;
V3 hardware stays Wi-Fi only.

---

## 2. OpenMuscle protocol v1.0 (UDP JSON)

Every v1.0 packet is a single UDP datagram containing one JSON object with this envelope:

```json
{
  "v":    "1.0",
  "type": "flexgrid",
  "id":   "flexgrid-v3-01",
  "ts":   12345000,
  "data": { },
  "meta": { }
}
```

| Field | Type | Meaning |
|---|---|---|
| `v` | string | Protocol version. Absence of `v` means a legacy packet (see section 6). |
| `type` | string | Device class. Routes parsing. Known values: `flexgrid`, `lask5`, `quest_hand`. |
| `id` | string | Device instance id, e.g. `flexgrid-v3-01`. |
| `ts` | uint32 | Device-local timestamp in milliseconds (firmware uses `time.ticks_ms()`, so it wraps and is relative to device boot, not wall clock). |
| `data` | object | Payload, shape depends on `type`. See below. |
| `meta` | object | Optional telemetry, attached roughly once per second, not on every frame. |

### 2.1 FlexGrid sensor frame (`type: "flexgrid"`)

```json
{
  "v": "1.0",
  "type": "flexgrid",
  "id": "flexgrid-v3-01",
  "ts": 12345000,
  "data": {
    "matrix": [[0,1200,800,500], [0,1150,750,480], "... 15 columns total ..."],
    "rows": 4,
    "cols": 15
  },
  "meta": {
    "vbat": 4.15, "pct": 95, "uptime_s": 125,
    "free_mem": 245000, "rssi": -65,
    "imu": null, "reset_cause": 1, "reset_cause_name": "POWER_ON"
  }
}
```

- `data.matrix` is **column-major**: a list of columns, each column is a list of 4 row
  readings `[row0, row1, row2, row3]`.
- Values are raw 12-bit ADC samples, uint16, range 0 to 4095.
- `rows` and `cols` are optional. When absent, infer from the matrix dimensions. V4 and
  V3 hardware are 15 columns by 4 rows (60 cells). Older V1 boards were 16 by 4 (64
  cells). The phone must handle both by auto-detecting on the first packet, exactly as
  the PC app does.
- `meta` fields: `vbat` (volts, float), `pct` (battery percent 0 to 100), `uptime_s`,
  `free_mem` (heap bytes), `rssi` (dBm or null), plus reset diagnostics. Treat all of
  `meta` as optional; do not assume any field is present on a given frame.
- **Sequence number (draft):** the current firmware JSON frame has no per-frame sequence
  field. The 2026-06-17 direction update calls for one so a hub can detect dropped frames
  on lossy Wi-Fi. Proposed: add `"seq"` (uint, rolling) to the envelope. Until firmware
  emits it, the phone falls back to gap detection on `ts`. Coordinate with firmware.

### 2.2 Critical: matrix flatten order

The single most error-prone part of porting this. The PC builds its feature vector by
reading the column-major matrix in **row-major output order**
(`web/inference.py`, `web/state.py`):

```python
flat = [matrix[c][r] for r in range(rows) for c in range(cols)]
# outer loop r (row), inner loop c (col); indexing is matrix[col][row]
```

So the flat 60-vector is:

```
R0C0, R0C1, ... R0C14,   R1C0, ... R1C14,   R2C0, ... R2C14,   R3C0, ... R3C14
```

The CSV column names follow the same order: `R{row}C{col}`. The Android capture writer
must flatten identically or the model will see transposed features. See section 5.

### 2.3 LASK5 label frame (`type: "lask5"`)

```json
{
  "v": "1.0",
  "type": "lask5",
  "id": "lask5-01",
  "ts": 99999,
  "data": {
    "values": [p0, p1, p2, p3],
    "joystick": {"x": 2048, "y": 1900}
  },
  "meta": {"battery": 85}
}
```

- `data.values` are the **4 piston pressures** that become training labels
  `label_0 .. label_3`. Integers; can be negative on the legacy device.
- `data.joystick` (optional) carries thumb abduction (`x`) and thumb flexion (`y`) as
  raw ADC, used for robot-hand control rather than as a regression label by default.
- LASK5 lives on the same UDP port 3141; it is told apart from the FlexGrid by `type`.

### 2.4 Quest hand frame (`type: "quest_hand"`, WebSocket only)

Sent by the VR headset over WebSocket to `/ws/quest`, roughly 30 Hz. Not UDP, because
browsers cannot send UDP.

```json
{
  "device_id": "quest-right",
  "ts": 1623456789000,
  "handedness": "right",
  "joints": [
    {"name": "wrist", "pos": [x,y,z], "rot": [qx,qy,qz,qw], "valid": true},
    "... 24 more joints ..."
  ]
}
```

- 25 joints in canonical WebXR order (wrist, then thumb 4, index 5, middle 5, ring 5,
  pinky 5). The order is fixed; see `simulate/quest_hand.py` `JOINT_NAMES`.
- The PC flattens each joint to 7 floats `[px, py, pz, rx, ry, rz, rw]`, giving a
  175-float label vector when the Quest is the label source.
- `ts` here is wall-clock milliseconds from the browser, unlike the firmware's relative
  `ticks_ms`.

---

## 3. Time alignment (sensor to label matching)

Training pairs each sensor frame with the nearest label frame inside a time window
(`receiver/matcher.py`):

- LASK5 labels: window is 100 ms.
- Quest hand labels: window is 175 ms (accounts for browser to server latency).
- Matching keeps the nearest label within the window; sensor frames with no label in
  window are **dropped** and never written to the training CSV.

Because device `ts` values are not a shared clock (firmware `ticks_ms` is relative to
boot, browser `ts` is wall clock), the PC matches on its own **receive time**, not on
the packet `ts`. The phone capture pipeline must do the same: stamp each frame with the
phone's monotonic receive time and match on that.

---

## 4. Device status / telemetry

FlexGrid status rides in the `meta` object of sensor packets about once per second, and
can also be sent standalone when scanning is paused. Fields the firmware populates
(`flexgrid.py` status loop): `vbat`, `pct`, `uptime_s`, `free_mem`, `rssi`, `imu`
(placeholder, currently null), `reset_cause`, `reset_cause_name`.

For the phone UI, surface at minimum `pct` (battery) and `rssi` (link quality) when
present.

---

## 5. PC-compatible training CSV (the phone's export target)

The phone records sessions and exports them in the exact CSV the PC trainer reads
(`data/dataset.py`, `data/storage.py`). Getting this byte-compatible is what lets the PC
train on phone-captured data with no changes.

Header (FlexGrid 15x4 plus LASK5 4-piston labels):

```
timestamp,R0C0,R0C1,...,R0C14,R1C0,...,R1C14,R2C0,...,R2C14,R3C0,...,R3C14,label_0,label_1,label_2,label_3
```

Rules:
- `timestamp`: the phone receive time of the matched sensor frame.
- Sensor columns: `R{row}C{col}`, row-major as defined in section 2.2, raw ADC values.
- Label columns: `label_0 .. label_3` from the LASK5 `values`. If the label source is
  the Quest hand instead, the label columns are the 175 flattened joint channels and the
  PC detects them by name pattern; for v1 the phone targets the 4-piston LASK5 / manual
  label case.
- One row per matched sensor frame. Unmatched frames are dropped, same as the PC.
- The PC's `detect_columns` splits features from labels by the `R\d+C\d+` versus
  `label_*` naming, so the names must be exact.

Manual labels (user taps a target hand pose during capture) are written into the same
`label_0 .. label_3` columns, so a manually-labeled session is indistinguishable from a
LASK5-labeled one to the PC trainer.

---

## 6. Legacy packets (handle, do not emit)

Before v1.0 the devices sent bare payloads with no envelope:
- Legacy FlexGrid: a bare JSON array that is the 16x4 matrix, no `v`/`type`/`id`.
- Legacy LASK5: a Python-dict repr string like
  `{'id': 'OM-LASK5', 'ticks': 164587, 'data': [-30,-35,-30,-37], ...}`.

The PC parser auto-detects these by the absence of a `v` field and the leading
character. The phone only needs to **consume** them if it must interoperate with old
firmware; it should always **emit** v1.0. For v1 of the Android app, supporting legacy
ingest is optional and can be deferred.

---

## 7. Proposed BLE GATT mapping (not yet implemented in firmware)

The firmware has no BLE code today. This is the proposed design to coordinate with
whoever takes the firmware BLE work. The goal is one more transport, not a second
protocol: field semantics stay identical to the JSON above.

A full JSON sensor frame is roughly 1.2 KB, which exceeds a comfortable single BLE
notification even with a negotiated 247-byte MTU, so for BLE the sensor frame should be
a **compact binary** encoding of the same values rather than JSON:

```
Sensor notification payload (little-endian):
  uint8   version      (1)
  uint8   device_type  (0 = flexgrid)
  uint16  seq          (rolling sequence number)
  uint32  ts_ms        (device ticks, same semantics as JSON "ts")
  uint16  values[60]   (row-major R0C0..R3C14, same order as section 2.2)
Total: 8 + 120 = 128 bytes, fits one notification at MTU 247.
```

Suggested GATT layout:

| Characteristic | Properties | Payload |
|---|---|---|
| Sensor frame | Notify | The 128-byte binary frame above. |
| Device status | Read, Notify | `vbat`, `pct`, scan rate, firmware version. |
| Command | Write | start/stop stream, set scan interval. |
| Label frame | Notify | Reserved, only if LASK5 also gets BLE (Wi-Fi only today). |

Use one custom 128-bit service UUID with the characteristics above. The phone's BLE
parser then produces the same internal frame object as the UDP path, so everything
downstream (heatmap, capture, matching, export) is transport-agnostic.

This section is a proposal pending firmware-side agreement; treat the UDP JSON sections
above as authoritative and this one as a draft to ratify with the firmware effort.

---

## 8. Control-plane message taxonomy

The 2026-06-17 direction update expanded the protocol from the data plane (sensor, label,
and Quest frames in sections 2 through 5) to a full control plane. The Wi-Fi control plane
is now **implemented in V4 firmware** (FlexGridV4-Firmware/lib/{discovery,commands,
network_manager,subscribers}.py) and this section is updated to match what shipped. The
phone speaks it via `transport/TcpControlChannel.kt`, verified against a live device with
`tools/v4_probe.py`. BLE control is still draft (firmware-gated, section 9).

### 8.0 Conventions

- Control messages reuse the v1.0 envelope (`v`, `type`, `id`, `data`) plus a `msg_id` on
  anything that expects a reply.
- Reliable channel (V4): on Wi-Fi, control is a **raw TCP socket carrying newline-delimited
  JSON** on the device's cmd port (announce `services.cmd`, default 8001), one JSON object
  per line. The firmware chose raw TCP over a WebSocket because it is far lighter on the
  ESP32 and the hubs (Android, Python) open a plain socket trivially. The pre-V4 WebSocket
  design is preserved under `legacy/` in the Connect repo for reference. On BLE, control
  uses GATT write (hub to device) and notify or read (device to hub); still draft.
- Acks: every command gets one ack line. Success/failure is a **top-level `status` field**
  (`"ok"` or `"error"`), with `msg_id` echoed and the per-verb payload (or an error
  `message`) under `data`. Shape:
  `{"v":"1.0","type":"ack","status":"ok","msg_id":42,"data":{"verb":"set_scan_rate",...}}`.
- Encoding per transport: JSON on both Wi-Fi and BLE for all control messages; the 59 Hz
  sensor frame is also JSON today (the compact-binary option is a flagged decision, see
  section 9).

### 8.1 Announcement / discovery

Purpose: a device makes itself findable with no hardcoded hub IP.

Wi-Fi (primary): mDNS service record, type `_openmuscle._udp.local`, instance name =
device id, with TXT keys carrying a compact capability summary so a hub can list devices
without a follow-up query. Fallback: a UDP broadcast beacon to `255.255.255.255:3141`
about once per second while no hub is subscribed (for networks that block multicast).

Beacon / TXT payload:
```json
{"v":"1.0","type":"announce","id":"flexgrid-a3f9c1","role":"source","dev":"flexgrid",
 "fw":"0.1.7","transports":["wifi"],"caps":["sensor","status","cmd"],
 "matrix":[15,4],"services":{"sensor":3141,"cmd":8001}}
```
- `role` (`source` / `hub` / `actuator`) and the cross-device topology are defined
  in `DEVICE-DISCOVERY-SPEC.md`; that doc is authoritative for roles and discovery.
- `services` maps a capability to its port; the IP is taken from the packet source,
  never carried in the announce.
- V4 status: `role` and `services` shipped. The Android parser reads `services.sensor`
  (the UDP frame port) and `services.cmd` (the TCP command port), falling back to a
  top-level `port` only for legacy V3-style beacons that predate the services map. A live
  V4 announce looks exactly like the block above with `fw:"v4.0.0"` and
  `caps:["sensor","status","cmd","imu"]`.
- Beacon cadence: the device broadcasts ~1 Hz only while it has no subscriber, then goes
  quiet and resumes when the subscriber list empties.

BLE: standard BLE advertising carrying the OpenMuscle 128-bit service UUID; the scan
response carries the device id and a short capability byte. The phone scans for that
service UUID. V3 reports `transports:["wifi"]`; a V4 with BLE firmware reports
`["wifi","ble"]`.

### 8.2 Device info / capabilities

Purpose: full capability response to a hub query (the announce summary is deliberately
small). In V4 this is a **command verb**, not a separate message type: the hub sends
`get_info` over the TCP cmd channel and the device replies with an ack whose `data` carries
the info.

Request: `{"v":"1.0","type":"cmd","id":<hubId>,"msg_id":1,"data":{"verb":"get_info"}}`

Response (live V4):
```json
{"v":"1.0","type":"ack","status":"ok","msg_id":1,
 "data":{"verb":"get_info","id":"flexgrid-v3-02","dev":"flexgrid","fw":"v4.0.0",
   "matrix":[15,4],"caps":["sensor","status","cmd","imu"],"subscribers":[]}}
```
`matrix` is `[cols, rows]`. The phone maps this to `DeviceInfo` via
`ControlCodec.parseInfo`. (Battery / scan-rate-range / max_subscribers from the old draft
are not in the V4 get_info payload; battery rides the status frame, section 8.4.)

BLE: a GATT read characteristic would return the same object as JSON; still draft.

### 8.3 Commands (hub to device)

Purpose: change device state. Acked. V4 verbs (FlexGridV4-Firmware/lib/commands.py):
`subscribe`, `unsubscribe`, `heartbeat`, `get_info`, `set_scan_rate`, `start_stream`,
`stop_stream`, `reboot`. (`factory_reset` from the draft is not implemented in V4.)

`set_scan_rate` takes the scan interval in **milliseconds** (`interval_ms`, range 5..2000),
not Hz. The phone UI works in Hz and converts on the way out (`1000/hz`, rounded, clamped).
```json
{"v":"1.0","type":"cmd","id":"flexgrid-a3f9c1","msg_id":42,
 "data":{"verb":"set_scan_rate","interval_ms":17}}
```
```json
{"v":"1.0","type":"ack","status":"ok","msg_id":42,
 "data":{"verb":"set_scan_rate","interval_ms":17}}
```
An out-of-range or otherwise rejected command returns `status:"error"` with a human
message: `{"...","status":"error","data":{"verb":"set_scan_rate","message":"interval_ms out of range (5..2000): 3"}}`.

Multi-hub (subscribe / unsubscribe / heartbeat): a hub sends `subscribe` carrying the UDP
`port` it listens on, its `transport` (`"wifi"`), and a `hub_id`; the device omits the host
and uses the TCP source address. The device adds the hub to a subscriber list (max 4) and
unicasts each sensor frame to every subscriber. **V4 unicasts sensor frames only to
subscribers**, so a hub that never subscribes sees nothing (a V3-style broadcast device
still lights up the heatmap with no subscription). The subscription self-cleans: it expires
after ~5 s without a `heartbeat`, so the hub sends one ~1 Hz.
```json
{"v":"1.0","type":"cmd","id":"flexgrid-a3f9c1","msg_id":2,
 "data":{"verb":"subscribe","port":3141,"transport":"wifi","hub_id":"om-android-1a2b3c4d"}}
```
```json
{"v":"1.0","type":"ack","status":"ok","msg_id":2,
 "data":{"verb":"subscribe","accepted":true,"subscriber_count":1,"max_subscribers":4}}
```
Transport: Wi-Fi over the TCP cmd channel; BLE over a GATT write characteristic with the
ack via notify (still draft).

### 8.4 Status / heartbeat

Two separate things in V4:

- **Heartbeat** is a command verb (section 8.3) the hub sends ~1 Hz over the TCP cmd
  channel to hold its subscription. Ack: `{"...","status":"ok","data":{"verb":"heartbeat","refreshed":true}}`.
- **Status** is a ~1 Hz telemetry frame the device unicasts to subscribers over the **UDP
  sensor channel** (not the cmd channel). It reuses the sensor envelope with an empty
  `data` and the telemetry under `meta`:
  ```json
  {"v":"1.0","type":"flexgrid","id":"flexgrid-v3-02","ts":12345000,"data":{},
   "meta":{"pct":95,"vbat":4.15,"rssi":-65,"free_mem":245000,"scan_rate_hz":59}}
  ```

Time sync (the NTP-style ping-pong from the draft) is not implemented in V4; receive-time
matching (section 3) remains the basis for sensor-label pairing.

### 8.5 Session / recording control

Purpose: bracket a recording with metadata. **App-local in V4**: the firmware has no
`session` verb, so the phone records `SessionMeta` (user / location / intent) alongside the
CSV it builds and does not send it to the device. The transport layer keeps start/end hooks
for the recording layer to call; nothing goes on the wire. If a device or second hub ever
needs to observe sessions, a `session` verb can be added to the cmd channel later.

### 8.6 Device identity (minting)

Today identity is the `id` field (e.g. `flexgrid-v3-01`). Draft rule for uniqueness: mint
a UUID into NVS at first boot and derive a friendly display id `<board>-<6-char-hash>`
from it (e.g. `flexgrid-a3f9c1`). Two devices in the same building must never collide. The
friendly id is what the phone shows and what the user can override with a nickname.

---

## 9. Flagged decisions (need Tory's call)

These are real forks the direction update did not fully resolve. They are surfaced here
rather than silently chosen.

1. **Control-plane encoding on BLE: JSON or compact binary?** Direction update part 3 says
   every message type gets a compact-binary BLE representation; the Q2 answer (appendix of
   the direction update) says JSON for the control plane. Recommendation: JSON on both
   transports for the five control message types (small, low-frequency, fits one GATT
   notification at MTU 247, single shared decoder, debuggable), and compact binary only for
   the 59 Hz sensor frame. Open until ratified.
2. **Sensor-frame loss detection** needs a `seq` field the firmware does not emit yet
   (section 2.1). Adding it is firmware work; until then the phone detects gaps
   heuristically on `ts`.
3. **Discovery / subscribe shifts the phone** from a passive broadcast listener to an
   active subscriber that must heartbeat to stay subscribed. Phase 1 still works via the
   broadcast fallback, but the subscriber lifecycle is new transport-layer responsibility
   (sections 8.3 and 8.4).
