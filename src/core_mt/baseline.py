"""Chạy pretrained baseline Phase 2 mà không chạm vào final test.

Mỗi adapter dùng GenerationConfig của checkpoint, trừ khi model card chính
thức công bố runtime control bắt buộc. Mọi control và output normalization
đều được ghi vào evidence; chúng không phải tuning theo validation.
"""
from __future__ import annotations

import json
import platform
import re
import sys
from importlib.metadata import PackageNotFoundError, version
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .contracts import Direction
from .data import Phase02Dataset
from .metrics import score_predictions


def installed_version(package: str) -> str | None:
    """Return an installed package version without making it an inference dependency."""
    try:
        return version(package)
    except PackageNotFoundError:
        return None


@dataclass(frozen=True)
class CandidateSpec:
    model_key: str
    model_id: str
    direction: Direction
    input_prefix: str = ""
    input_prefix_role: str | None = None
    source_language: str | None = None
    target_language: str | None = None
    output_control_prefix: str | None = None
    generation_kwargs: dict[str, object] | None = None
    runtime_settings_source: str | None = None
    runtime_settings_reason: str | None = None


def load_candidate_spec(config_path: Path, model_key: str, direction: Direction) -> CandidateSpec:
    raw = json.loads(config_path.read_text(encoding="utf-8"))
    try:
        details = raw["candidates"][model_key]["directions"][direction.value]
    except KeyError as error:
        raise ValueError(f"Unknown Phase 2 candidate/direction: {model_key}/{direction.value}") from error
    return CandidateSpec(
        model_key=model_key,
        model_id=details["model_id"],
        direction=direction,
        input_prefix=details.get("input_prefix", ""),
        input_prefix_role=details.get("input_prefix_role"),
        source_language=details.get("source_language"),
        target_language=details.get("target_language"),
        output_control_prefix=details.get("output_control_prefix"),
        generation_kwargs=details.get("generation_kwargs"),
        runtime_settings_source=details.get("runtime_settings_source"),
        runtime_settings_reason=details.get("runtime_settings_reason"),
    )


