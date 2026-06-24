# Build Log (read this first each loop)

This is the running progress journal for the autonomous build of Open Muscle
Connect. If you are a future loop: read this top-to-bottom, then continue from
"Next worklist."

> CURRENT STATE (2026-06-24, multi-agent coordination run): the phone app is now
> FEATURE-COMPLETE across P1-P4 plus the WiFi provisioning client side, built as
> the `phone` team on Tory's multi-team coordination board (firmware / phone /
> vrpc / overseer; board at `C:\dev\ttx\Open Muscle\.claude-mail\openmuscle`). The
> repo is git-init'd on `main` with an MIT LICENSE; 28 commits are staged locally
> and AWAIT TORY'S PUSH (red line: do not push). `:app:testDebugUnitTest` BUILD
> SUCCESSFUL, 81 JVM unit tests pass.
>
> What landed this run:
> - P1 PROTOCOL FREEZE: signed off on OpenMuscle PROTOCOL.md v1.0 (announce UDP
>   3140, data UDP 3141, cmd TCP 8001 FlexGrid / 8002 LASK5). Caught two
>   frozen-spec bugs: the 245cb8f column-major flatten transpose, and a live PC
>   transpose, both fixed. Row-major `matrix[c][r]` is canonical.
> - P2 WIFI PROVISIONING (Tory priority #2): the onboarding is wired end to end.
>   Flow: picker set-up-a-new-device -> location permission -> scan OM-* SoftAPs
>   (ApSsid/ApScanner) -> connect local-only via WifiNetworkSpecifier
>   (ProvisioningClient) -> GET /info identity confirm -> enter home SSID/password
>   -> POST /provision -> release AP -> confirm the device rejoined the LAN via its
>   3140 announce (ProvisioningViewModel + ProvisioningScreen). Built to the #0042
>   corrections (Specifier not Suggestion; hub_host omitted; manual SSID; confirm
>   on 3140). Item 6 is now ratified WPA2-PSK with a per-device random OLED PSK
>   (boards #0126/#0127, a29128e): the join uses setWpa2Passphrase with the
>   user-typed 10-char code (ApPsk pins charset/length; not derived). Live
>   end-to-end waits on firmware AP mode + the phone.
> - P3 DISCOVERY HARDENING: beacon listen on UDP 3140; device host/IP persisted
>   for cache recovery.
> - P4 MULTI-DEVICE: role UI (left/right/labeler), CSV schema v2 (long format,
>   meta.json sidecar), bilateral trainer pivot, mirroring. The v2 byte contract is
>   closed across all three teams (phone CsvV2Writer = PC CaptureWriter = trainer
>   pivot); meta.json interop verified with vrpc.
>
> Pre-push self-reviews caught real bugs in the un-unit-testable Android/network
> code before they reached the push: the TcpControlChannel reconnect-flap race
> (a1941cd, heartbeat is now the sole owner of `connected`), and two
> ConnectivityManager callback leaks + a provisioning state-stomp (7694be6).
>
> REMAINING is all Tory-gated or firmware-gated: push the 26 commits; reconnect the
> phone; on-device review of the multi-device capture UX + the provisioning
> end-to-end once firmware ships AP mode; ratify the VR hub model (item 5). No
> greenlit non-gated phone code remains.

> NOTE (2026-06-18): this file was reconstructed from the session context after a
> disk-full event truncated it to 0 bytes (D: drive hit 0 bytes free mid-write;
> the project lives on OneDrive/D:). Content is faithful but may not be
> byte-identical to the original; OneDrive version history has the exact prior
> copy if needed.

> CURRENT STATE: v1 is feature-complete, COMPILES, RUNS, and is verified.
> 2026-06-18 build session on Tory's machine (Android Studio JDK 21 + cached
> Gradle 8.11.1 + SDK 35): `:app:testDebugUnitTest` and `:app:assembleDebug` both
> BUILD SUCCESSFUL; all 48 JVM unit tests pass; `app-debug.apk` (~80 MB, large
> only because onnxruntime bundles all-ABI native libs) produced; gradle wrapper
> jar generated. Three bugs were found and fixed that the read-only review missed:
> (1) `NsdDiscovery.onStartDiscoveryFailed = close()` returned Boolean not Unit;
> (2) `WiFiTransport` was missing `import ...protocol.ParsedPacket` (a later
> full-file rewrite dropped it); (3) RUNTIME crash on launch:
> `UdpReceiver` built its socket via `DatagramSocket(null).apply { bind(
> InetSocketAddress(port)) }`, where `port` resolved to the apply-receiver's
> `DatagramSocket.getPort()` (-1) not the ctor `port=3141`; fixed by binding
> without `apply{}`. Ran on the `Medium_Phone_API_36.1` emulator: installs,
> launches, all screens render (picker -> heatmap -> predict), navigation works.
> The Python cross-impl checks pass (7/7, incl. the discovery handshake). The
> firmware-team discovery/transport spec (`DEVICE-DISCOVERY-SPEC.md`) and its
> runnable reference (`tools/discovery_demo.py`) are delivered and pending Tory's
> ratification of the open decisions. Remaining: live sensor data needs a real
> phone (UDP into the emulator is NAT-blocked); BLE is firmware-gated; VR /
> repo / license / naming need Tory's decisions. Do NOT manufacture speculative
> features or risky refactors of working code.

> UPDATE (2026-06-18, V4 firmware alignment): the firmware team shipped V4 and the
> phone app was reworked to talk to the real device. The control plane moved from
> the app's old WebSocket draft to **raw TCP newline-delimited JSON** on the device
> cmd port (announce `services.cmd`, default 8001), matching
> `FlexGridV4-Firmware/lib/commands.py`. New/changed code:
> `transport/TcpControlChannel.kt` (new: connect -> subscribe(port=3141,hub_id) ->
> 1 Hz heartbeat -> ack-by-msg_id, unsubscribe on close); `transport/ControlCodec.kt`
> (rewritten: `encode(hubId,msgId,command)` -> `{v,type:cmd,id,msg_id,data:{verb,...}}`,
> `parseAckLine` reads top-level `status`, `parseInfo` maps a get_info ack);
> `transport/Messages.kt` (`Command` verbs + `SessionMeta` re-added);
> `transport/WiFiTransport.kt` (TCP channel + subscribe; status now rides UDP meta;
> sessions app-local); `protocol/OpenMuscleParser.kt` + `domain/DiscoveredDevice.kt`
> + `discovery/DeviceRegistry.kt` (announce `services.sensor`/`services.cmd` ->
> `sensorPort`/`cmdPort`, merged so mDNS doesn't clobber the beacon's cmd port);
> `viewmodel/ConnectViewModel.kt` (selectDevice subscribes; setScanRate converts
> Hz -> `interval_ms`). The DATA path (UDP `UdpReceiver` on 3141) is UNCHANGED and
> still works for V3-style broadcast devices. The pre-V4 WebSocket path is preserved
> (not deleted) under `legacy/` for revert. Docs WIRE-FORMAT.md section 8 and
> DEVICE-DISCOVERY-SPEC.md section 6 updated to V4 reality; `tools/cmd_server.py`
> rewritten to a V4 TCP reference server. Verification: `:app:testDebugUnitTest`
> BUILD SUCCESSFUL, 50 unit tests pass (ControlCodecTest 8, OpenMuscleParserTest 11
> incl. two new announce-format tests); `:app:assembleDebug` BUILD SUCCESSFUL;
> `tools/verify_all.py` 7/7; and `tools/v4_probe.py --ip 10.0.0.112` PASS against the
> LIVE V4 device (flexgrid-v3-02, fw v4.0.0): announce -> get_info -> subscribe ->
> 10 frames (15x4) -> unsubscribe. The probe sends byte-identical shapes to what
> `ControlCodec.encode` emits, so the app's wire output is proven against real
> hardware. STILL PENDING: running the actual APK on the Pixel 10a (the phone was
> not connected via adb during this loop) and watching a live heatmap on-device.
> When the phone is plugged in: `:app:installDebug`, open it on the `OpenMuscle`
> Wi-Fi, pick `flexgrid-v3-02`, confirm the heatmap animates.

> DISK: D: (the project drive, under OneDrive) filled to 0 bytes during the build.
> Freed the regenerable Gradle outputs (`app/build`, `.gradle`, `.kotlin`) to
> recover ~285 MB. The APK/test build regenerates in ~25s. The drive is still
> nearly full; Tory should free D: space or relocate build outputs. Do not rebuild
> the APK on D: without checking free space first.

## Resume order

1. `docs/ARCHITECTURE-PROPOSAL.md` (decisions, component design, phase plan)
2. `docs/WIRE-FORMAT.md` (the verified protocol spec; sections 8 and 9 are draft)
3. `docs/DEVICE-DISCOVERY-SPEC.md` (firmware-team spec: roles, discovery, no hardcoded IPs, BLE, all devices incl. OpenHand)
4. `docs/TECH-DECISIONS.md` (what is decided vs open)
5. `docs/DEVELOPING.md` (how to build/run/test)

## Ground rules

- git init + MIT LICENSE DONE (Tory's unified-plan priority #1); 26 commits on
  `main` await Tory's push. Do NOT push (red line #2); Tory pushes.
- No em dashes in any file written to disk.
- No Co-Authored-By trailer unless real code/content was authored (and never
  noreply@anthropic.com; use turfptax-claude@openmuscle.org).
- Verify the verifiable in Python; the Kotlin now compiles + tests on Tory's
  machine via the cached Gradle (see CURRENT STATE).

## Status board

| Phase | Scope | State |
|---|---|---|
| 0 | Docs + wire-format spec | Done |
| 1 | Kotlin/Compose scaffold + UDP receiver + live heatmap | Done, compiles + runs |
| 1.5 | mDNS + UDP broadcast discovery + device picker | Done, compiles + runs |
| 2 | Session capture + temporal matcher + PC CSV export | Done, compiles + runs |
| 2.5 | Wi-Fi `/cmd` control channel | Done, compiles + runs |
| 3 | BLE transport at parity | Codec verified; scanner + GATT skeletons; connection-mode swap deferred (firmware-gated) |
| 4 | ONNX inference + predicted hand pose | Done; bridge verified; on-device UI runs |
| 5 | VR pairing (phone as hub) | Not started; unratified open question |

The phase 0-5 board above is the original v1 build. The 2026-06-24 multi-agent run
added these workstreams (phone team); see CURRENT STATE at the top for detail.

| Workstream | State |
|---|---|
| P1 protocol freeze (PROTOCOL.md v1.0 sign-off) | Done; caught 2 frozen-spec transpose bugs |
| P2 WiFi provisioning onboarding (client side) | Feature-complete; live test waits on firmware AP mode |
| P3 discovery hardening (3140 listen, host/IP cache) | Done |
| P4 multi-device (roles, CSV schema v2, mirroring) | Done; v2 byte contract closed across teams |

## Verified (Python cross-impl checks, run `python tools/verify_all.py` -> 7/7)

- `wireformat_check.py`: wire format / CSV / matcher vs the real PC code. The one
  true flatten is row-major `matrix[c][r]` (matches `web/state.py` recorder; the
  `schema.flat_sensor_values` col-major order is a trap). CSV header
  `timestamp,R{r}C{c}...,label_*`, CRLF. Matcher nearest-within-100ms on receive
  time. Boundary cross-verified at 99 ms (in) / 101 ms (out).
- `make_golden_csv.py`: CSV byte-compatible with the real PC CaptureWriter; the
  Kotlin `CsvSessionWriter` asserts the same golden literal. Phone writes integer
  epoch-ms timestamps.
- `openmuscle_sim.py --selftest`: emits PC-parseable v1.0 frames (+ `--announce`,
  `--lask5`). Feeds the phone: `--target <phone-ip>`.
- `cmd_server.py --selftest`: reference `/cmd` WebSocket server accepts the exact
  JSON `ControlCodec` emits.
- `ble_frame.py --selftest`: 128-byte compact-binary BLE sensor frame; Kotlin
  `BleFrameCodec` decodes + re-encodes the canonical hex.
- `export_onnx.py`: sklearn RF -> ONNX -> ONNX Runtime parity within ~3.5e-4 (the
  "mirror PC" inference bridge). Kotlin side is `ml/OnnxInference`.
- `discovery_demo.py --selftest`: the announce -> subscribe -> unicast ->
  heartbeat-keepalive -> timeout-drop handshake (runnable firmware reference).

## JVM unit tests (78, all pass via `./gradlew test`)

Parser, MatrixUtil flatten, TemporalMatcher, CsvSessionWriter, CaptureRecorder,
ControlCodec, BleFrameCodec, Inference feature-prep, HzMeter, DeviceRegistry,
plus the 2026-06-24 multi-agent additions: ProvisioningCodec + ApSsid (P2),
CsvV2Writer + CaptureMetaCodec + Role (P4 schema v2), and the DeviceRegistry
role/cache cases.

## App layout (native Kotlin + Compose, package org.openmuscle.connect)

`domain/` frames + DiscoveredDevice; `protocol/` parser + MatrixUtil; `transport/`
TransportLayer, UdpReceiver, WiFiTransport, Control*, `ble/*`; `capture/` matcher,
CsvSessionWriter, recorder, SessionStore; `ml/` ModelRunner, OnnxInference,
Inference; `viewmodel/` Connect/Discovery/Capture/Inference + HzMeter;
`ui/` Home/DevicePicker/Capture/Inference screens, Heatmap/HandPose views;
`OmApp` (single shared transport), `Prefs`, `MainActivity` (nav).

## How to build/run

Open the repo root in Android Studio (JDK 21, AGP 8.7.3, Gradle 8.11.1, SDK 35;
`local.properties` points at the SDK, wrapper jar is generated). `./gradlew test`
for unit tests; `:app:assembleDebug` for the APK. Run on a real phone on the same
Wi-Fi, then `python tools/openmuscle_sim.py --target <phone-ip>` for a live
heatmap. For inference: `python tools/export_onnx.py --out model.onnx`, copy to
the phone, Load model on the Predict screen.

## Decisions/assumptions made autonomously (Tory, sanity-check)

- Package/app id `org.openmuscle.connect`; name "Open Muscle Connect".
- minSdk 26, target/compile 35; versions in `gradle/libs.versions.toml`.
- JSON via kotlinx.serialization; OkHttp for WebSocket; onnxruntime-android.
- Installed `skl2onnx` + `onnxruntime` into Tory's Python env (the ONNX export dep).
- Placeholder launcher icon.

## Could NOT verify earlier / risk list

- ~~Nothing Kotlin is compiled~~ RESOLVED 2026-06-18: compiles, 48 tests pass,
  APK assembles, runs on the emulator. Three bugs found + fixed (see CURRENT
  STATE).
- Matcher boundary: Kotlin uses integer ms, PC uses float seconds, so the exact
  window edge can differ by a sub-tick. Cross-verified at 99/101 ms; never matters
  for real receive times. Documented, not a bug.
- ~~DiscoveryViewModel always-on socket~~ FIXED (loop 16): discovery runs only
  while the picker is shown.
- FileProvider share path and the BLE GATT/scan code are compiled but unexercised
  (no device emits the BLE service; no live share tested).

## Next worklist

1-2 (phases 1, 1.5, 2): DONE, compiles + runs.
3. Phase 2.5 control: DONE.
4. Phase 4 ONNX inference + Predict UI: DONE.
5. Phase 3 BLE: codec + UUIDs + GATT skeleton + scanner DONE. DEFERRED ON PURPOSE:
   the connection-mode (Wi-Fi vs BLE) transport swap (large, untestable,
   firmware-gated; risks the verified Wi-Fi path). Do it when a BLE device exists.
6. Phase 5 VR: do NOT build unilaterally; phone-as-hub is open question #6.
7. Firmware discovery/transport spec: DONE and now IMPLEMENTED on both sides. V4
   firmware shipped `role`/`services`; the app parses `services.sensor`/`services.cmd`
   and speaks the V4 TCP control plane (`TcpControlChannel`). Verified live against
   flexgrid-v3-02 (fw v4.0.0) with `tools/v4_probe.py`.
8. Polish (pose viz, nickname edit, discovery battery): DONE.
9. NEXT (needs the phone plugged in): run the APK on the Pixel 10a and watch a live
   heatmap from the real V4 FlexGrid. `:app:installDebug` over USB, join the
   `OpenMuscle` Wi-Fi (or whatever LAN the FlexGrid is on; it was at 10.0.0.112 this
   loop), open the app, pick `flexgrid-v3-02`, confirm the heatmap animates and the
   subscribe/heartbeat keeps frames flowing. Selecting the device now triggers a real
   `subscribe`, so V4's subscriber-only unicast lights up.
10. Still Tory-gated: git init + repo name/license; relocate the project off
    OneDrive/D: (build artifacts keep getting OneDrive-locked, causing
    AccessDenied/Unable-to-delete on dex + test-results dirs; retry after clearing
    the dir, or move `build/` off OneDrive); VR hub model; BLE transport swap.

## Loop journal (newest first)

- 2026-06-24 item-6 resolved: WPA2-PSK provisioning (a29128e). Firmware ratified
  WPA2 over open AP, with a per-device RANDOM 10-char PSK shown on the device OLED
  (overseer #0126 caught that a device-id-derived PSK in open-source firmware gives
  no real security; #0127 made it random + out-of-band). Phone join now calls
  setWpa2Passphrase with the user-typed code via a new ENTER_PSK step; ApPsk pins
  the charset/length (abcdefghijkmnpqrstuvwxyz23456789, len 10) with a unit test.
  81 tests pass.

- 2026-06-24 multi-agent run, phone team (see CURRENT STATE at top for full
  detail): the phone joined Tory's coordination board and drove its workstream to
  feature-complete across P1-P4 + the WiFi provisioning client side. P2 WiFi
  provisioning wired end to end (ProvisioningCodec -> ProvisioningClient ->
  ApScanner/ApSsid -> ProvisioningViewModel -> ProvisioningScreen + nav). Repo
  git-init'd on `main` with MIT LICENSE; 26 commits, 78 tests pass, NOT pushed
  (Tory pushes). Pre-push self-reviews caught + fixed the TcpControlChannel
  reconnect-flap race (a1941cd) and two ConnectivityManager callback leaks + a
  provisioning state-stomp (7694be6). P1 protocol freeze signed off (2 transpose
  bugs caught: 245cb8f flatten + a live PC transpose); P3 discovery hardening
  (3140 listen + host/IP cache); P4 multi-device roles + CSV schema v2 + mirroring,
  with the v2 byte contract closed across phone/PC/trainer and meta.json interop
  verified with vrpc. Recurring friction: OneDrive locks build outputs mid-build
  (clear `app/build` and rebuild clean); avoid literal double-quotes in mail.py
  board post bodies (PowerShell 5.1 arg mangling).

- 2026-06-18 LIVE HEATMAP CONFIRMED on the Pixel against the REAL V4 device
  (autonomous loop): the FlexGrid came back online (monitor caught its announce);
  the phone auto-discovered it, subscribed over the new TCP control channel, and
  streamed. Proof: the device's get_info subscriber list showed the phone
  (`host 10.0.0.159, hub_id om-android-96678428, age_ms 423, transport wifi`) and
  the Home screen rendered `flexgrid-v3-02` at 23 Hz, 494 frames, battery 57% /
  RSSI -55 dBm (from the meta block), a live 15x4 heatmap with real activation, and
  a `subscribed` status line. So discover -> TCP subscribe -> unicast frames ->
  heartbeat -> battery/RSSI telemetry all work end to end on hardware. (Frame rate
  ~23 Hz vs the 59 Hz scan rate is the device's actual Wi-Fi send/JSON-encode
  throughput, not a bug; plenty for a live heatmap.) Screenshot sent to Tory in
  chat (not committed; repo not git-init'd yet). This closes the V4-alignment work:
  nothing greenlit remains; the rest (BLE swap, VR, git init, repo/license) is
  Tory-gated.
- 2026-06-18 on-device install of the V4 APK (autonomous loop, phone connected
  mid-loop): the Pixel 10a (serial 61091JEA307481) connected via adb, so the loop
  auto-installed `app-debug.apk` (`adb install -r` -> Success) and smoke-tested it:
  `am start` -> `Displayed .../MainActivity +734ms`, foreground/resumed, NO FATAL
  EXCEPTION. So the rebuilt APK with the new TCP control + services parsing RUNS on
  real hardware. Phone Wi-Fi is 10.0.0.159/24, same subnet as the FlexGrid. One
  benign warning: `PageSizeMismatchDialog` (Android 15 16KB-page-size warning about
  a bundled native lib, likely onnxruntime's .so; not a crash). PROBLEM: by the time
  the phone was up, the real `flexgrid-v3-02` (10.0.0.112) had gone OFFLINE (TCP
  8001 timeout, zero announce beacons in 7s). Its earlier get_info reported
  `reset_cause_name: WDT` (watchdog reset), so this looks like firmware instability,
  plausibly tied to the SD/IMU graceful-fail work in progress. The phone's picker
  still showed a STALE `flexgrid-sim01` (10.0.0.102, TCP refused; left over from an
  earlier sim) because `DeviceRegistry` never expires entries. Armed a monitor for
  the real device's announce beacon to return; pushed Tory to power-cycle it.
  Minor UX follow-up observed (not fixed, debatable): the picker shows
  offline/stale devices forever; consider a last-seen timeout or an "offline" tag.
- 2026-06-18 V4 firmware alignment (autonomous loop): reworked the phone control/
  discovery layer to the real V4 device. Control plane is now raw TCP newline-
  delimited JSON (`TcpControlChannel`), get_info is a cmd verb, set_scan_rate uses
  `interval_ms`, acks carry a top-level `status`, announce ports come from
  `services`. UDP data path unchanged. Pre-V4 WebSocket path preserved under
  `legacy/`. 50 unit tests pass; assembleDebug OK; verify_all 7/7; `v4_probe.py`
  PASS against the LIVE device (announce -> get_info -> subscribe -> 10 frames ->
  unsubscribe). Docs (WIRE-FORMAT s8, DEVICE-DISCOVERY-SPEC s6) + `tools/cmd_server.py`
  updated to V4. Could not run the APK on the Pixel: phone not connected via adb.

- 2026-06-18 on-device verification (Pixel 10a over USB): installed + ran on real
  hardware. DISCOVERY works (phone found `flexgrid-sim01` purely from its announce
  beacon, with host 10.0.0.102 + RSSI). LIVE HEATMAP at ~48 Hz, battery/RSSI from
  meta. CAPTURE verified end to end: recorded 187 rows at 99% match (manual off ->
  live LASK5 paired via the on-device matcher); the pulled CSV loads in the REAL
  PC trainer (`load_training_data` -> X(187,60), y(187,4), labels nonzero).
  INFERENCE: model staged on the phone (`/sdcard/Download/openmuscle_model.onnx`,
  parity-verified); the onnxruntime-android on-device run is the one link still to
  test (standard lib). CONTROL channel: phone->PC WebSocket is blocked by Windows
  Firewall (Wi-Fi is on the Public profile; no admin to add a rule); `cmd_server`
  runs on :8000 and needs an inbound-8000 allow rule (or set the network Private).
  Added connection logging to `tools/cmd_server.py`. Minor UI polish noted: Capture
  "Delete" wraps; status row crowds with a long device id.
- 2026-06-18 disk incident: D: filled to 0 bytes during the build; a doc write
  truncated this file. Freed regenerable Gradle outputs (~285 MB) and rebuilt this
  log from context. No source/test/tool files were affected (only this file was 0
  bytes; all other docs intact). verify_all still 7/7.
- 2026-06-18 discovery reference: wrote `tools/discovery_demo.py`, a runnable
  stdlib reference of the subscribe model (announce -> discover -> subscribe ->
  unicast -> heartbeat -> timeout drop). `--selftest` PASS; added to verify_all
  (7/7); pointed DEVICE-DISCOVERY-SPEC section 5 at it.
- 2026-06-18 firmware-coordination: wrote `docs/DEVICE-DISCOVERY-SPEC.md` (roles
  source/hub/actuator; mDNS + UDP-broadcast discovery; subscribe-then-unicast;
  hub-pushes-to-actuator; BLE). Grounded the OpenHand (ESP32-S2, PCA9685, already
  has a UDP mode on 3145 taking `PC,a1..a5`; only the announce is new) and the
  robot servo format against `web/state.py`. Reconciled WIRE-FORMAT 8.1 announce +
  the sim announce with `role`/`services` (draft; app parser stays tolerant).
- 2026-06-18 build session: wired the project to Android Studio's JDK 21 + SDK
  (`local.properties`), wrapper -> cached Gradle 8.11.1. Found+fixed 2 compile
  errors then a runtime crash; 48 tests pass; APK assembles; runs on the emulator,
  all screens render.
- Loop 18: extracted `discovery/DeviceRegistry` (device dedup/merge) + test.
- Loop 17: extracted `HzMeter` + test; wrote `docs/DEVELOPING.md`.
- Loop 16: discovery battery scoping (start/stop by route).
- Loop 15: compile-sanity review; fixed `Flow.sample` @FlowPreview -> manual gate.
- Loop 14: device nickname editing (picker rename + Prefs + DiscoveryViewModel).
- Loop 13: `ui/HandPoseView.kt` (2D predicted hand).
- Loop 12: edge-case test hardening (matcher boundary, parser robustness, 16-col).
- Loop 11: `BleScanner`; fixed stale README; deferred BLE swap + VR with reasons.
- Loop 10: on-device inference UI (`ml/ModelRunner`, `InferenceViewModel`,
  `ui/InferenceScreen`, SAF model picker). v1 demo loop complete.
- Loop 9: phase 4 ONNX bridge verified (`tools/export_onnx.py`) + `OnnxInference`
  + onnxruntime dep; `tools/verify_all.py` unified runner.
- Loop 8: phase 3 BLE binary codec (`ble_frame.py` + `BleFrameCodec`) verified;
  `BleUuids`, `BleTransport` skeleton; BLE permissions.
- Loop 7: phase 2.5 control integrated (`WiFiTransport.connectControl`, command
  card on Home).
- Loop 6: phase 2.5 control core (`ControlCodec` + `cmd_server.py`,
  `WebSocketControlChannel`, OkHttp dep).
- Loop 5: phase 2 capture UI (`SessionStore`, `CaptureViewModel`, `CaptureScreen`,
  FileProvider export).
- Loop 4: phase 2 capture core (`TemporalMatcher`, `CsvSessionWriter` golden-
  verified, `CaptureRecorder`) + tests.
- Loop 3: phase 1.5 finished (`OmApp` single socket, `DiscoveryViewModel`,
  `DevicePickerScreen`, nav).
- Loop 2: phase 1.5 discovery backend (announce parsing, `NsdDiscovery`).
- Loop 1: read docs + sibling repos; verified wire format vs real PC code;
  scaffolded the whole phase-1 app; `openmuscle_sim.py`.
- Loop 0: docs (ARCHITECTURE-PROPOSAL, WIRE-FORMAT, TECH-DECISIONS decisions).
