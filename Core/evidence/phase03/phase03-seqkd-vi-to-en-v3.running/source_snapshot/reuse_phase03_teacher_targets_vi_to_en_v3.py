"""Create the audited VI-to-EN v3 run by reusing valid v2 Teacher targets."""
from __future__ import annotations

import hashlib
import json
import platform
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from run_phase03_teacher_targets import ROOT, sha256_file, sha256_tree, validate_targets, write_json


PROTOCOL_PATH = ROOT / "configs/phase03_protocol_teacher_target_reuse_vi_to_en_v3.json"
POLICY_PATH = ROOT / "configs/phase03_teacher_target_reuse_vi_to_en_v3.json"
RECORD_PATH = ROOT / "configs/phase03_experiments/phase03-seqkd-vi-to-en-v3.json"
TRAIN_PATH = ROOT / "data/processed/it_en_vi/train.jsonl"
PARENT_INPUT_POLICY_PATH = ROOT / "configs/phase03_vi_to_en_v2_input_policy.json"
SNAPSHOT_RUNNERS = (
    Path(__file__),
    ROOT / "tools/run_phase03_student_training.py",
    ROOT / "tools/run_phase03_student_evaluation.py",
    ROOT / "tools/prepare_phase03_review_sheet.py",
)

PARENT_POLICY_IDS = {
    "c3415a68a988702f855e8e6dd23398b2aa2dddea771f47428df941a3305fcff3",
    "e679898355a6de521823b1c00f22fb949c007177097554d77d2428577d0a51f6",
    "8e53e283484f5624ade61e3791ddcaa71df6030db70960f0575a9e1baf0780ef",
    "ee111c9ad966968cf0e2212ec80b2a44f9be56e789826f2623e36ef2d01fa24f",
}
PARENT_OVER_LIMIT = {
    "17e7e477bd1ea1b7cb23a7302364e048ed8fdf7859991ef644510badcb9ed2da": 514,
    "404358bd56778bb4c53a1edb034b75cd33d36b06825e0c68727e4efc584a6e1d": 515,
    "63a75d7bf16327a02500902951c74d61ebf8e6966733be898b86d89c7a4cbbac": 513,
    "ab04abe93f4173d4c918b0de5ae7f78d59da6d7f9d0ddae2d1b2f70234bb912b": 684,
}


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
            row_id, source = raw.get("dataset_row_id"), raw.get("vi_clean")
            if not isinstance(row_id, str) or not isinstance(source, str) or not source:
                raise ValueError(f"Invalid VI-to-EN IT Train row at {line_number}.")
            if row_id not in excluded_ids:
                rows.append({"dataset_row_id": row_id, "source": source, "source_sha256": source_hash(source)})
    if len(rows) != policy["eligible_rows"]:
        raise ValueError("Eligible row count differs from the approved reuse policy.")
    return rows


def validate_policy(policy: dict[str, Any]) -> None:
    excluded = policy.get("excluded_rows")
    if not isinstance(excluded, list) or len(excluded) != 8:
        raise ValueError("Reuse policy must name exactly eight exclusions.")
    ids = {item.get("dataset_row_id") for item in excluded}
    if None in ids or len(ids) != len(excluded) or ids != PARENT_POLICY_IDS | set(PARENT_OVER_LIMIT):
        raise ValueError("Reuse policy exclusions differ from the approved boundary.")
    if policy.get("source_rows") != 121547 or policy.get("eligible_rows") != 121539:
        raise ValueError("Reuse policy row counts differ from the approved correction.")
    previous = policy.get("parent_input_policy", {})
    if previous.get("path") != PARENT_INPUT_POLICY_PATH.relative_to(ROOT).as_posix() or previous.get("sha256") != sha256_file(PARENT_INPUT_POLICY_PATH):
        raise ValueError("Parent VI-to-EN input policy differs from the approved checksum.")


def validate_parent(policy: dict[str, Any], parent_rows: list[dict[str, Any]], report: dict[str, Any]) -> None:
    if report.get("status") != "blocked_target_over_limit" or report.get("expected_rows") != 121543 or report.get("persisted_target_rows") != 121543:
        raise ValueError("Parent report is not the expected blocked v2 target scan.")
    errors = report.get("structural_errors", {})
    if any(errors.get(key) != [] for key in ("missing_dataset_row_ids", "duplicate_dataset_row_ids", "unexpected_dataset_row_ids", "source_hash_mismatch_dataset_row_ids", "empty_target_dataset_row_ids")):
        raise ValueError("Parent report has an unexpected structural error.")
    over_limit = {item.get("dataset_row_id"): item.get("student_target_tokens") for item in report.get("over_limit_rows", [])}
    if over_limit != PARENT_OVER_LIMIT or report.get("student_training_permitted") is not False:
        raise ValueError("Parent report over-limit rows differ from the approved reuse policy.")
    if len(parent_rows) != 121543:
        raise ValueError("Parent target file row count differs from the approved parent run.")


