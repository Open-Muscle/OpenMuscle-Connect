"""Generate and verify the canonical schema-v2 capture CSV (the byte golden).

Unlike make_golden_csv.py (which drives the real v1 PC CaptureWriter), there is
no v2 writer on either side yet: vrpc aligns the PC CaptureWriter and the phone
implements CsvSessionWriter v2 TO this golden. So this script pins the exact
bytes with a minimal csv.writer reference (the same csv.writer both real writers
use), proving the format is achievable and giving both teams a byte-for-byte
target.

Schema (docs/CSV-SCHEMA-V2.md): one row per source frame, columns
    ts_hub_ms, role, device_id, R{r}C{c}..., label_0..M
Features are row-major R{r}C{c} (r outer; value k = matrix[c][r]); labels are
floats; ts_hub_ms is epoch milliseconds; CRLF line endings.

    python tools/make_golden_csv_v2.py
"""

import csv
import io


def flatten_row_major(matrix):
    """matrix[col][row] -> row-major flat list (the canonical order, board #0053)."""
    rows = len(matrix[0])
    cols = len(matrix)
    return [matrix[c][r] for r in range(rows) for c in range(cols)]


def header(rows, cols, n_labels):
    feats = [f"R{r}C{c}" for r in range(rows) for c in range(cols)]
    labels = [f"label_{i}" for i in range(n_labels)]
    return ["ts_hub_ms", "role", "device_id"] + feats + labels


def write(rows_data, rows, cols, n_labels):
    """rows_data: list of (ts_hub_ms, role, device_id, matrix[col][row], [labels])."""
    buf = io.StringIO()
    w = csv.writer(buf)   # csv default lineterminator is CRLF
    w.writerow(header(rows, cols, n_labels))
    for ts, role, dev, matrix, labels in rows_data:
        w.writerow([ts, role, dev] + flatten_row_major(matrix) + labels)
    return buf.getvalue()


# Single-source: one band (role=left), 2x2 matrix, 2 labels.
SINGLE = write([
    (1718000000000, "left", "fg-left", [[12, 20], [18, 25]], [1.0, 0.5]),
    (1718000000033, "left", "fg-left", [[13, 21], [19, 24]], [0.8, 0.5]),
], rows=2, cols=2, n_labels=2)

EXPECTED_SINGLE = (
    "ts_hub_ms,role,device_id,R0C0,R0C1,R1C0,R1C1,label_0,label_1\r\n"
    "1718000000000,left,fg-left,12,18,20,25,1.0,0.5\r\n"
    "1718000000033,left,fg-left,13,19,21,24,0.8,0.5\r\n"
)

# Bilateral: two bands (left + right) interleaved by arrival time. Same header;
# the role column disambiguates, so features stay R{r}C{c} (no L_/R_ prefix). The
# trainer pivots these long rows into the 120-col Left||Right matrix (8.4).
BILATERAL = write([
    (1718000000000, "left",  "fg-left",  [[12, 20], [18, 25]], [1.0, 0.5]),
    (1718000000007, "right", "fg-right", [[30, 22], [28, 19]], [1.0, 0.5]),
    (1718000000033, "left",  "fg-left",  [[13, 21], [19, 24]], [0.8, 0.5]),
    (1718000000040, "right", "fg-right", [[31, 23], [27, 18]], [0.8, 0.5]),
], rows=2, cols=2, n_labels=2)

EXPECTED_BILATERAL = (
    "ts_hub_ms,role,device_id,R0C0,R0C1,R1C0,R1C1,label_0,label_1\r\n"
    "1718000000000,left,fg-left,12,18,20,25,1.0,0.5\r\n"
    "1718000000007,right,fg-right,30,28,22,19,1.0,0.5\r\n"
    "1718000000033,left,fg-left,13,19,21,24,0.8,0.5\r\n"
    "1718000000040,right,fg-right,31,27,23,18,0.8,0.5\r\n"
)


def main():
    ok = True
    for name, got, exp in [("single-source", SINGLE, EXPECTED_SINGLE),
                           ("bilateral", BILATERAL, EXPECTED_BILATERAL)]:
        match = got == exp
        print(f"[{'PASS' if match else 'FAIL'}] {name}")
        print("   ", repr(got))
        if not match:
            print("    EXPECTED:", repr(exp))
            ok = False
    print()
    print("RESULT:", "PASS (canonical v2 bytes pinned; both writers must reproduce these)"
          if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
