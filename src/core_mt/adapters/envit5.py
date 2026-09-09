"""Adapter EnViT5: phần riêng của checkpoint T5 song ngữ VietAI.

EnViT5 là một checkpoint dùng cho cả EN→VI và VI→EN. Chiều dịch được chỉ ra
bằng tiền tố input `en:` hoặc `vi:` theo model card, khác với OPUS-MT dùng hai
checkpoint Marian. Decoder có thể trả lại tiền tố target; adapter chỉ gỡ đúng
token giao diện này trước khi runner chấm, không làm sạch nội dung bản dịch.
"""
from __future__ import annotations

import json
from pathlib import Path

from ..contracts import Direction


class EnViT5Adapter:
    """Bọc `VietAI/envit5-translation` theo interface TranslationAdapter chung."""

    model_key = "envit5"

    def __init__(self, direction: Direction, config_path: Path, device: str) -> None:
        raw = json.loads(config_path.read_text(encoding="utf-8"))
        if raw.get("model_key") != self.model_key:
            raise ValueError("EnViT5 adapter received an incompatible config.")
        self.direction, self.device = direction, device
        self._project_root = config_path.resolve().parent.parent
        self.spec = raw["directions"][direction.value]
        self._model = self._tokenizer = self._torch = None
        self._resolved_revision: str | None = None
        self._snapshot: Path | None = None

    def prepare_input(self, source_text: str) -> str:
        """Gắn language/task prefix bắt buộc của model card vào câu nguồn."""
        if not isinstance(source_text, str) or not source_text.strip():
            raise ValueError("EnViT5 source text must be a non-empty string.")
        return f"{self.spec['source_prefix']} {source_text}"

    def normalize_output(self, decoded_text: str) -> str:
        """Bỏ duy nhất target tag mà giao diện EnViT5 có thể sinh lại."""
        output = decoded_text.strip()
        prefix = self.spec["target_prefix"]
        return output[len(prefix):].strip() if output.startswith(prefix) else output

    def load(self) -> None:
        """Resolve revision, tải snapshot chính thức và load T5 ở chế độ eval."""
        try:
            import torch
            from huggingface_hub import HfApi, snapshot_download
            from transformers import AutoModelForSeq2SeqLM, AutoTokenizer
        except ImportError as error:
            raise RuntimeError("Install EnViT5 dependencies from requirements.txt before loading the model.") from error
        info = HfApi().model_info(self.spec["model_id"], revision=self.spec["revision"])
        self._resolved_revision = info.sha
        # Windows thường không cho process thường tạo symlink trong HF cache.
        # local_dir + False bắt buộc copy file thật vào workspace dự án để run
        # không phụ thuộc Developer Mode hoặc quyền Administrator.
        local_snapshot = self._project_root / "models" / "envit5" / info.sha
        snapshot = snapshot_download(
            self.spec["model_id"], revision=info.sha,
            allow_patterns=["*.json", "*.bin", "*.safetensors", "*.model", "*.txt"],
            local_dir=local_snapshot, local_dir_use_symlinks=False,
        )
        self._snapshot = Path(snapshot)
        self._tokenizer = AutoTokenizer.from_pretrained(self._snapshot)
        self._model = AutoModelForSeq2SeqLM.from_pretrained(self._snapshot).to(self.device).eval()
        self._torch = torch

    def translate(self, source_text: str, *, num_beams: int, max_new_tokens: int) -> str:
        """Sinh bản dịch với generation settings frozen của shared protocol."""
        if self._model is None or self._tokenizer is None or self._torch is None:
            raise RuntimeError("Call load() before translate().")
        with self._torch.inference_mode():
            encoded = self._tokenizer(self.prepare_input(source_text), return_tensors="pt", truncation=True).to(self.device)
            generated = self._model.generate(**encoded, num_beams=num_beams, max_new_tokens=max_new_tokens)
            decoded = self._tokenizer.batch_decode(generated, skip_special_tokens=True)[0]
        return self.normalize_output(decoded)

    def metadata(self) -> dict[str, object]:
        """Trả evidence checkpoint và language-prefix cần tái lập một run."""
        if self._model is None or self._snapshot is None or self._resolved_revision is None:
            raise RuntimeError("Call load() before metadata().")
        artifact_bytes = sum(item.stat().st_size for item in self._snapshot.rglob("*") if item.is_file())
        return {
            "provider": "VietAI", "architecture": "T5 encoder-decoder",
            "model_id": self.spec["model_id"], "requested_revision": self.spec["revision"],
            "resolved_revision": self._resolved_revision, "source_prefix": self.spec["source_prefix"],
            "target_prefix": self.spec["target_prefix"], "prefix_reason": self.spec["prefix_reason"],
            "parameter_count": sum(parameter.numel() for parameter in self._model.parameters()),
            "artifact_bytes": artifact_bytes, "precision": str(next(self._model.parameters()).dtype),
        }
