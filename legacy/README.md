# legacy/ — preserved pre-V4 control plane

These files are NOT compiled into the app. They are a snapshot of the control
plane the phone used before the V4 firmware shipped, kept here so we can revert
if the TCP control channel ever needs to be swapped back out.

## What changed and why

The data plane never changed. Sensor frames are UDP in both V3 and V4
(`UdpReceiver` on port 3141). TCP is only the new **control** channel
(subscribe / heartbeat / get_info / set_scan_rate / start_stream / stop_stream /
reboot), which is low rate (commands plus a 1 Hz heartbeat), so TCP vs UDP
latency does not matter there.

Before V4, the app modeled the control plane as a **WebSocket** to a `/cmd`
endpoint. No OpenMuscle firmware ever actually served that; it was our forward
design. When the firmware team built V4 they chose raw TCP newline-delimited
JSON instead (see `FlexGridV4-Firmware/lib/commands.py`), which is lighter on the
ESP32 than a WebSocket stack. The live app now matches the firmware:
`app/.../transport/TcpControlChannel.kt`.

## Files here

| File | Was | Notes |
|---|---|---|
| `WebSocketControlChannel.kt` | `app/src/main/java/org/openmuscle/connect/transport/` | OkHttp WebSocket client, ack-by-msg_id, 1 Hz heartbeat. |
| `ControlCodec.pre-v4.kt` | `app/.../transport/ControlCodec.kt` | Old codec: top-level `get_info`/`session` types, `data.ok` ack, `scan_rate_hz`. |
| `cmd_server.ws.py` | `tools/cmd_server.py` | WebSocket reference server the old codec round-tripped against. |

## To revert to the WebSocket control plane

1. Restore `ControlCodec.pre-v4.kt` over the current `ControlCodec.kt` and
   `WebSocketControlChannel.kt` back into `transport/`.
2. Restore the old `Command` shape in `Messages.kt`
   (`SetScanRate(hz)`, `Subscribe(host, port)`, `Unsubscribe`) and the
   `SessionMeta` encode path.
3. Point `WiFiTransport.connectControl` back at `WebSocketControlChannel`
   (`ws://host:port/cmd`).
4. Restore `cmd_server.ws.py` over `tools/cmd_server.py`.
5. The firmware would also need a WebSocket `/cmd` server again; V4 does not have
   one.