def create_snapshot(run_dir: Path) -> None:
    (run_dir / "source_snapshot").mkdir()
    (run_dir / "config_snapshot").mkdir()
    for path in SNAPSHOT_RUNNERS:
        if not path.is_file():
            raise FileNotFoundError(f"Cannot snapshot missing Phase 03 runner: {path}")
        shutil.copy2(path, run_dir / "source_snapshot" / path.name)
    for path in (PROTOCOL_PATH, POLICY_PATH, RECORD_PATH, PARENT_INPUT_POLICY_PATH):
        shutil.copy2(path, run_dir / "config_snapshot" / path.name)
    (run_dir / "command.txt").write_text(" ".join(sys.argv) + "\n", encoding="utf-8", newline="\n")
    (run_dir / "dependency_versions.txt").write_text(json.dumps({"python": sys.version.split()[0], "platform": platform.platform()}, indent=2) + "\n", encoding="utf-8", newline="\n")


def write_snapshot_manifest(run_dir: Path) -> None:
    files = sorted(path for path in run_dir.rglob("*") if path.is_file())
    write_json(run_dir / "snapshot_manifest.json", {"files": [{"path": path.relative_to(run_dir).as_posix(), "sha256": sha256_file(path)} for path in files]})


def require_approval(protocol: dict[str, Any], policy: dict[str, Any], record: dict[str, Any]) -> None:
    if protocol.get("status") != "frozen_before_experiments":
        raise PermissionError("Reuse protocol must be frozen_before_experiments before execution.")
    if policy.get("status") != "approved_before_experiment" or record.get("status") != "approved_before_experiment":
        raise PermissionError("Reuse policy and experiment record must be approved before execution.")
    if record.get("run_id") != "phase03-seqkd-vi-to-en-v3" or record.get("direction") != "vi_to_en":
        raise ValueError("Reuse experiment record identity is invalid.")


def main() -> int:
    protocol, policy, record = read_json(PROTOCOL_PATH), read_json(POLICY_PATH), read_json(RECORD_PATH)
    require_approval(protocol, policy, record)
    validate_policy(policy)
    parent = policy["parent_run"]
    parent_target, parent_report = ROOT / parent["target_path"], ROOT / parent["integrity_report_path"]
    if sha256_file(TRAIN_PATH) != parent["train_sha256"]:
        raise ValueError("Current IT Train checksum differs from the approved parent input.")
    if not parent_target.is_file() or sha256_file(parent_target) != parent["target_sha256"]:
        raise ValueError("Parent target file is missing or differs from the approved checksum.")
    if not parent_report.is_file() or sha256_file(parent_report) != parent["integrity_report_sha256"]:
        raise ValueError("Parent integrity report is missing or differs from the approved checksum.")
    parent_rows = [json.loads(line) for line in parent_target.read_text(encoding="utf-8").splitlines()]
    validate_parent(policy, parent_rows, read_json(parent_report))
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
    from tqdm.auto import tqdm

    run_dir.mkdir(parents=True)
    create_snapshot(run_dir)
    target_path = run_dir / "teacher_targets" / "vi_to_en.jsonl"
    target_path.parent.mkdir()
    with target_path.open("x", encoding="utf-8", newline="\n") as handle:
        for row in tqdm(reused_rows, desc="Reuse VI-to-EN targets", unit="target"):
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    tokenizer = AutoTokenizer.from_pretrained(student_cache, local_files_only=True)
    report = validate_targets(eligible_rows, reused_rows, lambda target: len(tokenizer(text_target=target, add_special_tokens=True, truncation=False, verbose=False)["input_ids"]), record["target_integrity"]["student_token_limit"])
    report.update({"stage": "phase03_teacher_target_reuse_integrity", "run_id": record["run_id"], "direction": "vi_to_en", "target_path": target_path.relative_to(ROOT).as_posix(), "target_sha256": sha256_file(target_path), "student_tokenizer": {"model_id": student["model_id"], "revision": student["revision"]}, "created_at_utc": datetime.now(timezone.utc).isoformat()})
    write_json(target_path.with_suffix(".integrity.json"), report)
    write_json(run_dir / "reuse_lineage.json", {"stage": "phase03_teacher_target_reuse", "parent_run": parent, "excluded_dataset_row_ids": sorted(item["dataset_row_id"] for item in policy["excluded_rows"]), "reused_rows": len(reused_rows), "reused_target_sha256": report["target_sha256"], "created_at_utc": datetime.now(timezone.utc).isoformat()})
    write_snapshot_manifest(run_dir)
    print(f"PHASE03_TEACHER_TARGET_REUSE={report['status']} run={record['run_id']} direction=vi_to_en")
    return 0 if report["status"] == "pass" else 2


if __name__ == "__main__":
    raise SystemExit(main())
