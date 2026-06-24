"""Wire-format compatibility check for Open Muscle Connect.

This drives the REAL PC application code (parser, CaptureWriter, TemporalMatcher,
and the pandas training-data loader) with packets shaped exactly the way the
Android app will produce and consume them. It is the executable proof behind
docs/WIRE-FORMAT.md: if this passes, a session recorded by the phone in the
documented CSV layout loads through the PC pipeline with the column->value
mapping intact (no transpose) and trains with no changes.

Run from anywhere:
    python tools/wireformat_check.py

It needs the sibling OpenMuscle-Software repo checked out next to this one, plus
pandas (used by the PC dataset loader).
"""

import json
import sys
import tempfile
from pathlib import Path

# Locate the PC app source: ../../OpenMuscle-Software/pc/src relative to this file.
HERE = Path(__file__).resolve()
PC_SRC = HERE.parents[2] / "OpenMuscle-Software" / "pc" / "src"
if not PC_SRC.is_dir():
    print(f"FAIL: cannot find PC app source at {PC_SRC}")
    sys.exit(2)
sys.path.insert(0, str(PC_SRC))

from openmuscle.protocol.parser import parse_packet          # noqa: E402
from openmuscle.protocol.schema import OpenMusclePacket       # noqa: E402
from openmuscle.data.storage import CaptureWriter             # noqa: E402
from openmuscle.data.dataset import load_training_data, detect_columns  # noqa: E402
from openmuscle.receiver.matcher import TemporalMatcher       # noqa: E402

ROWS, COLS = 4, 15           # FlexGrid V3 / V4
PASS, FAIL = "PASS", "FAIL"
failures = []


def check(name, cond):
    print(f"  [{PASS if cond else FAIL}] {name}")
    if not cond:
        failures.append(name)


def build_flexgrid_matrix():
    """matrix[col][row] = col*100 + row. The off-diagonal values make any
    row/col transpose immediately visible (R0C1 must be 100, not 1)."""
    return [[c * 100 + r for r in range(ROWS)] for c in range(COLS)]


def flatten_row_major(matrix):
    """The one true flatten, matching web/state.py recorder and the
    CaptureWriter header. rows = len(matrix[0]), cols = len(matrix)."""
    rows = len(matrix[0])
    cols = len(matrix)
    return [matrix[c][r] for r in range(rows) for c in range(cols)]


