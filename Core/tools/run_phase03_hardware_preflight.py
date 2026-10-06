"""Measure Phase 03 GPU feasibility without starting a KD experiment.

The runner reads eight deterministic rows from IT Train only.  Teacher output
exists in memory solely long enough to construct the Student training batch;
no generated text, checkpoint, prediction, metric or quality decision is
written.  Its JSON record contains runtime and memory observations only.
"""
from __future__ import annotations

import argparse
import gc
import json
import os
import platform
import sys
import traceback
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
PROTOCOL_PATH = ROOT / "configs/phase03_protocol.json"
STUDENTS_PATH = ROOT / "configs/phase03_students.json"
TRAIN_PATH = ROOT / "data/processed/it_en_vi/train.jsonl"
BASE_MODEL_PATH = ROOT / "models/envit5/840bc88104d5a4277af740eaedb024df8c3093e7"

# Preflight must use only already-resolved identities, never a Hub refresh.
os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def cached_snapshot(model_id: str, revision: str) -> Path:
    """Use the exact local Hub snapshot so preflight never contacts the network."""
    directory_name = "models--" + model_id.replace("/", "--")
    path = Path.home() / ".cache/huggingface/hub" / directory_name / "snapshots" / revision
    if not path.is_dir():
        raise FileNotFoundError(f"Missing cached Student revision: {path}")
    return path


def load_samples(sample_size: int) -> list[dict[str, str]]:
    """Return the first IT Train rows; this is a fixed hardware-only sample."""
    rows: list[dict[str, str]] = []
    with TRAIN_PATH.open(encoding="utf-8") as handle:
        for line in handle:
            raw = json.loads(line)
            rows.append({
                "dataset_row_id": raw["dataset_row_id"],
                "en": raw["en_clean"],
                "vi": raw["vi_clean"],
            })
            if len(rows) == sample_size:
                return rows
    raise ValueError(f"IT Train has fewer than {sample_size} rows.")


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


def normalize_target(text: str, prefix: str) -> str:
    if not text.startswith(prefix):
        return text
    remainder = text[len(prefix):]
    return remainder[1:] if remainder[:1].isspace() else remainder


def release_model(torch: Any, model: Any | None) -> None:
    if model is not None:
        del model
    clear_cuda(torch)


def teacher_targets(
    *,
    torch: Any,
    direction: str,
    sources: list[str],
    teacher: dict[str, Any],
) -> tuple[list[str], list[dict[str, Any]], dict[str, Any]]:
    """Try teacher inference batches and retain one in-memory target list."""
    from peft import PeftModel
    from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

    details = teacher["directions"][direction]
    model: Any | None = None
    attempts: list[dict[str, Any]] = []
    targets: list[str] = []
    metadata: dict[str, Any] = {}
    try:
        tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL_PATH, local_files_only=True)
        base = AutoModelForSeq2SeqLM.from_pretrained(
            BASE_MODEL_PATH,
            torch_dtype=torch.float16,
            local_files_only=True,
        )
        model = PeftModel.from_pretrained(base, ROOT / details["checkpoint"])
        model.to("cuda")
        model.eval()
        metadata = {
            "base_model_path": str(BASE_MODEL_PATH.relative_to(ROOT)),
            "adapter_checkpoint": details["checkpoint"],
            "source_prefix": details["source_prefix"],
            "output_control_prefix": details["output_control_prefix"],
            "generation_overrides": {"max_length": 512},
        }
        for batch_size in (8, 4, 2, 1):
            clear_cuda(torch)
            try:
                batch = [details["source_prefix"] + source for source in sources[:batch_size]]
                encoded = tokenizer(batch, padding=True, return_tensors="pt")
                encoded = {name: value.to("cuda") for name, value in encoded.items()}
                torch.cuda.synchronize()
                started = time.perf_counter()
                with torch.inference_mode():
                    generated = model.generate(**encoded, max_length=512)
                torch.cuda.synchronize()
                elapsed = time.perf_counter() - started
                decoded = tokenizer.batch_decode(generated, skip_special_tokens=True)
                attempts.append({
                    "batch_size": batch_size, "status": "pass", "elapsed_seconds": round(elapsed, 3),
                    "sources_per_second": round(batch_size / elapsed, 3), **memory_gib(torch),
                })
                if not targets:
                    # This output is held only to form one Student preflight batch.
                    targets = [normalize_target(text, details["output_control_prefix"]) for text in decoded]
            except torch.cuda.OutOfMemoryError:
                attempts.append({"batch_size": batch_size, "status": "oom"})
                clear_cuda(torch)
            except Exception as error:  # Record a runtime failure without hiding it.
                attempts.append({"batch_size": batch_size, "status": "error", "error": f"{type(error).__name__}: {error}"})
                clear_cuda(torch)
    finally:
        release_model(torch, model)
    return targets, attempts, metadata


