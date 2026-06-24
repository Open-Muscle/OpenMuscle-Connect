# The OpenMuscle Ecosystem

How Open Muscle Connect fits with the rest of the project. Read this before designing transports, protocols, or app architecture.

## High-level system diagram

```
                  ┌───────────────────────┐
                  │   Quest 3 + WebXR     │
                  │   (OpenMuscle-AR)     │
                  └──────────┬────────────┘
                             │
                             │ ?? pairing protocol TBD
                             │
                ┌────────────┴─────────────┐
                │                          │
   ┌────────────▼─────────────┐  ┌─────────▼──────────────┐
   │ FlexGrid wearable        │  │  Phone:                │
   │ (OpenMuscle-FlexGrid V4) │  │  Open Muscle Connect   │
   │                          │  │  (this repo)           │
   │ ESP32-S3 + 15x4 Velostat │  │                        │
   │ 59 Hz scan rate          │  │  - BLE central         │
   │                          │  │  - Wi-Fi UDP receiver  │
   │ Firmware: MicroPython    │  │  - SGDRegressor train  │
   │ (FlexGridV3-Firmware)    │  │  - Inference           │
   │                          │  │  - Heatmap viz         │
   │ Streams over:            │  │  - Hand pose viz       │
   │  - Wi-Fi UDP (today)     │  │                        │
   │  - BLE (planned)         │  │                        │
   └────────────┬─────────────┘  └─────────┬──────────────┘
                │                          │
                │ same packet format       │
                │                          │
   ┌────────────▼──────────────┐ ┌─────────▼──────────────┐
   │ LASK5 hand labeler        │ │  PC app:               │
   │ (OpenMuscle-LASK5)        │ │  Open Muscle Lab       │
   │                           │ │  (OpenMuscle-Software) │
   │ Separate device worn on   │ │                        │
   │ the other hand or held;   │ │  Web UI in browser,    │
   │ provides GROUND-TRUTH     │ │  Python server,        │
   │ hand pose labels for      │ │  ML pipeline,          │
   │ supervised training       │ │  Quest pairing path    │
   │                           │ │                        │
   │ Streams over Wi-Fi UDP    │ │                        │
   └───────────────────────────┘ └────────────────────────┘
```

## Devices, apps, and what they do

### FlexGrid wearable (OpenMuscle-FlexGrid)

- The forearm-mounted band with the 60-cell Velostat sensor matrix.
- V4 is currently in fab (ordered 2026-06-12, expected 2 weeks).
- ESP32-S3-WROOM-1-N16R8 controller, runs MicroPython firmware.
- Scans the matrix at ~59 Hz, producing a 60-value frame per scan.
- Today: transmits frames over Wi-Fi UDP to a destination IP baked into the firmware.
- Planned: BLE GATT service that exposes the same frames as notifications. **Not yet implemented in firmware.** Firmware work for the BLE side is a sibling effort to your app work.

### LASK5 hand labeler (OpenMuscle-LASK5)

- A separate device the user wears on the OPPOSITE hand or holds in the other hand. It captures actual hand-position data (finger flex, piston positions) and broadcasts it as the "ground truth" label for supervised training.
- Without LASK5 you can still use manual labels or camera-based pose detection.
- Same Wi-Fi UDP transport as the FlexGrid.
- Lives at COM24 over USB on Tory's machine for development.

### PC application "Open Muscle Lab" (OpenMuscle-Software)

- Python server with a web UI in `pc/src/openmuscle/web/`.
- Listens for UDP packets from both the FlexGrid and the LASK5.
- Trains an SGDRegressor (scikit-learn) from paired sensor + label data.
- Runs real-time inference on incoming FlexGrid frames.
- Renders a heatmap, a ground-truth-vs-predicted comparator, and (when paired) a Quest hand-viewer.
- Pairs with the VR application over a separate protocol.
- This is the **reference implementation** you are porting to Android. Read its source as your specification.

### VR / AR application (OpenMuscle-AR)

- Quest 3 + WebXR application that runs in the headset's browser.
- Shows a 3D hand mirroring either the captured-real-hand or the model-predicted hand.
- Can record real hand movements as training labels using the Quest's hand tracking.
- Communicates with the PC app today; the goal is for it to also pair with Open Muscle Connect.

### Open Muscle Connect (this repository)

- The Android companion app you are about to build.
- Talks to the FlexGrid over BLE (new) and Wi-Fi UDP (matching the PC app).
- Talks to the LASK5 over Wi-Fi UDP if available.
- Trains a regressor on-device.
- Runs inference on-device.
- Pairs with the VR app if the headset is in the same network or nearby.

## Wire formats you need to understand

**Do not invent your own packet format until you have read the existing one.** The PC app and the firmware already speak a specific protocol.

The current Wi-Fi UDP packet (V3 firmware, expected to carry forward to V4) is something like:
- Sender IP and port identify the device (FlexGrid vs LASK5).
- A payload of 60 sensor readings as packed integers (the FlexGrid) or a smaller payload of label values (the LASK5).
- Often a frame timestamp or sequence number.

**You must read `FlexGridV3-Firmware\` and `OpenMuscle-Software\pc\src\openmuscle\` to confirm the exact format.** Do not work from this description; treat it as a pointer.

For BLE, the firmware does not yet implement a service, so you have design latitude. Suggested approach:
- Custom GATT service with a single notify characteristic
- Each notification is one sensor frame, same packed-integer payload as the UDP version
- A second characteristic for device status (battery, scan rate, version)

Coordinate this design with whoever takes the firmware BLE work.

## ML pipeline

The PC app's ML approach (read the source for the exact details, but at a high level):

1. **Feature vector**: 60 channels per frame (the 15x4 matrix flattened), optionally with rolling-window statistics over the last N frames.
2. **Labels**: 4 piston positions for the OpenHand robot, or N finger flexion values from LASK5 or VR hand tracking.
3. **Model**: scikit-learn `SGDRegressor` with `partial_fit` for online learning. Adaptive training: the model updates as new labeled frames arrive.
4. **Convergence**: usable predictions from ~2000 labeled samples (a few minutes of recording). Tory has demonstrated this on V3 hardware.
5. **Inference**: per incoming sensor frame, predict the label vector and broadcast it to the consumer (the OpenHand robot or the VR app).

For Android:
- TensorFlow Lite or ONNX Runtime Mobile is the standard path for on-device regression.
- A linear model like SGDRegressor is small enough to run in plain Java/Kotlin without a ML runtime, if you prefer simplicity. Compare both options.
- The training step is the harder one; consider whether you train on the phone or call out to the PC app for heavy training.

## Naming convention across the project

Tory likes the naming convention to follow a theme:

- The wearable is the **FlexGrid**.
- The PC app's working name is **Open Muscle Lab**.
- This phone app is **Open Muscle Connect**.
- The VR app is **OpenMuscle-AR** (existing name, may rebrand).
- The labeler is **LASK5**.

Folder names in the GitHub org use hyphenated form (`OpenMuscle-Connect`); displayed names use spaces ("Open Muscle Connect").

## What to do next

After reading this document, the recommended next steps for your first session are listed in `..\CLAUDE.md` under "Recommended first session plan." Start by reading the firmware and PC app source, not by coding.
