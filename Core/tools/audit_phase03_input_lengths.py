"""Audit every Phase 03 IT Train source against each fixed Student tokenizer.

The audit is read-only with respect to training data and models.  It stores
token-length statistics and row identifiers only, so it cannot become a
translation or quality-evaluation artifact.
"""
from __future__ import annotations

import argparse
import json
import os
import platform
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
TRAIN_PATH = ROOT / "data/processed/it_en_vi/train.jsonl"
STUDENTS_PATH = ROOT / "configs/phase03_students.json"
os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")


def cached_snapshot(model_id: str, revision: str) -> Path:
    directory = "models--" + model_id.replace("/", "--")
    snapshot = Path.home() / ".cache/huggingface/hub" / directory / "snapshots" / revision
    if not snapshot.is_dir():
        raise FileNotFoundError(f"Missing cached Student revision: {snapshot}")
    return snapshot


def audit_direction(direction: str, details: dict[str, str]) -> dict[str, Any]:
    from transformers import AutoConfig, AutoTokenizer

    snapshot = cached_snapshot(details["model_id"], details["revision"])
    tokenizer = AutoTokenizer.from_pretrained(snapshot, local_files_only=True)
    config = AutoConfig.from_pretrained(snapshot, local_files_only=True)
    architecture_limit = getattr(config, "max_position_embeddings", None)
    if not isinstance(architecture_limit, int) or architecture_limit < 1:
        raise ValueError(f"Student {direction} has no finite max_position_embeddings.")

    start = time.perf_counter()
    total = 0
    over_limit = 0
    thresholds = {128: 0, 256: 0, 384: 0, architecture_limit: 0}
    longest: list[tuple[int, str]] = []
    source_key = "en_clean" if direction == "en_to_vi" else "vi_clean"
    prefix = details["input_prefix"]
    with TRAIN_PATH.open(encoding="utf-8") as handle:
        for line in handle:
            row = json.loads(line)
            length = len(tokenizer(prefix + row[source_key], add_special_tokens=True, truncation=False, verbose=False)["input_ids"])
            total += 1
            over_limit += length > architecture_limit
            for threshold in thresholds:
                thresholds[threshold] += length > threshold
            longest.append((length, row["dataset_row_id"]))
            longest.sort(reverse=True)
            del longest[8:]
    elapsed = time.perf_counter() - start
    return {
        "student": {"model_id": details["model_id"], "revision": details["revision"], "input_prefix": prefix},
        "source_field": source_key,
        "architecture_input_limit_tokens": architecture_limit,
        "rows": total,
        "max_tokens": longest[0][0],
        "rows_over_limit": over_limit,
        "percent_over_limit": round(over_limit * 100 / total, 6),
        "rows_over_threshold": {str(key): value for key, value in thresholds.items()},
        "longest_rows": [{"dataset_row_id": row_id, "tokens": length} for length, row_id in longest],
        "elapsed_seconds": round(elapsed, 3),
        "rows_per_second": round(total / elapsed, 2),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audit-id", required=True, help="New immutable audit identifier.")
    args = parser.parse_args()
    if not args.audit_id.replace("-", "").replace("_", "").isalnum():
        raise ValueError("audit-id may contain only letters, numbers, hyphens and underscores.")
    output = ROOT / "evidence/phase03/design_audits" / f"{args.audit_id}.json"
    if output.exists():
        raise FileExistsError(f"Audit record already exists: {output}")

    students = json.loads(STUDENTS_PATH.read_text(encoding="utf-8"))["students"]["student"]["directions"]
    started = time.perf_counter()
    report = {
        "stage": "phase03_input_length_audit",
        "audit_id": args.audit_id,
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
        "purpose": "Determine whether a fixed Student input-length policy is required before Phase 03 execution.",
        "input": str(TRAIN_PATH.relative_to(ROOT)),
        "data_boundary": "IT Train only; no text, reference, target, prediction, metric or model artifact is stored.",
        "runtime": {"python": sys.version.split()[0], "platform": platform.platform()},
        "directions": {},
    }
    for direction in ("en_to_vi", "vi_to_en"):
        print(f"PHASE03_INPUT_AUDIT direction={direction} status=running", flush=True)
        report["directions"][direction] = audit_direction(direction, students[direction])
    report["total_elapsed_seconds"] = round(time.perf_counter() - started, 3)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"PHASE03_INPUT_AUDIT=PASS record={output.relative_to(ROOT)}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
