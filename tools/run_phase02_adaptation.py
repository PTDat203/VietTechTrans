"""Adapt EnViT5 đã được người thực hiện giữ, chỉ bằng IT Train.

Điểm bắt đầu là experiment record đã hoàn tất và command line. Script lưu một
checkpoint cuối cho một experiment đã khai báo; không đọc validation/final test
và không chọn checkpoint từ metric.
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from core_mt.adaptation import (
    Envit5AdaptationDataset,
    attach_envit5_lora,
    ensure_completed_experiment_record,
    experiment_record_path,
    json_safe,
    runtime_metadata,
    sha256_file,
    sha256_tree,
    validate_identifier,
)
from core_mt.baseline import load_candidate_spec
from core_mt.contracts import Direction
from core_mt.data import load_it_role


def write_json_exclusive(path: Path, payload: dict[str, object]) -> None:
    with path.open("x", encoding="utf-8", newline="\n") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed < 1:
        raise argparse.ArgumentTypeError("value must be positive")
    return parsed


def positive_float(value: str) -> float:
    parsed = float(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("value must be positive")
    return parsed


def main() -> int:
    parser = argparse.ArgumentParser(description="Adapt EnViT5 using the sealed IT Train split only.")
    parser.add_argument("--model", choices=("envit5",), required=True)
    parser.add_argument("--method", choices=("full_ft", "lora"), required=True)
    parser.add_argument("--direction", choices=tuple(direction.value for direction in Direction), required=True)
    parser.add_argument("--experiment-id", required=True)
    parser.add_argument("--checkpoint-id", required=True, help="Name for the one final checkpoint saved by this experiment.")
    parser.add_argument("--epochs", type=positive_float, required=True)
    parser.add_argument("--per-device-train-batch-size", type=positive_int, required=True)
    parser.add_argument("--gradient-accumulation-steps", type=positive_int, required=True)
    parser.add_argument("--learning-rate", type=positive_float, required=True)
    parser.add_argument("--seed", type=int, required=True)
    parser.add_argument("--fp16", action="store_true", help="Use CUDA FP16 mixed precision; record this only after a matching preflight passes.")
    parser.add_argument("--lora-rank", type=positive_int)
    parser.add_argument("--lora-alpha", type=positive_int)
    parser.add_argument("--lora-dropout", type=float)
    parser.add_argument("--use-cpu", action="store_true", help="Force CPU training; otherwise the installed Trainer selects available hardware.")
    args = parser.parse_args()

    direction = Direction(args.direction)
    experiment_id = validate_identifier(args.experiment_id, field="experiment-id")
    checkpoint_id = validate_identifier(args.checkpoint_id, field="checkpoint-id")
    if args.method == "lora" and (args.lora_rank is None or args.lora_alpha is None or args.lora_dropout is None):
        raise ValueError("LoRA adaptation requires --lora-rank, --lora-alpha and --lora-dropout.")
    if args.method != "lora" and any(value is not None for value in (args.lora_rank, args.lora_alpha, args.lora_dropout)):
        raise ValueError("LoRA settings are only accepted with --method lora.")
    spec = load_candidate_spec(ROOT / "configs" / "phase02_models.json", args.model, direction)
    record_path = experiment_record_path(ROOT, model=args.model, direction=direction, experiment_id=experiment_id)
    record_sha256 = ensure_completed_experiment_record(
        record_path, experiment_id=experiment_id, checkpoint_id=checkpoint_id, direction=direction, model_id=spec.model_id,
    )

    train_data = load_it_role("adaptation_train", ROOT)
    experiment_evidence = record_path.parent
    training_run = experiment_evidence / "training_run.json"
    training_staging = experiment_evidence / "training_run.json.running"
    checkpoint_root = ROOT / "models" / "phase02" / experiment_id
    checkpoint_path = checkpoint_root / checkpoint_id
    checkpoint_staging = checkpoint_root / f"{checkpoint_id}.running"
    for path in (training_run, training_staging, checkpoint_path, checkpoint_staging):
        if path.exists():
            raise FileExistsError(f"Refusing to replace existing adaptation artifact: {path}")

    try:
        import torch
        import transformers
        from transformers import AutoModelForSeq2SeqLM, AutoTokenizer, DataCollatorForSeq2Seq, Seq2SeqTrainer, Seq2SeqTrainingArguments, set_seed
    except ImportError as error:
        raise RuntimeError("Install torch, transformers, accelerate and sentencepiece before adaptation.") from error

    checkpoint_root.mkdir(parents=True, exist_ok=False)
    checkpoint_staging.mkdir()
    started_at = datetime.now(timezone.utc).isoformat()
    try:
        set_seed(args.seed)
        tokenizer = AutoTokenizer.from_pretrained(spec.model_id)
        model = AutoModelForSeq2SeqLM.from_pretrained(spec.model_id)
        source_revision = getattr(model.config, "_commit_hash", None)
        lora_settings = None
        if args.method == "lora":
            model, lora_settings = attach_envit5_lora(
                model, rank=args.lora_rank, alpha=args.lora_alpha, dropout=args.lora_dropout,
            )
        dataset = Envit5AdaptationDataset(train_data, tokenizer=tokenizer, spec=spec)
        training_arguments = Seq2SeqTrainingArguments(
            output_dir=str(checkpoint_staging),
            do_train=True,
            do_eval=False,
            eval_strategy="no",
            save_strategy="no",
            logging_strategy="steps",
            logging_steps=1_000,
            report_to=[],
            push_to_hub=False,
            per_device_train_batch_size=args.per_device_train_batch_size,
            gradient_accumulation_steps=args.gradient_accumulation_steps,
            learning_rate=args.learning_rate,
            optim="adamw_torch",
            num_train_epochs=args.epochs,
            seed=args.seed,
            data_seed=args.seed,
            full_determinism=True,
            fp16=args.fp16,
            use_cpu=args.use_cpu,
            remove_unused_columns=False,
        )
        trainer = Seq2SeqTrainer(
            model=model,
            args=training_arguments,
            train_dataset=dataset,
            data_collator=DataCollatorForSeq2Seq(tokenizer=tokenizer, model=model, label_pad_token_id=-100),
        )
        train_output = trainer.train()
        trainer.save_model(str(checkpoint_staging))
        tokenizer.save_pretrained(checkpoint_staging)
        checkpoint_sha256 = sha256_tree(checkpoint_staging)
        completed_at = datetime.now(timezone.utc).isoformat()
        payload = {
            "stage": "adaptation_training",
            "model": {"key": args.model, "model_id": spec.model_id, "source_checkpoint_revision": source_revision},
            "adaptation_method": {"name": args.method, "lora": lora_settings},
            "direction": direction.value,
            "experiment_id": experiment_id,
            "checkpoint_id": checkpoint_id,
            "checkpoint": {
                "path": str(checkpoint_path.relative_to(ROOT)).replace("\\", "/"),
                "sha256_tree": checkpoint_sha256,
            },
            "experiment_record": {
                "path": str(record_path.relative_to(ROOT)).replace("\\", "/"),
                "sha256": record_sha256,
            },
            "train_data": {
                "role": train_data.role,
                "path": str(train_data.path.relative_to(ROOT)).replace("\\", "/"),
                "sha256": sha256_file(train_data.path),
                "rows": len(train_data.rows),
            },
            "language_control": {
                "input_prefix": spec.input_prefix,
                "target_control_prefix": spec.output_control_prefix,
                "target_prefix_in_labels": True,
                "note": "EnViT5 language-control prefixes are checkpoint format, not rewritten dataset text.",
            },
            "training_configuration": {
                "epochs": args.epochs,
                "per_device_train_batch_size": args.per_device_train_batch_size,
                "gradient_accumulation_steps": args.gradient_accumulation_steps,
                "effective_batch_size": args.per_device_train_batch_size * args.gradient_accumulation_steps,
                "learning_rate": args.learning_rate,
                "seed": args.seed,
                "optimizer": "adamw_torch",
                "lr_scheduler_type": "linear",
                "warmup_ratio": 0.0,
                "save_strategy": "no; one final checkpoint is saved after training",
                "evaluation_during_training": "none",
                "logging_steps": 1_000,
                "mixed_precision": "fp16" if args.fp16 else "none",
                "use_cpu_requested": args.use_cpu,
                "source_truncation": False,
                "target_truncation": False,
            },
            "runtime": runtime_metadata(torch, transformers),
            "trainer_metrics": json_safe(train_output.metrics),
            "runner_sha256": sha256_file(Path(__file__)),
            "adaptation_module_sha256": sha256_file(ROOT / "src" / "core_mt" / "adaptation.py"),
            "protocol_sha256": sha256_file(ROOT / "configs" / "phase02_protocol.json"),
            "candidate_config_sha256": sha256_file(ROOT / "configs" / "phase02_models.json"),
            "command": sys.argv,
            "started_at_utc": started_at,
            "completed_at_utc": completed_at,
        }
        write_json_exclusive(training_staging, payload)
        checkpoint_staging.rename(checkpoint_path)
        training_staging.rename(training_run)
    except Exception:
        # Keep the sibling .running checkpoint and/or JSON for inspection.
        raise

    print("PHASE_02_ADAPTATION=PASS")
    print(checkpoint_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
