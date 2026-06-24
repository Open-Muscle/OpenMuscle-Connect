# OpenMuscle Device Discovery and Transport Spec (for the firmware team)

This is the cross-device coordination spec: how every OpenMuscle device finds
the others with no hardcoded IP addresses, over both Wi-Fi and Bluetooth, using
one shared protocol. It covers the FlexGrid, the LASK5, the OpenHand robot, the
phone app (Connect), the PC app (Lab), and the VR headset.

It is written for firmware implementers. The exact byte-level and JSON layouts of
each message live in the companion `WIRE-FORMAT.md`; this document defines the
roles, the discovery flow, and what each device must implement. Where this and
`WIRE-FORMAT.md` overlap, `WIRE-FORMAT.md` is authoritative for the wire bytes and
this document is authoritative for roles and discovery.

Status: draft for review. Sections marked "RATIFY" are decisions to settle with
the team before firmware locks them in (collected at the end).

---

## 1. Goals

1. **No hardcoded IPs.** Today the FlexGrid bakes a target IP into firmware and
   the PC bakes the robot's IP into a CLI flag. We replace both with discovery.
2. **One protocol, every device.** FlexGrid, LASK5, OpenHand, phone, PC, and VR
   all speak the same OpenMuscle v1.0 message format.
3. **Wi-Fi and Bluetooth, both first-class.** Wi-Fi for lab/high-rate use, BLE
   for self-contained field use with no network. A device may support one or both.
4. **Many hubs at once.** A device can stream to the phone and the desktop
   simultaneously during development.

---

## 2. Device roles

Every device has exactly one **role**. The role drives discovery and routing.

| Role | Devices | Produces | Consumes |
|---|---|---|---|
| `source` | FlexGrid, LASK5 | sensor frames (FlexGrid), label frames (LASK5) | commands, subscriptions |
| `hub` | Phone (Connect), PC (Lab) | predictions, snapshots, commands | sensor + label frames |
| `actuator` | OpenHand robot, VR headset | physical/visual hand pose | prediction frames from a hub |

Notes:
- The OpenHand robot is an `actuator`: the hub sends it predicted servo angles.
- The VR headset is both an `actuator` (it renders the predicted/real hand) and,
  when it streams Quest hand-tracking back for training, a `source`. It advertises
  `role: "actuator"` with `caps` listing `labels` so a hub knows it can also send
  labels (see section 6).
- A `hub` is also discoverable so that consumers (VR) can connect TO it.

---

## 3. Topology and data flow

```
   SOURCES                        HUBS                         ACTUATORS
 +-----------+   sensor frames  +--------------+  predictions  +-------------+
 | FlexGrid  | ---------------> |  Phone       | ------------> | OpenHand    |
 | (source)  |   (subscribe)    |  (Connect)   |   PC,a1..a5   | robot       |
 +-----------+                  |              |               +-------------+
                                |  PC (Lab)    |
 +-----------+   label frames   |  (hub)       |  predictions  +-------------+
 | LASK5     | ---------------> |              | ------------> | VR headset  |
 | (source)  |   (subscribe)    +--------------+   snapshot/   | (actuator)  |
 +-----------+                       ^   |          pose       +-------------+
                                     |   |                          |
                                     |   +-- VR connects to hub ----+
                                     +------ VR streams labels ------+
```

Three flows, all bootstrapped by discovery (no IPs configured anywhere):
1. **source -> hub**: a hub discovers a source, sends `subscribe`; the source
   unicasts its frames to every subscribed hub.
2. **hub -> actuator**: a hub discovers an actuator and pushes prediction frames
   to the address the actuator advertised.
3. **consumer -> hub**: VR discovers a hub and connects to it (WebSocket) to
   receive the live snapshot and to stream hand-tracking labels back.

---

## 4. Discovery

A device announces itself the moment it has a transport up, and re-announces
periodically. Discovery never depends on a configured address.

### 4.1 Wi-Fi discovery (mDNS primary, broadcast fallback)

**Primary: mDNS / DNS-SD.** Register one service:
- Service type: `_openmuscle._udp.local`
- Instance name: the device id (e.g. `flexgrid-a3f9c1`)
- TXT record: the compact announce summary (keys below)

**Fallback: UDP broadcast beacon.** Some networks block multicast (corporate
Wi-Fi, captive portals, routers with multicast disabled). When a device has no
active subscriber, it also broadcasts an announce JSON to `255.255.255.255:3141`
about once per second. Hubs listen on 3141 and pick these up. Stop broadcasting
once at least one hub has subscribed (to keep the channel quiet).

