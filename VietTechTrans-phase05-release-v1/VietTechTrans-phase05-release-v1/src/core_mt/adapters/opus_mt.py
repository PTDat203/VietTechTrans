"""Adapter OPUS-MT: phần riêng duy nhất của nhánh Marian bilingual.

Shared layer đã quyết định dataset, direction, seed và generation settings.
Adapter này chỉ làm ba việc OPUS-specific: chọn checkpoint theo direction, thêm
target-language token khi model card yêu cầu, và gọi tokenizer/model Marian.
Không có rule nào phụ thuộc General Test hoặc IT Test trong file này.
"""
from __future__ import annotations

import json
from pathlib import Path

from ..contracts import Direction


class OpusMTAdapter:
    """Bọc hai checkpoint OPUS-MT thành cùng interface `TranslationAdapter`.

    EN→VI và VI→EN là hai checkpoint khác nhau. Điều này khác model multilingual:
    checkpoint được chọn trước khi inference, không phải bằng cách nhìn kết quả
    test rồi chọn model tốt hơn.
    """

    model_key = "opus_mt"

    def __init__(self, direction: Direction, config_path: Path, device: str) -> None:
        raw = json.loads(config_path.read_text(encoding="utf-8"))
        if raw.get("model_key") != self.model_key:
            raise ValueError("OPUS-MT adapter received an incompatible config.")
        self.direction, self.device = direction, device
        self.spec = raw["directions"][direction.value]
        self._model = self._tokenizer = self._torch = None
        self._resolved_revision: str | None = None
        self._snapshot: Path | None = None

    def prepare_input(self, source_text: str) -> str:
        """Apply the model-card-required target token, not a tuned prompt."""
        if not isinstance(source_text, str) or not source_text.strip():
            raise ValueError("OPUS-MT source text must be a non-empty string.")
        prefix = self.spec["source_prefix"]
        return f"{prefix} {source_text}" if prefix else source_text

    def load(self) -> None:
        """Resolve `main` to SHA, then download exactly that official revision."""
        try:
            import torch
            from huggingface_hub import HfApi, snapshot_download
            from transformers import AutoModelForSeq2SeqLM, AutoTokenizer
        except ImportError as error:
            raise RuntimeError("Install OPUS-MT dependencies from requirements.txt before loading the model.") from error
        info = HfApi().model_info(self.spec["model_id"], revision=self.spec["revision"])
        self._resolved_revision = info.sha
        snapshot = snapshot_download(
            self.spec["model_id"], revision=info.sha,
            allow_patterns=["*.json", "*.bin", "*.safetensors", "*.spm", "*.model"],
        )
        self._snapshot = Path(snapshot)
        self._tokenizer = AutoTokenizer.from_pretrained(self._snapshot)
        self._model = AutoModelForSeq2SeqLM.from_pretrained(self._snapshot).to(self.device).eval()
        self._torch = torch

    def translate(self, source_text: str, *, num_beams: int, max_new_tokens: int) -> str:
        """Generate raw decoded text under frozen common generation settings."""
        if self._model is None or self._tokenizer is None or self._torch is None:
            raise RuntimeError("Call load() before translate().")
        with self._torch.inference_mode():
            encoded = self._tokenizer(self.prepare_input(source_text), return_tensors="pt", truncation=True).to(self.device)
            generated = self._model.generate(**encoded, num_beams=num_beams, max_new_tokens=max_new_tokens)
            return self._tokenizer.batch_decode(generated, skip_special_tokens=True)[0].strip()

    def metadata(self) -> dict[str, object]:
        """Return evidence required to reproduce one OPUS-MT run."""
        if self._model is None or self._snapshot is None or self._resolved_revision is None:
            raise RuntimeError("Call load() before metadata().")
        artifact_bytes = sum(item.stat().st_size for item in self._snapshot.rglob("*") if item.is_file())
        return {
            "provider": "Helsinki-NLP", "architecture": "Marian encoder-decoder",
            "model_id": self.spec["model_id"], "requested_revision": self.spec["revision"],
            "resolved_revision": self._resolved_revision, "source_prefix": self.spec["source_prefix"],
            "prefix_reason": self.spec["prefix_reason"],
            "parameter_count": sum(parameter.numel() for parameter in self._model.parameters()),
            "artifact_bytes": artifact_bytes, "precision": str(next(self._model.parameters()).dtype),
        }
