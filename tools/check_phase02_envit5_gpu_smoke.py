"""Measure whether one EnViT5 optimizer update fits on the active CUDA device.

This is a local preflight, not a Phase 02 experiment.  It reads IT Train only,
does the requested gradient-accumulation micro-steps and one AdamW update in
memory, and writes no model, metric,
prediction, or evidence artifact.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from core_mt.adaptation import Envit5AdaptationDataset, attach_envit5_lora
from core_mt.baseline import load_candidate_spec
from core_mt.contracts import Direction
from core_mt.data import load_it_role


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed < 1:
        raise argparse.ArgumentTypeError("value must be positive")
    return parsed


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Run one non-persistent EnViT5 CUDA training-step preflight batch."
    )
    parser.add_argument("--direction", choices=tuple(item.value for item in Direction), required=True)
    parser.add_argument("--batch-size", type=positive_int, required=True)
    parser.add_argument("--precision", choices=("fp32", "fp16"), required=True)
    parser.add_argument("--method", choices=("full_ft", "lora"), default="full_ft")
    parser.add_argument("--gradient-accumulation-steps", type=positive_int, default=1)
    parser.add_argument("--learning-rate", type=float, default=1e-3)
    args = parser.parse_args()
    if args.learning_rate <= 0:
        raise ValueError("--learning-rate must be positive.")

    try:
        import torch
        from transformers import AutoModelForSeq2SeqLM, AutoTokenizer, DataCollatorForSeq2Seq
    except ImportError as error:
        raise RuntimeError("Install torch, transformers and accelerate before this preflight.") from error
    if not torch.cuda.is_available():
        raise RuntimeError("CUDA is unavailable. Activate the verified GPU environment first.")

    direction = Direction(args.direction)
    spec = load_candidate_spec(ROOT / "configs" / "phase02_models.json", "envit5", direction)
    train_data = load_it_role("adaptation_train", ROOT)
    tokenizer = AutoTokenizer.from_pretrained(spec.model_id)
    dataset = Envit5AdaptationDataset(train_data, tokenizer=tokenizer, spec=spec)

    # Pick the longest individual pairs deterministically.  This does not remove
    # or change any training pair; it makes a passed preflight conservative for
    # dynamic padding, which is also used by the adaptation runner.
    lengths = [
        max(len(dataset[index]["input_ids"]), len(dataset[index]["labels"]))
        for index in range(len(dataset))
    ]
    selected_indices = sorted(range(len(dataset)), key=lambda index: (-lengths[index], index))[\
        :args.batch_size * args.gradient_accumulation_steps
    ]
    collator = DataCollatorForSeq2Seq(tokenizer=tokenizer, label_pad_token_id=-100, return_tensors="pt")
    micro_batches = [
        collator([dataset[index] for index in selected_indices[offset:offset + args.batch_size]])
        for offset in range(0, len(selected_indices), args.batch_size)
    ]

    device_index = 0
    device = torch.device(f"cuda:{device_index}")
    torch.cuda.empty_cache()
    # This Windows CUDA build only accepts the implicit current device here.
    torch.cuda.reset_peak_memory_stats()
    model = None
    try:
        model = AutoModelForSeq2SeqLM.from_pretrained(spec.model_id)
        lora_settings = None
        if args.method == "lora":
            # One fixed preflight configuration; its only purpose is to check
            # whether a minimal adapter can execute on the fixed hardware.
            model, lora_settings = attach_envit5_lora(model, rank=8, alpha=16, dropout=0.0)
        model = model.to(device)
        model.train()
        # Match the runner's optimizer family.  The in-memory update is thrown
        # away immediately; it exists to include AdamW state in the VRAM peak.
        optimizer = torch.optim.AdamW(model.parameters(), lr=args.learning_rate)
        use_fp16 = args.precision == "fp16"
        scaler = torch.amp.GradScaler("cuda", enabled=use_fp16)
        optimizer.zero_grad(set_to_none=True)
        for batch in micro_batches:
            device_batch = {name: value.to(device) for name, value in batch.items()}
            with torch.amp.autocast("cuda", dtype=torch.float16, enabled=use_fp16):
                loss = model(**device_batch).loss / args.gradient_accumulation_steps
            scaler.scale(loss).backward()
        scaler.step(optimizer)
        scaler.update()
        peak_allocated = torch.cuda.max_memory_allocated() / 1024**3
        peak_reserved = torch.cuda.max_memory_reserved() / 1024**3
        dedicated_vram = torch.cuda.get_device_properties(device_index).total_memory / 1024**3
    except torch.OutOfMemoryError:
        print("PHASE_02_ENVI_T5_GPU_SMOKE=OUT_OF_MEMORY")
        return 2
    finally:
        if model is not None:
            del model
        torch.cuda.empty_cache()

    status = "PASS" if peak_reserved <= dedicated_vram else "EXCEEDS_DEDICATED_VRAM"
    print(f"PHASE_02_ENVI_T5_GPU_SMOKE={status}")
    print(f"direction={direction.value}")
    print(f"batch_size={args.batch_size}")
    print(f"gradient_accumulation_steps={args.gradient_accumulation_steps}")
    print(f"effective_batch_size={args.batch_size * args.gradient_accumulation_steps}")
    print(f"learning_rate={args.learning_rate}")
    print(f"precision={args.precision}")
    print(f"adaptation_method={args.method}")
    if args.method == "lora":
        print(f"lora_rank={lora_settings['rank']}")
        print(f"lora_alpha={lora_settings['alpha']}")
        print(f"lora_target_modules={','.join(lora_settings['target_modules'])}")
    print(f"selected_train_rows={selected_indices}")
    print(f"max_pair_tokens_in_batch={max(lengths[index] for index in selected_indices)}")
    print(f"dedicated_vram_gib={dedicated_vram:.2f}")
    print(f"peak_allocated_gib={peak_allocated:.2f}")
    print(f"peak_reserved_gib={peak_reserved:.2f}")
    return 0 if status == "PASS" else 3


if __name__ == "__main__":
    raise SystemExit(main())
