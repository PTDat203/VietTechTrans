"""Read-only gate for the audited VI-to-EN v3 Teacher-target reuse run."""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from core_mt.contracts import Direction
from core_mt.data import load_general_validation, load_it_role
from reuse_phase03_teacher_targets_vi_to_en_v3 import (
    PARENT_OVER_LIMIT,
    PARENT_POLICY_IDS,
    load_eligible_rows,
    validate_parent,
    validate_policy,
)
from run_phase03_student_evaluation import load_comparison_rows
from run_phase03_teacher_targets import sha256_file


PROTOCOL = ROOT / "configs/phase03_protocol_teacher_target_reuse_vi_to_en_v3.json"
POLICY = ROOT / "configs/phase03_teacher_target_reuse_vi_to_en_v3.json"
RECORD = ROOT / "configs/phase03_experiments/phase03-seqkd-vi-to-en-v3.json"
AMENDMENT = ROOT / "configs/phase03_evaluation_input_length_amendment_vi_to_en_v3.json"
PARENT_PROTOCOL = ROOT / "configs/phase03_protocol_vi_to_en_v2.json"


def read(path: Path) -> dict[str, object]:
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> int:
    protocol, policy, record, amendment = read(PROTOCOL), read(POLICY), read(RECORD), read(AMENDMENT)
    if (protocol.get("status"), policy.get("status"), record.get("status"), amendment.get("status")) != ("frozen_before_experiments", "approved_before_experiment", "approved_before_experiment", "approved_before_experiment"):
        raise ValueError("VI-to-EN v3 approval states are inconsistent.")
    if protocol.get("parent_protocol", {}).get("sha256") != sha256_file(PARENT_PROTOCOL):
        raise ValueError("VI-to-EN v3 parent protocol checksum differs.")
    if protocol.get("reuse_policy") != POLICY.relative_to(ROOT).as_posix() or protocol.get("experiment_record") != RECORD.relative_to(ROOT).as_posix():
        raise ValueError("VI-to-EN v3 reuse amendment paths are invalid.")
    if record.get("run_id") != "phase03-seqkd-vi-to-en-v3" or record.get("direction") != "vi_to_en" or record.get("data", {}).get("reuse_policy") != POLICY.relative_to(ROOT).as_posix() or record["data"].get("eligible_rows") != 121539:
        raise ValueError("VI-to-EN v3 experiment record is invalid.")
    validate_policy(policy)
    if len(load_eligible_rows(policy)) != 121539:
        raise ValueError("VI-to-EN v3 eligible rows differ from the policy.")
    parent = policy["parent_run"]
    target, report = ROOT / parent["target_path"], ROOT / parent["integrity_report_path"]
    if sha256_file(target) != parent["target_sha256"] or sha256_file(report) != parent["integrity_report_sha256"]:
        raise ValueError("VI-to-EN v2 parent evidence differs from the approved checksums.")
    validate_parent(policy, [json.loads(line) for line in target.read_text(encoding="utf-8").splitlines()], read(report))
    excluded = {item["dataset_row_id"] for item in policy["excluded_rows"]}
    if excluded != PARENT_POLICY_IDS | set(PARENT_OVER_LIMIT):
        raise ValueError("VI-to-EN v3 exclusions are not the fixed parent-policy plus parent-integrity union.")
    if amendment.get("student_run_id") != record["run_id"] or amendment.get("student_input_limit_tokens") != 512:
        raise ValueError("VI-to-EN v3 evaluation amendment identity is invalid.")
    for role, dataset in {"selection_validation": load_it_role("selection_validation", ROOT), "general_validation": load_general_validation(ROOT)}.items():
        eligible_rows, teacher_rows = load_comparison_rows(dataset, amendment["datasets"][role], Direction.VI_TO_EN)
        if len(eligible_rows) != len(teacher_rows) or len(eligible_rows) != amendment["datasets"][role]["eligible_rows"]:
            raise ValueError(f"VI-to-EN v3 comparison cohort is invalid: {role}")
    print("PHASE03_VI_TO_EN_V3_DESIGN=PASS")
    print("NEXT_ACTION=reuse_may_begin")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
