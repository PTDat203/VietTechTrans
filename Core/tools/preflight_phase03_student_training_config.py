"""Test the proposed Phase 03 Student training runtime without creating KD evidence.

Each direction receives 16 longest eligible IT Train sources.  The frozen
Teacher generates in-memory labels once; Student then performs exactly one AMP
FP16 update using the proposed physical batch, accumulation, optimizer,
scheduler and clipping.  No text, target, checkpoint or metric is written.
"""
from __future__ import annotations

import argparse
import gc
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
POLICY_PATH = ROOT / "configs/phase03_input_length_policy.json"
TRAIN_PATH = ROOT / "data/processed/it_en_vi/train.jsonl"
BASE_MODEL_PATH = ROOT / "models/envit5/840bc88104d5a4277af740eaedb024df8c3093e7"
os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")

PHYSICAL_BATCH = 4
GRADIENT_ACCUMULATION = 4
LEARNING_RATE = 5e-5
MAX_GRAD_NORM = 1.0


def cache_snapshot(model_id: str, revision: str) -> Path:
    path = Path.home() / ".cache/huggingface/hub" / ("models--" + model_id.replace("/", "--")) / "snapshots" / revision
    if not path.is_dir():
        raise FileNotFoundError(f"Missing cached Student revision: {path}")
    return path


def release(torch: Any, *values: Any) -> None:
    for value in values:
        if value is not None:
            del value
    gc.collect()
    torch.cuda.empty_cache()
    torch.cuda.reset_peak_memory_stats()


def memory(torch: Any) -> dict[str, float]:
    unit = 1024 ** 3
    return {
        "peak_allocated_gib": round(torch.cuda.max_memory_allocated() / unit, 3),
        "peak_reserved_gib": round(torch.cuda.max_memory_reserved() / unit, 3),
    }


def normalize(text: str, prefix: str) -> str:
    if not text.startswith(prefix):
        return text
    remainder = text[len(prefix):]
    return remainder[1:] if remainder[:1].isspace() else remainder


def longest_eligible_rows(direction: str, student: dict[str, str], excluded_ids: set[str]) -> list[dict[str, str]]:
    from transformers import AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(cache_snapshot(student["model_id"], student["revision"]), local_files_only=True)
    source_field = "en_clean" if direction == "en_to_vi" else "vi_clean"
    ranked: list[tuple[int, str, dict[str, str]]] = []
    with TRAIN_PATH.open(encoding="utf-8") as handle:
        for line in handle:
            row = json.loads(line)
            if row["dataset_row_id"] in excluded_ids:
                continue
            compact = {"dataset_row_id": row["dataset_row_id"], "en": row["en_clean"], "vi": row["vi_clean"]}
            count = len(tokenizer(student["input_prefix"] + row[source_field], add_special_tokens=True, truncation=False, verbose=False)["input_ids"])
            ranked.append((count, compact["dataset_row_id"], compact))
    ranked.sort(key=lambda item: (item[0], item[1]), reverse=True)
    return [item[2] for item in ranked[: PHYSICAL_BATCH * GRADIENT_ACCUMULATION]]


def teacher_generate(torch: Any, direction: str, sources: list[str], teacher: dict[str, Any]) -> list[str]:
    from peft import PeftModel
    from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

    details = teacher["directions"][direction]
    model: Any | None = None
    try:
        tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL_PATH, local_files_only=True)
        base = AutoModelForSeq2SeqLM.from_pretrained(BASE_MODEL_PATH, torch_dtype=torch.float16, local_files_only=True)
        model = PeftModel.from_pretrained(base, ROOT / details["checkpoint"])
        model.to("cuda")
        model.eval()
        encoded = tokenizer([details["source_prefix"] + text for text in sources], padding=True, return_tensors="pt")
        encoded = {name: value.to("cuda") for name, value in encoded.items()}
        with torch.inference_mode():
            generated = model.generate(**encoded, max_length=512)
        return [normalize(text, details["output_control_prefix"]) for text in tokenizer.batch_decode(generated, skip_special_tokens=True)]
    finally:
        release(torch, model)


