"""Reference OpenMuscle command-channel server (a stand-in V4 device).

Mirrors the V4 firmware (FlexGridV4-Firmware/lib/commands.py): a TCP server that
accepts newline-delimited JSON command lines and replies with one ack line each.
It exists so the Android TcpControlChannel has a runnable counterpart, and so the
exact JSON the Kotlin ControlCodec emits is proven to be accepted by a server
that speaks the same wire format as the firmware.

    python tools/cmd_server.py                 # serve on tcp://0.0.0.0:8001
    python tools/cmd_server.py --selftest      # spin up + round-trip locally

The CANONICAL_REQUESTS below are byte-identical to what ControlCodec produces;
ControlCodecTest.kt asserts the Kotlin side emits exactly these strings, and
tools/v4_probe.py sends the same shapes to real hardware.

(The pre-V4 WebSocket version is preserved at legacy/cmd_server.ws.py.)
"""

import argparse
import json
import socket
import threading

DEVICE_ID = "flexgrid-a3f9c1"
MAX_SUBSCRIBERS = 4

# Exactly what the Kotlin ControlCodec emits (see ControlCodecTest.kt).
CANONICAL_REQUESTS = {
    "get_info":
        '{"v":"1.0","type":"cmd","id":"flexgrid-a3f9c1","msg_id":1,'
        '"data":{"verb":"get_info"}}',
    "set_scan_rate":
        '{"v":"1.0","type":"cmd","id":"flexgrid-a3f9c1","msg_id":42,'
        '"data":{"verb":"set_scan_rate","interval_ms":17}}',
    "subscribe":
        '{"v":"1.0","type":"cmd","id":"flexgrid-a3f9c1","msg_id":2,'
        '"data":{"verb":"subscribe","port":3141,"transport":"wifi","hub_id":"hub-1"}}',
    "heartbeat":
        '{"v":"1.0","type":"cmd","id":"flexgrid-a3f9c1","msg_id":3,'
        '"data":{"verb":"heartbeat","port":3141,"transport":"wifi"}}',
}


def _ok(msg_id, verb, ack_data):
    data = {"verb": verb}
    data.update(ack_data or {})
    return {"v": "1.0", "type": "ack", "status": "ok", "msg_id": msg_id, "data": data}


def _err(msg_id, message, verb=None):
    data = {"message": message}
    if verb is not None:
        data["verb"] = verb
    return {"v": "1.0", "type": "ack", "status": "error", "msg_id": msg_id, "data": data}


class DeviceState:
    """Minimal in-memory mirror of the firmware's device_state for handlers."""

    def __init__(self):
        self.subscribers = []   # list of (host, port, transport)

    def handle(self, verb, data, peer):
        if verb == "subscribe":
            host = data.get("host") or peer[0]
            port = int(data["port"])
            transport = data.get("transport", "wifi")
            key = (host, port, transport)
            accepted = False
            if key not in self.subscribers and len(self.subscribers) < MAX_SUBSCRIBERS:
                self.subscribers.append(key)
                accepted = True
            elif key in self.subscribers:
                accepted = True
            return {"accepted": accepted, "subscriber_count": len(self.subscribers),
                    "max_subscribers": MAX_SUBSCRIBERS}
        if verb == "unsubscribe":
            host = data.get("host") or peer[0]
            port = int(data["port"])
            transport = data.get("transport", "wifi")
            key = (host, port, transport)
            removed = key in self.subscribers
            if removed:
                self.subscribers.remove(key)
            return {"removed": removed, "subscriber_count": len(self.subscribers)}
        if verb == "heartbeat":
            return {"refreshed": True}
        if verb == "get_info":
            return {"id": DEVICE_ID, "dev": "flexgrid", "fw": "v4.0.0",
                    "matrix": [15, 4], "caps": ["sensor", "status", "cmd", "imu"],
                    "subscribers": [{"host": h, "port": p} for (h, p, _t) in self.subscribers]}
        if verb == "set_scan_rate":
            ms = int(data["interval_ms"])
            if ms < 5 or ms > 2000:
                raise ValueError("interval_ms out of range (5..2000): {}".format(ms))
            return {"interval_ms": ms}
        if verb == "start_stream":
            return {"streaming": True}
        if verb == "stop_stream":
            return {"streaming": False}
        if verb == "reboot":
            return {"rebooting": True}
        raise KeyError("unknown_verb: " + str(verb))


