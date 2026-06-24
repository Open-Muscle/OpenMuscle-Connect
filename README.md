# Open Muscle Connect

**Status: v1 implemented over Wi-Fi; Bluetooth gated on V4 firmware.** This repository holds the Android companion application for the OpenMuscle ecosystem.

## What this is

Open Muscle Connect is the mobile companion app for the OpenMuscle FlexGrid wearable sensor bracelet. Goal: do everything the PC application currently does, on a phone in your pocket, plus pair with the VR application for richer training and inference workflows.

## What it needs to do

| Capability | Why it matters |
|---|---|
| **Bluetooth Low Energy connection to the FlexGrid wearable** | Lets the band stream sensor frames without needing a Wi-Fi network around |
| **Wi-Fi UDP connection to the FlexGrid wearable** (alternate transport) | Higher bandwidth path; matches the existing PC app's transport for parity |
| **Real-time sensor heatmap visualization** | Same 15 by 4 Velostat matrix view the PC app shows, sized for a phone screen |
| **On-device regressor training** | Capture labeled sessions and train a finger-position model on the phone itself |
| **On-device inference** | Predict finger positions in real time from incoming sensor frames, even when no PC is nearby |
| **Pairing with the OpenMuscle VR application** | Use the phone as the data/inference hub while the Quest headset handles label capture or visualization |
| **Session storage and management** | Record training sessions locally, optionally sync to a desktop or cloud later |
| **Battery-aware operation** | The wearable's 500 mAh LiPo and the phone's battery are both constraints |

Both Wi-Fi and BLE are first-class transports in the app: Wi-Fi for high-bandwidth lab use, BLE for self-contained field use with no network. BLE on the wearable requires V4 firmware (planned); V3 hardware is Wi-Fi only.

## Companion to the wider OpenMuscle stack

Open Muscle Connect is one of several apps and platforms in the OpenMuscle project. It does not replace the PC app or the firmware; it joins them.

- **Hardware:** [`OpenMuscle-FlexGrid`](https://github.com/Open-Muscle/OpenMuscle-FlexGrid) (V4 in fab as of June 2026)
- **Firmware:** [`FlexGridV3-Firmware`](https://github.com/Open-Muscle/FlexGridV3-Firmware) (MicroPython on the ESP32-S3 in the wearable)
- **PC application** (provisional working name: "Open Muscle Lab"): [`OpenMuscle-Software`](https://github.com/Open-Muscle/OpenMuscle-Software)
- **VR / AR application:** [`OpenMuscle-AR`](https://github.com/Open-Muscle/OpenMuscle-AR) (Quest 3 + WebXR companion)
- **Hand-position labeler hardware:** [`OpenMuscle-LASK5`](https://github.com/Open-Muscle/OpenMuscle-LASK5)
- **Documentation hub:** [`OpenMuscle-Hub`](https://github.com/Open-Muscle/OpenMuscle-Hub)

For the broader project mission, see [openmuscle.org](https://openmuscle.org).

## Status and roadmap

The app is scaffolded in native Kotlin and Jetpack Compose with a working v1 over the Wi-Fi transport: device discovery, the live 15 by 4 sensor heatmap, labeled session capture exported in the PC's exact training CSV format, a Wi-Fi command channel, and on-device inference that runs a PC-trained model exported to ONNX. The Bluetooth transport is implemented on the app side but gated on V4 firmware, which does not expose the BLE service yet; VR pairing is still an open design question.

The cross-format contracts (wire format, training CSV, control messages, the BLE binary frame, and the ONNX inference bridge) are verified against the real PC code and reference servers. Run `python tools/verify_all.py` to check all of them at once.

See `docs/BUILD-LOG.md` for implementation status and how to build and run, `docs/PROJECT-SCOPE.md` for the scope, `docs/ARCHITECTURE-PROPOSAL.md` for the design, and `docs/TECH-DECISIONS.md` for the decisions and remaining open questions.

## License

To match the rest of the OpenMuscle stack:
- Hardware: CERN-OHL-S-2.0
- Software: MIT

This repository will be MIT once the first source code lands.
