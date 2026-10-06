"""Evaluate one approved Phase 03 Student on one permitted validation set."""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from core_mt.contracts import Direction
from core_mt.data import Phase02Dataset, load_general_validation, load_it_role
from core_mt.metrics import score_predictions
from run_phase03_teacher_targets import sha256_file, sha256_tree


PROTOCOL_PATH = ROOT / "configs/phase03_protocol.json"
REUSE_PROTOCOL_PATH = ROOT / "configs/phase03_protocol_teacher_target_reuse_v2.json"
VI_TO_EN_V2_PROTOCOL_PATH = ROOT / "configs/phase03_protocol_vi_to_en_v2.json"
VI_TO_EN_REUSE_V3_PROTOCOL_PATH = ROOT / "configs/phase03_protocol_teacher_target_reuse_vi_to_en_v3.json"

EVALUATION_RUNS = {
    "en_to_vi": {
        "run_id": "phase03-seqkd-en-to-vi-v2",
        "record_path": ROOT / "configs/phase03_experiments/phase03-seqkd-en-to-vi-v2.json",
        "amendment_path": ROOT / "configs/phase03_evaluation_input_length_amendment_en_to_vi_v1.json",
        "protocol_paths": (PROTOCOL_PATH, REUSE_PROTOCOL_PATH),
    },
    "vi_to_en": {
        "run_id": "phase03-seqkd-vi-to-en-v3",
        "record_path": ROOT / "configs/phase03_experiments/phase03-seqkd-vi-to-en-v3.json",
        "amendment_path": ROOT / "configs/phase03_evaluation_input_length_amendment_vi_to_en_v3.json",
        "protocol_paths": (PROTOCOL_PATH, VI_TO_EN_V2_PROTOCOL_PATH, VI_TO_EN_REUSE_V3_PROTOCOL_PATH),
    },
}
def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: dict[str, Any]) -> None:
    with path.open("x", encoding="utf-8", newline="\n") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def write_jsonl(path: Path, rows: list[dict[str, object]]) -> None:
    with path.open("x", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")


def load_permitted_dataset(role: str) -> Phase02Dataset:
    if role == "selection_validation":
        dataset = load_it_role(role, ROOT)
    elif role == "general_validation":
        dataset = load_general_validation(ROOT)
    else:
        raise ValueError("Phase 03 evaluation permits only selection_validation or general_validation.")
    allowed_paths = {
        "selection_validation": ROOT / "data/processed/it_en_vi/validation.jsonl",
        "general_validation": ROOT / "data/processed/general_validation_en_vi/general_validation.jsonl",
    }
    if dataset.path != allowed_paths[role] or dataset.role != role:
        raise ValueError("Evaluation dataset differs from the approved Phase 03 validation role.")
    return dataset


def require_approved_inputs(direction: str, settings: dict[str, Any], record: dict[str, Any], training: dict[str, Any], amendment: dict[str, Any]) -> Path:
    if any(read_json(path).get("status") != "frozen_before_experiments" for path in settings["protocol_paths"]):
        raise PermissionError("All approved Phase 03 protocols must be frozen before evaluation.")
    run_id = settings["run_id"]
    if record.get("status") != "approved_before_experiment" or record.get("run_id") != run_id or record.get("direction") != direction:
        raise ValueError("Evaluation record does not match the approved direction and run.")
    if amendment.get("status") != "approved_before_experiment" or amendment.get("student_run_id") != run_id:
        raise PermissionError("Evaluation-length amendment must be approved before evaluation.")
    if training.get("stage") != "phase03_student_training" or training.get("run_id") != run_id or training.get("direction") != direction:
        raise ValueError("Training evidence does not match the approved evaluation run.")
    checkpoint = ROOT / record["output"]["final_checkpoint"]
    if not checkpoint.is_dir() or sha256_tree(checkpoint) != training.get("checkpoint", {}).get("sha256_tree"):
        raise ValueError("Student checkpoint is missing or differs from the training evidence.")
    target_path = ROOT / training.get("input", {}).get("target_path", "")
    if not target_path.is_file() or sha256_file(target_path) != training.get("input", {}).get("target_sha256"):
        raise ValueError("Teacher-target input differs from the target file recorded for training.")
    return checkpoint


def validate_source_lengths(rows: list[Any], tokenizer: Any, prefix: str, limit: int, direction: Direction) -> None:
    over_limit = []
    for row in rows:
        source, _ = row.for_direction(direction)
        token_count = len(tokenizer(prefix + source, truncation=False, add_special_tokens=True)["input_ids"])
        if token_count > limit:
            over_limit.append({"dataset_row_id": row.row_id, "student_input_tokens": token_count})
    if over_limit:
        raise ValueError("Validation source exceeds the Student input capacity: " + json.dumps(over_limit, ensure_ascii=False))


def load_comparison_rows(dataset: Phase02Dataset, details: dict[str, Any], direction: Direction) -> tuple[list[Any], list[dict[str, Any]]]:
    if len(dataset.rows) != details.get("source_rows"):
        raise ValueError("Validation row count differs from the approved amendment.")
    excluded = details.get("excluded_rows")
    if not isinstance(excluded, list):
        raise ValueError("Evaluation amendment has an invalid exclusion list.")
    excluded_ids = {item.get("dataset_row_id") for item in excluded if isinstance(item, dict)}
    dataset_by_id = {row.row_id: row for row in dataset.rows}
    if len(dataset_by_id) != len(dataset.rows) or None in excluded_ids or not excluded_ids.issubset(dataset_by_id):
        raise ValueError("Evaluation amendment excludes an invalid validation row.")
    eligible_rows = [row for row in dataset.rows if row.row_id not in excluded_ids]
    if len(eligible_rows) != details.get("eligible_rows"):
        raise ValueError("Eligible validation row count differs from the approved amendment.")
    teacher_details = details.get("teacher_predictions")
    if not isinstance(teacher_details, dict):
        raise ValueError("Evaluation amendment has no Teacher prediction lineage.")
    prediction_path = ROOT / teacher_details.get("path", "")
    run_path = ROOT / teacher_details.get("run_path", "")
    if not prediction_path.is_file() or sha256_file(prediction_path) != teacher_details.get("sha256"):
        raise ValueError("Teacher predictions differ from the approved amendment.")
    if not run_path.is_file() or sha256_file(run_path) != teacher_details.get("run_sha256"):
        raise ValueError("Teacher prediction run differs from the approved amendment.")
    teacher_rows = [json.loads(line) for line in prediction_path.read_text(encoding="utf-8").splitlines()]
    teacher_by_id = {row.get("row_id"): row for row in teacher_rows}
    if len(teacher_rows) != len(dataset.rows) or len(teacher_by_id) != len(teacher_rows) or set(teacher_by_id) != set(dataset_by_id):
        raise ValueError("Teacher predictions do not cover the full approved validation dataset exactly once.")
    for row_id, dataset_row in dataset_by_id.items():
        teacher_row = teacher_by_id[row_id]
        source, reference = dataset_row.for_direction(direction)
        if teacher_row.get("source") != source or teacher_row.get("reference") != reference or not isinstance(teacher_row.get("prediction"), str):
            raise ValueError(f"Teacher prediction differs from validation data for row {row_id}.")
    return eligible_rows, [teacher_by_id[row.row_id] for row in eligible_rows]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--direction", choices=("en_to_vi", "vi_to_en"), required=True)
    parser.add_argument("--dataset", choices=("selection_validation", "general_validation"), required=True)
    parser.add_argument("--batch-size", type=int, default=8)
    args = parser.parse_args()
    if args.batch_size < 1:
        raise ValueError("batch-size must be positive.")

    settings = EVALUATION_RUNS[args.direction]
    run_id = settings["run_id"]
    record_path = settings["record_path"]
    amendment_path = settings["amendment_path"]
    training_path = ROOT / "evidence/phase03" / f"{run_id}.running/training/{args.direction}/training_run.json"
    record = read_json(record_path)
    training = read_json(training_path)
    amendment = read_json(amendment_path)
    direction = Direction(args.direction)
    checkpoint = require_approved_inputs(args.direction, settings, record, training, amendment)
    dataset = load_permitted_dataset(args.dataset)
    details = amendment.get("datasets", {}).get(args.dataset)
    if not isinstance(details, dict) or details.get("path") != dataset.path.relative_to(ROOT).as_posix() or details.get("sha256") != sha256_file(dataset.path):
        raise ValueError("Evaluation dataset differs from the approved amendment.")
    eligible_rows, teacher_rows = load_comparison_rows(dataset, details, direction)
    run_dir = ROOT / "evidence/phase03" / f"{run_id}.running"
    output_dir = run_dir / "evaluation" / args.direction / args.dataset
    staging_dir = Path(f"{output_dir}.running")
    if output_dir.exists():
        raise FileExistsError(f"Evaluation evidence already exists and will not be replaced: {output_dir}")
    if staging_dir.exists():
        raise FileExistsError(f"Incomplete evaluation needs inspection before retry: {staging_dir}")

    import torch
    import transformers
    from tqdm.auto import tqdm
    from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

    device = record["execution_device"]
    if not torch.cuda.is_available() or torch.cuda.device_count() <= device["index"]:
        raise RuntimeError("Approved CUDA device is unavailable for evaluation.")
    if torch.cuda.get_device_name(device["index"]) != device["name"]:
        raise RuntimeError("Available CUDA device differs from the experiment record.")
    tokenizer = AutoTokenizer.from_pretrained(checkpoint, local_files_only=True)
    model = AutoModelForSeq2SeqLM.from_pretrained(checkpoint, local_files_only=True)
    input_limit = model.config.max_position_embeddings
    if input_limit != amendment.get("student_input_limit_tokens"):
        raise ValueError("Student input capacity differs from the approved evaluation amendment.")
    validate_source_lengths(eligible_rows, tokenizer, record["student"]["input_prefix"], input_limit, direction)

    staging_dir.mkdir(parents=True)
    try:
        model.to(f"cuda:{device['index']}")
        model.eval()
        records: list[dict[str, object]] = []
        predictions: list[str] = []
        references: list[str] = []
        prefix = record["student"]["input_prefix"]
        for start in tqdm(range(0, len(eligible_rows), args.batch_size), desc=f"Evaluate {args.dataset}", unit="batch"):
            batch = eligible_rows[start:start + args.batch_size]
            sources = [prefix + row.for_direction(direction)[0] for row in batch]
            encoded = tokenizer(sources, padding=True, truncation=False, return_tensors="pt")
            encoded = {name: value.to(f"cuda:{device['index']}") for name, value in encoded.items()}
            with torch.inference_mode():
                generated = model.generate(**encoded)
            decoded = tokenizer.batch_decode(generated, skip_special_tokens=True)
            for row, prediction in zip(batch, decoded, strict=True):
                source, reference = row.for_direction(direction)
                records.append({
                    "row_id": row.row_id,
                    "source": source,
                    "reference": reference,
                    "prediction": prediction,
                    "technical_tags": list(row.technical_tags),
                })
                predictions.append(prediction)
                references.append(reference)
        student_metrics = {
            "dataset_role": dataset.role,
            "rows": len(records),
            "inference_batch_size": args.batch_size,
            **score_predictions(predictions, references),
        }
        teacher_metrics = {
            "dataset_role": dataset.role,
            "rows": len(teacher_rows),
            "teacher_prediction_source": details["teacher_predictions"],
            "teacher_inference": "not_run; metrics recomputed from the approved Phase 02 prediction artifact",
            **score_predictions([row["prediction"] for row in teacher_rows], [row["reference"] for row in teacher_rows]),
        }
        write_jsonl(staging_dir / "student_predictions.jsonl", records)
        write_json(staging_dir / "student_metrics.json", student_metrics)
        write_json(staging_dir / "teacher_comparable_metrics.json", teacher_metrics)
        write_json(staging_dir / "comparison_scope.json", {
            "dataset_role": dataset.role,
            "dataset": {"path": details["path"], "sha256": details["sha256"], "source_rows": details["source_rows"], "eligible_rows": details["eligible_rows"]},
            "student_input_limit_tokens": input_limit,
            "excluded_rows": details["excluded_rows"],
            "teacher_predictions": details["teacher_predictions"],
            "teacher_inference": "not_run; existing Phase 02 predictions were reused only to recompute comparable corpus metrics",
        })
        generation_config = checkpoint / "generation_config.json"
        write_json(staging_dir / "run.json", {
            "stage": "phase03_student_evaluation",
            "run_id": run_id,
            "direction": args.direction,
            "dataset_role": dataset.role,
            "source_data": dataset.path.relative_to(ROOT).as_posix(),
            "source_data_sha256": sha256_file(dataset.path),
            "evaluated_checkpoint": {
                "path": checkpoint.relative_to(ROOT).as_posix(),
                "sha256_tree": sha256_tree(checkpoint),
                "parameter_count": sum(parameter.numel() for parameter in model.parameters()),
                "serialized_bytes": sum(path.stat().st_size for path in checkpoint.rglob("*") if path.is_file()),
            },
            "training_run": {"path": training_path.relative_to(ROOT).as_posix(), "sha256": sha256_file(training_path)},
            "teacher_target_input": training["input"],
            "student_generation_config_sha256": sha256_file(generation_config) if generation_config.is_file() else None,
            "runner_sha256": sha256_file(Path(__file__)),
            "protocols": [{"path": path.relative_to(ROOT).as_posix(), "sha256": sha256_file(path)} for path in settings["protocol_paths"]],
            "experiment_record_sha256": sha256_file(record_path),
            "evaluation_amendment": {"path": amendment_path.relative_to(ROOT).as_posix(), "sha256": sha256_file(amendment_path)},
            "runtime": {"torch": torch.__version__, "transformers": transformers.__version__, "device": device},
            "inference_batch_size": args.batch_size,
            "student_prediction_file": "student_predictions.jsonl",
            "student_metrics_file": "student_metrics.json",
            "teacher_comparable_metrics_file": "teacher_comparable_metrics.json",
            "comparison_scope_file": "comparison_scope.json",
            "created_at_utc": datetime.now(timezone.utc).isoformat(),
        })
        staging_dir.rename(output_dir)
    finally:
        del model
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
    print(f"PHASE03_STUDENT_EVALUATION=PASS run={run_id} direction={args.direction} dataset={args.dataset}")
    print(output_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
