"""Record the app's decoding policy on the four Validation sets (evidence/phase04/mt_length_cap/).

The app adds two things on top of the checkpoint's generation_config (DR-07, 2026-10-07):
app_max_length + prefer_finished in beam search, and collapse_repeats on the decoded text. This script
takes a `marian_ref.py eval --length-cap` run, puts it back in Phase 03 row order, compares it row by row
with the uncapped run of the same q8 graph (evidence/phase04/mt_export_q8), applies collapse_repeats and
re-scores everything with Core's `core_mt.metrics.score_predictions`. Writes per set
<direction>_<role>.predictions.jsonl (+ "app_prediction") and .summary.json, plus run.json.

Usage:
  python -I tools/mt/record_app_policy.py --eval-dir evidence/phase04/mt_length_cap
"""
from __future__ import annotations

import argparse
import hashlib
import json
import platform
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
APP = HERE.parents[1]
sys.path.insert(0, str(APP.parent / "Core" / "src"))
sys.path.insert(0, str(HERE))

from core_mt.metrics import score_predictions  # noqa: E402
from marian_ref import LENGTH_CAP_RATIO, LENGTH_CAP_SLACK, collapse_repeats, read_jsonl  # noqa: E402

SETS = ("en_to_vi_selection_validation", "en_to_vi_general_validation",
        "vi_to_en_selection_validation", "vi_to_en_general_validation")


def scores(pred: list[str], refs: list[str]) -> dict:
    s = score_predictions(pred, refs)
    return {"chrF++": s["chrF++"], "sacreBLEU": s["sacreBLEU"]}


def main() -> int:
    import onnxruntime

    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--eval-dir", type=Path, required=True, help="output of marian_ref.py eval --length-cap")
    ap.add_argument("--baseline", type=Path, default=APP / "evidence/phase04/mt_export_q8")
    args = ap.parse_args()

    results = {}
    for name in SETS:
        base = read_jsonl(args.baseline / f"{name}.predictions.jsonl")
        capped = {r["row_id"]: r for r in read_jsonl(args.eval_dir / f"{name}.predictions.jsonl")}
        if set(capped) != {r["row_id"] for r in base}:
            raise SystemExit(f"{name}: rows differ from {args.baseline}")
        rows, changed = [], []
        for b in base:
            c = capped[b["row_id"]]
            row = {**c, "uncapped_prediction": b["prediction"], "uncapped_out_tokens": b["out_tokens"],
                   "app_prediction": collapse_repeats(c["prediction"])}
            rows.append(row)
            if row["app_prediction"] != b["prediction"]:
                changed.append({"row_id": b["row_id"], "src_tokens": b["src_tokens"], "uncapped_out_tokens": b["out_tokens"],
                                "out_tokens": c["out_tokens"], "uncapped": b["prediction"], "app": row["app_prediction"]})
        out = args.eval_dir / f"{name}.predictions.jsonl"
        with out.open("w", encoding="utf-8", newline="\n") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        refs = [r["reference"] for r in rows]
        steps = np.array([r["steps"] for r in rows])
        limit = np.array([min(512, LENGTH_CAP_RATIO * r["src_tokens"] + LENGTH_CAP_SLACK) for r in rows])
        summary = {
            "rows": len(rows),
            "uncapped_q8": scores([r["uncapped_prediction"] for r in rows], refs),
            "capped": scores([r["prediction"] for r in rows], refs),
            "app": scores([r["app_prediction"] for r in rows], refs),
            "changed_by_cap": sum(r["prediction"] != r["uncapped_prediction"] for r in rows),
            "changed_by_app": len(changed),
            "uncapped_longer_than_limit": int(sum(r["uncapped_out_tokens"] + 1 >= l for r, l in zip(rows, limit))),
            "decoder_steps": {"mean": float(steps.mean()), "p95": float(np.percentile(steps, 95)), "max": int(steps.max()),
                              "rows_at_limit": int((steps >= limit - 2).sum())},
            "changed_rows": changed,
        }
        (args.eval_dir / f"{name}.summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
                                                           encoding="utf-8", newline="\n")
        results[name] = {"predictions_sha256": hashlib.sha256(out.read_bytes()).hexdigest(),
                         **{k: v for k, v in summary.items() if k != "changed_rows"}}
        print(name, json.dumps(results[name], ensure_ascii=False))

    run = {
        "stage": "phase04_app_mt_decoding_policy",
        "policy": {
            "max_length": f"min(generation_config.max_length, {LENGTH_CAP_RATIO} * source_ids + {LENGTH_CAP_SLACK})",
            "at_max_length": "best finished hypothesis if any, else forced EOS (beam_search prefer_finished)",
            "post_processing": "collapse_repeats: a unit of 1-3 words repeated >= 4 times in a row is kept once",
            "app": "GenerationParams.maxLengthFor, BeamSearch(preferFinished = true), OutputCleanup.collapseRepeats",
        },
        "baseline": "evidence/phase04/mt_export_q8 (same q8 graph, generation_config as is)",
        "metrics": "core_mt.metrics.score_predictions (chrF++ beta=2 word_order=2; sacreBLEU tokenize=none)",
        "runtime": {"onnxruntime": onnxruntime.__version__, "python": platform.python_version(), "cpu": platform.processor(),
                    "threads_per_process": 1, "parallel_processes": 14},
        "note": "ms is measured on the PC with 14 processes sharing the CPU; compare decoder_steps, not ms.",
        "results": results,
        "created_at_utc": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
    }
    (args.eval_dir / "run.json").write_text(json.dumps(run, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"APP_MT_POLICY=RECORDED -> {args.eval_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
