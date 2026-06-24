# Open Muscle Connect: Architecture Proposal

Status: draft for Tory's review. No code written yet. This document records the
decisions made so far, the design that follows from them, and a phase plan. It is the
gate before scaffolding the Android project.

It is grounded in a source read of the firmware, the PC app, and the VR app. The decoded
wire format lives in `docs/WIRE-FORMAT.md`; read that alongside this.

---

## 1. Decisions already made

| # | Decision | Rationale |
|---|---|---|
| Framework | **Native Kotlin + Jetpack Compose** | Best access to BLE, on-device ML runtime, and low-latency rendering at the wearable's 59 Hz scan rate. No iOS path in v1; that is an accepted tradeoff. |
| ML / training split | **Training stays on the PC; the phone captures and exports.** The phone mirrors the PC model for inference rather than training its own. | The PC already trains a RandomForest. Keeping training there means zero model divergence and no reimplementation. The phone's job is to record PC-compatible sessions and ship them to the PC. |
| Wire format | **Keep the existing OpenMuscle v1.0 JSON over UDP.** | Firmware emits it and the PC consumes it; there is no benefit to redesigning. The phone is one more consumer. |
| Transports | **Both Wi-Fi and BLE are first-class for v1** (direction update 2026-06-17). | Devs pick whichever fits: Wi-Fi for high-bandwidth lab use, BLE for self-contained field use. BLE is app-side-ready but gated on V4 firmware that does not exist yet; V3 stays Wi-Fi only. |
| Discovery | **No hardcoded hub IPs; discovery is part of the protocol** (direction update). | mDNS + UDP broadcast on Wi-Fi, advertising + GATT scan on BLE. Adds a new phase 1.5. |
| First step | **Docs first, scaffold after review.** | This document plus `WIRE-FORMAT.md` are the deliverable; scaffolding waits on your sign-off. |

A consequence worth stating plainly: the PC model is a scikit-learn RandomForest saved
as a Python pickle, which Kotlin cannot load. "Mirror the PC" for inference therefore
means the PC exports its trained model to **ONNX** (via `skl2onnx`) and the phone runs it
with **ONNX Runtime Mobile**. Same model, portable container, no divergence. This affects
the PC side (it needs an ONNX export step) and is a coordination item, not a phone-only
change.

---

## 2. What the phone is, in one sentence

