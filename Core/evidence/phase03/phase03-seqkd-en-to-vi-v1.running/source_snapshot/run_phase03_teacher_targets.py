"""Generate one staged Teacher target per eligible Phase 03 IT-Train row.

This runner is intentionally unavailable until the protocol and the selected
experiment record are approved.  It never accepts a user-supplied dataset path,
does not train a Student, and leaves the run in ``.running`` after the target
integrity result so a later training runner can use the same staged targets.
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
from typing import Any, Callable


ROOT = Path(__file__).resolve().parents[1]
PROTOCOL_PATH = ROOT / "configs/phase03_protocol.json"
POLICY_PATH = ROOT / "configs/phase03_input_length_policy.json"
TRAIN_PATH = ROOT / "data/processed/it_en_vi/train.jsonl"
RECORD_PATHS = {
    "en_to_vi": ROOT / "configs/phase03_experiments/phase03-seqkd-en-to-vi-v1.json",
    "vi_to_en": ROOT / "configs/phase03_experiments/phase03-seqkd-vi-to-en-v1.json",
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def sha256_tree(directory: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(item for item in directory.rglob("*") if item.is_file()):
        digest.update(path.relative_to(directory).as_posix().encode("utf-8"))
        digest.update(b"\0")
        with path.open("rb") as handle:
            for block in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(block)
        digest.update(b"\0")
    return digest.hexdigest()


def source_hash(source: str) -> str:
    return hashlib.sha256(source.encode("utf-8")).hexdigest()


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: dict[str, Any]) -> None:
    with path.open("x", encoding="utf-8", newline="\n") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def normalize_target(text: str, prefix: str) -> str:
    """Remove only the documented leading Teacher language-control prefix."""
    if not text.startswith(prefix):
        return text
    remainder = text[len(prefix):]
    return remainder[1:] if remainder[:1].isspace() else remainder


def load_expected_rows(direction: str, policy: dict[str, Any]) -> list[dict[str, str]]:
    excluded = {entry["dataset_row_id"] for entry in policy["directions"][direction]["excluded_rows"]}
    source_key = "en_clean" if direction == "en_to_vi" else "vi_clean"
    rows: list[dict[str, str]] = []
    with TRAIN_PATH.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            raw = json.loads(line)
            row_id, source = raw.get("dataset_row_id"), raw.get(source_key)
            if not isinstance(row_id, str) or not isinstance(source, str) or not source:
                raise ValueError(f"Invalid IT Train row at {line_number}.")
            if row_id not in excluded:
                rows.append({"dataset_row_id": row_id, "source": source, "source_sha256": source_hash(source)})
    if len(rows) != policy["directions"][direction]["eligible_rows"]:
        raise ValueError("Eligible source-row count differs from the approved input-length policy.")
    return rows


def validate_targets(
    expected_rows: list[dict[str, str]],
    target_rows: list[dict[str, Any]],
    token_count: Callable[[str], int],
    token_limit: int,
) -> dict[str, Any]:
    """Return every structural error and every over-limit target in one pass."""
    expected = {row["dataset_row_id"]: row for row in expected_rows}
    seen: set[str] = set()
    duplicate_ids: list[str] = []
    unexpected_ids: list[str] = []
    source_hash_mismatches: list[str] = []
    empty_target_ids: list[str] = []
    over_limit_rows: list[dict[str, Any]] = []
    for row in target_rows:
        row_id = row.get("dataset_row_id")
        if not isinstance(row_id, str):
            unexpected_ids.append(str(row_id))
            continue
        if row_id in seen:
            duplicate_ids.append(row_id)
            continue
        seen.add(row_id)
        expected_row = expected.get(row_id)
        if expected_row is None:
            unexpected_ids.append(row_id)
            continue
        if row.get("source_sha256") != expected_row["source_sha256"]:
            source_hash_mismatches.append(row_id)
        target = row.get("target")
        if not isinstance(target, str) or not target.strip():
            empty_target_ids.append(row_id)
            continue
        length = token_count(target)
        if length > token_limit:
            over_limit_rows.append({"dataset_row_id": row_id, "student_target_tokens": length})
    missing_ids = sorted(set(expected) - seen)
    structural_errors = {
        "missing_dataset_row_ids": missing_ids,
        "duplicate_dataset_row_ids": sorted(set(duplicate_ids)),
        "unexpected_dataset_row_ids": sorted(set(unexpected_ids)),
        "source_hash_mismatch_dataset_row_ids": sorted(set(source_hash_mismatches)),
        "empty_target_dataset_row_ids": sorted(set(empty_target_ids)),
    }
    has_structural_error = any(structural_errors.values())
    return {
        "status": "failed_structural_integrity" if has_structural_error else (
            "blocked_target_over_limit" if over_limit_rows else "pass"
        ),
        "expected_rows": len(expected_rows),
        "persisted_target_rows": len(target_rows),
        "structural_errors": structural_errors,
        "over_limit_rows": over_limit_rows,
        "student_token_limit": token_limit,
        "student_training_permitted": not has_structural_error and not over_limit_rows,
    }


def require_execution_approval(protocol: dict[str, Any], record: dict[str, Any], direction: str) -> None:
    if protocol.get("status") != "frozen_before_experiments":
        raise PermissionError("Protocol must be frozen_before_experiments before Phase 03 execution.")
    if record.get("status") != "approved_before_experiment":
        raise PermissionError("Experiment record must be approved_before_experiment before Phase 03 execution.")
    if record.get("direction") != direction or record.get("run_id") != f"phase03-seqkd-{direction.replace('_', '-')}-v1":
        raise ValueError("Experiment record identity does not match the requested direction.")


def create_snapshot(run_dir: Path, paths: list[Path]) -> None:
    source_snapshot = run_dir / "source_snapshot"
    config_snapshot = run_dir / "config_snapshot"
    source_snapshot.mkdir()
    config_snapshot.mkdir()
    shutil.copy2(Path(__file__), source_snapshot / Path(__file__).name)
    for path in paths:
        shutil.copy2(path, config_snapshot / path.name)
    (run_dir / "command.txt").write_text(" ".join(sys.argv) + "\n", encoding="utf-8", newline="\n")
    dependencies = {
        "python": sys.version.split()[0],
        "platform": platform.platform(),
    }
    (run_dir / "dependency_versions.txt").write_text(json.dumps(dependencies, indent=2) + "\n", encoding="utf-8", newline="\n")


def write_snapshot_manifest(run_dir: Path) -> None:
    snapshot_files = [path for path in run_dir.rglob("*") if path.is_file()]
    write_json(run_dir / "snapshot_manifest.json", {
        "files": [{"path": str(path.relative_to(run_dir)).replace("\\", "/"), "sha256": sha256_file(path)} for path in sorted(snapshot_files)],
    })


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--direction", choices=("en_to_vi", "vi_to_en"), required=True)
    args = parser.parse_args()
    protocol, policy, record = read_json(PROTOCOL_PATH), read_json(POLICY_PATH), read_json(RECORD_PATHS[args.direction])
    require_execution_approval(protocol, record, args.direction)
    run_dir = ROOT / "evidence/phase03" / f"{record['run_id']}.running"
    if run_dir.exists() or (ROOT / "evidence/phase03" / record["run_id"]).exists():
        raise FileExistsError(f"Refusing to overwrite Phase 03 run staging: {run_dir}")
    teacher = record["teacher"]
    base_model_root = ROOT / teacher["base_model_path"]
    if not base_model_root.is_dir():
        raise FileNotFoundError(f"Missing frozen Teacher base model: {base_model_root}")
    if sha256_tree(base_model_root) != teacher["base_model_sha256_tree"]:
        raise ValueError("Teacher base-model hash differs from the approved experiment record.")
    if sha256_tree(ROOT / teacher["checkpoint"]) != teacher["checkpoint_sha256_tree"]:
        raise ValueError("Teacher checkpoint hash differs from the approved experiment record.")

    import torch
    import transformers
    from peft import PeftModel
    from transformers import AutoModelForSeq2SeqLM, AutoTokenizer, GenerationConfig

    device = record["execution_device"]
    if not torch.cuda.is_available() or torch.cuda.device_count() <= device["index"]:
        raise RuntimeError("The approved CUDA device is not available.")
    if torch.cuda.get_device_name(device["index"]) != device["name"]:
        raise RuntimeError("The available CUDA device does not match the approved experiment record.")

    run_dir.mkdir(parents=True)
    create_snapshot(run_dir, [PROTOCOL_PATH, POLICY_PATH, RECORD_PATHS[args.direction]])
    expected_rows = load_expected_rows(args.direction, policy)
    student = record["student"]
    student_cache = Path.home() / ".cache/huggingface/hub" / ("models--" + student["model_id"].replace("/", "--")) / "snapshots" / student["revision"]
    if not student_cache.is_dir():
        raise FileNotFoundError(f"Missing cached Student revision: {student_cache}")
    if sha256_tree(student_cache) != student["snapshot_sha256_tree"]:
        raise ValueError("Student snapshot hash differs from the approved experiment record.")
    write_json(run_dir / "input_manifest.json", {
        "train": {"path": str(TRAIN_PATH.relative_to(ROOT)).replace("\\", "/"), "sha256": sha256_file(TRAIN_PATH)},
        "teacher_checkpoint": {"path": teacher["checkpoint"], "sha256_tree": teacher["checkpoint_sha256_tree"]},
        "student": {"model_id": student["model_id"], "revision": student["revision"]},
    })
    write_json(run_dir / "runtime.json", {"torch": torch.__version__, "transformers": transformers.__version__, "cuda": torch.version.cuda, "device": device, "precision": teacher["precision"]})
    write_snapshot_manifest(run_dir)
    target_path = run_dir / "teacher_targets" / f"{args.direction}.jsonl"
    target_path.parent.mkdir()
    model: Any | None = None
    try:
        teacher_tokenizer = AutoTokenizer.from_pretrained(base_model_root, local_files_only=True)
        base = AutoModelForSeq2SeqLM.from_pretrained(base_model_root, torch_dtype=torch.float16, local_files_only=True)
        model = PeftModel.from_pretrained(base, ROOT / teacher["checkpoint"])
        model.to(f"cuda:{device['index']}")
        model.eval()
        generation_values = teacher["generation"]
        generation_config = GenerationConfig(**generation_values)
        with target_path.open("x", encoding="utf-8", newline="\n") as handle:
            for start in range(0, len(expected_rows), teacher["generation_batch_size"]):
                batch = expected_rows[start:start + teacher["generation_batch_size"]]
                encoded = teacher_tokenizer([teacher["source_prefix"] + row["source"] for row in batch], padding=True, truncation=False, return_tensors="pt")
                encoded = {name: value.to(f"cuda:{device['index']}") for name, value in encoded.items()}
                with torch.inference_mode():
                    generated = model.generate(**encoded, generation_config=generation_config)
                targets = teacher_tokenizer.batch_decode(generated, skip_special_tokens=True)
                for row, target in zip(batch, targets, strict=True):
                    handle.write(json.dumps({
                        "dataset_row_id": row["dataset_row_id"],
                        "source_sha256": row["source_sha256"],
                        "target": normalize_target(target, teacher["output_control_prefix"]),
                    }, ensure_ascii=False) + "\n")
        student_tokenizer = AutoTokenizer.from_pretrained(student_cache, local_files_only=True)
        target_rows = [json.loads(line) for line in target_path.read_text(encoding="utf-8").splitlines()]
        report = validate_targets(
            expected_rows, target_rows,
            lambda target: len(student_tokenizer(text_target=target, add_special_tokens=True, truncation=False, verbose=False)["input_ids"]),
            record["target_integrity"]["student_token_limit"],
        )
        report.update({
            "stage": "phase03_teacher_target_integrity",
            "run_id": record["run_id"],
            "direction": args.direction,
            "target_path": str(target_path.relative_to(ROOT)).replace("\\", "/"),
            "target_sha256": sha256_file(target_path),
            "teacher_generation": {"precision": teacher["precision"], "batch_size": teacher["generation_batch_size"], "generation_config": generation_values},
            "student_tokenizer": {"model_id": student["model_id"], "revision": student["revision"]},
            "created_at_utc": datetime.now(timezone.utc).isoformat(),
        })
        write_json(target_path.with_suffix(".integrity.json"), report)
        print(f"PHASE03_TEACHER_TARGETS={report['status']} run={record['run_id']} direction={args.direction}")
        return 0 if report["status"] == "pass" else 2
    finally:
        if model is not None:
            del model
        if torch.cuda.is_available():
            torch.cuda.empty_cache()


if __name__ == "__main__":
    raise SystemExit(main())