**Announce payload** (the TXT record carries these as keys; the broadcast carries
the same as JSON):
```json
{
  "v": "1.0",
  "type": "announce",
  "id": "flexgrid-a3f9c1",
  "role": "source",
  "dev": "flexgrid",
  "fw": "0.2.0",
  "transports": ["wifi"],
  "caps": ["sensor", "status", "cmd"],
  "matrix": [15, 4],
  "services": { "sensor": 3141, "cmd": 8001 },
  "ts": 12345
}
```
- `role` and `dev` drive routing (section 2). `caps` lists optional capabilities.
- `services` maps a capability to the UDP/TCP port it lives on. The IP is never in
  the announce; the listener takes it from the packet/mDNS source address.
- `matrix` (FlexGrid only) is `[cols, rows]`, so a hub can size itself before the
  first frame.

### 4.2 BLE discovery (advertising + GATT scan)

A BLE-capable device advertises the OpenMuscle 128-bit service UUID (see
`WIRE-FORMAT.md` section 7). The advertisement payload includes:
- The OpenMuscle service UUID (so a hub filters its scan on it).
- One **role byte** in the manufacturer/service data (0 = source, 1 = hub,
  2 = actuator) plus a **device-type byte** (0 = flexgrid, 1 = lask5,
  2 = openhand, ...).
- The scan response carries the device id string.

The phone/PC scans for that service UUID, reads role+type from the advert, and
connects (GATT) to the ones it wants. No address is configured.

### 4.3 Device identity (how ids are minted)

Each device mints a stable id once and reuses it forever:
- At first boot, generate a UUID and store it in NVS / flash.
- Derive a friendly id `<dev>-<6 hex>` from it, e.g. `flexgrid-a3f9c1`,
  `lask5-7b21e0`, `openhand-0c4419`.
- Two devices in the same building must never collide. The friendly id is what
  hubs display; users can nickname it on their end.

---

## 5. Connection and subscription model

This is what removes the hardcoded IPs.

### 5.1 source -> hub (subscribe, then unicast)

1. Hub discovers a source (section 4) and learns its `services.cmd` port and IP.
2. Hub opens the source's command channel and sends `subscribe` with the hub's
   own receive address and port:
   ```json
   {"v":"1.0","type":"cmd","id":"flexgrid-a3f9c1","msg_id":1,
    "data":{"verb":"subscribe","host":"192.168.1.50","port":3141,"transport":"wifi"}}
   ```
3. The source adds `(host, port)` to a small subscriber list (cap ~4) and from
   then on **unicasts** each sensor/label frame to every subscriber.
4. The hub sends a heartbeat about once per second; a subscription that goes
   ~5 s without a heartbeat is dropped, so the list self-cleans when a hub leaves.
5. `unsubscribe` removes it immediately.

This replaces the current "bake one destination IP into firmware" approach. The
source no longer needs to know any address ahead of time.

> Runnable reference: `tools/discovery_demo.py` implements this exact flow
> (announce -> subscribe -> unicast -> heartbeat keepalive -> timeout drop) in
> stdlib Python. `python tools/discovery_demo.py --selftest` asserts it; `--source`
> and `--hub` let you point a real device or hub at it. Read it as pseudo-code for
> the firmware side.

### 5.2 hub -> actuator (push to the advertised address)

1. Hub discovers an actuator (e.g. OpenHand) and learns its `services.ingest`
   port and IP from the announce.
2. While inference is running, the hub pushes prediction frames to that address.
   For the OpenHand robot the payload is the existing servo CSV (section 6.3), so
   no robot-side format change is needed; only the address now comes from
   discovery instead of a hardcoded flag.
3. RATIFY: optionally the actuator can `subscribe` to a hub (pull model) instead
   of being pushed to. Push matches today's PC behavior and is the recommendation.

### 5.3 consumer -> hub (VR connects to the hub)

The VR headset discovers a `hub` and connects to its WebSocket services
(`services.snapshot` for the live pose to render, `services.labels` to stream
Quest hand-tracking back for training). Browsers cannot do UDP, so VR uses
WebSocket; this is unchanged from how VR talks to the PC today.

---

## 6. Message taxonomy

All messages share the v1.0 envelope: `v`, `type`, `id`, `ts`, and a `data`
object (plus optional `meta`). Full layouts in `WIRE-FORMAT.md`; summary here.

### 6.1 Control plane (low rate, reliable)

