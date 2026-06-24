"""Export a FlexGrid RandomForest to ONNX and verify ONNX Runtime parity.

This is the "mirror PC" inference bridge from docs/ARCHITECTURE-PROPOSAL.md: the
PC trains a scikit-learn RandomForestRegressor (it already does), this exports it
to ONNX with skl2onnx, and the phone runs that exact model with ONNX Runtime
Mobile. No model divergence: the phone is a runtime mirror of whatever the PC
last trained.

It is a runnable reference for the PC team. The feature contract matches the
phone: 60 inputs in row-major R0C0..R3C14 order (the same column order the
capture CSV uses, verified by tools/wireformat_check.py), and 4 piston outputs.

    python tools/export_onnx.py                       # synthetic train + parity check
    python tools/export_onnx.py --csv capture.csv --out model.onnx

Needs scikit-learn, skl2onnx, onnxruntime, numpy.
"""

import argparse
import csv
import sys
from pathlib import Path

import numpy as np
from sklearn.ensemble import RandomForestRegressor

N_FEATURES = 60
N_LABELS = 4


def synthetic_data(rows=400, seed=42):
    rng = np.random.RandomState(seed)
    x = rng.randint(0, 4096, size=(rows, N_FEATURES)).astype(np.float32)
    # 4 deterministic targets so the forest has real structure to learn.
    w = rng.rand(N_FEATURES, N_LABELS)
    y = (x @ w) / N_FEATURES
    return x, y


def load_csv(path):
    """Load a capture CSV (timestamp, R{r}C{c}..., label_*) into X, y."""
    with open(path, newline="") as f:
        reader = csv.reader(f)
        header = next(reader)
        feat_idx = [i for i, c in enumerate(header) if c.startswith("R") and "C" in c]
        label_idx = [i for i, c in enumerate(header) if c.startswith("label_")]
        xs, ys = [], []
        for row in reader:
            xs.append([float(row[i]) for i in feat_idx])
            ys.append([float(row[i]) for i in label_idx])
    return np.array(xs, dtype=np.float32), np.array(ys, dtype=np.float32)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", default=None, help="capture CSV to train on (default: synthetic)")
    ap.add_argument("--trees", type=int, default=25)
    ap.add_argument("--out", default=None, help="write model.onnx + golden.json here")
    ap.add_argument("--atol", type=float, default=1e-3)
    args = ap.parse_args()

    try:
        from skl2onnx import convert_sklearn
        from skl2onnx.common.data_types import FloatTensorType
        import onnxruntime as ort
    except ImportError as e:
        print(f"FAIL: missing ONNX tooling ({e}). pip install skl2onnx onnxruntime")
        return 2

    if args.csv:
        x, y = load_csv(args.csv)
        print(f"loaded {x.shape[0]} rows x {x.shape[1]} features from {args.csv}")
    else:
        x, y = synthetic_data()
        print(f"synthetic: {x.shape[0]} rows x {x.shape[1]} features, {y.shape[1]} labels")

    n_features = x.shape[1]
    model = RandomForestRegressor(n_estimators=args.trees, random_state=42)
    model.fit(x, y)

    initial_type = [("input", FloatTensorType([None, n_features]))]
    onnx_model = convert_sklearn(model, initial_types=initial_type)

    sess = ort.InferenceSession(onnx_model.SerializeToString())
    input_name = sess.get_inputs()[0].name

    probe = x[:10].astype(np.float32)
    pred_sklearn = model.predict(probe)
    pred_onnx = sess.run(None, {input_name: probe})[0]
    if pred_onnx.ndim == 1:
        pred_onnx = pred_onnx.reshape(pred_sklearn.shape)

    max_diff = float(np.max(np.abs(pred_sklearn - pred_onnx)))
    ok = bool(np.allclose(pred_sklearn, pred_onnx, atol=args.atol))
    print(f"max |sklearn - onnx| over 10 probes: {max_diff:.2e} (atol {args.atol:.0e})")

    if args.out:
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(onnx_model.SerializeToString())
        import json
        golden = {
            "n_features": n_features,
            "n_labels": int(y.shape[1]),
            "samples": [
                {"input": probe[i].astype(int).tolist(),
                 "expected": [round(float(v), 4) for v in pred_sklearn[i]]}
                for i in range(min(3, len(probe)))
            ],
        }
        golden_path = out.with_name("golden.json")
        golden_path.write_text(json.dumps(golden, indent=2))
        print(f"wrote {out} and {golden_path}")

    print("PASS: ONNX Runtime matches scikit-learn" if ok else "FAIL: prediction mismatch")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
