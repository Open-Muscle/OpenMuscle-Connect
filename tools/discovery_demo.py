"""Runnable reference of the OpenMuscle discovery handshake.

Demonstrates the subscribe model from docs/DEVICE-DISCOVERY-SPEC.md end to end, so
the firmware team has working pseudo-code for the hardest new part:

    source announces  ->  hub discovers  ->  hub subscribes (gives its address)
    ->  source unicasts frames to the hub  ->  hub heartbeats to stay subscribed
    ->  hub stops  ->  source drops the subscription on heartbeat timeout

No hardcoded addresses: the hub learns the source's command port from the announce
and the source learns the hub's receive address from the subscribe.

    python tools/discovery_demo.py --selftest          # run both locally, assert
    python tools/discovery_demo.py --source            # a source for a real hub
    python tools/discovery_demo.py --hub               # a hub for a real source

Stdlib only.
"""

import argparse
import json
import socket
import threading
import time

LOOPBACK = "127.0.0.1"


def announce_msg(device_id, cmd_port):
    return json.dumps({
        "v": "1.0", "type": "announce", "id": device_id, "role": "source",
        "dev": "flexgrid", "fw": "demo", "transports": ["wifi"],
        "caps": ["sensor", "status", "cmd"], "matrix": [3, 2],
        "services": {"sensor": 3141, "cmd": cmd_port},
    }).encode()


def sensor_frame(device_id, seq):
    return json.dumps({
        "v": "1.0", "type": "flexgrid", "id": device_id, "seq": seq,
        "ts": seq * 20, "data": {"matrix": [[0, 1], [10, 11], [20, 21]]},
    }).encode()


class SimSource:
    """A FlexGrid-like source: announces, accepts subscribe/heartbeat/unsubscribe,
    and unicasts frames to every live subscriber."""

    def __init__(self, device_id="flexgrid-demo", announce_addr=(LOOPBACK, 3141),
                 hz=50.0, hb_timeout=0.6, announce_period=0.2):
        self.device_id = device_id
        self.announce_addr = announce_addr
        self.hz = hz
        self.hb_timeout = hb_timeout
        self.announce_period = announce_period
        self.subscribers = {}                 # (host, port) -> last heartbeat time
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self.cmd_port = None

    def start(self):
        self._cmd = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._cmd.bind((LOOPBACK, 0))
        self._cmd.settimeout(0.1)
        self.cmd_port = self._cmd.getsockname()[1]
        self._tx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._tx.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        threading.Thread(target=self._cmd_loop, daemon=True).start()
        threading.Thread(target=self._produce_loop, daemon=True).start()
        return self

    def stop(self):
        self._stop.set()

    def _prune(self):
        now = time.time()
        with self._lock:
            for key in [k for k, t in self.subscribers.items() if now - t > self.hb_timeout]:
                del self.subscribers[key]

    def _cmd_loop(self):
        while not self._stop.is_set():
            try:
                data, _ = self._cmd.recvfrom(4096)
            except socket.timeout:
                self._prune()
                continue
            try:
                msg = json.loads(data)
            except json.JSONDecodeError:
                continue
            d = msg.get("data") or {}
            verb = d.get("verb") or msg.get("type")
            key = (d.get("host"), d.get("port"))
            if verb == "subscribe" and key[0] is not None:
                with self._lock:
                    self.subscribers[key] = time.time()
                self._cmd.sendto(json.dumps({"v": "1.0", "type": "ack",
                                 "id": self.device_id, "data": {"ok": True}}).encode(),
                                 (LOOPBACK, key[1]))
            elif verb in ("heartbeat",) and key[0] is not None:
                with self._lock:
                    if key in self.subscribers:
                        self.subscribers[key] = time.time()
            elif verb == "unsubscribe" and key[0] is not None:
                with self._lock:
                    self.subscribers.pop(key, None)
            self._prune()

    def _produce_loop(self):
        seq = 0
        last_announce = 0.0
        period = 1.0 / self.hz
        while not self._stop.is_set():
            now = time.time()
            if now - last_announce >= self.announce_period:
                self._tx.sendto(announce_msg(self.device_id, self.cmd_port), self.announce_addr)
                last_announce = now
            with self._lock:
                subs = list(self.subscribers.keys())
            frame = sensor_frame(self.device_id, seq)
            for host, port in subs:
                self._tx.sendto(frame, (host, port))
            seq += 1
            time.sleep(period)


