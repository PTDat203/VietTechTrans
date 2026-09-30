"""Shared safeguards and data preparation for Phase 02 IT adaptation.

This module deliberately supports only EnViT5, the candidate retained in the
two human-written selection records. It contains no validation or test loader.
"""
from __future__ import annotations

import hashlib
import json
import platform
import re
import sys
from pathlib import Path
from typing import Any

from .baseline import CandidateSpec, installed_version
from .contracts import Direction
from .data import Phase02Dataset


_SAFE_IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*\Z")
ENVIT5_LORA_TARGET_MODULES = ("q", "v")


def validate_identifier(value: str, *, field: str) -> str:
    if not _SAFE_IDENTIFIER.fullmatch(value):
        raise ValueError(f"{field} must contain only letters, digits, dot, underscore, or hyphen.")
    return value


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def sha256_tree(directory: Path) -> str:
    """Hash names and bytes of a saved checkpoint without depending on mtime."""
    if not directory.is_dir():
        raise FileNotFoundError(f"Checkpoint directory is missing: {directory}")
    digest = hashlib.sha256()
    for path in sorted(item for item in directory.rglob("*") if item.is_file()):
        digest.update(path.relative_to(directory).as_posix().encode("utf-8"))
        digest.update(b"\0")
        with path.open("rb") as handle:
            for block in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(block)
        digest.update(b"\0")
    return digest.hexdigest()


def experiment_record_path(root: Path, *, model: str, direction: Direction, experiment_id: str) -> Path:
    return root / "evidence" / "phase02" / model / direction.value / "adapted" / experiment_id / "experiment_record.md"


def ensure_completed_experiment_record(
    path: Path, *, experiment_id: str, checkpoint_id: str, direction: Direction, model_id: str,
) -> str:
    """Require the human record before a training run can start.

    The record remains human-readable Markdown; this check only rejects the
    unfilled template and an obviously mismatched record.
    """
    if not path.is_file():
        raise FileNotFoundError(f"Create and complete the experiment record before training: {path}")
    text = path.read_text(encoding="utf-8")
    blank_fields = []
    # The last section records actual post-training checkpoint and review
    # evidence, so it cannot be complete before the first training command.
    pre_run_text = text.split("## Checkpoint và đánh giá", maxsplit=1)[0]
    for line in pre_run_text.splitlines():
        if not line.startswith("|"):
            continue
        cells = [cell.strip() for cell in line.split("|")[1:-1]]
        if len(cells) == 2 and cells[0] not in {"Trường", "---", ":---"} and not cells[1]:
            blank_fields.append(cells[0])
    if blank_fields:
        raise ValueError(f"Experiment record still has blank fields: {', '.join(blank_fields)}")
    for required in (experiment_id, checkpoint_id, direction.value, model_id, "train.jsonl"):
        if required not in text:
            raise ValueError(f"Experiment record does not identify the required value: {required}")
    return sha256_file(path)


def envit5_source_text(spec: CandidateSpec, source: str) -> str:
    if spec.model_key != "envit5":
        raise ValueError("Phase 02 adaptation currently supports only EnViT5.")
    return f"{spec.input_prefix}{source}"


def envit5_target_text(spec: CandidateSpec, target: str) -> str:
    """Keep EnViT5's documented target-language control prefix in labels."""
    if spec.model_key != "envit5" or not spec.output_control_prefix:
        raise ValueError("EnViT5 target language control is required for adaptation.")
    return f"{spec.output_control_prefix} {target}"


def attach_envit5_lora(model: Any, *, rank: int, alpha: int, dropout: float) -> tuple[Any, dict[str, object]]:
    """Attach one explicit, small LoRA configuration to the EnViT5 T5 model."""
    if rank < 1 or alpha < 1 or not 0 <= dropout < 1:
        raise ValueError("LoRA rank/alpha must be positive and dropout must be in [0, 1).")
    try:
        from peft import LoraConfig, TaskType, get_peft_model
    except ImportError as error:
        raise RuntimeError("Install peft before LoRA adaptation or preflight.") from error
    settings: dict[str, object] = {
        "rank": rank,
        "alpha": alpha,
        "dropout": dropout,
        "target_modules": list(ENVIT5_LORA_TARGET_MODULES),
        "bias": "none",
        "task_type": "SEQ_2_SEQ_LM",
    }
    config = LoraConfig(
        r=rank,
        lora_alpha=alpha,
        lora_dropout=dropout,
        target_modules=list(ENVIT5_LORA_TARGET_MODULES),
        bias="none",
        task_type=TaskType.SEQ_2_SEQ_LM,
    )
    return get_peft_model(model, config), settings


class Envit5AdaptationDataset:
    """Lazy tokenizer wrapper; it preserves every source and target string."""

    def __init__(self, dataset: Phase02Dataset, *, tokenizer: Any, spec: CandidateSpec) -> None:
        if dataset.role != "adaptation_train":
            raise ValueError("Adaptation dataset must be IT Train only.")
        self.dataset = dataset
        self.tokenizer = tokenizer
        self.spec = spec

    def __len__(self) -> int:
        return len(self.dataset.rows)

    def __getitem__(self, index: int) -> dict[str, list[int]]:
        source, target = self.dataset.rows[index].for_direction(self.spec.direction)
        encoded_source = self.tokenizer(envit5_source_text(self.spec, source), truncation=False)
        encoded_target = self.tokenizer(text_target=envit5_target_text(self.spec, target), truncation=False)
        return {
            "input_ids": encoded_source["input_ids"],
            "attention_mask": encoded_source["attention_mask"],
            "labels": encoded_target["input_ids"],
        }


def runtime_metadata(torch: Any, transformers: Any) -> dict[str, object]:
    gpu_names = []
    if torch.cuda.is_available():
        gpu_names = [torch.cuda.get_device_name(index) for index in range(torch.cuda.device_count())]
    return {
        "python": sys.version.split()[0],
        "platform": platform.platform(),
        "torch": torch.__version__,
        "transformers": transformers.__version__,
        "tokenizers": installed_version("tokenizers"),
        "huggingface_hub": installed_version("huggingface_hub"),
        "sentencepiece": installed_version("sentencepiece"),
        "accelerate": installed_version("accelerate"),
        "cuda_available": torch.cuda.is_available(),
        "cuda_device_names": gpu_names,
    }


def json_safe(value: object) -> object:
    """Convert trainer metrics to JSON primitives without changing their values."""
    return json.loads(json.dumps(value, default=float))