A field capture-and-visualize tool: it connects to the FlexGrid, shows the live sensor
heatmap, records labeled training sessions in the PC's exact CSV format, and hands those
sessions to the PC to train. Inference (loading the PC's exported model) and VR pairing
come later, not in v1.

This is a narrower and more honest v1 than "do everything the PC does on the phone,"
and it matches the decision that training lives on the PC.

---

## 3. Component design

```
   FlexGrid wearable                         Phone: Open Muscle Connect
   (UDP JSON today, BLE later)
            |                          +-------------------------------------+
   sensor   |  UDP 3141 / BLE notify   |  TransportLayer                     |
   frames   +------------------------> |   - UdpReceiver (port 3141)         |
            |                          |   - BleClient (later)               |
   LASK5    |  UDP 3141                |   -> emits a common SensorFrame      |
   labels   +------------------------> |      regardless of transport        |
                                       +------------------+------------------+
                                                          |
                                       +------------------v------------------+
                                       |  FrameBus (in-memory, coroutine     |
                                       |  Flow of timestamped frames)        |
                                       +---+----------+-----------+----------+
                                           |          |           |
                              +------------v--+  +----v-----+  +--v---------------+
                              | HeatmapView   |  | Capture  |  | StatusView       |
                              | (Compose      |  | Recorder |  | (battery, rssi,  |
                              | canvas, 15x4) |  | + Matcher|  |  link, Hz)       |
                              +---------------+  +----+-----+  +------------------+
                                                      |
                                              +-------v----------+
                                              | CSV exporter      |
                                              | (PC-compatible)   |
                                              +-------+----------+
                                                      |
                                              share / HTTP upload to PC
```

### 3.1 Transport layer

One interface, two implementations, so everything above it is transport-agnostic:

- `UdpReceiver`: binds UDP 3141, parses OpenMuscle v1.0 JSON, routes by `type`
  (`flexgrid` vs `lask5`), emits a normalized `SensorFrame` or `LabelFrame`. Buildable
  and testable today against the PC's packet simulator, no hardware needed.
- `BleClient` (later phase): subscribes to the proposed GATT notify characteristic,
  decodes the compact binary frame from `WIRE-FORMAT.md` section 7, emits the same
  `SensorFrame`. Depends on the firmware BLE work landing.

Both stamp each frame with the phone's monotonic receive time, because device
timestamps are not a shared clock (see `WIRE-FORMAT.md` section 3).

### 3.2 Heatmap

Compose `Canvas` drawing the 15x4 grid, plasma-style colormap, values 0 to 4095,
auto-detecting matrix dimensions from the first frame (handles 15x4 and legacy 16x4).
Render on a throttled tick (about 20 to 30 Hz is enough for the eye) even though frames
arrive at roughly 50 to 59 Hz; decouple draw rate from receive rate so the UI thread is
never blocked.

### 3.3 Capture recorder and matcher

This is the core of v1. It mirrors the PC's `TemporalMatcher`:

- Buffer incoming label frames (LASK5, or manual taps, or later Quest).
- For each sensor frame, find the nearest label within the window (100 ms for LASK5),
  match on receive time, drop sensor frames with no label in window.
- Append a row to the session in memory and persist incrementally so a crash mid-session
  never loses more than the last frame (a v1 reliability requirement from the scope).
- Manual labels write into the same `label_0..3` columns, so a manually-labeled session
  is identical to a LASK5-labeled one to the PC trainer.

### 3.4 CSV export and transfer to PC

- Write the PC-exact CSV defined in `WIRE-FORMAT.md` section 5
  (`timestamp,R0C0..R3C14,label_0..3`).
- Transfer options, simplest first:
  1. Save to a shared folder / Android share sheet, user moves the file. Zero PC change.
  2. HTTP POST to a small upload endpoint on the PC web server. Needs a tiny PC-side
     addition; nicer UX. Flag for coordination, not required for v1.
- Local session storage in SQLite (Room) holds session metadata and lets the user review
  and re-export.

### 3.5 Inference (later phase, mirror PC via ONNX)

When inference comes to the phone: the PC adds a "train and export to ONNX" step, the
phone downloads the `.onnx`, and runs it with ONNX Runtime Mobile on each incoming
sensor frame to predict the 4 piston values, then renders a simple predicted hand pose.
No model is trained on the phone. This keeps the phone an exact mirror of whatever the PC
last trained.

Status: the export-and-parity bridge is implemented and verified in
`tools/export_onnx.py` (scikit-learn RandomForest to ONNX to ONNX Runtime; predictions
match within ~3.5e-4). The phone runtime is `app/.../ml/OnnxInference.kt`. The feature
vector it feeds the model is `MatrixUtil.flattenRowMajor(frame.matrix)`, the same
row-major order as the training CSV columns, so train-time and infer-time features align.

### 3.6 VR pairing (later phase, phone as hub)

Recommended topology: the phone is the hub. It already has the link to the wearable and a
Wi-Fi link to the Quest. The phone hosts the same two WebSocket endpoints the PC does
today (`/ws/quest` inbound for hand-tracking labels, `/ws/live` outbound for the snapshot
the headset renders). The Quest code then does not care whether it is talking to the PC
or the phone. This is a recommendation, not yet ratified; it depends on how you want the
VR app to evolve.

### 3.7 Discovery (Wi-Fi and BLE)

Devices no longer assume a hardcoded hub IP (direction update part 1); the phone discovers
them.

- **Wi-Fi:** mDNS resolver as primary (service `_openmuscle._udp.local`), with a UDP
  broadcast-beacon listener as fallback for networks that block multicast (corporate
  Wi-Fi, captive portals, routers with multicast disabled). Both are native on Android.
- **BLE:** scan for the OpenMuscle service UUID in advertisements, read the scan response
  for device id and capabilities.
- **Multi-device UI:** when several FlexGrids are nearby, list each by its `id` (e.g.
  `flexgrid-a3f9c1`) plus a user-set nickname, with live RSSI and a transport badge
  (Wi-Fi / BLE). The user taps which to connect. Identity minting is in
  `WIRE-FORMAT.md` section 8.6.

This is the new phase 1.5. Without it the phone only sees data when firmware is hardcoded
to its IP, which is the world we are leaving.

### 3.8 Connection mode and the extended TransportLayer

Both Wi-Fi and BLE are first-class for v1 (direction update part 2), so the user chooses a
connection mode and the abstraction must span commands and status, not just sensor frames.

User-facing: a first-run choice ("Connect via Wi-Fi" / "Connect via Bluetooth") with a
persisted default and a manual override in settings. The choice drives which discovery
sequence runs.

The `TransportLayer` interface, implemented by both `WiFiTransport` and `BleTransport`:

```kotlin
interface TransportLayer {
    fun discover(): Flow<DiscoveredDevice>           // mDNS + broadcast, or BLE scan
    suspend fun connect(device: DiscoveredDevice): Connection
    fun sensorFrames(): Flow<SensorFrame>            // UDP 3141 / GATT notify
    fun labelFrames(): Flow<LabelFrame>              // UDP 3141 / GATT notify
    fun status(): Flow<DeviceStatus>                 // WS push / GATT notify, ~1 Hz
    suspend fun getInfo(): DeviceInfo                 // capability query
    suspend fun sendCommand(cmd: Command): Ack        // WS /cmd / GATT write
    suspend fun startSession(meta: SessionMeta)
    suspend fun endSession()
    suspend fun disconnect()
}
```

- `WiFiTransport`: UDP for sensor and label frames; a WebSocket to the device `/cmd`
  endpoint for commands, status, and session control. It also sends the periodic heartbeat
  that keeps the phone on the device subscriber list.
- `BleTransport`: GATT notify for sensor, label, and status; GATT write for commands and
  session control. Same internal objects out, so HeatmapView, CaptureRecorder, and the
  rest never know which transport is active.

Encoding: sensor frames are compact binary on BLE and JSON on Wi-Fi; control-plane
messages are JSON on both (flagged for ratification, `WIRE-FORMAT.md` section 9).

Two caveats worth stating plainly:
- BLE multi-hub is radio-limited (~3 centrals); Wi-Fi unicast-to-N scales. For "phone
  records while desktop watches," Wi-Fi is the right transport.
- BLE end-to-end needs V4 firmware that does not exist yet and is not scheduled, so the
  BLE path is built and unit-tested against a mock now, and is demoable only when firmware
  lands.

---

## 4. Phase plan

Each phase is independently demonstrable. Phases 1 and 2 need no new hardware and no
firmware changes; they run against the PC packet simulator and real V3 boards over Wi-Fi.

| Phase | Deliverable | Depends on | Demoable result |
|---|---|---|---|
| 0 | This proposal plus `WIRE-FORMAT.md` (incl. control-plane draft) | nothing | You review and approve the plan |
| 1 | Kotlin/Compose scaffold + UDP receiver + live heatmap | nothing | Live FlexGrid heatmap over Wi-Fi (hardcoded / broadcast) |
| 1.5 (NEW) | mDNS + UDP broadcast discovery + multi-device picker UI | phase 1 | Phone finds FlexGrids with no hardcoded IP |
| 2 | Session capture + temporal matcher + PC-compatible CSV export | phase 1 | Record a session on the phone, train it on the PC unchanged |
| 2.5 (NEW) | Wi-Fi control channel (`/cmd` WebSocket): commands, status, heartbeat, session control, subscribe | phase 1.5 | Phone starts/stops streaming, sets scan rate, multi-hub subscribe |
| 3 | BLE transport at full parity with Wi-Fi | V4 firmware BLE work (unscheduled) | Phone streams from the wearable with no Wi-Fi network |
| 4 | On-phone inference via ONNX-exported PC model + predicted hand pose | PC ONNX export step | Phone predicts hand pose live from a PC-trained model |
| 5 | VR pairing (phone as hub) | VR app changes | Quest renders from the phone instead of the PC |

The control-plane message types are specified at draft level now (`WIRE-FORMAT.md`
section 8); the Wi-Fi side is implemented in phase 2.5 as each capability is hit, and phase
3 mirrors them on BLE.

v1, per the scope's definition of done, is roughly phases 1 through 3 (with 1.5 and 2.5)
plus a basic version of 4. BLE in phase 3 is firmware-gated, so it may land after the Wi-Fi
feature set even though it is designated first-class. Phase 5 and cloud sync are explicitly
post-v1.

---

## 5. Still-open questions for you

1. **CSV transfer mechanism**: start with file-share only (zero PC change), or do you
   want the HTTP-upload-to-PC path in v1? I lean file-share first, upload as a fast
   follow.
2. **BLE payload**: ratify the compact-binary GATT design in `WIRE-FORMAT.md` section 7
   with whoever does the firmware BLE work, or keep JSON-over-BLE with fragmentation? I
   lean binary for the sensor frame.
3. **VR topology**: confirm phone-as-hub, or do you want the Quest to keep talking to the
   PC and treat the phone as an additional sensor source?
4. **Naming and repo**: still "Open Muscle Connect"? And hold off on `git init` until you
   confirm the repo name and MIT license, per your own rule.
5. **Control-plane encoding on BLE (flagged conflict)**: the direction update says every
   control message gets a compact-binary BLE representation, but the Q2 answer says JSON
   for the control plane. I recommend JSON on both transports for the five control message
   types (small, low-frequency, fits one GATT notification, single decoder) and compact
   binary only for the 59 Hz sensor frame. Your call; see `WIRE-FORMAT.md` section 9.
6. **BLE is first-class but firmware-gated**: the app can implement BLE at parity now, but
   no firmware speaks BLE yet and V4 BLE is not scheduled, so a working BLE demo may slip
   past v1 even though the app supports it. Flagging so "BLE first-class v1" is read as
   app-side-ready, hardware-pending, not "BLE ships in v1 guaranteed."

None of these block phases 1, 1.5, or 2. If you approve the plan, the next action is the
Kotlin/Compose scaffold plus the UDP receiver and heatmap.