def test_student(
    *, torch: Any, direction: str, sources: list[str], targets: list[str], student: dict[str, str]
) -> dict[str, Any]:
    from transformers import AutoModelForSeq2SeqLM, AutoTokenizer, get_linear_schedule_with_warmup

    snapshot = cache_snapshot(student["model_id"], student["revision"])
    tokenizer = AutoTokenizer.from_pretrained(snapshot, local_files_only=True)
    target_lengths = [len(tokenizer(text_target=target, add_special_tokens=True, truncation=False, verbose=False)["input_ids"]) for target in targets]
    capacity = 512
    if max(target_lengths) > capacity:
        return {
            "status": "blocked_target_capacity",
            "max_student_target_tokens": max(target_lengths),
            "target_rows_over_capacity": sum(length > capacity for length in target_lengths),
        }
    model: Any | None = None
    optimizer: Any | None = None
    try:
        # No torch_dtype: this is the real AMP layout, with FP32 parameters and optimizer state.
        model = AutoModelForSeq2SeqLM.from_pretrained(snapshot, local_files_only=True)
        model.to("cuda")
        model.train()
        optimizer = torch.optim.AdamW(model.parameters(), lr=LEARNING_RATE, weight_decay=0.0, fused=True)
        scheduler = get_linear_schedule_with_warmup(optimizer, num_warmup_steps=0, num_training_steps=1)
        scaler = torch.amp.GradScaler("cuda")
        torch.cuda.reset_peak_memory_stats()
        losses: list[float] = []
        torch.cuda.synchronize()
        started = time.perf_counter()
        optimizer.zero_grad(set_to_none=True)
        for start in range(0, len(sources), PHYSICAL_BATCH):
            batch_sources = [student["input_prefix"] + value for value in sources[start:start + PHYSICAL_BATCH]]
            encoded = tokenizer(batch_sources, padding=True, return_tensors="pt")
            encoded_targets = tokenizer(text_target=targets[start:start + PHYSICAL_BATCH], padding=True, return_tensors="pt")
            labels = encoded_targets["input_ids"]
            labels[labels == tokenizer.pad_token_id] = -100
            encoded = {name: value.to("cuda") for name, value in encoded.items()}
            labels = labels.to("cuda")
            with torch.autocast(device_type="cuda", dtype=torch.float16):
                loss = model(**encoded, labels=labels).loss
                scaled_loss = loss / GRADIENT_ACCUMULATION
            if not torch.isfinite(loss):
                return {"status": "non_finite_loss", "loss": float(loss.detach().cpu())}
            losses.append(float(loss.detach().cpu()))
            scaler.scale(scaled_loss).backward()
        scaler.unscale_(optimizer)
        grad_norm = float(torch.nn.utils.clip_grad_norm_(model.parameters(), MAX_GRAD_NORM).detach().cpu())
        scaler.step(optimizer)
        scaler.update()
        scheduler.step()
        torch.cuda.synchronize()
        elapsed = time.perf_counter() - started
        training_memory = memory(torch)
        # Transformer TrainingArguments defaults per_device_eval_batch_size to 8.
        # This generation result is discarded; it measures only that default.
        model.eval()
        torch.cuda.reset_peak_memory_stats()
        eval_sources = [student["input_prefix"] + value for value in sources[:8]]
        eval_encoded = tokenizer(eval_sources, padding=True, return_tensors="pt")
        eval_encoded = {name: value.to("cuda") for name, value in eval_encoded.items()}
        torch.cuda.synchronize()
        evaluation_started = time.perf_counter()
        with torch.inference_mode(), torch.autocast(device_type="cuda", dtype=torch.float16):
            model.generate(**eval_encoded)
        torch.cuda.synchronize()
        evaluation_elapsed = time.perf_counter() - evaluation_started
        return {
            "status": "pass",
            "precision": "AMP_FP16_with_FP32_parameters",
            "optimizer": "adamw_torch_fused",
            "learning_rate": LEARNING_RATE,
            "scheduler": "linear",
            "warmup_steps": 0,
            "weight_decay": 0.0,
            "physical_batch": PHYSICAL_BATCH,
            "gradient_accumulation": GRADIENT_ACCUMULATION,
            "effective_batch": PHYSICAL_BATCH * GRADIENT_ACCUMULATION,
            "max_grad_norm": MAX_GRAD_NORM,
            "microbatch_losses_finite": len(losses) == GRADIENT_ACCUMULATION,
            "gradient_norm_before_clip": round(grad_norm, 6),
            "max_student_target_tokens": max(target_lengths),
            "elapsed_seconds": round(elapsed, 3),
            **training_memory,
            "evaluation_default": {
                "per_device_eval_batch": 8,
                "status": "pass",
                "elapsed_seconds": round(evaluation_elapsed, 3),
                **memory(torch),
            },
        }
    except torch.cuda.OutOfMemoryError:
        return {"status": "oom"}
    except Exception as error:
        return {"status": "error", "error": f"{type(error).__name__}: {error}"}
    finally:
        release(torch, model, optimizer)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preflight-id", required=True)
    args = parser.parse_args()
    output = ROOT / "evidence/phase03/preflight" / f"{args.preflight_id}.json"
    if output.exists():
        raise FileExistsError(f"Preflight record already exists: {output}")

    import torch

    if not torch.cuda.is_available():
        raise RuntimeError("Student runtime preflight requires CUDA.")
    protocol = json.loads(PROTOCOL_PATH.read_text(encoding="utf-8"))
    students = json.loads(STUDENTS_PATH.read_text(encoding="utf-8"))["students"]["student"]["directions"]
    policy = json.loads(POLICY_PATH.read_text(encoding="utf-8"))["directions"]
    report: dict[str, Any] = {
        "stage": "phase03_student_training_runtime_preflight",
        "preflight_id": args.preflight_id,
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
        "purpose": "Check proposed Student runtime on longest eligible inputs; not a KD experiment or quality evidence.",
        "not_persisted": ["teacher_targets", "student_checkpoint", "predictions", "metrics"],
        "directions": {},
    }
    for direction in protocol["directions"]:
        print(f"PHASE03_STUDENT_RUNTIME_PREFLIGHT direction={direction} stage=select_inputs", flush=True)
        excluded = {item["dataset_row_id"] for item in policy[direction]["excluded_rows"]}
        rows = longest_eligible_rows(direction, students[direction], excluded)
        source_key = "en" if direction == "en_to_vi" else "vi"
        sources = [row[source_key] for row in rows]
        print(f"PHASE03_STUDENT_RUNTIME_PREFLIGHT direction={direction} stage=teacher_in_memory", flush=True)
        targets = teacher_generate(torch, direction, sources, protocol["teacher"])
        print(f"PHASE03_STUDENT_RUNTIME_PREFLIGHT direction={direction} stage=student_amp_update", flush=True)
        outcome = test_student(torch=torch, direction=direction, sources=sources, targets=targets, student=students[direction])
        report["directions"][direction] = {
            "dataset_row_ids": [row["dataset_row_id"] for row in rows],
            "student": {"model_id": students[direction]["model_id"], "revision": students[direction]["revision"]},
            "outcome": outcome,
        }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"PHASE03_STUDENT_RUNTIME_PREFLIGHT=PASS record={output.relative_to(ROOT)}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