def run_hub(discovery_port, frames_wanted=15, discover_timeout=3.0, heartbeat=True,
            log=lambda *_: None):
    """Discover a source, subscribe, receive frames. Returns (source_cmd_port,
    frames_received, recv_port, stop_heartbeat_fn)."""
    disc = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    disc.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    disc.bind(("", discovery_port))
    disc.settimeout(discover_timeout)

    recv = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    recv.bind((LOOPBACK, 0))
    recv.settimeout(2.0)
    recv_port = recv.getsockname()[1]

    # 1. Discover: wait for an announce, read the source's cmd port from services.
    deadline = time.time() + discover_timeout
    source_cmd = None
    while time.time() < deadline:
        try:
            data, _ = disc.recvfrom(4096)
        except socket.timeout:
            break
        msg = json.loads(data)
        if msg.get("type") == "announce" and msg.get("role") == "source":
            source_cmd = msg["services"]["cmd"]
            log("discovered", msg["id"], "cmd port", source_cmd)
            break
    if source_cmd is None:
        raise RuntimeError("no source discovered")

    # 2. Subscribe with our receive address.
    cmd = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sub = {"v": "1.0", "type": "cmd", "id": "hub-demo", "msg_id": 1,
           "data": {"verb": "subscribe", "host": LOOPBACK, "port": recv_port, "transport": "wifi"}}
    cmd.sendto(json.dumps(sub).encode(), (LOOPBACK, source_cmd))

    # 3. Heartbeat to stay subscribed.
    stop_hb = threading.Event()

    def _hb():
        hb = {"v": "1.0", "type": "heartbeat", "id": "hub-demo",
              "data": {"host": LOOPBACK, "port": recv_port}}
        while not stop_hb.is_set():
            cmd.sendto(json.dumps(hb).encode(), (LOOPBACK, source_cmd))
            time.sleep(0.2)
    if heartbeat:
        threading.Thread(target=_hb, daemon=True).start()

    # 4. Receive frames.
    got = 0
    while got < frames_wanted:
        try:
            data, _ = recv.recvfrom(4096)
        except socket.timeout:
            break
        if json.loads(data).get("type") == "flexgrid":
            got += 1
    log("received", got, "frames")
    return source_cmd, got, recv_port, stop_hb.set


def selftest():
    failures = []

    def check(name, cond):
        print(f"  [{'PASS' if cond else 'FAIL'}] {name}")
        if not cond:
            failures.append(name)

    port = _free_udp_port()
    source = SimSource(announce_addr=(LOOPBACK, port)).start()
    try:
        _, got, recv_port, stop_hb = run_hub(port, frames_wanted=15)
        check("hub discovered source and subscribed", source.cmd_port is not None)
        check("hub received >= 15 frames", got >= 15)
        check("source has the hub as a subscriber", (LOOPBACK, recv_port) in source.subscribers)

        # Stop heartbeating; the source must drop us after the timeout.
        stop_hb()
        time.sleep(source.hb_timeout + 0.4)
        check("source dropped the subscriber after heartbeat timeout",
              (LOOPBACK, recv_port) not in source.subscribers)
    finally:
        source.stop()

    print()
    if failures:
        print(f"RESULT: FAIL ({len(failures)} checks)")
        return 1
    print("RESULT: PASS (announce -> subscribe -> stream -> heartbeat-timeout-drop)")
    return 0


def _free_udp_port():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind((LOOPBACK, 0))
    p = s.getsockname()[1]
    s.close()
    return p


def main():
    ap = argparse.ArgumentParser(description="OpenMuscle discovery handshake demo")
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--source", action="store_true")
    ap.add_argument("--hub", action="store_true")
    ap.add_argument("--port", type=int, default=3141, help="discovery/announce port")
    args = ap.parse_args()

    if args.selftest:
        raise SystemExit(selftest())
    if args.source:
        src = SimSource(announce_addr=("255.255.255.255", args.port), announce_period=1.0).start()
        print(f"source up: announcing on {args.port}, cmd port {src.cmd_port} (Ctrl-C to stop)")
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            src.stop()
        return
    if args.hub:
        _, got, _, _ = run_hub(args.port, frames_wanted=10_000_000,
                               log=lambda *a: print(*a))
        print(f"hub received {got} frames")
        return
    ap.print_help()


if __name__ == "__main__":
    main()
