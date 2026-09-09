"""Cổng dữ liệu cho baseline trước domain adaptation.

Phase 06 chỉ được nhìn General Test và IT Test. Thay vì trông chờ người chạy
tự nhớ quy tắc này, `_RELEASED_PATHS` là whitelist: tên `train` và `validation`
không có loader công khai. Module còn chuẩn hóa schema khác nhau của FLORES và
IT corpus về một `TestExample` có ID, English và Vietnamese.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from .contracts import Direction


@dataclass(frozen=True)
class TestExample:
    """Một cặp dịch chuẩn, độc lập với schema JSONL gốc của từng test set."""
    row_id: str
    en: str
    vi: str

    def for_direction(self, direction: Direction) -> tuple[str, str]:
        """Trả về `(source, reference)` đúng theo direction, không sửa text."""
        return (self.en, self.vi) if direction is Direction.EN_TO_VI else (self.vi, self.en)


@dataclass(frozen=True)
class LockedEvaluationSet:
    """Tập evaluation đã sealed, chỉ có vai trò chấm cuối cùng."""
    name: str
    path: Path
    rows: tuple[TestExample, ...]

    def examples(self, direction: Direction) -> tuple[tuple[str, str, str], ...]:
        """Trả `(row_id, source, reference)` cho shared inference engine sau này."""
        return tuple((row.row_id, *row.for_direction(direction)) for row in self.rows)


_RELEASED_PATHS = {
    "general_test": Path("data/processed/general_test_flores200_devtest_v1/general_test.jsonl"),
    "it_test": Path("data/processed/it_en_vi_v1/it_test.jsonl"),
}


def load_locked_evaluation_set(name: str, root: Path) -> LockedEvaluationSet:
    """Load một test set trong whitelist; train và validation bị từ chối.

    `general_test` dùng các trường FLORES `row_id/en/vi`; `it_test` dùng
    `dataset_row_id/en_clean/vi_clean`. Sau khi qua hàm này, adapter không cần
    biết khác biệt đó và không có lý do để tự đọc JSONL theo cách riêng.
    """
    if name not in _RELEASED_PATHS:
        raise ValueError(f"Unsupported evaluation set: {name}")
    path = root / _RELEASED_PATHS[name]
    if not path.is_file():
        raise FileNotFoundError(f"Missing sealed evaluation data: {path}")
    rows: list[TestExample] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            raise ValueError(f"Blank line in sealed evaluation data: {path}:{line_number}")
        raw = json.loads(line)
        if name == "general_test":
            required = ("row_id", "en", "vi")
        else:
            required = ("dataset_row_id", "en_clean", "vi_clean")
        if not all(key in raw for key in required):
            raise ValueError(f"Invalid {name} schema at {path}:{line_number}")
        if name == "general_test":
            row_id, en, vi = raw["row_id"], raw["en"], raw["vi"]
        else:
            row_id, en, vi = raw["dataset_row_id"], raw["en_clean"], raw["vi_clean"]
        if not str(row_id).strip() or not all(isinstance(value, str) and value.strip() for value in (en, vi)):
            raise ValueError(f"Invalid {name} value at {path}:{line_number}")
        rows.append(TestExample(str(row_id), en, vi))
    if not rows:
        raise ValueError(f"Empty evaluation set: {path}")
    return LockedEvaluationSet(name=name, path=path, rows=tuple(rows))