def _ack_for(line, peer, state):
    try:
        pkt = json.loads(line.decode("utf-8"))
    except Exception as e:
        return _err(None, "invalid_json: {}".format(e))
    msg_id = pkt.get("msg_id")
    data = pkt.get("data") or {}
    verb = data.get("verb")
    if not verb:
        return _err(msg_id, "missing verb in data")
    try:
        ack_data = state.handle(verb, data, peer)
    except KeyError as e:
        return _err(msg_id, str(e).strip("'\""))
    except Exception as e:
        return _err(msg_id, str(e), verb=verb)
    return _ok(msg_id, verb, ack_data)


def _handle_client(conn, addr, state):
    print(f"client connected: {addr}", flush=True)
    buf = b""
    try:
        while True:
            chunk = conn.recv(4096)
            if not chunk:
                break
            buf += chunk
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                line = line.strip()
                if not line:
                    continue
                ack = _ack_for(line, addr, state)
                verb = (json.loads(line.decode("utf-8")).get("data") or {}).get("verb", "?")
                print(f"recv {verb} -> {ack['status']} {ack['data']}", flush=True)
                conn.sendall(json.dumps(ack).encode("utf-8") + b"\n")
    except Exception as e:
        print(f"client {addr} errored: {e}", flush=True)
    finally:
        conn.close()
        print(f"client disconnected: {addr}", flush=True)


def _serve(host, port):
    state = DeviceState()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((host, port))
    srv.listen(5)
    print(f"cmd server on tcp://{host}:{port} (Ctrl-C to stop)", flush=True)
    try:
        while True:
            conn, addr = srv.accept()
            threading.Thread(target=_handle_client, args=(conn, addr, state), daemon=True).start()
    except KeyboardInterrupt:
        pass
    finally:
        srv.close()


def _selftest() -> int:
    failures = []

    def check(name, cond):
        print(f"  [{'PASS' if cond else 'FAIL'}] {name}")
        if not cond:
            failures.append(name)

    state = DeviceState()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 0))
    srv.listen(1)
    port = srv.getsockname()[1]

    def serve_one():
        conn, addr = srv.accept()
        _handle_client(conn, addr, state)

    t = threading.Thread(target=serve_one)
    t.start()

    c = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    c.connect(("127.0.0.1", port))
    f = c.makefile("rwb")

    def round_trip(req):
        f.write(req.encode("utf-8") + b"\n")
        f.flush()
        return json.loads(f.readline().decode("utf-8"))

    r = round_trip(CANONICAL_REQUESTS["subscribe"])
    check("subscribe -> ok, accepted, count 1",
          r["status"] == "ok" and r["data"]["accepted"] and r["data"]["subscriber_count"] == 1)

    r = round_trip(CANONICAL_REQUESTS["get_info"])
    check("get_info -> ok, matrix [15,4], caps include imu",
          r["status"] == "ok" and r["data"]["matrix"] == [15, 4] and "imu" in r["data"]["caps"])

    r = round_trip(CANONICAL_REQUESTS["set_scan_rate"])
    check("set_scan_rate 17ms -> ok, interval_ms 17",
          r["status"] == "ok" and r["data"]["interval_ms"] == 17)

    r = round_trip(CANONICAL_REQUESTS["heartbeat"])
    check("heartbeat -> ok, refreshed", r["status"] == "ok" and r["data"]["refreshed"])

    # An out-of-range scan rate must come back as a status:error ack.
    bad = '{"v":"1.0","type":"cmd","id":"x","msg_id":9,"data":{"verb":"set_scan_rate","interval_ms":3}}'
    r = round_trip(bad)
    check("set_scan_rate 3ms -> error", r["status"] == "error" and "out of range" in r["data"]["message"])

    # Close the client so the server handler's recv() returns and its thread
    # exits; join it before returning so no thread prints during interpreter
    # shutdown (that would crash with a stdout-lock fatal error).
    c.close()
    t.join(timeout=2.0)
    srv.close()

    print()
    if failures:
        print(f"RESULT: FAIL ({len(failures)} checks)")
        return 1
    print("RESULT: PASS (Kotlin ControlCodec strings accepted; V4 acks correct)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="OpenMuscle V4 command-channel reference server")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8001)
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return _selftest()
    _serve(args.host, args.port)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
