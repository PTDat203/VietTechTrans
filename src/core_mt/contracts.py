"""Hợp đồng dữ liệu giữa shared layer và các nhánh model.

`Direction` chuẩn hóa hai phép dịch EN→VI và VI→EN. `TranslationAdapter` mô
tả tối thiểu một model sau này phải làm được: tải một checkpoint, nhận câu
nguồn với generation settings chung, và trả về prediction thô. Nhờ hợp đồng
này, runner chung không cần biết OPUS-MT dùng prefix, EnViT5 dùng text prompt
hay M2M-100 dùng language code.
"""
from __future__ import annotations

from enum import Enum
from typing import Protocol


class Direction(str, Enum):
    """Hai chiều dịch được frozen cho toàn bộ bảng CORE comparison."""
    EN_TO_VI = "en_to_vi"
    VI_TO_EN = "vi_to_en"

    @property
    def source_language(self) -> str:
        return "en" if self is Direction.EN_TO_VI else "vi"

    @property
    def target_language(self) -> str:
        return "vi" if self is Direction.EN_TO_VI else "en"


class TranslationAdapter(Protocol):
    """Giao diện bắt buộc của một nhánh model.

    Adapter chỉ xử lý khác biệt bắt buộc của checkpoint. Nó không được tự đổi
    beam size, max token, test set hoặc thực hiện domain adaptation.
    """

    model_key: str
    direction: Direction

    def load(self) -> None:
        """Load one immutable official pretrained checkpoint."""

    def translate(self, source_text: str, *, num_beams: int, max_new_tokens: int) -> str:
        """Return decoded text after framework special-token removal only."""

    def metadata(self) -> dict[str, object]:
        """Return checkpoint identity, resolved revision and runtime metadata."""
