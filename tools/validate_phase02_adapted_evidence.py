"""Kiểm tra provenance train và hai validation run của một checkpoint adapted.

Điểm bắt đầu là experiment/checkpoint ID. Gate nối checkpoint với training
record, IT Train và hai evaluation evidence trước khi review hoặc freeze Core.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from core_mt.adaptation import experiment_record_path, sha256_file, sha256_tree, validate_identifier
from core_mt.contracts import Direction
from core_mt.data import load_general_validation, load_it_role


def main() -> int:
    parser = argparse.ArgumentParser(description="Check one adapted checkpoint before Notebook 02.02 or manual review.")
    parser.add_argument("--model", choices=("envit5",), required=True)
    parser.add_argument("--direction", choices=tuple(direction.value for direction in Direction), required=True)
    parser.add_argument("--experiment-id", required=True)
    parser.add_argument("--checkpoint-id", required=True)
    args = parser.parse_args()

    direction = Direction(args.direction)
    experiment_id = validate_identifier(args.experiment_id, field="experiment-id")
    checkpoint_id = validate_identifier(args.checkpoint_id, field="checkpoint-id")
    record_path = experiment_record_path(ROOT, model=args.model, direction=direction, experiment_id=experiment_id)
    training_path = record_path.with_name("training_run.json")
    if not record_path.is_file() or not training_path.is_file():
        raise FileNotFoundError("Missing experiment_record.md or training_run.json.")
    training = json.loads(training_path.read_text(encoding="utf-8"))
    checkpoint_path = ROOT / "models" / "phase02" / experiment_id / checkpoint_id
    checkpoint_hash = sha256_tree(checkpoint_path)
    if training.get("stage") != "adaptation_training" or training.get("direction") != direction.value:
        raise ValueError("Training record has the wrong stage or direction.")
    if training.get("experiment_id") != experiment_id or training.get("checkpoint_id") != checkpoint_id:
        raise ValueError("Training record does not identify this checkpoint.")
    if training.get("train_data", {}).get("role") != "adaptation_train":
        raise ValueError("Training record does not use the adaptation_train role.")
    if training.get("adaptation_method", {}).get("name") not in {"full_ft", "lora"}:
        raise ValueError("Training record has no supported adaptation method.")
    train = load_it_role("adaptation_train", ROOT)
    if training["train_data"].get("sha256") != sha256_file(train.path) or training["train_data"].get("rows") != len(train.rows):
        raise ValueError("Training record does not match the sealed IT Train input.")
    if training.get("checkpoint", {}).get("sha256_tree") != checkpoint_hash:
        raise ValueError("Saved checkpoint differs from the training record.")

    expected = {
        "selection_validation": load_it_role("selection_validation", ROOT),
        "general_validation": load_general_validation(ROOT),
    }
    result_rows = []
    base = record_path.parent / checkpoint_id
    for role, dataset in expected.items():
        evidence = base / role
        required = [evidence / name for name in ("predictions.jsonl", "metrics.json", "run.json")]
        missing = [str(path.name) for path in required if not path.is_file()]
        if missing:
            raise FileNotFoundError(f"Missing {role} evidence: {', '.join(missing)}")
        metrics = json.loads((evidence / "metrics.json").read_text(encoding="utf-8"))
        run = json.loads((evidence / "run.json").read_text(encoding="utf-8"))
        prediction_count = sum(1 for _ in (evidence / "predictions.jsonl").open(encoding="utf-8"))
        if metrics.get("dataset_role") != role or metrics.get("rows") != len(dataset.rows) or prediction_count != len(dataset.rows):
            raise ValueError(f"{role} does not contain the expected number of evaluated rows.")
        if run.get("stage") != "adapted_evaluation" or run.get("dataset_role") != role:
            raise ValueError(f"{role} run.json has the wrong stage or role.")
        if run.get("evaluated_checkpoint", {}).get("sha256_tree") != checkpoint_hash:
            raise ValueError(f"{role} was not evaluated from the recorded checkpoint bytes.")
        if run.get("training_run", {}).get("sha256") != sha256_file(training_path):
            raise ValueError(f"{role} does not point to the current training record.")
        result_rows.append((role, metrics["chrF++"], metrics["sacreBLEU"], metrics["rows"]))

    print("PHASE_02_ADAPTED_EVIDENCE=PASS")
    print(f"checkpoint={checkpoint_path.relative_to(ROOT)}")
    for role, chrf, bleu, rows in result_rows:
        print(f"{role}: rows={rows}; chrF++={chrf:.6f}; SacreBLEU={bleu:.6f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
