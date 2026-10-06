"""Read-only design gate for the EN-to-VI Phase 03 Teacher-target reuse amendment."""
from __future__ import annotations

import json

from reuse_phase03_teacher_targets import (
    POLICY_PATH, PROTOCOL_PATH, RECORD_PATH, ROOT, TRAIN_PATH, load_eligible_rows,
    read_json, sha256_file, validate_parent, validate_policy,
)


def main() -> int:
    protocol, policy, record = read_json(PROTOCOL_PATH), read_json(POLICY_PATH), read_json(RECORD_PATH)
    statuses = (protocol.get("status"), policy.get("status"), record.get("status"))
    if statuses not in {
        ("draft_for_design_review", "draft_for_final_approval", "draft_for_final_approval"),
        ("frozen_before_experiments", "approved_before_experiment", "approved_before_experiment"),
    }:
        raise ValueError("Reuse protocol, policy, and record do not have a consistent approval state.")
    if protocol.get("reuse_policy") != POLICY_PATH.relative_to(ROOT).as_posix() or protocol.get("experiment_record") != RECORD_PATH.relative_to(ROOT).as_posix():
        raise ValueError("Reuse protocol does not identify the configured policy and experiment record.")
    if record.get("run_id") != "phase03-seqkd-en-to-vi-v2" or record.get("direction") != "en_to_vi":
        raise ValueError("Reuse experiment record identity is invalid.")
    if record.get("data", {}).get("reuse_policy") != POLICY_PATH.relative_to(ROOT).as_posix() or record["data"].get("eligible_rows") != 121544:
        raise ValueError("Reuse experiment record data boundary is invalid.")
    validate_policy(policy)
    parent = policy["parent_run"]
    if sha256_file(TRAIN_PATH) != parent["train_sha256"]:
        raise ValueError("IT Train checksum no longer matches the parent run.")
    parent_target = ROOT / parent["target_path"]
    parent_report = ROOT / parent["integrity_report_path"]
    if sha256_file(parent_target) != parent["target_sha256"] or sha256_file(parent_report) != parent["integrity_report_sha256"]:
        raise ValueError("Parent target artifact or integrity report checksum differs from the approved reuse policy.")
    validate_parent(policy, [json.loads(line) for line in parent_target.read_text(encoding="utf-8").splitlines()], read_json(parent_report))
    if len(load_eligible_rows(policy)) != record["data"]["eligible_rows"]:
        raise ValueError("Current eligible source rows differ from the experiment record.")
    print("PHASE03_TEACHER_TARGET_REUSE_V2_DESIGN=PASS")
    print("NEXT_ACTION=" + ("human_final_approval" if protocol["status"] == "draft_for_design_review" else "reuse_may_begin"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
