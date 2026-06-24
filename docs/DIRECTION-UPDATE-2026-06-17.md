# Direction update from Tory: 2026-06-17

Read this at the top of your next session. It updates the architecture direction in ways that affect phases 3 and beyond. Phases 1 and 2 of your current plan (Kotlin scaffold, UDP receiver, heatmap, capture + matcher + PC-compatible CSV export) are unaffected; keep going on those.

Your work in `ARCHITECTURE-PROPOSAL.md`, `WIRE-FORMAT.md`, and `TECH-DECISIONS.md` is good. The source reads were real, the corrections you made (RandomForest not SGDRegressor, JSON not packed binary) are right, and the phase plan is sensible. This document does not invalidate any of that; it extends it.

The full text of Tory's team-facing architecture proposal is appended at the bottom of this file as an appendix. Read that first if you want the unfiltered version, then come back to the per-section breakdown below.

---

## 1. Devices stop using hardcoded destination IPs

Discovery becomes a first-class part of the protocol.

**Wi-Fi discovery:** mDNS as primary, UDP broadcast beacon as fallback. mDNS gets blocked on some Wi-Fi configurations (corporate networks, captive portals, certain consumer routers with multicast disabled), so the broadcast fallback is needed for field reliability. Both ESP32-S3 and Android have native mDNS support.

**BLE discovery:** standard BLE advertising plus GATT service scan. The phone scans for devices advertising the OpenMuscle service UUID.

Implications for your code:
- Phase 1 (UDP receiver + heatmap) needs a small phase 1.5 immediately after: mDNS resolver + UDP broadcast listener. Without it, your phone only receives data when the firmware happens to be hardcoded to its IP, which is the world your briefing assumed but not the world Tory is moving to.
- The phone needs to surface "which device is which" in the UI when multiple FlexGrids are nearby. Use the device's `id` field (e.g., `flexgrid-v3-01`) plus a friendly nickname the user can set.

## 2. Both Bluetooth and Wi-Fi are first-class transports for v1

**This is the biggest direction shift.** BLE is not a "later phase." Tory wants the dev-facing app to support both modes equally so users can pick whichever fits their environment. Wi-Fi for high-bandwidth lab use, BLE for self-contained field use with no network.

This means every protocol capability must work on BOTH transports:

| Capability | Wi-Fi | BLE |
|---|---|---|
| Discovery | mDNS + UDP broadcast | BLE advertising + GATT service scan |
| Sensor stream | UDP 3141 JSON | GATT notify, compact binary |
| Label stream | UDP 3141 JSON | GATT notify |
| Commands (start/stop, set scan rate) | WebSocket or TCP | GATT write characteristic |
| Status / heartbeat | WebSocket push or UDP frame | GATT notify or read characteristic |
| Session / recording control | WebSocket | GATT write characteristic |
| Multi-hub support | UDP unicast to N subscribers | Limited by BLE central count (~3 typical, varies by stack) |

Implications:

- Phase 3 in your current plan becomes "BLE transport at parity with Wi-Fi," not "BLE as a later add."
- Your `TransportLayer` abstraction in `ARCHITECTURE-PROPOSAL.md` section 3.1 needs to span commands and status, not just sensor frames. Define interface methods for `send_command`, `subscribe_status`, etc., and implement them on both UDP/WebSocket and BLE GATT.
- Phone UI needs an explicit "connection mode" choice. Either a startup screen ("Connect via Wi-Fi" / "Connect via Bluetooth") or auto-detect with a manual override in settings. Settle this early because it shapes the discovery sequence and the UI's first-run experience.
- BLE multi-hub support is constrained by the radio. Acknowledge in the spec that BLE multi-hub is capped at a few connections while Wi-Fi UDP scales arbitrarily. For dev scenarios where multiple hubs need to watch one device simultaneously, Wi-Fi is the right transport.
- V3 firmware will likely stay Wi-Fi only. Adding BLE GATT to MicroPython on ESP32-S3 is non-trivial work, and the firmware team's bandwidth is going to V4 bring-up. Document in the README that BLE transport is V4 firmware onwards; V3 users get Wi-Fi only.

## 3. Protocol expands to cover the full message taxonomy

`WIRE-FORMAT.md` currently covers sensor, label, and Quest frame types. It needs to add the following at draft level (TBD sections to be ratified with the firmware effort):

- **Announcement / discovery**: device broadcasts its presence (mDNS service record on Wi-Fi, BLE advertisement on Bluetooth). Includes device id, type, firmware version, supported transports, supported capabilities.
- **Device info / capabilities** (response to a query from a hub): firmware version, scan rate range, sensor matrix dimensions, IMU presence, battery state, RSSI.
- **Commands** (hub to device): start/stop stream, set scan rate, set subscriber list, set scan-rate, reboot, factory-reset. Versioned and acked.
- **Status / heartbeat**: roughly 1 Hz on both transports, carries battery, RSSI, free memory, current scan rate, time sync ping-pong fields.
- **Session / recording control**: start session (with metadata: user, location, intent), end session, reject a session (e.g., bad data).