class PretrainedAdapter:
    """Transformer adapter, loaded only when an actual baseline run begins."""

    def __init__(self, spec: CandidateSpec, *, checkpoint_path: Path | None = None, adaptation_method: str = "full_ft") -> None:
        self.spec = spec
        self.checkpoint_path = checkpoint_path
        self.adaptation_method = adaptation_method
        self.model_key = spec.model_key
        self.direction = spec.direction
        self.model: Any = None
        self.tokenizer: Any = None
        self.torch: Any = None
        self.normalized_output_segments = 0

    def load(self) -> None:
        try:
            import torch
            import transformers
            from transformers import AutoModelForSeq2SeqLM, AutoTokenizer
        except ImportError as error:
            raise RuntimeError("Install torch, transformers and sentencepiece in the active environment.") from error
        self.torch = torch
        if self.checkpoint_path is not None and self.adaptation_method == "lora":
            try:
                from peft import PeftModel
            except ImportError as error:
                raise RuntimeError("Install peft before evaluating a LoRA-adapted checkpoint.") from error
            self.tokenizer = AutoTokenizer.from_pretrained(self.checkpoint_path)
            base_model = AutoModelForSeq2SeqLM.from_pretrained(self.spec.model_id)
            self.model = PeftModel.from_pretrained(base_model, self.checkpoint_path)
        else:
            source = str(self.checkpoint_path) if self.checkpoint_path is not None else self.spec.model_id
            self.tokenizer = AutoTokenizer.from_pretrained(source)
            self.model = AutoModelForSeq2SeqLM.from_pretrained(source)
        self.model.eval()
        self.normalized_output_segments = 0
        self.transformers_version = transformers.__version__
        self.runtime_versions = {
            "torch_version": torch.__version__,
            "tokenizers_version": __import__("tokenizers").__version__,
            "huggingface_hub_version": __import__("huggingface_hub").__version__,
            "sentencepiece_version": __import__("sentencepiece").__version__,
        }

    def translate(self, source_text: str) -> str:
        return self.translate_many([source_text])[0]

    def translate_many(self, source_texts: list[str]) -> list[str]:
        if self.model is None or self.tokenizer is None or self.torch is None:
            raise RuntimeError("Call load() before translate().")
        if not source_texts:
            return []
        segment_groups = [self._split_source(source_text) for source_text in source_texts]
        sources = [f"{self.spec.input_prefix}{segment}" for group in segment_groups for segment in group]
        generation_kwargs = dict(self.spec.generation_kwargs or {})
        if self.spec.model_key == "nllb200_distilled_600m":
            self.tokenizer.src_lang = self.spec.source_language
            encoded = self.tokenizer(sources, padding=True, return_tensors="pt")
            generated = self.model.generate(
                **encoded,
                forced_bos_token_id=self.tokenizer.convert_tokens_to_ids(self.spec.target_language),
                **generation_kwargs,
            )
        else:
            encoded = self.tokenizer(sources, padding=True, return_tensors="pt")
            generated = self.model.generate(**encoded, **generation_kwargs)
        decoded = [self._normalize_decoded_output(text) for text in self.tokenizer.batch_decode(generated, skip_special_tokens=True)]
        predictions = []
        offset = 0
        for group in segment_groups:
            predictions.append(" ".join(decoded[offset:offset + len(group)]))
            offset += len(group)
        return predictions

    def _normalize_decoded_output(self, prediction: str) -> str:
        """Remove only the documented leading EnViT5 language-control output tag."""
        prefix = self.spec.output_control_prefix
        if prefix is None or not prediction.startswith(prefix):
            return prediction
        self.normalized_output_segments += 1
        remainder = prediction[len(prefix):]
        return remainder[1:] if remainder[:1].isspace() else remainder

    def segment_counts(self, source_texts: list[str]) -> list[int]:
        """Report deterministic source segmentation required by model input limits."""
        if self.model is None or self.tokenizer is None:
            raise RuntimeError("Call load() before segment_counts().")
        return [len(self._split_source(source_text)) for source_text in source_texts]

    def _input_limit(self) -> int | None:
        if self.model is None or self.tokenizer is None:
            raise RuntimeError("Call load() before reading the input limit.")
        values = []
        for owner in (self.model.config, self.tokenizer):
            for field in ("max_position_embeddings", "max_source_positions", "n_positions", "model_max_length"):
                value = getattr(owner, field, None)
                if isinstance(value, int) and 0 < value < 100_000:
                    values.append(value)
        # T5-family checkpoints use relative position bias and may intentionally
        # expose no finite architecture-level input limit. Do not invent a
        # truncation or segmentation threshold in that case.
        return min(values) if values else None

    def _token_length(self, source_text: str) -> int:
        encoded = self.tokenizer(
            f"{self.spec.input_prefix}{source_text}",
            add_special_tokens=True,
            truncation=False,
            verbose=False,
        )
        return len(encoded["input_ids"])

    def _split_source(self, source_text: str) -> list[str]:
        """Split only when the checkpoint cannot encode a complete source string.

        The split is deterministic, preserves all source characters, and is not a
        generation setting. Whitespace is retained inside fragments; a long
        unbroken token (for example a URL) is split at the longest encodable
        character boundary as a last resort.
        """
        limit = self._input_limit()
        if limit is None:
            return [source_text]
        if self._token_length(source_text) <= limit:
            return [source_text]
        fragments = re.findall(r"\S+\s*|\s+", source_text, flags=re.UNICODE)
        chunks: list[str] = []
        current = ""
        for fragment in fragments:
            candidate = current + fragment
            if current and self._token_length(candidate) > limit:
                chunks.append(current)
                current = ""
            if self._token_length(fragment) <= limit:
                current += fragment
                continue
            # An unbroken source token exceeds the model capacity. Find the
            # largest prefix that fits, without discarding any character.
            remainder = fragment
            while remainder:
                low, high = 1, len(remainder)
                while low < high:
                    midpoint = (low + high + 1) // 2
                    if self._token_length(remainder[:midpoint]) <= limit:
                        low = midpoint
                    else:
                        high = midpoint - 1
                if low < 1:
                    raise ValueError("A single character exceeds the checkpoint input limit.")
                chunks.append(remainder[:low])
                remainder = remainder[low:]
        if current:
            chunks.append(current)
        if not chunks or "".join(chunks) != source_text:
            raise AssertionError("Input segmentation must preserve the source text exactly.")
        return chunks

    def metadata(self) -> dict[str, object]:
        if self.model is None or self.tokenizer is None:
            raise RuntimeError("Call load() before metadata().")
        generation_config = summarize_generation_config(self.model.generation_config.to_dict())
        return {
            "model_id": self.spec.model_id,
            "model_key": self.model_key,
            "adaptation_method": self.adaptation_method,
            "direction": self.direction.value,
            "checkpoint_revision": getattr(self.model.config, "_commit_hash", None),
            "transformers_version": self.transformers_version,
            **self.runtime_versions,
            "python_version": sys.version.split()[0],
            "platform": platform.platform(),
            "runtime": {
                "python": sys.version.split()[0],
                "platform": platform.platform(),
                "torch": getattr(self.torch, "__version__", None),
                "transformers": self.transformers_version,
                "tokenizers": installed_version("tokenizers"),
                "huggingface_hub": installed_version("huggingface_hub"),
                "sentencepiece": installed_version("sentencepiece"),
            },
            "generation_policy": (
                "checkpoint_default"
                if not self.spec.generation_kwargs
                else "checkpoint_default_with_official_model_card_runtime_settings"
            ),
            "checkpoint_generation_config": generation_config,
            "generation_overrides": self.spec.generation_kwargs or {},
            "generation_settings_source": self.spec.runtime_settings_source,
            "generation_settings_reason": self.spec.runtime_settings_reason,
            "output_normalization": {
                "policy": (
                    "remove_exact_leading_control_prefix_and_one_delimiter_whitespace"
                    if self.spec.output_control_prefix
                    else "none"
                ),
                "control_prefix": self.spec.output_control_prefix,
                "normalized_output_segments": self.normalized_output_segments,
            },
            "input_length_policy": {
                "name": (
                    "deterministic_whitespace_then_character_segmentation"
                    if self._input_limit() is not None
                    else "no_finite_checkpoint_input_limit"
                ),
                "checkpoint_input_token_limit": self._input_limit(),
                "applied_only_when_source_exceeds_limit": self._input_limit() is not None,
                "generation_settings_changed": False,
            },
            "required_language_control": {
                "input_prefix": self.spec.input_prefix,
                "input_prefix_role": self.spec.input_prefix_role,
                "source_language": self.spec.source_language,
                "target_language": self.spec.target_language,
            },
        }


