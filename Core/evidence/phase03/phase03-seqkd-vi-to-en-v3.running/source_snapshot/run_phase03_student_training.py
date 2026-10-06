"""Train one approved Phase 03 Student from integrity-checked staged targets."""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from run_phase03_teacher_targets import (
    RECORD_PATHS, ROOT, load_expected_rows, read_json, require_execution_approval,
    resolve_execution_inputs, sha256_file, sha256_tree, validate_targets, write_json,
)
from reuse_phase03_teacher_targets import load_eligible_rows
from reuse_phase03_teacher_targets_vi_to_en_v3 import load_eligible_rows as load_vi_to_en_v3_eligible_rows


POLICY_PATH = ROOT / "configs/phase03_input_length_policy.json"
REUSE_V2_RUN_ID = "phase03-seqkd-en-to-vi-v2"
REUSE_V2_PROTOCOL_PATH = ROOT / "configs/phase03_protocol_teacher_target_reuse_v2.json"
REUSE_V2_POLICY_PATH = ROOT / "configs/phase03_teacher_target_reuse_en_to_vi_v2.json"
REUSE_V2_RECORD_PATH = ROOT / "configs/phase03_experiments/phase03-seqkd-en-to-vi-v2.json"
VI_TO_EN_REUSE_V3_RUN_ID = "phase03-seqkd-vi-to-en-v3"
VI_TO_EN_REUSE_V3_PROTOCOL_PATH = ROOT / "configs/phase03_protocol_teacher_target_reuse_vi_to_en_v3.json"
VI_TO_EN_REUSE_V3_POLICY_PATH = ROOT / "configs/phase03_teacher_target_reuse_vi_to_en_v3.json"
VI_TO_EN_REUSE_V3_RECORD_PATH = ROOT / "configs/phase03_experiments/phase03-seqkd-vi-to-en-v3.json"


def cached_student_path(student: dict[str, Any]) -> Path:
    return Path.home() / ".cache/huggingface/hub" / ("models--" + student["model_id"].replace("/", "--")) / "snapshots" / student["revision"]


class DistillationDataset:
    def __init__(self, rows: list[dict[str, str]], targets: dict[str, str], tokenizer: Any, prefix: str) -> None:
        self.rows, self.targets, self.tokenizer, self.prefix = rows, targets, tokenizer, prefix

    def __len__(self) -> int:
        return len(self.rows)

    def __getitem__(self, index: int) -> dict[str, list[int]]:
        row = self.rows[index]
        return {
            **self.tokenizer(self.prefix + row["source"], truncation=False),
            "labels": self.tokenizer(text_target=self.targets[row["dataset_row_id"]], truncation=False)["input_ids"],
        }


