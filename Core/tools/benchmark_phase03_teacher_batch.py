"""Compare frozen Teacher generation at batch 8 and batch 16.

This is a hardware/runtime benchmark, not KD execution.  It uses a fixed mix
of ordinary and longest IT Train inputs, keeps decoded outputs only in memory,
and records hashes solely to verify that changing batch size did not change
the deterministic Teacher targets.
"""
from __future__ import annotations

import argparse
import gc
import hashlib
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
PROTOCOL_PATH = ROOT / "configs/phase03_protocol.json"
STUDENTS_PATH = ROOT / "configs/phase03_students.json"
LENGTH_POLICY_PATH = ROOT / "configs/phase03_input_length_policy.json"
TRAIN_PATH = ROOT / "data/processed/it_en_vi/train.jsonl"
BASE_MODEL_PATH = ROOT / "models/envit5/840bc88104d5a4277af740eaedb024df8c3093e7"
os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")


def clear_cuda(torch: Any) -> None:
    gc.collect()
    torch.cuda.empty_cache()
    torch.cuda.reset_peak_memory_stats()


def memory_gib(torch: Any) -> dict[str, float]:
    unit = 1024 ** 3
    return {
        "peak_allocated_gib": round(torch.cuda.max_memory_allocated() / unit, 3),
        "peak_reserved_gib": round(torch.cuda.max_memory_reserved() / unit, 3),
    }


def normalized(text: str, prefix: str) -> str:
    if not text.startswith(prefix):
        return text
    remainder = text[len(prefix):]
    return remainder[1:] if remainder[:1].isspace() else remainder


def output_hash(texts: list[str]) -> str:
    return hashlib.sha256("\0".join(texts).encode("utf-8")).hexdigest()


def student_snapshot(model_id: str, revision: str) -> Path:
    path = Path.home() / ".cache/huggingface/hub" / ("models--" + model_id.replace("/", "--")) / "snapshots" / revision
    if not path.is_dir():
        raise FileNotFoundError(f"Missing cached Student revision: {path}")
    return path


def mixed_rows(direction: str) -> list[dict[str, str]]:
    """Choose eight ordinary and eight longest *eligible* rows for a direction."""
    from transformers import AutoTokenizer

    students = json.loads(STUDENTS_PATH.read_text(encoding="utf-8"))["students"]["student"]["directions"]
    policy = json.loads(LENGTH_POLICY_PATH.read_text(encoding="utf-8"))["directions"][direction]
    excluded_ids = {item["dataset_row_id"] for item in policy["excluded_rows"]}
    details = students[direction]
    tokenizer = AutoTokenizer.from_pretrained(student_snapshot(details["model_id"], details["revision"]), local_files_only=True)
    first_rows: list[dict[str, str]] = []
    longest: list[tuple[int, str, dict[str, str]]] = []
    source_key = "en" if direction == "en_to_vi" else "vi"
    with TRAIN_PATH.open(encoding="utf-8") as handle:
        for line in handle:
            row = json.loads(line)
            compact = {"dataset_row_id": row["dataset_row_id"], "en": row["en_clean"], "vi": row["vi_clean"]}
            if compact["dataset_row_id"] in excluded_ids:
                continue
            if len(first_rows) < 8:
                first_rows.append(compact)
            tokens = len(tokenizer(details["input_prefix"] + compact[source_key], add_special_tokens=True, truncation=False, verbose=False)["input_ids"])
            longest.append((tokens, compact["dataset_row_id"], compact))
    longest.sort(key=lambda item: (item[0], item[1]), reverse=True)
    long_rows = [item[2] for item in longest[:8]]
    rows = first_rows + long_rows
    if len(rows) != 16:
        raise ValueError(f"Expected 16 benchmark rows for {direction}, got {len(rows)}.")
    return rows