| type | direction | purpose |
|---|---|---|
| `announce` | device -> network | discovery (section 4) |
| `cmd` / `ack` | hub -> device | verbs `subscribe`, `unsubscribe`, `heartbeat`, `get_info`, `set_scan_rate`, `start_stream`, `stop_stream`, `reboot`; every cmd is acked with a top-level `status` |
| `status` | device -> hub | ~1 Hz battery, RSSI, free memory, scan rate; rides the UDP sensor channel as a frame with empty `data` and telemetry under `meta` |

V4 reality (FlexGridV4 firmware): `get_info` is a **cmd verb** (not a separate
`info` type); its reply is an ack carrying the capability payload under `data`.
`set_scan_rate` takes `interval_ms` (5..2000), not Hz. There is no device-side
`session` verb; the phone keeps session metadata locally (it owns recording).
`factory_reset` from the early draft is not implemented.

Transport for the control plane: Wi-Fi over a raw **TCP channel carrying
newline-delimited JSON** on the device cmd port (announce `services.cmd`, default
8001), one object per line, reliable so commands are never silently dropped. BLE
over GATT write (command) and notify (ack); still draft. The pre-V4 WebSocket
design is preserved under `legacy/` in the Connect repo.

### 6.2 Data plane (high rate)

- **Sensor frame** (FlexGrid -> hub): the 60-value 15x4 matrix. JSON on Wi-Fi
  UDP; compact 128-byte binary on BLE (see 6.4). A rolling `seq` is added so a
  hub can detect dropped frames.
- **Label frame** (LASK5 -> hub): 4 piston values plus optional joystick. JSON on
  Wi-Fi UDP; GATT notify on BLE.

### 6.3 Prediction / actuation (hub -> actuator)

- **OpenHand robot** (OpenHand V2, ESP32-S2, 5 fingers via PCA9685): keep the
  existing payload so the servo logic is unchanged. UDP datagram, ASCII:
  ```
  PC,a1,a2,a3,a4,a5
  ```
  where `a1` = thumb and `a2..a5` = fingers, each an integer servo angle 0..179.
  This is exactly the firmware's existing `PC` device config (linear 0..179
  passthrough). Today the hub maps LASK5 joystick X -> thumb and the 4 piston
  predictions -> fingers (reversed: thumb, P4, P3, P2, P1; 0..1 normalized ->
  0..179). The robot advertises `role:"actuator"`, `dev:"openhand"`,
  `caps:["actuate"]`, `services:{"ingest":3145}`, and `"format":"servo_csv_v1"`
  so the hub knows to send this exact CSV.
  Current reality: the firmware already has a "UDP Listen" mode that joins the
  `OpenMuscle` SSID and listens on **3145** for this CSV, plus an ESP-NOW mode for
  the LASK5 direct path (firmware in `OpenMuscle-Software/embedded/devices/
  openhand_v2/`). The ONLY new work is adding the announce so the hub learns its
  address instead of being given it via the PC `--hand IP` flag.
