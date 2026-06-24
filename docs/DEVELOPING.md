# Developing Open Muscle Connect

How to build, run, and verify the app. For implementation status and the loop
journal, see `BUILD-LOG.md`; for the protocol, `WIRE-FORMAT.md`.

## Prerequisites

- Android Studio (Koala or newer); JDK 17+ (Android Studio's bundled JBR 21 is what
  the CLI builds use here). The project targets AGP 8.7.3 / Gradle 8.11.1 /
  Kotlin 2.0.21, compileSdk 35, minSdk 26.
- Python 3.12+ for the verifier tools (and `pip install skl2onnx onnxruntime`
  for the ONNX export tool).
- A phone (or emulator) on the same Wi-Fi as the dev machine.

## Build and run

1. Open the repo root in Android Studio. On first sync it generates the missing
   `gradle/wrapper/gradle-wrapper.jar` (not committed; it is a binary). If you
   prefer the CLI and have a system Gradle, run `gradle wrapper` once, then
   `./gradlew assembleDebug`.
2. Run the app on the phone.
3. Feed it without hardware:
   `python tools/openmuscle_sim.py --target <phone-ip> --announce`
   The device appears in the picker (from the announce beacon, or just from its
   sensor frames); pick it, or tap "Show any device", to reach the heatmap.
4. Exercise the control channel without hardware: run `python tools/cmd_server.py`
   (a V4 TCP reference device on `:8001`); the phone's subscribe/heartbeat/get_info
   round-trip against it just like the real firmware.

### Against a real V4 FlexGrid

The V4 firmware speaks the live protocol the app now targets: it announces over a
UDP broadcast beacon, serves a TCP command channel (newline-delimited JSON, default
port 8001), and unicasts sensor frames only to subscribers.

- Sanity-check any device on the LAN from your PC first:
  `python tools/v4_probe.py --ip <device-ip>`. It listens for the announce, opens
  the TCP channel, `get_info` + `subscribe`, receives frames, then `unsubscribe`s.
  Expect `RESULT: PASS`. (The probe sends byte-identical message shapes to what the
  app's `ControlCodec` emits.)
- On the phone: join the same Wi-Fi as the FlexGrid, open the app, pick the device
  in the picker (e.g. `flexgrid-v3-02`). Selecting it sends a real `subscribe`, so
  V4's subscriber-only unicast starts and the heatmap animates. Battery/RSSI come
  from the ~1 Hz `meta` block the firmware piggybacks on a sensor frame.
- Install over USB from the CLI: `./gradlew installDebug` (or
  `adb install -r app/build/outputs/apk/debug/app-debug.apk`).

## Test

- JVM unit tests (pure logic, no device): `./gradlew test` (70+ tests). They cover
  the parser + version policy, the row-major flatten, the temporal matcher, the v1
  and v2 CSV writers (byte-matched to the PC), the role registry + device cache, the
  multi-source recorder + meta sidecar, the control codec, the BLE binary frame, the
  inference prep, and HzMeter.
- Cross-implementation contracts (against the real PC code and reference servers):
  `python tools/verify_all.py`. Expect 8/8 (incl. the schema-v2 byte golden).

## The capture-to-inference loop

discover -> live heatmap -> capture a labeled session -> export the CSV to the PC
-> the PC trains a RandomForest and exports it to ONNX
(`python tools/export_onnx.py --csv <capture.csv> --out model.onnx`) -> share the
`.onnx` to the phone -> Predict screen -> load model -> live predicted pose.

Captures are written in schema v2 (docs/CSV-SCHEMA-V2.md): one row per source frame,
`ts_hub_ms, role, device_id, R{r}C{c}..., label_0..M`, plus a `<capture>.meta.json`
sidecar (mirror flag, role map, label source). The byte format is pinned by
`tools/make_golden_csv_v2.py` and matches the PC writer + the trainer.

## Multi-device capture

For two bands (Left/Right) plus a labeler:
1. In the device picker, tag each device with a role chip (Left / Right / Labeler);
   the tag persists per device id.
2. Tap "Multi-device capture". It subscribes to every tagged device at once (N TCP
   control channels), pairs each band frame with the nearest labeler label, and writes
   role-tagged v2 rows. Toggle "Mirror" for the one-limb case (two bands on one arm).
3. The trainer pivots the long CSV into the bilateral Left||Right 120-feature matrix
   (CSV-SCHEMA-V2 section 3); single-source captures train as-is. Tonight's hardware
   proves the band + labeler path (one FlexGrid + the LASK5); two bands at once is
   simulator-only until a second board is live.

## Project layout

```
app/src/main/java/org/openmuscle/connect/
  domain/       SensorFrame, LabelFrame, DeviceStatus, DiscoveredDevice, Role
  protocol/     OpenMuscleParser (+ version policy), MatrixUtil (row-major flatten)
  transport/    TransportLayer, UdpReceiver (3140+3141), WiFiTransport (N channels),
                TcpControlChannel, ControlCodec, DeviceProbe, ble/*
  discovery/    DeviceRegistry, DeviceCache (persistent), NsdDiscovery
  capture/      TemporalMatcher, CsvSessionWriter (v1 ref), CsvV2Writer,
                CaptureRecorder, MultiSourceRecorder, CaptureMeta, SessionStore
  ml/           ModelRunner, OnnxInference, Inference
  viewmodel/    Connect/Discovery/Capture/MultiCapture/Inference view models, HzMeter
  ui/           Home/DevicePicker/Capture/MultiCapture/Inference screens, Heatmap/HandPose
legacy/         pre-V4 WebSocket control path (not compiled), kept for revert
tools/          Python verifiers + reference servers + the ONNX export tool
docs/           the design and status docs (incl. CSV-SCHEMA-V2.md)
```

## Tools reference

| Tool | What it does |
|---|---|
| `verify_all.py` | Runs every cross-impl check (8/8 expected) |
| `wireformat_check.py` | Wire format / CSV / matcher vs the real PC code |
| `make_golden_csv.py` | v1 CSV byte-compatibility vs the PC CaptureWriter |
| `make_golden_csv_v2.py` | schema-v2 CSV byte contract (single-source + bilateral) |
| `openmuscle_sim.py` | FlexGrid + LASK5 + announce UDP simulator |
| `cmd_server.py` | V4 TCP command-channel reference device (newline-delimited JSON) |
| `v4_probe.py` | Probe a live V4 device end to end (announce -> get_info -> subscribe -> frames) |
| `ble_frame.py` | BLE binary frame encode/decode spec |
| `export_onnx.py` | Train RF -> ONNX, verify ONNX Runtime parity |
| `discovery_demo.py` | Runnable reference of the announce/subscribe discovery flow |