Each message type gets a representation on BOTH transports: JSON envelope on Wi-Fi UDP/WebSocket, compact binary frame on BLE GATT. The semantic content is identical; only the encoding differs. Aim for a binary BLE frame layout that mirrors the JSON field layout one-to-one so a single shared decoder can produce the same internal object regardless of transport.

## 4. Multi-hub support

Device should stream to multiple hubs simultaneously, useful in dev for "phone records while desktop watches in parallel."

**Wi-Fi model**: hubs send a subscribe command to the device. Device maintains a small list of subscriber IPs/ports and unicasts each sensor frame to all of them. A subscription times out if the hub stops sending heartbeats so the list does not grow stale.

**BLE model**: limited by the radio (~3 centrals typical). Either accept that BLE multi-hub is capped, or use BLE 5.0 extended advertising for connection-less broadcasts (lower update rate, no per-hub state). For v1, accept the cap and document it.

## 5. Wi-Fi command channel

Commands need a reliable path on Wi-Fi too. WebSocket connection from phone to device (or vice versa) carries the command message types. Don't make commands BLE-only; they should work on whichever transport the user picked.

Suggested: device runs a small WebSocket server on the same port range as the existing PC web UI (8000 family), exposes a `/cmd` endpoint that accepts JSON command messages and replies with acks. The phone opens a WebSocket to that endpoint once discovery completes.

## 6. Phone-as-hotspot mode (v1.5 or v2)

Stretch goal. The phone provides its own Wi-Fi network so the wearable can connect with no external infrastructure. iOS heavily restricts programmatic hotspot control. Android allows it with caveats; the user often has to enable hotspot manually.

For v1, document that the user can enable hotspot manually and that the wearable will need to be re-provisioned onto the phone's SSID. Do not try to control hotspot programmatically in v1.

## Gaps to address in the spec

These came up in the architecture discussion and aren't yet handled in `WIRE-FORMAT.md`:

- **Time sync**: today implicit via receive-time matching, which is fine for sensor-label pairing within a session. Add a heartbeat message with device-side and hub-side timestamps so the hub can compute a clock offset for telemetry, debugging, and any future cross-device analysis. NTP-style ping-pong on session start, periodic re-sync, exposed as a simple "device clock offset" in the UI.
- **Device identity**: partially in v1.0 protocol as the `id` field (e.g., `"flexgrid-v3-01"`). Define how IDs are minted: UUID burned to NVS at first boot, with a friendly fallback like `<board>-<6-char-hash>` for display in the UI. Two devices in the same building must never collide.

## What this means for your phase plan

| Phase | Updated description | Status |
|---|---|---|
| 0 | This proposal + `WIRE-FORMAT.md` | Done, ready for Tory review |
| 1 | Kotlin scaffold + UDP receiver + live heatmap | Unblocked, start coding |
| 1.5 (NEW) | mDNS + UDP broadcast discovery on Wi-Fi, with the multi-device UI surface | Required before phase 1 actually works without hardcoded IPs |
| 2 | Session capture + matcher + PC-compatible CSV export | Unblocked, start coding |
| 3 | BLE transport at full parity with Wi-Fi (was: BLE as later add) | Depends on firmware BLE work for V4 |
| 4 | ONNX inference on phone | Unchanged |
| 5 | VR pairing (phone as hub) | Unchanged |

The Wi-Fi command channel (WebSocket) and the new message types in `WIRE-FORMAT.md` belong roughly between phase 2 and phase 3; specify them at draft level now, implement the Wi-Fi side as you naturally hit each capability, then the BLE side in phase 3 mirrors what you already have.

## Specific asks of you (Connect Claude)

1. Update `WIRE-FORMAT.md` with TBD sections for the five new message types listed in part 3 above. Spec each one at draft level so the firmware effort has a starting point. Cover both Wi-Fi JSON and BLE binary representations.
2. Add a "Discovery" subsection to `ARCHITECTURE-PROPOSAL.md` covering both Wi-Fi (mDNS + broadcast) and BLE (advertising + scan), and how the phone surfaces "which device is which" when multiple FlexGrids are nearby.
3. Add a "Connection mode" subsection to `ARCHITECTURE-PROPOSAL.md` covering the user-facing choice between BLE and Wi-Fi, and how the `TransportLayer` abstraction extends to commands and status (not just sensor frames). Spell out the interface methods.
4. Update `TECH-DECISIONS.md` section 3 (BLE service design) with a decision pointing at the new direction: BLE is now first-class equal-tier transport, not "later phase."
5. Flag any conflicts you see between this new direction and your existing decisions. Don't silently absorb a conflict. If something here breaks a design assumption you already made, surface it back to Tory before changing course.

These updates are docs and spec work, not code. The Kotlin scaffold for phase 1 can start in parallel; nothing about phase 1 changes.

---

## Appendix: Tory's full architecture proposal (verbatim)

