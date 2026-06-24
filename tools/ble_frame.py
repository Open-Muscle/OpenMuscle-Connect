"""Reference encoder/decoder for the compact-binary BLE sensor frame.

Implements the layout in docs/WIRE-FORMAT.md section 7 (little-endian):

    uint8   version      (1)
    uint8   device_type  (0 = flexgrid)
    uint16  seq
    uint32  ts_ms
    uint16  values[60]   (row-major R0C0..R3C14)
    total = 8 + 120 = 128 bytes

This is the spec for the firmware BLE work and the phone's BleFrameCodec. Its
--selftest prints the canonical hex that BleFrameCodecTest.kt decodes, so the
Python and Kotlin sides are cross-verified.

    python tools/ble_frame.py --selftest
"""

import argparse
import struct

ROWS, COLS = 4, 15
HEADER = "<BBHI"          # version, device_type, seq, ts_ms
BODY = "<%dH" % (ROWS * COLS)
FRAME_SIZE = struct.calcsize(HEADER) + struct.calcsize(BODY)   # 128


def encode(matrix, seq, ts_ms, version=1, device_type=0):
    """matrix is column-major matrix[col][row]; values are packed row-major."""
    flat = [matrix[c][r] for r in range(ROWS) for c in range(COLS)]
    return struct.pack(HEADER, version, device_type, seq, ts_ms) + struct.pack(BODY, *flat)


def decode(b):
    version, device_type, seq, ts = struct.unpack_from(HEADER, b, 0)
    flat = list(struct.unpack_from(BODY, b, struct.calcsize(HEADER)))
    matrix = [[flat[r * COLS + c] for r in range(ROWS)] for c in range(COLS)]
    return version, device_type, seq, ts, matrix


def selftest():
    matrix = [[c * 100 + r for r in range(ROWS)] for c in range(COLS)]  # matrix[col][row]
    frame = encode(matrix, seq=7, ts_ms=123456)
    assert len(frame) == FRAME_SIZE, f"size {len(frame)} != {FRAME_SIZE}"

    version, dtype, seq, ts, decoded = decode(frame)
    ok = (
        version == 1 and dtype == 0 and seq == 7 and ts == 123456
        and decoded == matrix
        and decoded[1][0] == 100   # matrix[col1][row0], catches a transpose
        and decoded[0][1] == 1     # matrix[col0][row1]
    )
    print(f"frame size: {len(frame)} bytes")
    print(f"canonical hex (seq=7, ts=123456, matrix[c][r]=100c+r):")
    print(f"  {frame.hex()}")
    print("PASS" if ok else "FAIL")
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        raise SystemExit(selftest())
    print(f"FRAME_SIZE = {FRAME_SIZE}")


if __name__ == "__main__":
    main()
