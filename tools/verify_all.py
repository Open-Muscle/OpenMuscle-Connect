"""Run every cross-implementation check and report one verdict.

These are the contracts the Android app shares with the PC code and firmware:
wire format, CSV layout, UDP/control/BLE message shapes, and the ONNX inference
bridge. Each is verified against real reference code (the PC app, a reference
server, or scikit-learn). Run this after touching anything protocol-shaped.

    python tools/verify_all.py
"""

import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent

CHECKS = [
    ("wire format (vs real PC parser / CSV / matcher)", ["wireformat_check.py"]),
    ("golden CSV (vs real PC CaptureWriter)", ["make_golden_csv.py"]),
    ("golden CSV v2 (schema-v2 byte contract)", ["make_golden_csv_v2.py"]),
    ("UDP simulator round-trip", ["openmuscle_sim.py", "--selftest"]),
    ("/cmd control server (vs ControlCodec strings)", ["cmd_server.py", "--selftest"]),
    ("BLE binary frame", ["ble_frame.py", "--selftest"]),
    ("ONNX export parity (sklearn vs onnxruntime)", ["export_onnx.py"]),
    ("discovery handshake (announce/subscribe/heartbeat)", ["discovery_demo.py", "--selftest"]),
]


def main():
    results = []
    for name, argv in CHECKS:
        proc = subprocess.run(
            [sys.executable, str(HERE / argv[0]), *argv[1:]],
            capture_output=True, text=True,
        )
        ok = proc.returncode == 0
        results.append((name, ok))
        print(f"[{'PASS' if ok else 'FAIL'}] {name}")
        if not ok:
            print("  --- stdout tail ---")
            print("  " + "\n  ".join(proc.stdout.strip().splitlines()[-8:]))
            print("  --- stderr tail ---")
            print("  " + "\n  ".join(proc.stderr.strip().splitlines()[-8:]))

    passed = sum(1 for _, ok in results if ok)
    print()
    print(f"RESULT: {passed}/{len(results)} checks passed")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
