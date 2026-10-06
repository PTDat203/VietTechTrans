"""Create an audited Phase 03 target run by reusing a fixed parent artifact.

This is intentionally limited to the approved EN-to-VI v2 correction. It never
loads a Teacher model, uses a GPU, or accepts a caller-supplied exclusion rule.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import platform
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from run_phase03_teacher_targets import ROOT, sha256_file, sha256_tree, validate_targets, write_json


PROTOCOL_PATH = ROOT / "configs/phase03_protocol_teacher_target_reuse_v2.json"
POLICY_PATH = ROOT / "configs/phase03_teacher_target_reuse_en_to_vi_v2.json"
RECORD_PATH = ROOT / "configs/phase03_experiments/phase03-seqkd-en-to-vi-v2.json"
TRAIN_PATH = ROOT / "data/processed/it_en_vi/train.jsonl"


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def source_hash(source: str) -> str:
    return hashlib.sha256(source.encode("utf-8")).hexdigest()


def load_eligible_rows(policy: dict[str, Any]) -> list[dict[str, str]]:
    excluded_ids = {item["dataset_row_id"] for item in policy["excluded_rows"]}
    rows: list[dict[str, str]] = []
    with TRAIN_PATH.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            raw = json.loads(line)
            row_id, source = raw.get("dataset_row_id"), raw.get("en_clean")
            if not isinstance(row_id, str) or not isinstance(source, str) or not source:
                raise ValueError(f"Invalid EN-to-VI IT Train row at {line_number}.")
            if row_id not in excluded_ids:
                rows.append({"dataset_row_id": row_id, "source": source, "source_sha256": source_hash(source)})
    if len(rows) != policy["eligible_rows"]:
        raise ValueError("Eligible row count differs from the approved reuse policy.")
    return rows


def validate_policy(policy: dict[str, Any]) -> None:
    excluded = policy.get("excluded_rows")
    if not isinstance(excluded, list) or len(excluded) != 3:
        raise ValueError("Reuse policy must name exactly three exclusions.")
    ids = {item.get("dataset_row_id") for item in excluded}
    if None in ids or len(ids) != len(excluded):
        raise ValueError("Reuse policy has invalid or duplicate excluded IDs.")
    if any(item.get("reason") != "empty_teacher_target_in_parent_run" for item in excluded):
        raise ValueError("Reuse policy may exclude only the approved empty-target reason.")
    if policy.get("source_rows") != 121547 or policy.get("eligible_rows") != 121544:
        raise ValueError("Reuse policy row counts differ from the approved correction.")


def validate_parent(policy: dict[str, Any], parent_rows: list[dict[str, Any]], report: dict[str, Any]) -> None:
    parent = policy["parent_run"]
    excluded_ids = sorted(item["dataset_row_id"] for item in policy["excluded_rows"])
    if report.get("status") != "failed_structural_integrity" or report.get("expected_rows") != 121547 or report.get("persisted_target_rows") != 121547:
        raise ValueError("Parent report is not the expected failed v1 target scan.")
    errors = report.get("structural_errors", {})
    if errors.get("empty_target_dataset_row_ids") != excluded_ids:
        raise ValueError("Parent report empty-target IDs differ from the approved reuse policy.")
    for key in ("missing_dataset_row_ids", "duplicate_dataset_row_ids", "unexpected_dataset_row_ids", "source_hash_mismatch_dataset_row_ids"):
        if errors.get(key) != []:
            raise ValueError(f"Parent report has additional structural error: {key}.")
    if report.get("over_limit_rows") != [] or report.get("student_training_permitted") is not False:
        raise ValueError("Parent report does not have the expected over-limit/training state.")
    if len(parent_rows) != 121547:
        raise ValueError("Parent target file row count differs from the approved parent run.")


def create_snapshot(run_dir: Path) -> None:
    (run_dir / "source_snapshot").mkdir()
    (run_dir / "config_snapshot").mkdir()
    shutil.copy2(Path(__file__), run_dir / "source_snapshot" / Path(__file__).name)
    for path in (PROTOCOL_PATH, POLICY_PATH, RECORD_PATH):
        shutil.copy2(path, run_dir / "config_snapshot" / path.name)
    (run_dir / "command.txt").write_text(" ".join(sys.argv) + "\n", encoding="utf-8", newline="\n")
    (run_dir / "dependency_versions.txt").write_text(json.dumps({"python": sys.version.split()[0], "platform": platform.platform()}, indent=2) + "\n", encoding="utf-8", newline="\n")


def write_snapshot_manifest(run_dir: Path) -> None:
    files = sorted(path for path in run_dir.rglob("*") if path.is_file())
    write_json(run_dir / "snapshot_manifest.json", {
        "files": [{"path": path.relative_to(run_dir).as_posix(), "sha256": sha256_file(path)} for path in files],
    })


def require_approval(protocol: dict[str, Any], policy: dict[str, Any], record: dict[str, Any]) -> None:
    if protocol.get("status") != "frozen_before_experiments":
        raise PermissionError("Reuse protocol must be frozen_before_experiments before execution.")
    if policy.get("status") != "approved_before_experiment" or record.get("status") != "approved_before_experiment":
        raise PermissionError("Reuse policy and experiment record must be approved before execution.")
    if record.get("run_id") != "phase03-seqkd-en-to-vi-v2" or record.get("direction") != "en_to_vi":
        raise ValueError("Reuse experiment record identity is invalid.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    protocol, policy, record = read_json(PROTOCOL_PATH), read_json(POLICY_PATH), read_json(RECORD_PATH)
    require_approval(protocol, policy, record)
    validate_policy(policy)
    parent = policy["parent_run"]
    parent_target = ROOT / parent["target_path"]
    parent_report = ROOT / parent["integrity_report_path"]
    if sha256_file(TRAIN_PATH) != parent["train_sha256"]:
        raise ValueError("Current IT Train checksum differs from the approved parent input.")
    if not parent_target.is_file() or sha256_file(parent_target) != parent["target_sha256"]:
        raise ValueError("Parent target file is missing or differs from the approved checksum.")
    if not parent_report.is_file() or sha256_file(parent_report) != parent["integrity_report_sha256"]:
        raise ValueError("Parent integrity report is missing or differs from the approved checksum.")
    parent_rows = [json.loads(line) for line in parent_target.read_text(encoding="utf-8").splitlines()]
    parent_integrity = read_json(parent_report)
    validate_parent(policy, parent_rows, parent_integrity)
    eligible_rows = load_eligible_rows(policy)
    eligible_ids = {row["dataset_row_id"] for row in eligible_rows}
    reused_rows = [row for row in parent_rows if row.get("dataset_row_id") in eligible_ids]
    if len(reused_rows) != len(eligible_rows):
        raise ValueError("Parent artifact does not provide exactly one target for every eligible row.")

    run_dir = ROOT / "evidence/phase03" / f"{record['run_id']}.running"
    if run_dir.exists() or (ROOT / "evidence/phase03" / record["run_id"]).exists():
        raise FileExistsError(f"Refusing to overwrite Phase 03 reuse staging: {run_dir}")
    student = record["student"]
    student_cache = Path.home() / ".cache/huggingface/hub" / ("models--" + student["model_id"].replace("/", "--")) / "snapshots" / student["revision"]
    if not student_cache.is_dir() or sha256_tree(student_cache) != student["snapshot_sha256_tree"]:
        raise ValueError("Student snapshot is missing or differs from the approved experiment record.")

    from transformers import AutoTokenizer

    run_dir.mkdir(parents=True)
    create_snapshot(run_dir)
    target_path = run_dir / "teacher_targets" / "en_to_vi.jsonl"
    target_path.parent.mkdir()
    with target_path.open("x", encoding="utf-8", newline="\n") as handle:
        for row in reused_rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    tokenizer = AutoTokenizer.from_pretrained(student_cache, local_files_only=True)
    report = validate_targets(
        eligible_rows, reused_rows,
        lambda target: len(tokenizer(text_target=target, add_special_tokens=True, truncation=False, verbose=False)["input_ids"]),
        record["target_integrity"]["student_token_limit"],
    )
    report.update({
        "stage": "phase03_teacher_target_reuse_integrity",
        "run_id": record["run_id"],
        "direction": "en_to_vi",
        "target_path": target_path.relative_to(ROOT).as_posix(),
        "target_sha256": sha256_file(target_path),
        "student_tokenizer": {"model_id": student["model_id"], "revision": student["revision"]},
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
    })
    write_json(target_path.with_suffix(".integrity.json"), report)
    write_json(run_dir / "reuse_lineage.json", {
        "stage": "phase03_teacher_target_reuse",
        "parent_run": parent,
        "excluded_dataset_row_ids": sorted(item["dataset_row_id"] for item in policy["excluded_rows"]),
        "reused_rows": len(reused_rows),
        "reused_target_sha256": report["target_sha256"],
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
    })
    write_snapshot_manifest(run_dir)
    print(f"PHASE03_TEACHER_TARGET_REUSE={report['status']} run={record['run_id']} direction=en_to_vi")
    return 0 if report["status"] == "pass" else 2


if __name__ == "__main__":
    raise SystemExit(main())
