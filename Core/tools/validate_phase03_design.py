"""Kiểm tra read-only bản thiết kế Phase 03 trước khi có experiment."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_DIRECTIONS = {"en_to_vi", "vi_to_en"}
EXPECTED_RECORD_KEYS = {
    "schema_version", "phase", "status", "run_id", "direction", "execution_device",
    "teacher", "student", "training", "data", "target_integrity", "output",
    "final_approval_required",
}
EXPECTED_INTEGRITY_KEYS = {
    "student_token_limit", "scan_scope", "on_any_target_over_limit",
    "required_report_fields", "preserve_teacher_targets", "forbidden_automatic_actions",
}
EXPECTED_GENERATION = {
    "max_length": 512, "min_length": 0, "do_sample": False, "num_beams": 1,
    "early_stopping": False, "length_penalty": 1.0, "repetition_penalty": 1.0,
    "no_repeat_ngram_size": 0, "decoder_start_token_id": 0, "eos_token_id": 1,
    "pad_token_id": 0,
}


def sha256_tree(directory: Path) -> str:
    if not directory.is_dir():
        raise FileNotFoundError(f"Missing teacher checkpoint: {directory}")
    digest = hashlib.sha256()
    for path in sorted(item for item in directory.rglob("*") if item.is_file()):
        digest.update(path.relative_to(directory).as_posix().encode("utf-8"))
        digest.update(b"\0")
        with path.open("rb") as handle:
            for block in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(block)
        digest.update(b"\0")
    return digest.hexdigest()


def main() -> int:
    protocol = json.loads((ROOT / "configs/phase03_protocol.json").read_text(encoding="utf-8"))
    protocol_status = protocol.get("status")
    expected_record_status = {
        "draft_for_design_review": "draft_for_final_approval",
        "frozen_before_experiments": "approved_before_experiment",
    }.get(protocol_status)
    if expected_record_status is None:
        raise ValueError("Protocol status must be draft_for_design_review or frozen_before_experiments.")
    if set(protocol.get("directions", ())) != EXPECTED_DIRECTIONS:
        raise ValueError("Phase 03 must retain exactly EN→VI and VI→EN.")
    method = protocol.get("knowledge_distillation", {})
    if method.get("status") != "sequence_level_approved_before_experiment":
        raise ValueError("The design gate requires an approved sequence-level KD selection.")
    if not (ROOT / method.get("research_record", "")).is_file():
        raise FileNotFoundError("Missing KD-method research record.")
    roles = protocol.get("data_roles", {})
    expected_roles = {"distillation_train", "selection_validation", "general_validation", "prohibited"}
    if set(roles) != expected_roles:
        raise ValueError("Unexpected Phase 03 data roles.")
    expected_prohibited = {"data/processed/it_en_vi/it_test.jsonl", "data/processed/general_test_flores200_devtest/general_test.jsonl"}
    if set(roles["prohibited"]) != expected_prohibited:
        raise ValueError("Phase 03 must prohibit both final-test paths.")
    if not all((ROOT / roles[name]).is_file() for name in ("distillation_train", "selection_validation", "general_validation")):
        raise FileNotFoundError("A permitted Phase 03 input is missing.")
    length_policy = protocol.get("input_length_policy", {})
    if length_policy.get("approval_status") != "approved_before_experiment":
        raise ValueError("Phase 03 requires an approved input-length policy.")
    policy_path = ROOT / length_policy.get("config", "")
    if not policy_path.is_file():
        raise FileNotFoundError("Missing Phase 03 input-length policy.")
    policy = json.loads(policy_path.read_text(encoding="utf-8"))
    if policy.get("status") != "approved_before_experiment" or policy.get("source_data") != roles["distillation_train"]:
        raise ValueError("Input-length policy is not approved for the Phase 03 train input.")
    audit_path = ROOT / policy.get("audit_record", "")
    if not audit_path.is_file():
        raise FileNotFoundError("Missing input-length audit record.")
    audit = json.loads(audit_path.read_text(encoding="utf-8"))
    if set(policy.get("directions", ())) != EXPECTED_DIRECTIONS:
        raise ValueError("Input-length policy must define both directions.")
    train_ids = {json.loads(line)["dataset_row_id"] for line in (ROOT / roles["distillation_train"]).read_text(encoding="utf-8").splitlines()}
    if len(train_ids) != policy.get("source_rows"):
        raise ValueError("Input-length policy source row count differs from IT Train.")
    for direction, details in policy["directions"].items():
        excluded = details.get("excluded_rows", [])
        excluded_ids = {item.get("dataset_row_id") for item in excluded}
        if None in excluded_ids or len(excluded_ids) != len(excluded):
            raise ValueError(f"Input-length policy has invalid excluded IDs: {direction}")
        if not excluded_ids.issubset(train_ids):
            raise ValueError(f"Input-length policy excludes a non-IT-Train ID: {direction}")
        expected_audit_rows = {
            item["dataset_row_id"]: item["tokens"]
            for item in audit["directions"][direction]["longest_rows"]
            if item["tokens"] > details.get("student_input_limit_tokens")
        }
        if {item["dataset_row_id"]: item["input_tokens"] for item in excluded} != expected_audit_rows:
            raise ValueError(f"Input-length policy exclusions do not match the completed audit: {direction}")
        if details.get("eligible_rows") != len(train_ids) - len(excluded):
            raise ValueError(f"Input-length policy eligible row count is incorrect: {direction}")
    # Read the locked Student identities before comparing draft experiment records.
    configured_directions = json.loads((ROOT / "configs/phase03_students.json").read_text(encoding="utf-8"))["students"]["student"]["directions"]
    experiment_paths = {
        "en_to_vi": "configs/phase03_experiments/phase03-seqkd-en-to-vi-v1.json",
        "vi_to_en": "configs/phase03_experiments/phase03-seqkd-vi-to-en-v1.json",
    }
    for direction, relative_path in experiment_paths.items():
        record_path = ROOT / relative_path
        if not record_path.is_file():
            raise FileNotFoundError(f"Missing draft experiment record: {direction}")
        record = json.loads(record_path.read_text(encoding="utf-8"))
        if set(record) != EXPECTED_RECORD_KEYS:
            raise ValueError(f"Unexpected or missing draft experiment-record fields: {direction}")
        if record.get("status") != expected_record_status or record.get("direction") != direction:
            raise ValueError(f"Invalid draft experiment record identity: {direction}")
        if record.get("run_id") != f"phase03-seqkd-{direction.replace('_', '-')}-v1":
            raise ValueError(f"Draft run ID is invalid: {direction}")
        device = record.get("execution_device", {})
        if device != {"type": "cuda", "index": 0, "name": "NVIDIA GeForce RTX 3050 Ti Laptop GPU"}:
            raise ValueError(f"Draft execution device differs from approved preflight device: {direction}")
        teacher = record.get("teacher", {})
        if teacher.get("model_id") != protocol["teacher"]["model_id"] or teacher.get("model_revision") != protocol["teacher"]["model_revision"]:
            raise ValueError(f"Draft Teacher identity differs from the frozen protocol: {direction}")
        if teacher.get("base_model_path") != protocol["teacher"]["base_model"]["path"] or teacher.get("base_model_sha256_tree") != protocol["teacher"]["base_model"]["sha256_tree"]:
            raise ValueError(f"Draft Teacher base model differs from the frozen protocol: {direction}")
        if any(teacher.get(name) != protocol["teacher"]["directions"][direction][name] for name in ("checkpoint", "checkpoint_sha256_tree", "source_prefix", "output_control_prefix")):
            raise ValueError(f"Draft Teacher differs from the frozen protocol: {direction}")
        student = record.get("student", {})
        if student.get("model_id") != configured_directions[direction]["model_id"] or student.get("revision") != configured_directions[direction]["revision"]:
            raise ValueError(f"Draft Student differs from the locked technical identity: {direction}")
        training = record.get("training", {})
        expected_training = {
            "seed": 42, "data_seed": 42, "optim": "adamw_torch_fused", "learning_rate": 5e-5,
            "lr_scheduler_type": "linear", "warmup_ratio": 0.0, "weight_decay": 0.0,
            "max_grad_norm": 1.0, "num_train_epochs": 1, "evaluation_strategy": "no",
            "save_strategy": "no", "load_best_model_at_end": False,
        }
        if any(training.get(name) != value for name, value in expected_training.items()):
            raise ValueError(f"Draft training settings do not match approved design: {direction}")
        if student.get("fp16") is not True or student.get("per_device_train_batch_size") != 4 or student.get("gradient_accumulation_steps") != 4 or student.get("effective_batch_size") != 16 or student.get("per_device_eval_batch_size") != 8:
            raise ValueError(f"Draft Student runtime differs from approved preflight: {direction}")
        if teacher.get("precision") != "fp16" or teacher.get("generation_batch_size") != 16 or teacher.get("generation") != EXPECTED_GENERATION:
            raise ValueError(f"Draft Teacher runtime differs from approved preflight: {direction}")
        if record.get("data", {}).get("eligible_rows") != policy["directions"][direction]["eligible_rows"]:
            raise ValueError(f"Draft eligible row count differs from input-length policy: {direction}")
        integrity = record.get("target_integrity", {})
        if set(integrity) != EXPECTED_INTEGRITY_KEYS:
            raise ValueError(f"Draft target-integrity fields are incomplete: {direction}")
        if (
            integrity.get("student_token_limit") != 512
            or integrity.get("scan_scope") != "Tokenize every persisted Teacher target with this Student tokenizer after generation completes and before Student training."
            or integrity.get("on_any_target_over_limit") != "training_blocked_needs_protocol_review"
            or integrity.get("required_report_fields") != ["dataset_row_id", "student_target_tokens"]
            or integrity.get("preserve_teacher_targets") is not True
            or integrity.get("forbidden_automatic_actions") != ["truncate_target", "exclude_target", "rewrite_target", "regenerate_target"]
        ):
            raise ValueError(f"Draft target-integrity rule is incomplete: {direction}")
        if record.get("output") != {"final_checkpoint": f"models/phase03/{record['run_id']}/final-epoch-1"}:
            raise ValueError(f"Draft output checkpoint is invalid: {direction}")
    decision = json.loads((ROOT / protocol["teacher"]["core_decision"]).read_text(encoding="utf-8"))
    if decision.get("status") != protocol["teacher"]["required_decision_status"]:
        raise ValueError("Phase 02 Core decision is not frozen.")
    if set(decision.get("selections", ())) != EXPECTED_DIRECTIONS:
        raise ValueError("Phase 02 Core decision must contain both directions.")
    for direction, expected in protocol["teacher"]["directions"].items():
        if decision["selections"][direction].get("checkpoint") != expected["checkpoint"]:
            raise ValueError(f"Teacher checkpoint differs from frozen decision: {direction}")
        if sha256_tree(ROOT / expected["checkpoint"]) != expected["checkpoint_sha256_tree"]:
            raise ValueError(f"Teacher checkpoint hash differs: {direction}")
    student_config = json.loads((ROOT / "configs/phase03_students.json").read_text(encoding="utf-8"))
    if student_config.get("status") != "draft_for_design_review":
        raise ValueError("Student configuration is not a design-review draft.")
    students = student_config.get("students", {})
    if set(students) != {"student"}:
        raise ValueError("Student configuration must define exactly one Student.")
    configured_directions = students["student"].get("directions", {})
    if set(configured_directions) != EXPECTED_DIRECTIONS:
        raise ValueError("Student configuration must define both directions.")
    for direction, configured in configured_directions.items():
        required = {"model_id", "revision", "snapshot_sha256_tree", "input_prefix", "revision_evidence"}
        if not required.issubset(configured):
            raise ValueError(f"Student technical identity is incomplete: {direction}")
        if protocol["student"]["directions"][direction] != {
            "model_id": configured["model_id"],
            "revision": configured["revision"],
            "source_prefix": configured["input_prefix"],
        }:
            raise ValueError(f"Protocol and Student configuration differ: {direction}")
        experiment = json.loads((ROOT / experiment_paths[direction]).read_text(encoding="utf-8"))
        if experiment["student"].get("snapshot_sha256_tree") != configured["snapshot_sha256_tree"]:
            raise ValueError(f"Experiment and Student snapshot hashes differ: {direction}")
        evidence_path = ROOT / configured["revision_evidence"]
        if not evidence_path.is_file():
            raise FileNotFoundError(f"Missing Student revision evidence: {direction}")
        evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
        adapter = evidence.get("adapter", {})
        if adapter.get("direction") != direction or adapter.get("model_id") != configured["model_id"] or adapter.get("checkpoint_revision") != configured["revision"]:
            raise ValueError(f"Student revision evidence does not match configuration: {direction}")
    print("PHASE_03_DESIGN=PASS")
    print("NEXT_ACTION=" + ("human_final_approval_of_experiment_records" if protocol_status == "draft_for_design_review" else "execution_may_begin"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
