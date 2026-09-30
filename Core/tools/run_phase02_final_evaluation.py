"""Chạy một final evaluation của Core MT đã khóa sau final gate.

Điểm bắt đầu là Core decision đã freeze. Model và checkpoint chỉ được đọc từ
decision; final result để báo cáo, không thể dùng để đổi Core.
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

from core_mt.adaptation import sha256_tree, validate_identifier
from core_mt.baseline import PretrainedAdapter, load_candidate_spec, run_final_evaluation
from core_mt.contracts import Direction
from core_mt.data import load_general_test_for_final_reporting, load_it_test_for_final_reporting
from core_mt.protocol import load_phase02_protocol


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def write_jsonl(path: Path, rows: list[dict[str, object]]) -> None:
    with path.open("x", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")


def frozen_selection(direction: Direction) -> tuple[str, Path, str, str, dict[str, object]]:
    """Return only the model and checkpoint fixed in the human Core decision."""
    decision_path = ROOT / "evidence" / "phase02" / "core_mt_decision.json"
    if not decision_path.is_file():
        raise FileNotFoundError("Final evaluation requires a frozen Core-MT decision.")
    decision = json.loads(decision_path.read_text(encoding="utf-8"))
    if decision.get("status") != "frozen_before_final_evaluation":
        raise ValueError("Core-MT decision is not frozen for final evaluation.")
    selections = decision.get("selections")
    if not isinstance(selections, dict) or set(selections) != {item.value for item in Direction}:
        raise ValueError("Core-MT decision must contain exactly two directions.")
    item = selections.get(direction.value)
    if not isinstance(item, dict):
        raise ValueError(f"Missing frozen selection for {direction.value}.")
    model = item.get("model")
    checkpoint_text = item.get("checkpoint")
    rationale = item.get("rationale")
    if not isinstance(model, str) or not isinstance(checkpoint_text, str) or not isinstance(rationale, str) or not rationale.strip():
        raise ValueError("Frozen Core-MT selection is incomplete.")
    checkpoint_path = ROOT / checkpoint_text
    try:
        relative = checkpoint_path.relative_to(ROOT / "models" / "phase02")
    except ValueError as error:
        raise ValueError("Frozen checkpoint must be inside models/phase02.") from error
    if len(relative.parts) != 2:
        raise ValueError("Frozen checkpoint path must identify one experiment and one checkpoint.")
    experiment_id = validate_identifier(relative.parts[0], field="experiment-id")
    checkpoint_id = validate_identifier(relative.parts[1], field="checkpoint-id")
    return model, checkpoint_path, experiment_id, checkpoint_id, decision


def main() -> int:
    parser = argparse.ArgumentParser(description="Run one final Phase 2 evaluation of the frozen Core MT.")
    parser.add_argument("--direction", choices=tuple(item.value for item in Direction), required=True)
    parser.add_argument("--dataset", choices=("it_test", "general_test"), required=True)
    args = parser.parse_args()

    protocol = load_phase02_protocol(ROOT / "configs" / "phase02_protocol.json")
    direction = Direction(args.direction)
    model, checkpoint_path, experiment_id, checkpoint_id, decision = frozen_selection(direction)
    candidate_config = json.loads((ROOT / "configs" / "phase02_models.json").read_text(encoding="utf-8"))
    if model not in candidate_config.get("candidates", {}):
        raise ValueError("Frozen Core-MT model is not an approved Phase 2 candidate.")

    training_run_path = ROOT / "evidence" / "phase02" / model / direction.value / "adapted" / experiment_id / "training_run.json"
    if not training_run_path.is_file():
        raise FileNotFoundError("Frozen checkpoint has no adaptation training record.")
    training_run = json.loads(training_run_path.read_text(encoding="utf-8"))
    if (
        training_run.get("stage") != "adaptation_training"
        or training_run.get("direction") != direction.value
        or training_run.get("experiment_id") != experiment_id
        or training_run.get("checkpoint_id") != checkpoint_id
    ):
        raise ValueError("Training record does not match the frozen Core checkpoint.")
    checkpoint_sha256 = sha256_tree(checkpoint_path)
    if checkpoint_sha256 != training_run.get("checkpoint", {}).get("sha256_tree"):
        raise ValueError("Frozen checkpoint bytes differ from the checkpoint recorded after adaptation.")
    adaptation_method = training_run.get("adaptation_method", {}).get("name")
    if adaptation_method not in {"full_ft", "lora"}:
        raise ValueError("Training record has no supported adaptation method.")

    expected_paths = {ROOT / path for path in protocol.locked_final_sets}
    dataset = load_it_test_for_final_reporting(ROOT) if args.dataset == "it_test" else load_general_test_for_final_reporting(ROOT)
    if dataset.path not in expected_paths:
        raise ValueError("Final runner attempted to use a dataset outside the frozen final-test paths.")
    output_dir = ROOT / "evidence" / "phase02" / "final" / direction.value / args.dataset
    staging_dir = Path(f"{output_dir}.running")
    if output_dir.exists():
        raise FileExistsError(f"Final evidence already exists and will not be replaced: {output_dir}")
    if staging_dir.exists():
        raise FileExistsError(f"An incomplete final evaluation needs inspection before retry: {staging_dir}")

    staging_dir.mkdir(parents=True)
    spec = load_candidate_spec(ROOT / "configs" / "phase02_models.json", model, direction)
    try:
        adapter = PretrainedAdapter(spec, checkpoint_path=checkpoint_path, adaptation_method=adaptation_method)
        predictions, metrics = run_final_evaluation(adapter, dataset, batch_size=1)
        (staging_dir / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        write_jsonl(staging_dir / "predictions.jsonl", predictions)
        run = {
            "stage": "final_evaluation",
            "dataset_role": dataset.role,
            "source_data": str(dataset.path.relative_to(ROOT)).replace("\\", "/"),
            "source_data_sha256": sha256_file(dataset.path),
            "frozen_core_decision": {
                "path": "evidence/phase02/core_mt_decision.json",
                "sha256": sha256_file(ROOT / "evidence" / "phase02" / "core_mt_decision.json"),
                "model": model,
                "direction": direction.value,
            },
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
            "inference_batch_size": 1,
            "prediction_file": "predictions.jsonl",
            "metrics_file": "metrics.json",
        }
        (staging_dir / "run.json").write_text(json.dumps(run, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        staging_dir.rename(output_dir)
    except Exception:
        raise
    print("PHASE_02_FINAL_EVALUATION=PASS")
    print(output_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
