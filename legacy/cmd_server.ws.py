"""PRE-V4 SNAPSHOT (not used). See legacy/README.md.

Reference OpenMuscle /cmd control-channel server over a WebSocket. It accepted
cmd / get_info / session messages and replied with ack / info. It existed so the
old Android WebSocketControlChannel had a runnable counterpart, and so the exact
JSON the old Kotlin ControlCodec emitted was proven to be accepted by a server.

    python cmd_server.ws.py                 # serve on ws://0.0.0.0:8000/cmd
    python cmd_server.ws.py --selftest      # spin up + round-trip locally
"""

import argparse
import asyncio
import json

import websockets

DEVICE_ID = "flexgrid-a3f9c1"
KNOWN_VERBS = {
    "start_stream", "stop_stream", "set_scan_rate",
    "subscribe", "unsubscribe", "reboot", "factory_reset",
}

# Exactly what the old Kotlin ControlCodec emitted (pre-V4 ControlCodecTest.kt).
CANONICAL_REQUESTS = {
    "set_scan_rate":
        '{"v":"1.0","type":"cmd","id":"flexgrid-a3f9c1","msg_id":42,'
        '"data":{"verb":"set_scan_rate","scan_rate_hz":59}}',
    "get_info":
        '{"v":"1.0","type":"get_info","id":"flexgrid-a3f9c1","msg_id":1}',
    "session_start":
        '{"v":"1.0","type":"session","id":"flexgrid-a3f9c1","msg_id":7,'
        '"data":{"verb":"start","session_id":"sess-1",'
        '"meta":{"user":"tory","location":"lab","intent":"calibration"}}}',
    "subscribe":
        '{"v":"1.0","type":"cmd","id":"flexgrid-a3f9c1","msg_id":2,'
        '"data":{"verb":"subscribe","host":"192.168.1.50","port":3141}}',
}


def reply_for(msg: dict):
    t = msg.get("type")
    mid = msg.get("msg_id")
    if t == "heartbeat":
        return None   # keep-alive only; no reply
    if t == "cmd":
        verb = (msg.get("data") or {}).get("verb")
        if verb in KNOWN_VERBS:
            return {"v": "1.0", "type": "ack", "id": DEVICE_ID, "msg_id": mid, "data": {"ok": True}}
        return {"v": "1.0", "type": "ack", "id": DEVICE_ID, "msg_id": mid,
                "data": {"ok": False, "error": f"unknown verb {verb}"}}
    if t == "get_info":
        return {"v": "1.0", "type": "info", "id": DEVICE_ID, "ts": 0, "data": {
            "dev": "flexgrid", "fw": "0.1.7", "matrix": [15, 4],
            "scan_rate_hz": 59, "scan_rate_range": [10, 120], "imu": False,
            "battery": {"pct": 95, "vbat": 4.15},
            "transports": ["wifi"], "max_subscribers": 4}}
    if t == "session":
        return {"v": "1.0", "type": "ack", "id": DEVICE_ID, "msg_id": mid, "data": {"ok": True}}
    return {"v": "1.0", "type": "ack", "id": DEVICE_ID, "msg_id": mid,
            "data": {"ok": False, "error": f"unknown type {t}"}}


async def handler(ws):
    peer = getattr(ws, "remote_address", None)
    print(f"client connected: {peer}", flush=True)
    try:
        async for raw in ws:
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                continue
            reply = reply_for(msg)
            if msg.get("type") != "heartbeat":
                verb = (msg.get("data") or {}).get("verb", "")
                print(f"recv {msg.get('type')} {verb} -> {reply['data'] if reply else None}", flush=True)
            if reply is not None:
                await ws.send(json.dumps(reply))
    finally:
        print(f"client disconnected: {peer}", flush=True)


async def _selftest() -> int:
    failures = []

    def check(name, cond):
        print(f"  [{'PASS' if cond else 'FAIL'}] {name}")
        if not cond:
            failures.append(name)

    async with websockets.serve(handler, "127.0.0.1", 0) as server:
        port = server.sockets[0].getsockname()[1]
        async with websockets.connect(f"ws://127.0.0.1:{port}/cmd") as ws:
            await ws.send(CANONICAL_REQUESTS["set_scan_rate"])
            r = json.loads(await ws.recv())
            check("set_scan_rate -> ack ok, msg_id 42",
                  r["type"] == "ack" and r["data"]["ok"] and r["msg_id"] == 42)

            await ws.send(CANONICAL_REQUESTS["get_info"])
            r = json.loads(await ws.recv())
            check("get_info -> info, matrix [15,4]",
                  r["type"] == "info" and r["data"]["matrix"] == [15, 4])

            await ws.send(CANONICAL_REQUESTS["session_start"])
            r = json.loads(await ws.recv())
            check("session start -> ack ok", r["type"] == "ack" and r["data"]["ok"])

            await ws.send(CANONICAL_REQUESTS["subscribe"])
            r = json.loads(await ws.recv())
            check("subscribe -> ack ok", r["type"] == "ack" and r["data"]["ok"])

    print()
    if failures:
        print(f"RESULT: FAIL ({len(failures)} checks)")
        return 1
    print("RESULT: PASS (old Kotlin ControlCodec strings accepted; replies correct)")
    return 0


async def _serve(host, port):
    async with websockets.serve(handler, host, port):
        print(f"cmd server on ws://{host}:{port}/cmd (Ctrl-C to stop)")
        await asyncio.Future()


def main() -> int:
    ap = argparse.ArgumentParser(description="OpenMuscle /cmd reference server (pre-V4 WebSocket)")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8000)
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return asyncio.run(_selftest())
    try:
        asyncio.run(_serve(args.host, args.port))
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
