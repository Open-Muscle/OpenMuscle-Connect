# Open Architectural Decisions

These are the decisions you and Tory need to settle before writing significant code. Each section lists the question, the options, the main tradeoffs, and a starting bias. Do not commit to any of these unilaterally. Surface each one to Tory with a short writeup and a recommendation, then implement.

## 1. Android framework

**Question:** What do we build the app in?

| Option | Pros | Cons |
|---|---|---|
| **Native Kotlin + Jetpack Compose** | Best access to BLE APIs, best on-device ML integration, official Android first-party tooling, fastest at runtime, smallest install. | No iOS port path. Tory has to learn Kotlin if he does not already. Slower initial scaffold. |
| **Flutter + Dart** | Cross-platform (free iOS port later). Hot reload speeds up UI iteration. Strong widget ecosystem. | BLE support is via third-party packages, sometimes flaky. ML integration is more indirect. Some performance overhead on real-time UI. |
| **React Native** | Web developers can contribute. Code sharing with a future web app or with the existing PC web UI. | BLE story is worse than Kotlin or Flutter. JS bridge adds latency that may matter for 59 Hz sensor streams. |

**DECIDED (2026-06-17): Native Kotlin + Jetpack Compose.** Real-time BLE, on-device ML, and low-latency 59 Hz rendering all favor native; no iOS path in v1 is an accepted tradeoff. See `docs/ARCHITECTURE-PROPOSAL.md`.

## 2. On-device ML runtime

**Question:** How do we train and run the regressor on the phone?

| Option | Pros | Cons |
|---|---|---|
| **Hand-rolled linear model in Kotlin** | Tiny dependency footprint, full control, easy to match the PC app's SGDRegressor math exactly. | Re-implementing what scikit-learn already gives the PC side. Less portable to bigger future models. |
| **TensorFlow Lite** | Industry standard. Supports on-device training (recently). Future-proof for bigger neural net architectures. | Heavy. Training-on-device support is still maturing. Model export from scikit-learn requires a conversion path. |
| **ONNX Runtime Mobile** | Direct path from sklearn (via `skl2onnx`) to mobile. Cross-platform. | Inference only by default; training-on-device is awkward. |
| **PyTorch Mobile** | Familiar to ML engineers. Decent mobile story. | Larger install size; sklearn does not export to PyTorch directly. |

**DECIDED (2026-06-17): mirror the PC model, do not train on the phone.** Training stays on the PC (currently RandomForest). For phone inference in a later phase, the PC exports its trained model to ONNX (`skl2onnx`) and the phone runs it with ONNX Runtime Mobile, giving zero model divergence. The phone's v1 ML role is recording PC-compatible sessions, not training. Note: the PC pipeline uses `RandomForestRegressor`, not `SGDRegressor` as older docs state. See `docs/ARCHITECTURE-PROPOSAL.md`.

## 3. BLE service design on the wearable

**Question:** What does the BLE service look like? Coordinate with the firmware team or Tory before locking this in.

**DECIDED (2026-06-17): BLE is a first-class, equal-tier transport for v1, not a later phase** (direction update 2026-06-17). The app implements BLE at parity with Wi-Fi across sensor, label, command, and status. Caveat: no firmware speaks BLE yet and V4 BLE is not scheduled, so the BLE path is built and unit-tested against a mock now and is demoable only when V4 firmware lands; V3 stays Wi-Fi only. The GATT layout and the compact-binary sensor frame are drafted in `docs/WIRE-FORMAT.md` sections 7 and 8; the control-plane-on-BLE encoding (JSON vs binary) is flagged open in `docs/WIRE-FORMAT.md` section 9. Still open within this decision: ratify the GATT characteristic UUIDs and the binary frame layout with the firmware effort.