def student_training(
    *,
    torch: Any,
    direction: str,
    sources: list[str],
    targets: list[str],
    student: dict[str, Any],
) -> list[dict[str, Any]]:
    """Measure one zero-learning-rate optimizer step for each physical batch."""
    from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

    details = student["students"]["student"]["directions"][direction]
    attempts: list[dict[str, Any]] = []
    if not targets:
        return [{"status": "skipped", "reason": "Teacher produced no successful in-memory batch."}]
    model: Any | None = None
    try:
        snapshot = cached_snapshot(details["model_id"], details["revision"])
        tokenizer = AutoTokenizer.from_pretrained(snapshot, local_files_only=True)
        model = AutoModelForSeq2SeqLM.from_pretrained(
            snapshot, local_files_only=True, torch_dtype=torch.float16,
        )
        model.to("cuda")
        model.train()
        for batch_size in (4, 2, 1):
            if batch_size > len(targets):
                continue
            clear_cuda(torch)
            optimizer: Any | None = None
            try:
                batch_sources = [details["input_prefix"] + source for source in sources[:batch_size]]
                encoded_inputs = tokenizer(batch_sources, padding=True, return_tensors="pt")
                encoded_targets = tokenizer(text_target=targets[:batch_size], padding=True, return_tensors="pt")
                labels = encoded_targets["input_ids"]
                labels[labels == tokenizer.pad_token_id] = -100
                encoded_inputs = {name: value.to("cuda") for name, value in encoded_inputs.items()}
                labels = labels.to("cuda")
                optimizer = torch.optim.AdamW(model.parameters(), lr=0.0)
                torch.cuda.synchronize()
                started = time.perf_counter()
                loss = model(**encoded_inputs, labels=labels).loss
                loss.backward()
                optimizer.step()
                optimizer.zero_grad(set_to_none=True)
                torch.cuda.synchronize()
                elapsed = time.perf_counter() - started
                attempts.append({
                    "batch_size": batch_size,
                    "status": "pass",
                    "optimizer": "AdamW",
                    "optimizer_lr": 0.0,
                    "elapsed_seconds": round(elapsed, 3),
                    "sources_per_second": round(batch_size / elapsed, 3),
                    **memory_gib(torch),
                })
            except torch.cuda.OutOfMemoryError:
                attempts.append({"batch_size": batch_size, "status": "oom"})
                clear_cuda(torch)
            except Exception as error:
                attempts.append({"batch_size": batch_size, "status": "error", "error": f"{type(error).__name__}: {error}"})
                clear_cuda(torch)
            finally:
                if optimizer is not None:
                    del optimizer
    finally:
        release_model(torch, model)
    return attempts


