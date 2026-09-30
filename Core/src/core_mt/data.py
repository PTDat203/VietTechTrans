"""Data boundary for Phase 2.

IT Train is the only adaptation input. IT Validation and General Validation
are selection inputs. IT Test and General Test stay unavailable to the normal
runner until a frozen Core-MT decision authorizes final reporting.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from .contracts import Direction


@dataclass(frozen=True)
class ParallelExample:
    row_id: str
    en: str
    vi: str
    technical_tags: tuple[str, ...] = ()

    def for_direction(self, direction: Direction) -> tuple[str, str]:
        return (self.en, self.vi) if direction is Direction.EN_TO_VI else (self.vi, self.en)


@dataclass(frozen=True)
class Phase02Dataset:
    role: str
    path: Path
    rows: tuple[ParallelExample, ...]


_IT_ROLES = {"adaptation_train": "train.jsonl", "selection_validation": "validation.jsonl"}


def _load_rows(path: Path, *, require_tags: bool) -> tuple[ParallelExample, ...]:
    if not path.is_file():
        raise FileNotFoundError(f"Missing Phase 2 input: {path}")
    rows = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        raw = json.loads(line)
        raw_identifier = raw.get("dataset_row_id", raw.get("row_id"))
        # IT data uses string IDs while the sealed FLORES General Test uses
        # numeric row_id values. Both identify a row; normalize only the ID
        # representation and leave the parallel text untouched.
        identifier = str(raw_identifier) if isinstance(raw_identifier, int) and not isinstance(raw_identifier, bool) else raw_identifier
        en, vi = raw.get("en_clean", raw.get("en")), raw.get("vi_clean", raw.get("vi"))
        tags = raw.get("technical_tags", [])
        if not all(isinstance(value, str) and value.strip() for value in (identifier, en, vi)):
            raise ValueError(f"Invalid parallel pair at {path}:{number}")
        if require_tags and not isinstance(tags, list):
            raise ValueError(f"Invalid technical_tags at {path}:{number}")
        rows.append(ParallelExample(identifier, en, vi, tuple(tags) if isinstance(tags, list) else ()))
    if not rows:
        raise ValueError(f"Empty Phase 2 input: {path}")
    return tuple(rows)


def load_it_role(role: str, root: Path) -> Phase02Dataset:
    if role not in _IT_ROLES:
        raise ValueError("Only adaptation_train and selection_validation are available in Phase 2.")
    path = root / "data/processed/it_en_vi" / _IT_ROLES[role]
    return Phase02Dataset(role, path, _load_rows(path, require_tags=True))


def load_general_validation(root: Path) -> Phase02Dataset:
    path = root / "data/processed/general_validation_en_vi/general_validation.jsonl"
    return Phase02Dataset("general_validation", path, _load_rows(path, require_tags=False))


def load_it_test_for_final_reporting(root: Path) -> Phase02Dataset:
    """Load IT Test only for the gated final-reporting runner."""
    path = root / "data/processed/it_en_vi/it_test.jsonl"
    return Phase02Dataset("it_test", path, _load_rows(path, require_tags=True))


def load_general_test_for_final_reporting(root: Path) -> Phase02Dataset:
    """Load General Test only for the gated final-reporting runner."""
    path = root / "data/processed/general_test_flores200_devtest/general_test.jsonl"
    return Phase02Dataset("general_test", path, _load_rows(path, require_tags=False))
