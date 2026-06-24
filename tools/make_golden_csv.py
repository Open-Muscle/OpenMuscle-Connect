"""Generate and verify the golden capture CSV.

Drives the REAL PC CaptureWriter with fixed inputs and checks the exact bytes
(including the CRLF line terminator Python's csv module emits by default)
against the literal that the Kotlin CsvSessionWriterTest asserts. Run this if
either side changes so the phone's CSV stays byte-compatible with what the PC
trainer reads.

    python tools/make_golden_csv.py
"""

import sys
import tempfile
from pathlib import Path

PC_SRC = Path(__file__).resolve().parents[2] / "OpenMuscle-Software" / "pc" / "src"
if not PC_SRC.is_dir():
    print(f"FAIL: cannot find PC app source at {PC_SRC}")
    raise SystemExit(2)
sys.path.insert(0, str(PC_SRC))

from openmuscle.data.storage import CaptureWriter  # noqa: E402

ROWS, COLS = 2, 3

# The exact bytes the Kotlin CsvSessionWriter must reproduce. CRLF line endings
# are intentional: Python's csv.writer emits them, so the phone must too.
EXPECTED = (
    "timestamp,R0C0,R0C1,R0C2,R1C0,R1C1,R1C2,label_0,label_1\r\n"
    "1000,0,10,20,1,11,21,100,200\r\n"
    "1020,100,110,120,101,111,121,105,205\r\n"
)


def flatten(matrix):
    rows = len(matrix[0])
    cols = len(matrix)
    return [matrix[c][r] for r in range(rows) for c in range(cols)]


def main():
    m1 = [[0, 1], [10, 11], [20, 21]]      # matrix[col][row] = 10*col + row
    m2 = [[100, 101], [110, 111], [120, 121]]
    with tempfile.TemporaryDirectory() as td:
        path = str(Path(td) / "golden.csv")
        with CaptureWriter(output_path=path, matrix_rows=ROWS,
                           matrix_cols=COLS, label_count=2) as w:
            w.write_row(1000, flatten(m1), [100, 200])
            w.write_row(1020, flatten(m2), [105, 205])
        data = Path(path).read_bytes().decode("utf-8")

    print("real PC CaptureWriter output (repr):")
    print(" ", repr(data))
    if data == EXPECTED:
        print("PASS: real PC output matches the literal in CsvSessionWriterTest")
        return 0
    print("FAIL: real PC output differs from EXPECTED")
    print("  EXPECTED:", repr(EXPECTED))
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