def student_inference(
    *, torch: Any, direction: str, sources: list[str], student: dict[str, Any]
) -> list[dict[str, Any]]:
    """Measure Student decoding only; output is immediately discarded."""
    from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

    details = student["students"]["student"]["directions"][direction]
    model: Any | None = None
    try:
        snapshot = cached_snapshot(details["model_id"], details["revision"])
        tokenizer = AutoTokenizer.from_pretrained(snapshot, local_files_only=True)
        model = AutoModelForSeq2SeqLM.from_pretrained(
            snapshot, local_files_only=True, torch_dtype=torch.float16,
        )
        model.to("cuda")
        model.eval()
        clear_cuda(torch)
        batch = [details["input_prefix"] + source for source in sources]
        encoded = tokenizer(batch, padding=True, return_tensors="pt")
        encoded = {name: value.to("cuda") for name, value in encoded.items()}
        torch.cuda.synchronize()
        started = time.perf_counter()
        with torch.inference_mode():
            model.generate(**encoded)
        torch.cuda.synchronize()
        elapsed = time.perf_counter() - started
        return [{
            "batch_size": len(batch), "status": "pass", "elapsed_seconds": round(elapsed, 3),
            "sources_per_second": round(len(batch) / elapsed, 3), **memory_gib(torch),
        }]
    except torch.cuda.OutOfMemoryError:
        return [{"batch_size": len(sources), "status": "oom"}]
    except Exception as error:
        return [{"batch_size": len(sources), "status": "error", "error": f"{type(error).__name__}: {error}"}]
    finally:
        release_model(torch, model)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preflight-id", required=True, help="New immutable record identifier.")
    parser.add_argument("--sample-size", type=int, default=8)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.sample_size != 8:
        raise ValueError("Phase 03 preflight is approved only for the fixed 8-row IT Train sample.")
    if not args.preflight_id.replace("-", "").replace("_", "").isalnum():
        raise ValueError("preflight-id may contain only letters, numbers, hyphens and underscores.")
    output_path = ROOT / "evidence/phase03/preflight" / f"{args.preflight_id}.json"
    if output_path.exists():
        raise FileExistsError(f"Preflight record already exists: {output_path}")

    import torch
    import transformers

    if not torch.cuda.is_available():
        raise RuntimeError("Phase 03 hardware preflight requires an available CUDA GPU.")
    protocol = read_json(PROTOCOL_PATH)
    students = read_json(STUDENTS_PATH)
    samples = load_samples(args.sample_size)
    gpu = torch.cuda.get_device_properties(0)
    report: dict[str, Any] = {
        "stage": "phase03_hardware_preflight",
        "preflight_id": args.preflight_id,
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
        "purpose": "GPU feasibility only; this is not a KD experiment or quality evidence.",
        "data_boundary": {
            "input": str(TRAIN_PATH.relative_to(ROOT)),
            "sample_rule": "first 8 rows in file order; no source, reference or generated text is stored",
            "dataset_row_ids": [row["dataset_row_id"] for row in samples],
            "prohibited": protocol["data_roles"]["prohibited"],
        },
        "runtime": {
            "python": sys.version.split()[0],
            "platform": platform.platform(),
            "torch": torch.__version__,
            "transformers": transformers.__version__,
            "cuda": torch.version.cuda,
            "gpu_name": gpu.name,
            "gpu_total_memory_gib": round(gpu.total_memory / (1024 ** 3), 3),
            "precision": "fp16",
        },
        "scope": {
            "teacher_generation": "one in-memory inference per attempted physical batch; max_length=512 from frozen Phase 02 runtime",
            "student_training": "one forward/backward/AdamW step per attempted physical batch; AdamW lr=0.0 prevents a learning update",
            "not_performed": ["full teacher target generation", "target persistence", "student checkpoint persistence", "validation", "test", "quality metric", "model or decoding selection"],
        },
        "directions": {},
    }

    for direction in protocol["directions"]:
        sources = [row["en"] if direction == "en_to_vi" else row["vi"] for row in samples]
        print(f"PHASE03_PREFLIGHT direction={direction} stage=teacher_generation", flush=True)
        targets, teacher_attempts, teacher_metadata = teacher_targets(
            torch=torch, direction=direction, sources=sources, teacher=protocol["teacher"],
        )
        print(f"PHASE03_PREFLIGHT direction={direction} stage=student_training", flush=True)
        student_attempts = student_training(
            torch=torch, direction=direction, sources=sources[:len(targets)], targets=targets, student=students,
        )
        print(f"PHASE03_PREFLIGHT direction={direction} stage=student_inference", flush=True)
        student_inference_attempts = student_inference(
            torch=torch, direction=direction, sources=sources, student=students,
        )
        report["directions"][direction] = {
            "teacher": {"identity": teacher_metadata, "attempts": teacher_attempts},
            "student": {
                "model_id": students["students"]["student"]["directions"][direction]["model_id"],
                "revision": students["students"]["student"]["directions"][direction]["revision"],
                "attempts": student_attempts,
                "inference_attempts": student_inference_attempts,
            },
        }

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"PHASE03_HARDWARE_PREFLIGHT=PASS record={output_path.relative_to(ROOT)}", flush=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"PHASE03_HARDWARE_PREFLIGHT=FAIL error={type(error).__name__}: {error}", file=sys.stderr, flush=True)
        traceback.print_exc()
        raise SystemExit(1)
