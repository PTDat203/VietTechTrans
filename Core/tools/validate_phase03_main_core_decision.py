"""Read-only validation for both frozen Phase 03 Student decisions."""
from __future__ import annotations

import csv
import hashlib
import json
from pathlib import Path

from prepare_phase03_review_sheet import FIELDS, ROOT, build_review_rows
from run_phase03_teacher_targets import sha256_tree


DECISION_PATH = ROOT / "evidence/phase03/main_core_decision.json"
EXPECTED_RUN_IDS = {"en_to_vi": "phase03-seqkd-en-to-vi-v2", "vi_to_en": "phase03-seqkd-vi-to-en-v3"}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def require_file_hash(item: dict[str, object]) -> Path:
    path = ROOT / str(item["path"])
    if not path.is_file():
        raise FileNotFoundError(f"Missing decision artifact: {path}")
    if sha256_file(path) != item["sha256"]:
        raise ValueError(f"Decision artifact checksum differs: {path}")
    return path


def validate_review(direction: str, review: dict[str, object]) -> None:
    path = require_file_hash(review)
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames != FIELDS:
            raise ValueError("Frozen review columns differ from the review contract.")
        rows = list(reader)
    expected = {row["dataset_row_id"]: row for row in build_review_rows(direction)}
    if len(rows) != review["rows"] or {row["dataset_row_id"] for row in rows} != set(expected):
        raise ValueError(f"Frozen review does not contain the sealed 50-row {direction} sample.")
    for row in rows:
        source = expected[row["dataset_row_id"]]
        if any(row[field] != source[field] for field in ("direction", "source", "reference", "teacher_prediction", "student_prediction")):
            raise ValueError("Frozen review changes a locked evidence column.")
    if sum(row["translation_error"] == "yes" for row in rows) != review["translation_error_yes"]:
        raise ValueError("Frozen review translation-error count differs from the decision.")
    if sum(row["technical_error"] == "yes" for row in rows) != review["technical_error_yes"]:
        raise ValueError("Frozen review technical-error count differs from the decision.")


def validate_direction(direction: str, selection: dict[str, object]) -> None:
    if selection.get("status") != "frozen_for_phase04" or selection.get("run_id") != EXPECTED_RUN_IDS[direction]:
        raise ValueError(f"{direction} is not frozen with its approved Phase 03 run.")
    require_file_hash(selection["experiment_record"])
    training_path = require_file_hash(selection["training"])
    training = json.loads(training_path.read_text(encoding="utf-8"))
    if training.get("run_id") != EXPECTED_RUN_IDS[direction] or training.get("direction") != direction:
        raise ValueError("Frozen training evidence has the wrong run identity.")
    if training["input"]["rows"] != selection["teacher_target_input"]["rows"] or training["input"]["target_sha256"] != selection["teacher_target_input"]["sha256"]:
        raise ValueError("Frozen training input differs from the decision target input.")
    if training["student"]["model_id"] != selection["student"]["model_id"] or training["student"]["revision"] != selection["student"]["revision"]:
        raise ValueError("Frozen Student identity differs from the decision.")
    checkpoint = ROOT / selection["checkpoint"]["path"]
    if sha256_tree(checkpoint) != selection["checkpoint"]["sha256_tree"]:
        raise ValueError("Frozen checkpoint checksum differs from the decision.")

    integrity_path = ROOT / selection["teacher_target_input"]["integrity_report"]
    integrity = json.loads(integrity_path.read_text(encoding="utf-8"))
    if integrity.get("status") != "pass" or integrity.get("persisted_target_rows") != selection["teacher_target_input"]["rows"] or integrity.get("target_sha256") != selection["teacher_target_input"]["sha256"]:
        raise ValueError("Frozen Teacher-target integrity evidence is invalid.")

    for cohort in selection["evaluation"].values():
        for metric in (cohort["student_metrics"], cohort["teacher_comparable_metrics"]):
            path = require_file_hash(metric)
            values = json.loads(path.read_text(encoding="utf-8"))
            if values.get("rows") != cohort["rows"] or values.get("chrF++") != metric["chrF++"] or values.get("sacreBLEU") != metric["sacreBLEU"]:
                raise ValueError(f"Frozen metric content differs from the decision: {path}")
    validate_review(direction, selection["manual_review"])


def main() -> int:
    decision = json.loads(DECISION_PATH.read_text(encoding="utf-8"))
    if decision.get("stage") != "phase03_main_core_decision" or decision.get("status") != "completed_with_two_frozen_directions_for_phase04":
        raise ValueError("Phase 03 decision is not complete for both directions.")
    selections = decision.get("decisions", {})
    for direction in EXPECTED_RUN_IDS:
        validate_direction(direction, selections.get(direction, {}))
    print("PHASE03_MAIN_CORE_DECISION=PASS directions=en_to_vi,vi_to_en")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