_EVIDENCE_GENERATION_FIELDS = (
    "max_length", "min_length", "num_beams", "length_penalty", "early_stopping",
    "no_repeat_ngram_size", "renormalize_logits", "bad_words_ids",
    "forced_bos_token_id", "forced_eos_token_id", "pad_token_id", "bos_token_id",
    "eos_token_id", "decoder_start_token_id",
)


def summarize_generation_config(raw_config: dict[str, object]) -> dict[str, object]:
    """Keep only non-null checkpoint settings that change generated output."""
    return {
        field: raw_config[field]
        for field in _EVIDENCE_GENERATION_FIELDS
        if raw_config.get(field) is not None
    }


def _run_evaluation(
    adapter: PretrainedAdapter,
    dataset: Phase02Dataset,
    *,
    batch_size: int,
    permitted_roles: set[str],
) -> tuple[list[dict[str, object]], dict[str, object]]:
    """Return aligned predictions and metrics for one explicitly permitted role."""
    if dataset.role not in permitted_roles:
        raise ValueError(f"Evaluation does not permit dataset role: {dataset.role}.")
    if batch_size < 1:
        raise ValueError("batch_size must be positive.")
    adapter.load()
    records: list[dict[str, object]] = []
    predictions: list[str] = []
    references: list[str] = []
    completed_rows = 0
    next_progress_report = 1_000
    for start in range(0, len(dataset.rows), batch_size):
        rows = dataset.rows[start:start + batch_size]
        pairs = [row.for_direction(adapter.direction) for row in rows]
        sources = [source for source, _ in pairs]
        segment_counts = adapter.segment_counts(sources)
        batch_predictions = adapter.translate_many(sources)
        for row, (source, reference), prediction, segment_count in zip(rows, pairs, batch_predictions, segment_counts, strict=True):
            records.append({
                "row_id": row.row_id,
                "source": source,
                "reference": reference,
                "prediction": prediction,
                "technical_tags": list(row.technical_tags),
                "input_segments": segment_count,
            })
            predictions.append(prediction)
            references.append(reference)
        completed_rows += len(rows)
        while completed_rows >= next_progress_report:
            print(
                f"PHASE_02_PROGRESS rows_completed={next_progress_report}/{len(dataset.rows)} "
                f"dataset={dataset.role}",
                flush=True,
            )
            next_progress_report += 1_000
    if completed_rows % 1_000:
        print(
            f"PHASE_02_PROGRESS rows_completed={completed_rows}/{len(dataset.rows)} "
            f"dataset={dataset.role} status=inference_complete",
            flush=True,
        )
    metrics = score_predictions(predictions, references)
    return records, {
        "dataset_role": dataset.role,
        "rows": len(records),
        "inference_batch_size": batch_size,
        "segmented_rows": sum(count > 1 for count in (row["input_segments"] for row in records)),
        "max_input_segments": max(row["input_segments"] for row in records),
        **metrics,
    }


def run_pretrained_evaluation(adapter: PretrainedAdapter, dataset: Phase02Dataset, *, batch_size: int = 8) -> tuple[list[dict[str, object]], dict[str, object]]:
    """Evaluate one validation set during screening or adapted-checkpoint evaluation."""
    return _run_evaluation(
        adapter,
        dataset,
        batch_size=batch_size,
        permitted_roles={"selection_validation", "general_validation"},
    )


def run_final_evaluation(adapter: PretrainedAdapter, dataset: Phase02Dataset, *, batch_size: int = 1) -> tuple[list[dict[str, object]], dict[str, object]]:
    """Evaluate one final test set after the Core-MT decision has been frozen."""
    return _run_evaluation(
        adapter,
        dataset,
        batch_size=batch_size,
        permitted_roles={"it_test", "general_test"},
    )