def generate(
    *, torch: Any, model: Any, tokenizer: Any, sources: list[str], prefix: str, output_prefix: str, batch_size: int
) -> tuple[list[str], dict[str, Any]]:
    outputs: list[str] = []
    clear_cuda(torch)
    torch.cuda.synchronize()
    started = time.perf_counter()
    for start in range(0, len(sources), batch_size):
        encoded = tokenizer([prefix + source for source in sources[start:start + batch_size]], padding=True, return_tensors="pt")
        encoded = {name: value.to("cuda") for name, value in encoded.items()}
        with torch.inference_mode():
            generated = model.generate(**encoded, max_length=512)
        outputs.extend(normalized(text, output_prefix) for text in tokenizer.batch_decode(generated, skip_special_tokens=True))
    torch.cuda.synchronize()
    elapsed = time.perf_counter() - started
    return outputs, {
        "batch_size": batch_size,
        "rows": len(sources),
        "elapsed_seconds": round(elapsed, 3),
        "sources_per_second": round(len(sources) / elapsed, 3),
        **memory_gib(torch),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--benchmark-id", required=True)
    args = parser.parse_args()
    if not args.benchmark_id.replace("-", "").replace("_", "").isalnum():
        raise ValueError("benchmark-id may contain only letters, numbers, hyphens and underscores.")
    output = ROOT / "evidence/phase03/preflight" / f"{args.benchmark_id}.json"
    if output.exists():
        raise FileExistsError(f"Benchmark record already exists: {output}")

    import torch
    from peft import PeftModel
    from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

    if not torch.cuda.is_available():
        raise RuntimeError("Teacher batch benchmark requires CUDA.")
    protocol = json.loads(PROTOCOL_PATH.read_text(encoding="utf-8"))
    report: dict[str, Any] = {
        "stage": "phase03_teacher_batch_benchmark",
        "benchmark_id": args.benchmark_id,
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
        "purpose": "Measure batch-16 Teacher throughput and verify deterministic target equality with batch 8.",
        "data_boundary": "IT Train only; 16 row IDs per direction; generated text is not stored.",
        "batch_mix": "first 8 eligible IT Train rows plus the 8 longest eligible rows, ranked by the fixed Student tokenizer for that direction",
        "teacher_generation": {"max_length": 512, "precision": "fp16", "batch_sizes": [8, 16]},
        "directions": {},
    }
    for direction in protocol["directions"]:
        print(f"PHASE03_TEACHER_BATCH_BENCHMARK direction={direction} status=running", flush=True)
        details = protocol["teacher"]["directions"][direction]
        rows = mixed_rows(direction)
        source_key = "en" if direction == "en_to_vi" else "vi"
        sources = [row[source_key] for row in rows]
        model: Any | None = None
        try:
            tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL_PATH, local_files_only=True)
            base = AutoModelForSeq2SeqLM.from_pretrained(BASE_MODEL_PATH, torch_dtype=torch.float16, local_files_only=True)
            model = PeftModel.from_pretrained(base, ROOT / details["checkpoint"])
            model.to("cuda")
            model.eval()
            outputs_8, result_8 = generate(
                torch=torch, model=model, tokenizer=tokenizer, sources=sources,
                prefix=details["source_prefix"], output_prefix=details["output_control_prefix"], batch_size=8,
            )
            outputs_16, result_16 = generate(
                torch=torch, model=model, tokenizer=tokenizer, sources=sources,
                prefix=details["source_prefix"], output_prefix=details["output_control_prefix"], batch_size=16,
            )
            report["directions"][direction] = {
                "dataset_row_ids": [row["dataset_row_id"] for row in rows],
                "batch_8": result_8,
                "batch_16": result_16,
                "deterministic_output_check": {
                    "batch_8_sha256": output_hash(outputs_8),
                    "batch_16_sha256": output_hash(outputs_16),
                    "identical": outputs_8 == outputs_16,
                },
            }
        finally:
            if model is not None:
                del model
            clear_cuda(torch)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"PHASE03_TEACHER_BATCH_BENCHMARK=PASS record={output.relative_to(ROOT)}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
