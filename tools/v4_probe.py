"""Probe a live V4 FlexGrid device and verify the discovery + control + stream
protocol end to end (announce beacon -> TCP get_info -> subscribe -> frames ->
unsubscribe). Use it to sanity-check any device on the network.

    python tools/v4_probe.py --ip 10.0.0.112

Matches the V4 firmware (FlexGridV4-Firmware/lib/{discovery,commands,
network_manager}.py): command channel is TCP newline-delimited JSON on the
device's services.cmd port; sensor frames unicast to subscribers only.
"""

import argparse
import json
import socket
import time


def listen_announce(port, timeout):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("", port))
    s.settimeout(timeout)
    try:
        while True:
            data, addr = s.recvfrom(2048)
            try:
                m = json.loads(data)
            except ValueError:
                continue
            if m.get("type") == "announce":
                return m, addr[0]
    except socket.timeout:
        return None, None
    finally:
        s.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ip", required=True, help="device IP (from the announce or DHCP)")
    ap.add_argument("--cmd-port", type=int, default=8001)
    ap.add_argument("--discovery-port", type=int, default=3141)
    ap.add_argument("--frames", type=int, default=10)
    args = ap.parse_args()

    print("[1] listening for announce beacon ...")
    announce, src = listen_announce(args.discovery_port, 3.0)
    if announce:
        print(f"    announce from {src}: {json.dumps(announce)}")
        cmd_port = announce.get("services", {}).get("cmd", args.cmd_port)
    else:
        print("    (no announce in 3s; device may have a subscriber already)")
        cmd_port = args.cmd_port

    # UDP socket the device will unicast frames to.
    recv = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    recv.bind(("0.0.0.0", 0))
    recv.settimeout(3.0)
    recv_port = recv.getsockname()[1]

    # TCP command channel (newline-delimited JSON).
    print(f"[2] connecting TCP {args.ip}:{cmd_port} ...")
    c = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    c.settimeout(5.0)
    c.connect((args.ip, cmd_port))
    f = c.makefile("rwb")
    mid = [0]

    def cmd(verb, **data):
        mid[0] += 1
        msg = {"v": "1.0", "type": "cmd", "id": "claude-probe", "msg_id": mid[0],
               "data": dict({"verb": verb}, **data)}
        f.write((json.dumps(msg) + "\n").encode("utf-8"))
        f.flush()
        return json.loads(f.readline().decode("utf-8"))

    print("[3] get_info ->", json.dumps(cmd("get_info")))
    sub = cmd("subscribe", port=recv_port, transport="wifi", hub_id="claude-probe")
    print("[4] subscribe ->", json.dumps(sub))

    print(f"[5] receiving up to {args.frames} frames on udp:{recv_port} ...")
    got, seqs, dims = 0, [], None
    for _ in range(args.frames):
        try:
            data, _ = recv.recvfrom(4096)
        except socket.timeout:
            break
        fm = json.loads(data)
        if fm.get("type") == "flexgrid" and "matrix" in (fm.get("data") or {}):
            got += 1
            seqs.append(fm.get("seq"))
            mat = fm["data"]["matrix"]
            dims = (len(mat), len(mat[0]) if mat else 0)
    print(f"    frames={got}  seqs={seqs}  matrix(cols,rows)={dims}")

    print("[6] unsubscribe ->", json.dumps(cmd("unsubscribe", port=recv_port, transport="wifi")))
    c.close()
    recv.close()

    ok = announce is not None and got > 0
    print("\nRESULT:", "PASS (announce + get_info + subscribe + frames)" if ok
          else "PARTIAL (see above)")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
