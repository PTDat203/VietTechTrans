"""Read-only gate for the VI-to-EN v2 run and its validation-length amendment."""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from core_mt.contracts import Direction
from core_mt.data import load_general_validation, load_it_role
from run_phase03_student_evaluation import load_comparison_rows
from run_phase03_teacher_targets import load_expected_rows, sha256_file


PROTOCOL = ROOT / "configs/phase03_protocol_vi_to_en_v2.json"
POLICY = ROOT / "configs/phase03_vi_to_en_v2_input_policy.json"
RECORD = ROOT / "configs/phase03_experiments/phase03-seqkd-vi-to-en-v2.json"
AMENDMENT = ROOT / "configs/phase03_evaluation_input_length_amendment_vi_to_en_v2.json"
PARENT_PROTOCOL = ROOT / "configs/phase03_protocol.json"
PARENT_POLICY = ROOT / "configs/phase03_input_length_policy.json"
EXPECTED_CONTENTLESS_IDS = {
    "8e53e283484f5624ade61e3791ddcaa71df6030db70960f0575a9e1baf0780ef",
    "ee111c9ad966968cf0e2212ec80b2a44f9be56e789826f2623e36ef2d01fa24f",
}
EXPECTED_LENGTH_IDS = {
    "c3415a68a988702f855e8e6dd23398b2aa2dddea771f47428df941a3305fcff3",
    "e679898355a6de521823b1c00f22fb949c007177097554d77d2428577d0a51f6",
}
EXPECTED_VALIDATION_ID = "948273d4ab1d4f0978ca1ad3b664b36ecc0e78b704514d7598e4deafd606e5ad"


def read(path: Path) -> dict[str, object]:
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> int:
    protocol, policy, record, amendment = read(PROTOCOL), read(POLICY), read(RECORD), read(AMENDMENT)
    statuses = (protocol.get("status"), policy.get("status"), record.get("status"), amendment.get("status"))
    if statuses not in {
        ("draft_for_final_approval",) * 4,
        ("frozen_before_experiments", "approved_before_experiment", "approved_before_experiment", "approved_before_experiment"),
    }:
        raise ValueError("VI-to-EN v2 approval states are inconsistent.")
    if protocol.get("parent_protocol", {}).get("sha256") != sha256_file(PARENT_PROTOCOL):
        raise ValueError("VI-to-EN v2 parent protocol checksum differs.")
    if policy.get("parent_policy", {}).get("sha256") != sha256_file(PARENT_POLICY):
        raise ValueError("VI-to-EN v2 parent policy checksum differs.")
    if protocol.get("input_policy") != POLICY.relative_to(ROOT).as_posix() or protocol.get("experiment_record") != RECORD.relative_to(ROOT).as_posix():
        raise ValueError("VI-to-EN v2 amendment paths are invalid.")
    if record.get("run_id") != "phase03-seqkd-vi-to-en-v2" or record.get("direction") != "vi_to_en":
        raise ValueError("VI-to-EN v2 experiment identity is invalid.")
    if record.get("data", {}).get("input_length_policy") != POLICY.relative_to(ROOT).as_posix() or record["data"].get("eligible_rows") != 121543:
        raise ValueError("VI-to-EN v2 data boundary is invalid.")
    details = policy.get("directions", {}).get("vi_to_en", {})
    excluded = details.get("excluded_rows", [])
    excluded_ids = {item.get("dataset_row_id") for item in excluded if isinstance(item, dict)}
    if excluded_ids != EXPECTED_LENGTH_IDS | EXPECTED_CONTENTLESS_IDS or len(excluded) != 4 or details.get("eligible_rows") != 121543:
        raise ValueError("VI-to-EN v2 exclusions are invalid.")
    if len(load_expected_rows("vi_to_en", policy)) != 121543:
        raise ValueError("VI-to-EN v2 eligible rows differ from the policy.")
    if amendment.get("student_run_id") != record["run_id"] or amendment.get("student_input_limit_tokens") != 512:
        raise ValueError("VI-to-EN evaluation amendment identity is invalid.")
    for role, dataset in {
        "selection_validation": load_it_role("selection_validation", ROOT),
        "general_validation": load_general_validation(ROOT),
    }.items():
        eligible_rows, teacher_rows = load_comparison_rows(dataset, amendment["datasets"][role], Direction.VI_TO_EN)
        if len(eligible_rows) != len(teacher_rows) or len(eligible_rows) != amendment["datasets"][role]["eligible_rows"]:
            raise ValueError(f"VI-to-EN comparison cohort is invalid: {role}")
    selection_exclusions = amendment["datasets"]["selection_validation"]["excluded_rows"]
    if selection_exclusions != [{"dataset_row_id": EXPECTED_VALIDATION_ID, "student_input_tokens": 935, "reason": "exceeds_fixed_student_input_capacity"}]:
        raise ValueError("VI-to-EN selection-validation exclusion is invalid.")
    if amendment["datasets"]["general_validation"]["excluded_rows"]:
        raise ValueError("VI-to-EN General Validation must not exclude a row.")
    print("PHASE03_VI_TO_EN_V2_DESIGN=PASS")
    print("NEXT_ACTION=" + ("human_final_approval" if protocol["status"] == "draft_for_final_approval" else "execution_may_begin"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
