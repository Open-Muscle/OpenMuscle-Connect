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

1. Group rows by `ts_hub_ms` proximity (nearest-match within the capture's window). vrpc
   confirms this reuses the PC's existing `TemporalMatcher`, the same machinery that pairs
   sensor to label.
2. For each aligned group, concatenate features **Left then Right**: `features = left.features || right.features` (60 + 60 = 120 for two V4 bands).
3. Attach the labels for that group.

So 8.4's "120 feature columns" is the **training matrix width after the pivot**, not the CSV
column count. The CSV is always 60 features per row.

Pivot details (settled with vrpc, #0072 and #0086):
- **Label selection.** A left row and a right row in the same group each carry labels matched
  to *their own* arrival time, so the two label vectors can differ. The group is anchored on the
  Left row, so the pivoted 120-feature row uses the Left row's labels (the labeler value nearest
  the group's `ts_hub_ms`).
- **Pivot pairing window + unpaired groups.** The left/right pairing window (distinct from the
  capture sensor-to-label window) defaults to **50 ms** and is configurable; it pairs the nearest
  left and right frames into one bilateral sample (pandas `merge_asof` on the PC = the static
  `TemporalMatcher`). For bilateral, **drop** groups missing a side rather than zero-fill (a
  half-present group is not a valid bilateral sample).
- **Wide-matrix column order (the inference contract).** The 120 feature columns are the 60 Left
  features followed by the 60 Right, each side in the canonical row-major order with an `_L` / `_R`
  suffix: `R0C0_L, R0C1_L, ..., R3C14_L, R0C0_R, R0C1_R, ..., R3C14_R`. This order is contractual:
  a hub doing bilateral inference MUST build its 120-feature vector the same way, so the phone's
  bilateral inference matches a model the PC trained on this matrix. (Confirmed with vrpc, #0086;
  no separate byte golden needed since the wide matrix is never serialized to disk, only the long
  CSV is.)
- **Single vs bilateral guard.** A bilateral long CSV fed to the trainer *without* the pivot does
  not error: the column detector picks `R{r}C{c}` + `label_*` and silently trains a pooled,
  role-agnostic 60-feature model on mixed left+right rows (wrong intent, no crash). The trainer
  MUST detect more than one distinct `role` in a capture and require the pivot (or an explicit
  single/bilateral mode), so mixed-role data cannot silently mistrain. This guard lands with the
  pivot (vrpc, P4).

Single-source compatibility is free: vrpc verified the PC trainer's column detector
(`data/dataset.py` `detect_columns`) keys sensors off `R*` and labels off `label_*`, so the new
`ts_hub_ms` / `role` / `device_id` columns fall out of both sets. A v2 single-source long CSV
trains exactly like v1 with zero trainer changes.

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

**Sidecar coexistence (with vrpc, #0072).** The PC already writes `<capture>.labels.schema.json`
(the Quest joint -> `label_*` column map). The phone's `<capture>.meta.json` is additive and a
separate concern (capture metadata, not label-column mapping), so the two coexist: a Quest
bilateral capture can carry both. Phone and PC agree on both filenames; neither overwrites the
other.

## 6. Worked example (illustrative)

Two 2x2 FlexGrid bands (`fg-left`, `fg-right`) + a LASK5 labeler, 2 labels. Header then a left
frame and a right frame, each carrying the labels matched at that arrival time:

```
ts_hub_ms,role,device_id,R0C0,R0C1,R1C0,R1C1,label_0,label_1
1718000000000,left,fg-left,12,18,20,25,1.0,0.5
1718000000007,right,fg-right,30,28,22,19,1.0,0.5
1718000000033,left,fg-left,13,19,21,24,0.8,0.5
```

(Real bands are 15x4 -> `R0C0 .. R3C14`, 60 feature columns.) The runnable byte-exact golden is
`tools/make_golden_csv_v2.py` (single-source + bilateral), which pins these exact bytes with the
same `csv.writer` both real writers use. The phone `CsvSessionWriter` v2 and the PC `CaptureWriter`
v2 must each reproduce them byte-for-byte.

## 7. Status

- phone (me): owns this doc + the byte golden (`tools/make_golden_csv_v2.py`, landed). Next:
  implement the phone `CsvSessionWriter` v2 + role tagging to reproduce the golden.
- vrpc: **CONFIRMED** the layout works for the trainer (#0072), grounded in the real trainer code;
  will align the PC `CaptureWriter` to the byte golden byte-for-byte. Bilateral pivot + guard is
  P4 trainer work, holding.
- overseer: **endorsed** the long-capture / derived-trainer-matrix split (#0068, #0071).
- firmware: folding the 8.4 reconciliation one-liner into PROTOCOL.md (bundled with the 8.3
  column-major + meta-names fixes).
- overseer/Tory: the manual-label scale (separate flag) feeds `label_*` here, so its `[0,1]`
  decision pins these columns too.
