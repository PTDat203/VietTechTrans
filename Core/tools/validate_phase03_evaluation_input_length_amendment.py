"""Read-only gate for the EN-to-VI Phase 03 validation-length amendment."""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from core_mt.data import load_general_validation, load_it_role
from run_phase03_teacher_targets import sha256_file


AMENDMENT_PATH = ROOT / "configs/phase03_evaluation_input_length_amendment_en_to_vi_v1.json"
RUN_ID = "phase03-seqkd-en-to-vi-v2"
EXPECTED_EXCLUSION = "948273d4ab1d4f0978ca1ad3b664b36ecc0e78b704514d7598e4deafd606e5ad"


def read_json(path: Path) -> dict[str, object]:
    return json.loads(path.read_text(encoding="utf-8"))


def load_dataset(role: str):
    return load_it_role(role, ROOT) if role == "selection_validation" else load_general_validation(ROOT)


def main() -> int:
    amendment = read_json(AMENDMENT_PATH)
    if amendment.get("status") not in {"draft_for_final_approval", "approved_before_experiment"}:
        raise ValueError("Evaluation amendment has an invalid approval state.")
    if amendment.get("student_run_id") != RUN_ID or amendment.get("student_input_limit_tokens") != 512:
        raise ValueError("Evaluation amendment does not identify the fixed Student run and capacity.")
    datasets = amendment.get("datasets")
    if not isinstance(datasets, dict) or set(datasets) != {"selection_validation", "general_validation"}:
        raise ValueError("Evaluation amendment must define exactly the two permitted validation datasets.")
    for role, details in datasets.items():
        if not isinstance(details, dict):
            raise ValueError(f"Invalid dataset details: {role}")
        dataset = load_dataset(role)
        if dataset.path.relative_to(ROOT).as_posix() != details.get("path") or sha256_file(dataset.path) != details.get("sha256"):
            raise ValueError(f"Validation dataset differs from the amendment: {role}")
        if len(dataset.rows) != details.get("source_rows"):
            raise ValueError(f"Validation row count differs from the amendment: {role}")
        excluded = details.get("excluded_rows")
        if not isinstance(excluded, list):
            raise ValueError(f"Invalid exclusion list: {role}")
        excluded_ids = {item.get("dataset_row_id") for item in excluded if isinstance(item, dict)}
        if len(excluded_ids) != len(excluded) or not excluded_ids.issubset({row.row_id for row in dataset.rows}):
            raise ValueError(f"Invalid validation exclusions: {role}")
        if details.get("eligible_rows") != len(dataset.rows) - len(excluded):
            raise ValueError(f"Eligible validation count is invalid: {role}")
        if role == "selection_validation":
            if excluded_ids != {EXPECTED_EXCLUSION} or excluded[0].get("student_input_tokens") != 686 or excluded[0].get("reason") != "exceeds_fixed_student_input_capacity":
                raise ValueError("Selection-validation exclusion does not match the audited unsupported row.")
        elif excluded:
            raise ValueError("General Validation must not exclude a row.")
        teacher = details.get("teacher_predictions")
        if not isinstance(teacher, dict):
            raise ValueError(f"Missing Teacher prediction lineage: {role}")
        prediction_path, run_path = ROOT / str(teacher.get("path", "")), ROOT / str(teacher.get("run_path", ""))
        if not prediction_path.is_file() or not run_path.is_file() or sha256_file(prediction_path) != teacher.get("sha256") or sha256_file(run_path) != teacher.get("run_sha256"):
            raise ValueError(f"Teacher prediction lineage differs from the amendment: {role}")
    print("PHASE03_EVALUATION_LENGTH_AMENDMENT=PASS")
    print("NEXT_ACTION=" + ("human_final_approval" if amendment["status"] == "draft_for_final_approval" else "evaluation_may_begin"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