Recommended starting point:
- One custom GATT service with a generated UUID
- One **notify characteristic** for sensor frames. Each notification is one 60-channel frame plus a sequence number, packed.
- One **read characteristic** for device status (firmware version, battery state of charge, current scan rate).
- One **write characteristic** for commands (start/stop streaming, set scan rate, request status push).
- One **notify characteristic** for label data IF the LASK5 also gets BLE. (Today LASK5 is Wi-Fi only.)

Match the on-the-wire payload to the existing UDP packet format as closely as possible so the firmware just has another transport, not another protocol.

## 4. Wire format compatibility with the PC app

**Question:** Do we keep the existing UDP packet format and add BLE as a transport, or redesign the wire format?

**DECIDED (2026-06-17): keep the existing OpenMuscle v1.0 format.** The firmware emits it and the PC consumes it; the phone is one more consumer. Correction to earlier notes: the UDP payload is JSON text, not packed binary. The canonical spec is now decoded in `docs/WIRE-FORMAT.md`. For BLE, a compact-binary encoding of the same field semantics is proposed in `docs/WIRE-FORMAT.md` section 7, pending firmware agreement.

## 5. Training: phone-only, phone-cloud, or phone-PC hybrid?

**Question:** Where does heavy model training happen?

| Option | Pros | Cons |
|---|---|---|
| **Phone-only** | Self-contained. Works without internet. Privacy by default. | Phone CPU does the work; battery cost; harder to retrain on accumulated data later. |
| **Phone + cloud sync** | Heavy training on a server, lightweight inference on phone. Easy to retrain on aggregated data. | Requires internet, an account system, and a server you maintain. Adds a privacy story you have to manage. |
| **Phone + optional PC pairing** | Use the PC app's existing training when nearby; train on phone when alone. | Two code paths; more user-facing modes to explain. |

**DECIDED (2026-06-17): phone-PC hybrid; training on the PC.** The phone records labeled sessions and exports them in the PC's CSV format for the PC to train; the phone does not train. This supersedes the phone-only starting bias. See `docs/ARCHITECTURE-PROPOSAL.md`.

## 6. VR pairing topology

**Question:** When the user has a Quest 3 plus a FlexGrid plus a phone, how do they coordinate?

**Recommendation: phone as the hub.** The phone has both BLE (to the wearable) and Wi-Fi (to the Quest). The phone runs inference and broadcasts the predicted hand pose to the Quest over a local socket or WebRTC. The Quest renders. This isolates the Quest from the wearable, simplifying the headset-side code.

Alternative (decentralized): Quest pairs with FlexGrid directly over Wi-Fi UDP, runs its own inference in WebXR. Reduces the phone's role but duplicates the inference logic.

Settle this with Tory based on how the VR app currently architects this.

## 7. Naming finalization

**Question:** What is the official user-facing name?

Tory has said "Open Muscle Connect" for the Android app and "Open Muscle Lab" tentatively for the PC app, but he said he is not yet attached to either. Confirm before any user-facing text gets locked in.

For now, this repo uses **Open Muscle Connect** internally. If the name changes later, a one-pass find-and-replace handles it.

## 8. Repo, license, push timing

**Question:** When does this become a public GitHub repository?

This folder is currently not a git repo. Recommended approach:
- Keep it local until the architectural decisions in this document are settled and the project is scaffolded.
- Initialize the repo with the briefing files first (READMEs and docs are the cleanest first commit).
- Push to `Open-Muscle/OpenMuscle-Connect` under Tory's GitHub org.
- License MIT (matching the rest of the software side; only the hardware repos use CERN-OHL-S).

Get Tory's go-ahead before the first push.

## How to use this document

For each open question above:

1. Read it. Read the relevant sibling repos to understand current state.
2. Write a recommendation to Tory in 2-3 sentences with the main tradeoff (his preferred response style for exploratory questions). Do not survey exhaustively unless he asks.
3. Wait for his call before implementing.
4. Once he agrees, strike the question out of this document and replace it with the decision and one-line rationale.

Over time, this document should shrink. When it is empty, the project's architecture is settled.
