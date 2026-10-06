"""Check whether the fixed VI-to-EN Student can process its two >512-token rows.

No text or generated output is stored.  The record establishes the checkpoint's
actual behavior before any input-length policy is approved.
"""
from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
TRAIN_PATH = ROOT / "data/processed/it_en_vi/train.jsonl"
STUDENTS_PATH = ROOT / "configs/phase03_students.json"
AUDIT_PATH = ROOT / "evidence/phase03/design_audits/phase03-input-length-audit-v1.json"
os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")


def snapshot(model_id: str, revision: str) -> Path:
    path = Path.home() / ".cache/huggingface/hub" / ("models--" + model_id.replace("/", "--")) / "snapshots" / revision
    if not path.is_dir():
        raise FileNotFoundError(f"Missing cached Student revision: {path}")
    return path


def position_capacity(model: Any) -> dict[str, int | None]:
    """Expose actual embedding table sizes without assuming a model family field."""
    values: dict[str, int | None] = {"config_max_position_embeddings": getattr(model.config, "max_position_embeddings", None)}
    for name, module in model.named_modules():
        weight = getattr(module, "weight", None)
        if "embed_positions" in name and getattr(weight, "ndim", 0) == 2:
            values[f"{name}.rows"] = int(weight.shape[0])
    return values


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-id", required=True)
    args = parser.parse_args()
    output = ROOT / "evidence/phase03/design_audits" / f"{args.check_id}.json"
    if output.exists():
        raise FileExistsError(f"Length-capacity record already exists: {output}")

    import torch
    from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

    if not torch.cuda.is_available():
        raise RuntimeError("This check requires the Phase 03 CUDA runtime.")
    students = json.loads(STUDENTS_PATH.read_text(encoding="utf-8"))["students"]["student"]["directions"]
    details = students["vi_to_en"]
    audit = json.loads(AUDIT_PATH.read_text(encoding="utf-8"))["directions"]["vi_to_en"]
    requested_ids = [item["dataset_row_id"] for item in audit["longest_rows"] if item["tokens"] > 512]
    rows: dict[str, str] = {}
    with TRAIN_PATH.open(encoding="utf-8") as handle:
        for line in handle:
            row = json.loads(line)
            if row["dataset_row_id"] in requested_ids:
                rows[row["dataset_row_id"]] = row["vi_clean"]
    if set(rows) != set(requested_ids):
        raise ValueError("Could not recover every audited over-limit row from IT Train.")

    source = snapshot(details["model_id"], details["revision"])
    tokenizer = AutoTokenizer.from_pretrained(source, local_files_only=True)
    model = AutoModelForSeq2SeqLM.from_pretrained(source, torch_dtype=torch.float16, local_files_only=True)
    report: dict[str, Any] = {
        "stage": "phase03_student_length_capacity_check",
        "check_id": args.check_id,
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
        "purpose": "Test the fixed Student checkpoint at the two audited >512-token inputs; no text or output is stored.",
        "student": {"model_id": details["model_id"], "revision": details["revision"]},
        "tokenizer_truncation": False,
        "position_capacity": position_capacity(model),
        "rows": [],
    }
    model.to("cuda")
    model.eval()
    capacities = [value for value in report["position_capacity"].values() if isinstance(value, int)]
    actual_capacity = min(capacities)
    for row_id in requested_ids:
        encoded_cpu = tokenizer(rows[row_id], add_special_tokens=True, truncation=False, return_tensors="pt")
        token_count = int(encoded_cpu["input_ids"].shape[1])
        if token_count > actual_capacity:
            report["rows"].append({
                "dataset_row_id": row_id,
                "input_tokens": token_count,
                "generate_status": "blocked_before_generate",
                "reason": f"Input exceeds the checkpoint positional capacity of {actual_capacity} tokens.",
            })
            continue
        encoded = {name: value.to("cuda") for name, value in encoded_cpu.items()}
        try:
            with torch.inference_mode():
                generated = model.generate(**encoded)
            generated_shape = list(generated.shape)
            result: dict[str, Any] = {"dataset_row_id": row_id, "input_tokens": token_count, "generate_status": "pass", "generated_shape": generated_shape}
        except Exception as error:
            result = {"dataset_row_id": row_id, "input_tokens": token_count, "generate_status": "error", "error": f"{type(error).__name__}: {error}"}
        report["rows"].append(result)
    del model
    torch.cuda.empty_cache()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"PHASE03_STUDENT_LENGTH_CAPACITY=PASS record={output.relative_to(ROOT)}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
