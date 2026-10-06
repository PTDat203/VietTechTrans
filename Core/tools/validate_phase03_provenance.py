"""Validate frozen Phase 03 lineage without hiding documented historical limits."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

from run_phase03_teacher_targets import sha256_tree


ROOT = Path(__file__).resolve().parents[1]
DECISION_PATH = ROOT / "evidence/phase03/main_core_decision.json"
HISTORICAL_LIMITS = {
    "en_to_vi": {
        "runtime.json",
        "input_manifest.json",
        "source_snapshot/run_phase03_student_training.py",
        "source_snapshot/run_phase03_student_evaluation.py",
    },
    "vi_to_en": {"runtime.json", "input_manifest.json"},
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_json(path: Path) -> dict[str, object]:
    return json.loads(path.read_text(encoding="utf-8"))


def load_row_ids(path: Path) -> set[str]:
    with path.open(encoding="utf-8") as handle:
        identifiers: set[str] = set()
        for line in handle:
            if not line.strip():
                continue
            row = json.loads(line)
            # Dataset releases use dataset_row_id; generated predictions retain
            # the same value under row_id.
            identifier = row.get("dataset_row_id", row.get("row_id"))
            if not isinstance(identifier, str):
                raise ValueError(f"No usable row identifier in {path}")
            identifiers.add(identifier)
        return identifiers


def validate_snapshot(direction: str, run_root: Path) -> list[str]:
    manifest = read_json(run_root / "snapshot_manifest.json")
    listed = {item["path"]: item["sha256"] for item in manifest["files"]}
    for relative_path, expected_hash in listed.items():
        path = run_root / relative_path
        if not path.is_file() or sha256_file(path) != expected_hash:
            raise ValueError(f"Snapshot manifest differs: {path}")

    required = {
        "runtime.json",
        "input_manifest.json",
        "source_snapshot/run_phase03_student_training.py",
        "source_snapshot/run_phase03_student_evaluation.py",
    }
    missing = required - set(listed)
    if missing != HISTORICAL_LIMITS[direction]:
        raise ValueError(f"Unexpected snapshot coverage for {direction}: {sorted(missing)}")
    return sorted(missing)


def validate_cohort(direction: str, checkpoint: dict[str, object], cohort: dict[str, object]) -> None:
    student_path = ROOT / cohort["student_metrics"]["path"]
    teacher_path = ROOT / cohort["teacher_comparable_metrics"]["path"]
    for path, expected_hash in ((student_path, cohort["student_metrics"]["sha256"]), (teacher_path, cohort["teacher_comparable_metrics"]["sha256"])):
        if not path.is_file() or sha256_file(path) != expected_hash:
            raise ValueError(f"Metric artifact differs: {path}")

    student = read_json(student_path)
    teacher = read_json(teacher_path)
    if student["rows"] != teacher["rows"] != cohort["rows"]:
        raise ValueError("Student and Teacher metrics use different row counts.")

    evaluation_root = student_path.parent
    run = read_json(evaluation_root / "run.json")
    scope = read_json(evaluation_root / "comparison_scope.json")
    if run["direction"] != direction or run["dataset_role"] != student["dataset_role"]:
        raise ValueError("Evaluation run identity differs from its metric.")
    if run["evaluated_checkpoint"]["path"] != checkpoint["path"] or run["evaluated_checkpoint"]["sha256_tree"] != checkpoint["sha256_tree"]:
        raise ValueError("Evaluation uses a different frozen checkpoint.")
    if scope["dataset"]["eligible_rows"] != student["rows"]:
        raise ValueError("Comparison scope row count differs from the metric.")

    dataset_path = ROOT / scope["dataset"]["path"]
    if sha256_file(dataset_path) != scope["dataset"]["sha256"] or run["source_data"] != scope["dataset"]["path"]:
        raise ValueError("Comparison dataset lineage differs.")
    excluded = {item["dataset_row_id"] for item in scope["excluded_rows"]}
    expected_ids = load_row_ids(dataset_path) - excluded
    prediction_path = evaluation_root / run["student_prediction_file"]
    prediction_ids = load_row_ids(prediction_path)
    if prediction_ids != expected_ids or len(prediction_ids) != student["rows"]:
        raise ValueError("Student predictions do not match the approved comparison cohort.")


def validate_direction(direction: str, decision: dict[str, object]) -> list[str]:
    selection = decision["decisions"][direction]
    checkpoint = selection["checkpoint"]
    if sha256_tree(ROOT / checkpoint["path"]) != checkpoint["sha256_tree"]:
        raise ValueError(f"Frozen checkpoint hash differs for {direction}.")
    run_root = ROOT / selection["training"]["path"]
    run_root = run_root.parents[2]
    limits = validate_snapshot(direction, run_root)
    for cohort in selection["evaluation"].values():
        validate_cohort(direction, checkpoint, cohort)
    return limits


def validate_all() -> dict[str, list[str]]:
    decision = read_json(DECISION_PATH)
    if decision.get("status") != "completed_with_two_frozen_directions_for_phase04":
        raise ValueError("Phase 03 is not frozen for both directions.")
    return {direction: validate_direction(direction, decision) for direction in ("en_to_vi", "vi_to_en")}


def main() -> int:
    limits = validate_all()
    for direction, missing in limits.items():
        print(f"{direction}=PASS_WITH_DISCLOSED_HISTORICAL_LIMIT missing={','.join(missing)}")
    print("PHASE03_PROVENANCE=PASS_WITH_DISCLOSED_HISTORICAL_LIMIT")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