- **VR headset**: receives the predicted hand pose as part of the hub's WebSocket
  snapshot and renders it (no change to today's VR path).
- RATIFY: a future JSON `prediction` message (normalized finger/piston vector)
  could replace the CSV for new actuators; the CSV stays for the current robot.

### 6.4 BLE binary sensor frame

For BLE the 59 Hz sensor frame is a compact 128-byte binary record (a full JSON
frame is too big for one notification). Little-endian:
```
uint8  version   (1)
uint8  device_type (0 = flexgrid)
uint16 seq
uint32 ts_ms
uint16 values[60]   (row-major R0C0..R3C14)
```
The control plane (announce, cmd/ack, status) stays JSON even on BLE (small,
low-rate, one notification each), so a single decoder handles it on both
transports. See RATIFY item 1.

---

## 7. Transports and ports

| Capability | Wi-Fi | BLE |
|---|---|---|
| Discovery | mDNS `_openmuscle._udp` (5353) + UDP broadcast beacon (3141) | service-UUID advertising + GATT scan |
| Sensor / label stream | UDP 3141, JSON | GATT notify, 128-byte binary |
| Commands + acks | TCP newline-delimited JSON, cmd port (default 8001) | GATT write + notify, JSON |
| Status (device -> hub) | UDP sensor channel, ~1 Hz, telemetry under `meta` | GATT read/notify, JSON |
| Heartbeat (hub -> device) | `heartbeat` cmd verb on the TCP channel, ~1 Hz | GATT write, JSON |
| Prediction to robot | UDP to advertised `ingest` port, `PC,a1..a5` | (out of scope; robot is Wi-Fi) |
| Snapshot/labels to VR | WebSocket (8000 family) | (browsers are Wi-Fi only) |

Port assignments are defaults; the actual port a device uses is advertised in its
`services` map, so nothing is hardcoded on the consuming side.

Out of scope but noted: the LASK5 -> OpenHand direct **ESP-NOW** path stays as a
separate low-latency link between those two ESP32 devices. Discovery here is about
IP/BLE hub coordination, not ESP-NOW.

---

## 8. What each device must implement

### FlexGrid (source)
- Mint a stable id (section 4.3).
- Announce via mDNS + broadcast (Wi-Fi); advertise the service UUID (BLE, V4+).
- Accept `subscribe`/`unsubscribe`/heartbeat; maintain a subscriber list (~4) and
  unicast sensor frames to all subscribers. Drop a hardcoded destination IP.
- Add a rolling `seq` to sensor frames.
- Respond to `get_info`, `set_scan_rate`, `start_stream`/`stop_stream`; send
  ~1 Hz `status`.
- BLE: expose the GATT service (sensor notify, status, command) per WIRE-FORMAT 7.

### LASK5 (source)
- Same discovery + subscribe + status as the FlexGrid.
- Stream label frames (4 piston values + optional joystick) to subscribers.
- Keep the existing ESP-NOW path to the OpenHand untouched.

### OpenHand robot (actuator, ESP32-S2)
- ADD: mint a stable id and announce `role:"actuator"`, `dev:"openhand"`,
  `services:{"ingest":3145}`, `"format":"servo_csv_v1"` (mDNS + broadcast, while
  in UDP mode).
- UNCHANGED: the existing UDP Listen mode (joins `OpenMuscle` SSID, port 3145),
  the `parse_packet()` formats (`PC,a1..a5` CSV, stringified list, bare CSV), the
  `DEVICES` config dict, the PCA9685 finger mapping, and the ESP-NOW path from the
  LASK5. This device only needs the announce bolted on; nothing else changes.

### Phone (Connect) and PC (Lab) hubs
- Discover sources and actuators (mDNS + broadcast + BLE scan).
- Subscribe to sources, ingest + train/infer, push predictions to actuators,
  serve the VR snapshot/labels WebSocket.
- Announce `role:"hub"` so VR can find them. (The phone app already implements the
  discovery, subscribe model, and the heatmap/capture/inference path.)

### VR headset (actuator + label source)
- Discover a `hub`; connect to its snapshot WebSocket to render the predicted/real
  hand; optionally stream Quest hand-tracking back as labels.

---

## 9. Time sync and loss detection

- **Time sync**: device clocks are not shared (the firmware `ts` is ms-since-boot;
  the phone/PC use wall clock). Frames are paired on the hub's receive time. Add
  an NTP-style ping-pong to the `status`/heartbeat exchange (hub sends its clock,
  device echoes it next to its own `ts`) so a hub can compute and display a device
  clock offset. This does not change frame pairing; it is for telemetry/debugging.
- **Loss detection**: the `seq` field on sensor frames lets a hub count drops on
  lossy Wi-Fi. A dropped 59 Hz frame is a ~17 ms gap, acceptable; commands must
  use the reliable channel, never UDP.

---

## 10. Decisions to RATIFY with the team

1. **Control-plane encoding on BLE: JSON or compact binary?** Recommendation: JSON
   on both Wi-Fi and BLE for all control messages (announce/info/cmd/status/
   session), since they are small, low-rate, and fit one GATT notification at
   MTU 247; only the 59 Hz sensor frame goes binary on BLE. This keeps a single
   decoder and stays debuggable.
2. **Robot adopts the protocol vs stays a dumb consumer.** RESOLVED (confirmed
   against the OpenHand V2 firmware): it is an ESP32-S2 that already runs a UDP
   mode on 3145 and accepts the `PC,a1..a5` CSV, so it only needs the announce
   added; servo logic and the packet parser stay byte-identical. A JSON
   `prediction` message is reserved for future actuators.
3. **Push vs pull for actuators.** Recommendation: hub pushes to the discovered
   actuator address (matches today's PC behavior). Pull (actuator subscribes to a
   hub) is optional.
4. **mDNS service taxonomy.** Recommendation: one service type
   `_openmuscle._udp` with a `role` TXT key, rather than a service type per role,
   so each device does exactly one mDNS registration.
5. **Subscriber cap and timeout.** Recommendation: ~4 Wi-Fi subscribers,
   5 s heartbeat timeout. BLE is radio-limited (~3 centrals); document the cap.
6. **BLE on V3 vs V4.** V3 firmware stays Wi-Fi only; BLE GATT is V4-onward work.
   Confirm this is acceptable so the app can label BLE as "V4 and newer".

When these are settled, fold the answers into `WIRE-FORMAT.md` (the byte-level
spec) and the firmware can implement against it.
