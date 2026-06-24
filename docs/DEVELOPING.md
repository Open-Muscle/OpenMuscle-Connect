# Developing Open Muscle Connect

How to build, run, and verify the app. For implementation status and the loop
journal, see `BUILD-LOG.md`; for the protocol, `WIRE-FORMAT.md`.

## Prerequisites

- Android Studio (Koala or newer), JDK 17. The project targets AGP 8.7.3 /
  Gradle 8.10.2 / Kotlin 2.0.21, compileSdk 35, minSdk 26.
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

- JVM unit tests (pure logic, no device): `./gradlew test`. These cover the
  parser, the row-major flatten, the temporal matcher, the CSV writer, the
  control codec, the BLE binary frame, the inference feature prep, and HzMeter.
- Cross-implementation contracts (against the real PC code and reference
  servers): `python tools/verify_all.py`. Runs all seven checks; expect 7/7.

## The v1 demo loop

discover -> live heatmap -> capture a labeled session -> export the CSV to the PC
-> the PC trains a RandomForest and exports it to ONNX
(`python tools/export_onnx.py --csv <capture.csv> --out model.onnx`) -> share the
`.onnx` to the phone -> Predict screen -> load model -> live predicted pose.

## Project layout

```
app/src/main/java/org/openmuscle/connect/
  domain/       SensorFrame, LabelFrame, DeviceStatus, DiscoveredDevice
  protocol/     OpenMuscleParser, MatrixUtil (the row-major flatten)
  transport/    TransportLayer, UdpReceiver, WiFiTransport, Control*, ble/*
  capture/      TemporalMatcher, CsvSessionWriter, CaptureRecorder, SessionStore
  ml/           ModelRunner, OnnxInference, Inference
  viewmodel/    Connect/Discovery/Capture/Inference view models, HzMeter
  ui/           Home/DevicePicker/Capture/Inference screens, Heatmap/HandPose views
tools/          Python verifiers + reference servers + the ONNX export tool
docs/           the design and status docs
```

## Tools reference

| Tool | What it does |
|---|---|
| `verify_all.py` | Runs every cross-impl check (7/7 expected) |
| `wireformat_check.py` | Wire format / CSV / matcher vs the real PC code |
| `make_golden_csv.py` | CSV byte-compatibility vs the PC CaptureWriter |
| `openmuscle_sim.py` | FlexGrid + LASK5 + announce UDP simulator |
| `cmd_server.py` | V4 TCP command-channel reference device (newline-delimited JSON) |
| `v4_probe.py` | Probe a live V4 device end to end (announce -> get_info -> subscribe -> frames) |
| `ble_frame.py` | BLE binary frame encode/decode spec |
| `export_onnx.py` | Train RF -> ONNX, verify ONNX Runtime parity |
| `discovery_demo.py` | Runnable reference of the announce/subscribe discovery flow |
