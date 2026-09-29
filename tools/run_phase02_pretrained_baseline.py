"""Chạy một pretrained evaluation của Phase 02 trên validation đã khóa.

Điểm bắt đầu là model/direction/dataset từ command line. Model ID và runtime
control đến từ configs/phase02_models.json; script ghi một evidence directory,
không tự giữ candidate hoặc đọc final test.

Usage examples:
  python tools/run_phase02_pretrained_baseline.py --model opus_mt --direction en_to_vi --dataset selection_validation
  python tools/run_phase02_pretrained_baseline.py --model nllb200_distilled_600m --direction vi_to_en --dataset general_validation
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from core_mt.baseline import PretrainedAdapter, load_candidate_spec, run_pretrained_evaluation
from core_mt.contracts import Direction
from core_mt.data import load_general_validation, load_it_role


def write_jsonl(path: Path, rows: list[dict[str, object]]) -> None:
    with path.open("x", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate one official pretrained Phase 2 candidate.")
    parser.add_argument("--model", choices=("opus_mt", "envit5", "nllb200_distilled_600m"), required=True)
    parser.add_argument("--direction", choices=tuple(direction.value for direction in Direction), required=True)
    parser.add_argument("--dataset", choices=("selection_validation", "general_validation"), required=True)
    parser.add_argument("--batch-size", type=int, default=8, help="Inference batch size; does not alter checkpoint generation settings.")
    args = parser.parse_args()
    direction = Direction(args.direction)
    dataset = load_it_role(args.dataset, ROOT) if args.dataset == "selection_validation" else load_general_validation(ROOT)
    spec = load_candidate_spec(ROOT / "configs" / "phase02_models.json", args.model, direction)
    output_dir = ROOT / "evidence" / "phase02" / args.model / args.direction / "pretrained" / args.dataset
    if output_dir.exists():
        raise FileExistsError(f"Evidence already exists and will not be replaced: {output_dir}")
    staging_dir = Path(f"{output_dir}.running")
    if staging_dir.exists():
        # Run lỗi trước artifact đầu tiên chỉ để lại staging rỗng; đó chưa phải
        # evidence và có thể xóa trước khi chạy lại.
        if staging_dir.is_dir() and not any(staging_dir.iterdir()):
            staging_dir.rmdir()
        else:
            raise FileExistsError(f"An incomplete run needs inspection before retry: {staging_dir}")
    staging_dir.mkdir(parents=True)
    try:
        adapter = PretrainedAdapter(spec)
        predictions, metrics = run_pretrained_evaluation(adapter, dataset, batch_size=args.batch_size)
        (staging_dir / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        write_jsonl(staging_dir / "predictions.jsonl", predictions)
        run = {
            "stage": "pretrained",
            "dataset_role": dataset.role,
            "source_data": str(dataset.path.relative_to(ROOT)).replace("\\", "/"),
            "source_data_sha256": sha256_file(dataset.path),
            "runner_sha256": sha256_file(Path(__file__)),
            "adapter_sha256": sha256_file(ROOT / "src" / "core_mt" / "baseline.py"),
            "protocol_sha256": sha256_file(ROOT / "configs" / "phase02_protocol.json"),
            "candidate_config_sha256": sha256_file(ROOT / "configs" / "phase02_models.json"),
            "created_at_utc": datetime.now(timezone.utc).isoformat(),
            "adapter": adapter.metadata(),
            "inference_batch_size": args.batch_size,
            "prediction_file": "predictions.jsonl",
            "metrics_file": "metrics.json",
        }
        (staging_dir / "run.json").write_text(json.dumps(run, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        staging_dir.rename(output_dir)
    except Exception:
        # Retain a separate .running directory for diagnosis, never as evidence.
        raise
    print(f"PHASE_02_PRETRAINED_BASELINE=PASS\n{output_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
