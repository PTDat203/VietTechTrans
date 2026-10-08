"""Record a finished `marian_ref.py eval` run as evidence (evidence/phase04/mt_export_<variant>/).

Merges the shard files back into the Phase 03 row order, re-scores them with Core's
`core_mt.metrics.score_predictions`, and writes per direction and validation set:
  <direction>_<role>.predictions.jsonl, <direction>_<role>.summary.json
plus run.json (graph hash, checkpoint, decoding, runtime, host).

Usage:
  python -I tools/mt/record_eval.py --eval-dir <dir with *_q8_<role>.part*.jsonl> --phase03-preds <dir>
"""
from __future__ import annotations

import argparse
import hashlib
import json
import platform
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
APP = HERE.parents[1]
sys.path.insert(0, str(APP.parent / "Core" / "src"))
sys.path.insert(0, str(HERE))

from core_mt.metrics import score_predictions  # noqa: E402
from marian_ref import read_jsonl, summarize  # noqa: E402

ROLES = ("selection_validation", "general_validation")


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    import onnxruntime

    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--eval-dir", type=Path, required=True)
    ap.add_argument("--phase03-preds", type=Path, required=True, help="Phase 03 student_predictions.jsonl files as <direction>_<role>.jsonl")
    ap.add_argument("--variant", default="q8")
    ap.add_argument("--out", type=Path, default=APP / "evidence/phase04/mt_export_q8")
    ap.add_argument("--policy", choices=("generation_config", "app"), default="generation_config",
                    help="decoding of the eval run: the checkpoint's generation_config as is, or the app policy (eval --length-cap)")
    ap.add_argument("--processes", type=int, default=8, help="parallel processes of the eval run (for the latency note)")
    ap.add_argument("--threads", type=int, default=2, help="threads per process of the eval run")
    args = ap.parse_args()

    manifest = json.loads((APP / "app/src/main/assets/mt/manifest.json").read_text(encoding="utf-8"))
    files = {f["path"]: f for f in manifest["files"]}
    args.out.mkdir(parents=True, exist_ok=True)
    results = {}
    for direction, spec in manifest["directions"].items():
        for role in ROLES:
            parts = sorted(args.eval_dir.glob(f"{direction}_{args.variant}_{role}.part*.jsonl"))
            if not parts:
                raise SystemExit(f"missing shards for {direction} {role}")
            by_id = {r["row_id"]: r for p in parts for r in read_jsonl(p)}
            order = [r["row_id"] for r in read_jsonl(args.phase03_preds / f"{direction}_{role}.jsonl")]
            if set(order) != set(by_id):
                raise SystemExit(f"{direction} {role}: shards do not cover the Phase 03 rows exactly")
            pred = args.out / f"{direction}_{role}.predictions.jsonl"
            with pred.open("w", encoding="utf-8", newline="\n") as f:
                for row_id in order:
                    f.write(json.dumps(by_id[row_id], ensure_ascii=False) + "\n")
            summary = summarize([pred], args.out / f"{direction}_{role}.summary.json", score_predictions)
            results[f"{direction}/{role}"] = {"predictions_sha256": sha256_file(pred), **summary}
        model = spec["model"]
        results[f"{direction}/graph"] = {"path": model, "sha256": files[model]["sha256"], "bytes": files[model]["bytes"],
                                         "source_checkpoint": spec["source_checkpoint"], "generation": spec["generation"]}

    run = {
        "stage": "phase04_app_mt_export_quality",
        "variant": args.variant,
        "package_id": manifest["package_id"],
        "quantization": manifest["quantization"],
        "datasets": "Phase 03 Student evaluation cohorts (student_predictions.jsonl: same rows, sources, references)",
        "metrics": "core_mt.metrics.score_predictions (chrF++ beta=2 word_order=2; sacreBLEU tokenize=none)",
        "decoder": "tools/mt/marian_ref.py beam_search (port of transformers 4.57 _beam_search; Kotlin BeamSearch.kt is its port)",
        "decoding_policy": ("generation_config as is" if args.policy == "generation_config" else
                            "app: app_max_length + prefer_finished (DR-07 §12); collapse_repeats not applied in these files"),
        "runtime": {"onnxruntime": onnxruntime.__version__, "python": platform.python_version(), "cpu": platform.processor(),
                    "threads_per_process": args.threads, "parallel_processes": args.processes},
        "note": f"host_latency_ms is measured on the PC with {args.processes} processes sharing the CPU; it is not a phone latency.",
        "results": results,
        "created_at_utc": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
    }
    (args.out / "run.json").write_text(json.dumps(run, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"APP_MT_EXPORT_QUALITY=RECORDED -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