def main():
    print("OpenMuscle wire-format compatibility check")
    print(f"  PC source: {PC_SRC}")

    # 1. Encode v1.0 packets the way the Android app will, parse with real PC parser.
    print("\n[1] Parse v1.0 JSON through the real PC parser")
    matrix = build_flexgrid_matrix()
    fg_bytes = json.dumps({
        "v": "1.0", "type": "flexgrid", "id": "flexgrid-a3f9c1", "ts": 12345,
        "data": {"matrix": matrix, "rows": ROWS, "cols": COLS},
        "meta": {"vbat": 4.15, "pct": 95, "rssi": -65},
    }).encode("utf-8")
    lask_bytes = json.dumps({
        "v": "1.0", "type": "lask5", "id": "lask5-01", "ts": 12346,
        "data": {"values": [11, 22, 33, 44], "joystick": {"x": 2048, "y": 1900}},
    }).encode("utf-8")

    fg = parse_packet(fg_bytes)
    lk = parse_packet(lask_bytes)
    check("flexgrid packet parses", fg is not None and fg.device_type == "flexgrid")
    check("lask5 packet parses", lk is not None and lk.device_type == "lask5")
    check("flexgrid id preserved", fg.device_id == "flexgrid-a3f9c1")
    check("flexgrid ts preserved", fg.timestamp_ms == 12345)
    check("battery meta preserved", fg.metadata.get("pct") == 95)
    check("lask5 values preserved", lk.data.get("values") == [11, 22, 33, 44])

    # 2. Flatten + write through the real CaptureWriter, reload through the real loader.
    print("\n[2] Round-trip a recorded row through CaptureWriter + dataset loader")
    flat = flatten_row_major(fg.data["matrix"])
    check("flat length is 60", len(flat) == ROWS * COLS)

    with tempfile.TemporaryDirectory() as td:
        csv_path = str(Path(td) / "capture_check.csv")
        with CaptureWriter(output_path=csv_path, matrix_rows=ROWS,
                           matrix_cols=COLS, label_count=4) as w:
            w.write_row(fg.receive_time, flat, lk.data["values"])
        X, y = load_training_data(csv_path)
        sensor_cols, label_cols = detect_columns(__import__("pandas").read_csv(csv_path))

        check("60 sensor columns detected", len(sensor_cols) == 60)
        check("4 label columns detected", len(label_cols) == 4)
        check("sensor column names are R{r}C{c} row-major",
              sensor_cols == [f"R{r}C{c}" for r in range(ROWS) for c in range(COLS)])

        # The transpose-catching assertions. matrix[col][row] = col*100+row, so:
        #   R0C0 = matrix[0][0] = 0
        #   R0C1 = matrix[1][0] = 100   (a col-major flatten would put 1 here)
        #   R1C0 = matrix[0][1] = 1     (a col-major flatten would put a big number here)
        #   R3C14 = matrix[14][3] = 1403
        check("R0C0 == matrix[0][0] (0)", int(X["R0C0"][0]) == 0)
        check("R0C1 == matrix[1][0] (100), not transposed", int(X["R0C1"][0]) == 100)
        check("R1C0 == matrix[0][1] (1), not transposed", int(X["R1C0"][0]) == 1)
        check("R0C14 == matrix[14][0] (1400)", int(X["R0C14"][0]) == 1400)
        check("R3C14 == matrix[14][3] (1403)", int(X["R3C14"][0]) == 1403)
        check("labels round-trip", list(y.iloc[0]) == [11, 22, 33, 44])

    # 3. Temporal matcher behaviour (nearest label within window, on receive time).
    print("\n[3] TemporalMatcher nearest-within-window")
    m = TemporalMatcher(window_s=0.100)

    def lbl(rt, vals):
        return OpenMusclePacket("1.0", "lask5", "lask5-01", 0,
                                {"values": vals}, {}, receive_time=rt)

    def sensor(rt):
        return OpenMusclePacket("1.0", "flexgrid", "flexgrid-a3f9c1", 0,
                                {"matrix": matrix}, {}, receive_time=rt)

    m.add_label(lbl(1.000, [1, 1, 1, 1]))   # 50 ms before -> in window
    m.add_label(lbl(1.080, [2, 2, 2, 2]))   # 30 ms after  -> closer
    matched = m.match(sensor(1.050))
    check("matches the nearest label within window", matched is not None and matched.data["values"] == [2, 2, 2, 2])

    m2 = TemporalMatcher(window_s=0.100)
    m2.add_label(lbl(1.000, [9, 9, 9, 9]))
    far = m2.match(sensor(2.000))           # 1 s away -> no match, dropped
    check("drops sensor frame with no label in window", far is None and m2.unpaired_count == 1)

    # Boundary (float-safe, away from the exact tick): 99 ms in, 101 ms out.
    m3 = TemporalMatcher(window_s=0.100)
    m3.add_label(lbl(1.000, [3, 3, 3, 3]))
    check("99 ms gap matches (clearly inside)", m3.match(sensor(1.099)) is not None)
    m4 = TemporalMatcher(window_s=0.100)
    m4.add_label(lbl(1.000, [4, 4, 4, 4]))
    check("101 ms gap does not match (clearly outside)", m4.match(sensor(1.101)) is None)

    # Report.
    print()
    if failures:
        print(f"RESULT: FAIL ({len(failures)} failing checks)")
        for f in failures:
            print(f"  - {f}")
        sys.exit(1)
    print("RESULT: PASS (all checks green) -- Android port can mirror this logic 1:1")


if __name__ == "__main__":
    main()
