"""OpenMuscle FlexGrid UDP simulator.

Emits OpenMuscle protocol v1.0 JSON frames so the phone app (or the PC app) can
be exercised with no hardware. A moving Gaussian "press" sweeps the 15x4 matrix
so the heatmap visibly animates. Optionally also emits LASK5 label frames.

Examples:
    python tools/openmuscle_sim.py --target 192.168.1.50      # unicast to the phone
    python tools/openmuscle_sim.py --broadcast                # 255.255.255.255
    python tools/openmuscle_sim.py --target 127.0.0.1 --lask5 # feed the PC app + labels
    python tools/openmuscle_sim.py --selftest                 # send+recv+parse locally

To find the phone IP: on the phone, Settings > About > Status, or the device
picker once phase 1.5 lands. The firmware/sim must target the phone's IP (or
broadcast) because the phone listens on UDP 3141.
"""

import argparse
import json
import math
import socket
import sys
import time
from pathlib import Path

ROWS, COLS = 4, 15
DEFAULT_PORT = 3141


def frame_matrix(t: float):
    """Column-major matrix[col][row] with a moving blob. Values 0..4095."""
    cx = (math.sin(t * 1.2) * 0.5 + 0.5) * (COLS - 1)
    cy = (math.cos(t * 0.8) * 0.5 + 0.5) * (ROWS - 1)
    mat = []
    for c in range(COLS):
        col = []
        for r in range(ROWS):
            d2 = (c - cx) ** 2 + (r - cy) ** 2
            v = int(300 + 3500 * math.exp(-d2 / 3.0))
            col.append(max(0, min(4095, v)))
        mat.append(col)
    return mat


def flexgrid_packet(t: float, device_id: str, with_meta: bool):
    pkt = {
        "v": "1.0", "type": "flexgrid", "id": device_id, "ts": int(t * 1000),
        "data": {"matrix": frame_matrix(t), "rows": ROWS, "cols": COLS},
    }
    if with_meta:
        pkt["meta"] = {
            "vbat": round(4.0 + 0.2 * math.sin(t / 10), 3),
            "pct": 90 + int(5 * math.sin(t / 30)),
            "rssi": -55 - int(10 * abs(math.sin(t / 7))),
        }
    return pkt


def lask5_packet(t: float):
    blob = int(2000 + 1500 * math.sin(t * 1.2))
    return {
        "v": "1.0", "type": "lask5", "id": "lask5-sim01", "ts": int(t * 1000),
        "data": {"values": [blob, 4095 - blob, blob // 2, 4095 - blob // 2],
                 "joystick": {"x": 2048, "y": 2048}},
    }


def announce_packet(device_id: str, port: int):
    """Discovery beacon (docs/DEVICE-DISCOVERY-SPEC.md, WIRE-FORMAT 8.1). Broadcast
    ~1 Hz so a hub can find the device with no hardcoded IP. `role`/`services` are
    the spec's target fields; `port` is kept as a compat alias for services.sensor."""
    return {
        "v": "1.0", "type": "announce", "id": device_id, "role": "source",
        "dev": "flexgrid", "fw": "0.1.7", "transports": ["wifi"],
        "caps": ["sensor", "status", "cmd"], "matrix": [COLS, ROWS],
        "services": {"sensor": port, "cmd": 8001}, "port": port,
    }


def selftest() -> int:
    """Send a few frames to a local socket, receive them back, and parse them
    through the real PC parser if it is reachable. Verifies the simulator emits
    PC-compatible packets without needing a phone or the network."""
    pc_src = Path(__file__).resolve().parents[2] / "OpenMuscle-Software" / "pc" / "src"
    parse = None
    if pc_src.is_dir():
        sys.path.insert(0, str(pc_src))
        try:
            from openmuscle.protocol.parser import parse_packet as parse  # noqa
        except Exception:
            parse = None

    rx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    rx.bind(("127.0.0.1", 0))
    rx.settimeout(2.0)
    port = rx.getsockname()[1]
    tx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    sent = 0
    for i in range(5):
        t = i * 0.02
        tx.sendto(json.dumps(flexgrid_packet(t, "flexgrid-sim01", i == 0)).encode(),
                  ("127.0.0.1", port))
        data, _ = rx.recvfrom(8192)
        if parse is not None:
            p = parse(data)
            assert p is not None and p.device_type == "flexgrid", "parse failed"
            m = p.data["matrix"]
            assert len(m) == COLS and len(m[0]) == ROWS, "matrix dims wrong"
        sent += 1

    # Also verify an announce beacon round-trips and parses.
    tx.sendto(json.dumps(announce_packet("flexgrid-sim01", DEFAULT_PORT)).encode(),
              ("127.0.0.1", port))
    data, _ = rx.recvfrom(8192)
    if parse is not None:
        p = parse(data)
        assert p is not None and p.device_type == "announce", "announce parse failed"
    sent += 1

    suffix = " via the real PC parser" if parse else " (PC parser not found; structure-only)"
    print(f"selftest OK: {sent} frames sent, received, and validated{suffix}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="OpenMuscle FlexGrid UDP simulator")
    ap.add_argument("--target", default=None, help="destination IP (phone or PC)")
    ap.add_argument("--broadcast", action="store_true", help="send to 255.255.255.255")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    ap.add_argument("--hz", type=float, default=50.0)
    ap.add_argument("--lask5", action="store_true", help="also emit LASK5 label frames")
    ap.add_argument("--announce", action="store_true", help="also emit ~1 Hz discovery beacons")
    ap.add_argument("--id", default="flexgrid-sim01")
    ap.add_argument("--count", type=int, default=0, help="frames to send (0 = forever)")
    ap.add_argument("--selftest", action="store_true", help="local send/recv/parse check")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    if args.broadcast or args.target is None:
        dest = ("255.255.255.255", args.port)
        bcast = True
    else:
        dest = (args.target, args.port)
        bcast = False

    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    if bcast:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)

    period = 1.0 / args.hz
    t0 = time.time()
    n = 0
    print(f"Sending FlexGrid frames to {dest} at {args.hz:.0f} Hz"
          + (" + LASK5 labels" if args.lask5 else "") + " (Ctrl-C to stop)")
    try:
        beacon_every = max(1, int(args.hz))   # roughly once per second
        while args.count == 0 or n < args.count:
            t = time.time() - t0
            s.sendto(json.dumps(flexgrid_packet(t, args.id, n % 50 == 0)).encode(), dest)
            if args.lask5:
                s.sendto(json.dumps(lask5_packet(t)).encode(), dest)
            if args.announce and n % beacon_every == 0:
                s.sendto(json.dumps(announce_packet(args.id, args.port)).encode(), dest)
            n += 1
            time.sleep(period)
    except KeyboardInterrupt:
        pass
    print(f"\nstopped after {n} frames")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
