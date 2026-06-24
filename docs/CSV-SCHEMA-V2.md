# Capture CSV schema v2 (multi-device)

**Status:** PROPOSAL for sign-off by phone (owner) + vrpc (PC writer/trainer). Aligns with
PROTOCOL.md sections 8.1 to 8.5. The phone owns this as the byte reference both writers match.

This defines the capture CSV the phone (and PC) write once multi-device capture lands. v1 was
single-source (`timestamp, R{r}C{c}..., label_0..N`). v2 adds role tagging and multiple sources.

## 1. The canonical row (long format)

One row per **source frame**, written as it arrives:

```
ts_hub_ms, role, device_id, R0C0, R0C1, ..., R3C14, label_0, ..., label_M
```

| Column | Meaning |
|---|---|
| `ts_hub_ms` | Hub arrival time, **epoch milliseconds** (settled with vrpc). The canonical x-axis for cross-source alignment. Not the source-local `ts` (those clocks are unsynchronized, PROTOCOL.md 8.2). |
| `role` | Hub-assigned role: `left`, `right`, or `labeler`. Hub-local state, persisted per `device_id`. |
| `device_id` | The source `id` (e.g. `flexgrid-d7af0b`). Preserves provenance when two devices share a role. |
| `R{r}C{c}` | Sensor features, **row-major** (r outer, c inner): value at index k is `matrix[c][r]` with `r = k / cols`, `c = k % cols`. A 15x4 V4 band is `R0C0 .. R3C14` (60). This is the canonical order settled in board #0053 and matches the phone, the PC production writer (web/state.py), and all existing models. NOT column-major. |
| `label_0 .. label_M` | Labels matched to this frame (the most-recent labeler values within the match window, same as v1). Floats (LASK5 calibrated `[0,1]`; Quest joint floats). |

CRLF line endings (Python `csv` default), same as v1, for byte-compatibility.

## 2. Why long, not wide

PROTOCOL.md 8.2: sources stream **independently and unsynchronized**; the hub aligns by arrival
time, it does not receive synchronized frames. So the hub cannot produce a clean wide row with
left and right features side by side: a left band at ~30 Hz and a right band at ~30 Hz on
independent clocks do not coincide tick for tick. Forcing a wide row would require the hub to
resample or interpolate both streams, which is lossy and a modeling choice that belongs to the
trainer, not the capture writer.

The long format writes each frame as-is, tagged with its `role` + `device_id`. It is uniform for
1, 2, or N sources, and it degrades gracefully when a source drops mid-capture (PROTOCOL.md 8.2):
that role's rows simply stop, no zero-fill needed.

Feature columns stay `R{r}C{c}` (60) on every row; the `role` column disambiguates which band, so
there is no `L_`/`R_` column-name collision.

## 3. Trainer pivot to wide (PROTOCOL.md 8.4)

The Left-then-Right concat in 8.4 is the **trainer's** transform, applied when building the
feature matrix from the long CSV, not the on-disk layout:

1. Group rows by `ts_hub_ms` proximity (nearest-match within the capture's window).
2. For each aligned group, concatenate features **Left then Right**: `features = left.features || right.features` (60 + 60 = 120 for two V4 bands).
3. Attach the labeler's labels for that group.

So 8.4's "120 feature columns" is the **training matrix width after the pivot**, not the CSV
column count. The CSV is always 60 features per row.

> RECONCILIATION FLAG (firmware owns PROTOCOL.md): 8.3 (per-row role/device_id + one source's
> features) and 8.4 (120-column bilateral) read as two different layouts. They reconcile cleanly
> if 8.3 is the on-disk CSV (long) and 8.4 is the trainer matrix (wide, post-pivot). Recommend a
> one-line clarification in 8.4: "120 columns refers to the trainer feature matrix after pivoting
> the long capture CSV; the CSV itself is one source per row." Flagging like the column-major fix;
> doc-only.

## 4. Single-source capture

A single band is the long format with one role on every row (e.g. all `role=left`). This is the
natural successor to v1; the only deltas from v1 are the added `role` + `device_id` columns and
`timestamp` becoming `ts_hub_ms` (epoch ms, unchanged meaning).

## 5. Mirroring (PROTOCOL.md 8.5, one-limb capture)

Two bands on the remaining arm + a labeler. The label vector is position-mirrored per 8.5
(finger targets equal on both sides; Quest joint x negated, quaternion reflected across YZ). The
capture is tagged `meta.mirror: true` in the sidecar so training does not double-apply mirroring.

Recording metadata lives in a `<capture>.meta.json` sidecar (not new columns): `mirror`,
`role -> device_id` map, label source, and the per-joint mirror table for Quest.

## 6. Worked example (illustrative)

Two 2x2 FlexGrid bands (`fg-left`, `fg-right`) + a LASK5 labeler, 2 labels. Header then a left
frame and a right frame, each carrying the labels matched at that arrival time:

```
ts_hub_ms,role,device_id,R0C0,R0C1,R1C0,R1C1,label_0,label_1
1718000000000,left,fg-left,12,18,20,25,1.0,0.5
1718000000007,right,fg-right,30,28,22,19,1.0,0.5
1718000000033,left,fg-left,13,19,21,24,0.8,0.5
```

(Real bands are 15x4 -> `R0C0 .. R3C14`, 60 feature columns.) The runnable byte-exact golden +
generator (mirroring `tools/make_golden_csv.py`, driven from the shared reference writer) lands
once this layout is signed off, so the phone and PC writers stay byte-identical.

## 7. Open for sign-off

- phone (me): owns this doc + the forthcoming byte golden; will implement the phone v2 writer to it.
- vrpc: align the PC `CaptureWriter` to the byte golden; confirm the long-format + trainer-pivot split works for the trainer.
- firmware: the 8.4 reconciliation one-liner.
- overseer/Tory: the manual-label scale (separate flag) feeds `label_*` here, so its `[0,1]` decision pins these columns too.