def require_cuda_device(torch: Any, device: dict[str, Any]) -> None:
    visible = os.environ.get("CUDA_VISIBLE_DEVICES")
    if visible not in (None, "", "0"):
        raise RuntimeError("CUDA_VISIBLE_DEVICES must be unset or '0' for the approved cuda:0 run.")
    if not torch.cuda.is_available() or torch.cuda.device_count() <= device["index"]:
        raise RuntimeError("The approved CUDA device is unavailable.")
    if torch.cuda.get_device_name(device["index"]) != device["name"]:
        raise RuntimeError("The available CUDA device differs from the approved experiment record.")
    torch.cuda.set_device(device["index"])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--direction", choices=("en_to_vi", "vi_to_en"), required=True)
    parser.add_argument("--run-id", help="Approved run ID; defaults to the original v1 record for the selected direction.")
    args = parser.parse_args()
    default_run_id = f"phase03-seqkd-{args.direction.replace('_', '-')}-v1"
    run_id = args.run_id or default_run_id
    if run_id == REUSE_V2_RUN_ID:
        if args.direction != "en_to_vi":
            raise ValueError("The approved reuse v2 record is EN-to-VI only.")
        protocol, policy, record = read_json(REUSE_V2_PROTOCOL_PATH), read_json(REUSE_V2_POLICY_PATH), read_json(REUSE_V2_RECORD_PATH)
        rows_loader = load_eligible_rows
    elif args.direction == "vi_to_en" and run_id == VI_TO_EN_REUSE_V3_RUN_ID:
        protocol, policy, record = read_json(VI_TO_EN_REUSE_V3_PROTOCOL_PATH), read_json(VI_TO_EN_REUSE_V3_POLICY_PATH), read_json(VI_TO_EN_REUSE_V3_RECORD_PATH)
        rows_loader = load_vi_to_en_v3_eligible_rows
    elif args.direction == "vi_to_en" and run_id == "phase03-seqkd-vi-to-en-v2":
        protocol, policy, record = resolve_execution_inputs(args.direction, run_id)
        rows_loader = lambda value: load_expected_rows(args.direction, value)
    elif run_id == default_run_id:
        protocol, policy, record = read_json(ROOT / "configs/phase03_protocol.json"), read_json(POLICY_PATH), read_json(RECORD_PATHS[args.direction])
        rows_loader = lambda value: load_expected_rows(args.direction, value)
    else:
        raise ValueError("Unknown Phase 03 run ID.")
    require_execution_approval(protocol, record, args.direction, expected_run_id=run_id)

    run_dir = ROOT / "evidence/phase03" / f"{run_id}.running"
    target_path = run_dir / "teacher_targets" / f"{args.direction}.jsonl"
    report_path = target_path.with_suffix(".integrity.json")
    if not target_path.is_file() or not report_path.is_file():
        raise FileNotFoundError("Generate and validate staged Teacher targets before Student training.")
    report = read_json(report_path)
    if report.get("status") != "pass" or report.get("student_training_permitted") is not True:
        raise PermissionError("Student training is blocked until the persisted target-integrity report passes.")
    if report.get("target_sha256") != sha256_file(target_path):
        raise ValueError("Staged Teacher targets differ from the integrity-checked file.")

    import torch
    import transformers
    from transformers import AutoModelForSeq2SeqLM, AutoTokenizer, DataCollatorForSeq2Seq, Seq2SeqTrainer, Seq2SeqTrainingArguments, set_seed

    require_cuda_device(torch, record["execution_device"])
    student = record["student"]
    snapshot = cached_student_path(student)
    if not snapshot.is_dir() or sha256_tree(snapshot) != student["snapshot_sha256_tree"]:
        raise ValueError("Student snapshot is missing or differs from the approved experiment record.")
    rows = rows_loader(policy)
    target_rows = [json.loads(line) for line in target_path.read_text(encoding="utf-8").splitlines()]
    tokenizer = AutoTokenizer.from_pretrained(snapshot, local_files_only=True)
    integrity = validate_targets(
        rows, target_rows,
        lambda text: len(tokenizer(text_target=text, add_special_tokens=True, truncation=False, verbose=False)["input_ids"]),
        record["target_integrity"]["student_token_limit"],
    )
    if integrity["status"] != "pass":
        raise ValueError("Staged Teacher targets no longer satisfy the required integrity gate.")
    targets = {row["dataset_row_id"]: row["target"] for row in target_rows}

    final_path = ROOT / record["output"]["final_checkpoint"]
    staging_path = final_path.with_name(final_path.name + ".running")
    evidence_path = run_dir / "training" / args.direction / "training_run.json"
    trainer_output = run_dir / "training" / args.direction / "trainer_output"
    if final_path.exists() or staging_path.exists() or evidence_path.exists() or trainer_output.exists():
        raise FileExistsError("Refusing to overwrite a Student checkpoint or training evidence.")
    final_path.parent.mkdir(parents=True, exist_ok=True)
    trainer_output.parent.mkdir(parents=True, exist_ok=True)
    staging_path.mkdir()
    model: Any | None = None
    try:
        set_seed(record["training"]["seed"])
        model = AutoModelForSeq2SeqLM.from_pretrained(snapshot, local_files_only=True)
        dataset = DistillationDataset(rows, targets, tokenizer, student["input_prefix"])
        training = record["training"]
        arguments = Seq2SeqTrainingArguments(
            output_dir=str(trainer_output), do_train=True, do_eval=False,
            eval_strategy=training["evaluation_strategy"], save_strategy=training["save_strategy"],
            report_to=[], push_to_hub=False, per_device_train_batch_size=student["per_device_train_batch_size"],
            gradient_accumulation_steps=student["gradient_accumulation_steps"], learning_rate=training["learning_rate"],
            optim=training["optim"], lr_scheduler_type=training["lr_scheduler_type"], warmup_ratio=training["warmup_ratio"],
            weight_decay=training["weight_decay"], max_grad_norm=training["max_grad_norm"],
            num_train_epochs=training["num_train_epochs"], seed=training["seed"], data_seed=training["data_seed"],
            fp16=student["fp16"], remove_unused_columns=False, disable_tqdm=False,
        )
        trainer = Seq2SeqTrainer(model=model, args=arguments, train_dataset=dataset, data_collator=DataCollatorForSeq2Seq(tokenizer=tokenizer, model=model, label_pad_token_id=-100))
        result = trainer.train()
        trainer.save_model(str(staging_path))
        tokenizer.save_pretrained(staging_path)
        checkpoint_hash = sha256_tree(staging_path)
        write_json(evidence_path, {
            "stage": "phase03_student_training", "run_id": record["run_id"], "direction": args.direction,
            "student": {"model_id": student["model_id"], "revision": student["revision"], "snapshot_sha256_tree": student["snapshot_sha256_tree"]},
            "training_configuration": training | {"physical_batch": student["per_device_train_batch_size"], "gradient_accumulation": student["gradient_accumulation_steps"], "effective_batch": student["effective_batch_size"], "fp16": student["fp16"]},
            "input": {"target_path": str(target_path.relative_to(ROOT)).replace("\\", "/"), "target_sha256": report["target_sha256"], "rows": len(rows)},
            "checkpoint": {"path": str(final_path.relative_to(ROOT)).replace("\\", "/"), "sha256_tree": checkpoint_hash},
            "runtime": {"torch": torch.__version__, "transformers": transformers.__version__, "device": record["execution_device"]},
            "trainer_metrics": json.loads(json.dumps(result.metrics, default=float)), "completed_at_utc": datetime.now(timezone.utc).isoformat(),
        })
        staging_path.rename(final_path)
        print(f"PHASE03_STUDENT_TRAINING=PASS run={record['run_id']} direction={args.direction}")
        return 0
    except Exception:
        raise
    finally:
        if model is not None:
            del model
        if torch.cuda.is_available():
            torch.cuda.empty_cache()


if __name__ == "__main__":
    raise SystemExit(main())