This is the team-facing message Tory drafted. It is the source for the direction update above. Reproduced here so you have the full context, not just the summary.

> **Communication Architecture: Key Ideas**
> FlexGrid + LASK5 + Phone App + Desktop + VR
>
> Team,
> To move from hardcoded UDP to a flexible system where the Phone App can fully replace the Desktop as the hub (enabling standalone VR use), here are the core ideas:
>
> **Idea 1: Dynamic Discovery (No More Hardcoded IPs)**
> - Devices (FlexGrid and LASK5) announce themselves on the network at boot.
> - Phone and Desktop apps discover available devices automatically.
> - Suggested approach: mDNS (preferred) or simple UDP broadcast beacon.
>
> **Idea 2: Standardized Protocol**
> - Create one lightweight, versioned message format used by firmware, phone, and desktop.
> - Core message types to define:
>   - Discovery / Announcement / Pairing
>   - Device Info and Capabilities
>   - Sensor Data (FlexGrid)
>   - Label / Event (LASK5)
>   - Commands (hub to device)
>   - Status / Heartbeat / Error
>   - Session / Recording control
>
> **Idea 3: Hub-Centric Model**
> - Phone App becomes the primary runtime hub for end users (receives data, coordinates labeling, records, forwards to VR).
> - Desktop App stays the main dev / debug / VR-prototyping environment but uses the same protocol.
> - Devices should be able to send data to multiple hubs at once during development.
>
> **Idea 4: Transport Strategy**
> - UDP for high-rate real-time sensor streams (low latency).
> - TCP / WebSocket (or reliable channel) for commands, configuration, and important events.
> - Both Bluetooth and Wi-Fi are first-class transports because this is for devs and having both available will benefit others who may want to use one or the other.
>
> **Idea 5: Phone as Full Desktop Replacement**
> - Long-term goal: Phone App can do everything the Desktop App currently does for data collection, labeling, and VR streaming.
> - Optional: Phone can create its own Wi-Fi hotspot for fully self-contained operation (no external network required).
>
> **Idea 6: VR Connection**
> - VR headset connects to the active hub (Phone preferred for standalone / field use).
> - Hub acts as the bridge / relay for processed or raw data streams to VR.
> - During development, VR can still connect directly to Desktop.
>
> **Idea 7: Phased Approach (Suggested)**
> 1. Agree on discovery method and basic protocol schema.
> 2. Firmware prototype (discovery + streaming on both ESP32 devices).
> 3. Phone App MVP (discovery, data receive, basic hub functions + VR relay test).
> 4. Desktop updated to new protocol + full end-to-end testing.
> 5. Add labeling integration, recording, config, error handling, and polish.
>
> **Quick Questions for Feedback**
> - mDNS or simple UDP broadcast for discovery?
> - JSON for fast iteration, or CBOR / Protobuf for ESP32 efficiency?
> - Any concerns with UDP packet loss on Wi-Fi for sensor data?
> - What data rate / latency does FlexGrid actually need?
> - VR headset model and preferred connection method to phone (Wi-Fi, USB-C, etc.)?
> - Should we support multiple simultaneous hubs (phone + desktop) during dev?
>
> This keeps the system simple, future-proof, and phone-first while we continue using the desktop heavily for development.
> Thoughts? Which ideas should we tackle first?
>
> Thanks,
> Tory

### Answers to Tory's six questions (from the architecture discussion that produced this direction update)

1. **mDNS or UDP broadcast for discovery?** Both. mDNS as primary, UDP broadcast as fallback for awkward networks. ~30 lines on each side; worth it for field reliability.
2. **JSON or CBOR/Protobuf?** Mix. JSON for control plane (discovery, commands, status, heartbeat: low frequency, easy to debug). Compact binary for sensor frames at 59 Hz (60 by int16 = 120 bytes vs ~600+ bytes as JSON; matters with multiple devices). Do not pick one for everything.
3. **UDP packet loss on Wi-Fi for sensor data?** Acceptable. A dropped frame on a 59 Hz stream is a 17 ms gap, within human perception threshold. Add sequence numbers in the packet so the hub can detect loss and log it. Commands MUST go through the reliable channel.
4. **Data rate and latency FlexGrid needs?** Bandwidth: ~7 kB/s per band, easy. Latency budget: under 50 ms end-to-end from sensor scan to predicted hand pose displayed for VR comfort, under 100 ms acceptable for non-VR. Wi-Fi UDP gets ~5 to 15 ms typically. BLE notifications add 10 to 30 ms.
5. **VR headset and preferred connection?** Quest 3 with WebXR. Connection: Wi-Fi to the phone hub, both on the same network. USB-C is a tethered dev convenience only. Do not promise USB-C for production; field use is wireless.
6. **Multiple simultaneous hubs during dev?** Yes. Device maintains a small list of subscribed hubs and unicasts each sensor frame to all of them on Wi-Fi. BLE supports a smaller number (radio limit); accept this constraint.
