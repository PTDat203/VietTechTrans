"""Đánh giá một checkpoint adapted trên một validation set được phép.

Điểm bắt đầu là experiment ID, checkpoint ID và dataset từ command line. Script
chỉ chạy khi training record và hash checkpoint khớp; output là evidence, không
phải quyết định chọn Core.
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

from core_mt.adaptation import experiment_record_path, sha256_tree, validate_identifier
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
    parser = argparse.ArgumentParser(description="Evaluate one adapted EnViT5 checkpoint on sealed validation data.")
    parser.add_argument("--model", choices=("envit5",), required=True)
    parser.add_argument("--direction", choices=tuple(direction.value for direction in Direction), required=True)
    parser.add_argument("--experiment-id", required=True)
    parser.add_argument("--checkpoint-id", required=True)
    parser.add_argument("--dataset", choices=("selection_validation", "general_validation"), required=True)
    parser.add_argument("--batch-size", type=int, default=8)
    args = parser.parse_args()
    if args.batch_size < 1:
        raise ValueError("batch-size must be positive.")

    direction = Direction(args.direction)
    experiment_id = validate_identifier(args.experiment_id, field="experiment-id")
    checkpoint_id = validate_identifier(args.checkpoint_id, field="checkpoint-id")
    spec = load_candidate_spec(ROOT / "configs" / "phase02_models.json", args.model, direction)
    record_path = experiment_record_path(ROOT, model=args.model, direction=direction, experiment_id=experiment_id)
    training_run_path = record_path.with_name("training_run.json")
    checkpoint_path = ROOT / "models" / "phase02" / experiment_id / checkpoint_id
    if not record_path.is_file() or not training_run_path.is_file():
        raise FileNotFoundError("Adapted evaluation requires the completed experiment record and training_run.json.")
    training_run = json.loads(training_run_path.read_text(encoding="utf-8"))
    if training_run.get("stage") != "adaptation_training":
        raise ValueError("training_run.json is not an adaptation training record.")
    if training_run.get("direction") != direction.value or training_run.get("experiment_id") != experiment_id or training_run.get("checkpoint_id") != checkpoint_id:
        raise ValueError("Training record does not match the requested adapted checkpoint.")
    checkpoint_sha256 = sha256_tree(checkpoint_path)
    if checkpoint_sha256 != training_run.get("checkpoint", {}).get("sha256_tree"):
        raise ValueError("Checkpoint bytes differ from the checkpoint recorded after adaptation.")

    dataset = load_it_role("selection_validation", ROOT) if args.dataset == "selection_validation" else load_general_validation(ROOT)
    output_dir = ROOT / "evidence" / "phase02" / args.model / direction.value / "adapted" / experiment_id / checkpoint_id / args.dataset
    staging_dir = Path(f"{output_dir}.running")
    if output_dir.exists():
        raise FileExistsError(f"Evidence already exists and will not be replaced: {output_dir}")
    if staging_dir.exists():
        raise FileExistsError(f"An incomplete adapted evaluation needs inspection before retry: {staging_dir}")
    staging_dir.mkdir(parents=True)
    try:
        adaptation_method = training_run.get("adaptation_method", {}).get("name")
        if adaptation_method not in {"full_ft", "lora"}:
            raise ValueError("Training record has no supported adaptation method.")
        adapter = PretrainedAdapter(spec, checkpoint_path=checkpoint_path, adaptation_method=adaptation_method)
        predictions, metrics = run_pretrained_evaluation(adapter, dataset, batch_size=args.batch_size)
        (staging_dir / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        write_jsonl(staging_dir / "predictions.jsonl", predictions)
        run = {
            "stage": "adapted_evaluation",
            "dataset_role": dataset.role,
            "source_data": str(dataset.path.relative_to(ROOT)).replace("\\", "/"),
            "source_data_sha256": sha256_file(dataset.path),
            "experiment_id": experiment_id,
            "evaluated_checkpoint": {
                "id": checkpoint_id,
                "path": str(checkpoint_path.relative_to(ROOT)).replace("\\", "/"),
                "sha256_tree": checkpoint_sha256,
            },
            "training_run": {
                "path": str(training_run_path.relative_to(ROOT)).replace("\\", "/"),
                "sha256": sha256_file(training_run_path),
                "source_pretrained_checkpoint_revision": training_run["model"].get("source_checkpoint_revision"),
                "adaptation_method": training_run["adaptation_method"],
            },
            "runner_sha256": sha256_file(Path(__file__)),
            "adapter_sha256": sha256_file(ROOT / "src" / "core_mt" / "baseline.py"),
            "adaptation_module_sha256": sha256_file(ROOT / "src" / "core_mt" / "adaptation.py"),
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
        raise
    print("PHASE_02_ADAPTED_EVALUATION=PASS")
    print(output_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
