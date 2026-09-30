"""Build and summarize the pre-registered manual-review evidence."""
from __future__ import annotations

import csv
import hashlib
import json
from pathlib import Path

from .data import Phase02Dataset


def select_review_rows(dataset: Phase02Dataset, count: int) -> tuple[dict[str, object], ...]:
    if count < 1:
        raise ValueError("Review count must be supplied and positive.")
    tagged = [row for row in dataset.rows if row.technical_tags]
    if count > len(tagged):
        raise ValueError(f"Requested {count} review rows but only {len(tagged)} tagged validation rows exist.")
    rank = lambda row: hashlib.sha256(f"phase02-review:{row.row_id}".encode()).hexdigest()
    # Path and command are rare but can make instructions unusable when altered.
    # Include their available rows first; the rest is deterministic from tagged rows.
    priority = sorted((row for row in tagged if {"has_path", "has_command"} & set(row.technical_tags)), key=rank)
    selected = priority[:count]
    selected_ids = {row.row_id for row in selected}
    remaining = sorted((row for row in tagged if row.row_id not in selected_ids), key=rank)
    selected.extend(remaining[:count - len(selected)])
    return tuple({"row_id": row.row_id, "en": row.en, "vi": row.vi, "technical_tags": ";".join(row.technical_tags), "translation_error": "", "technical_error": "", "notes": ""} for row in selected)


def write_review_template(path: Path, rows: tuple[dict[str, object], ...]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=["row_id", "en", "vi", "technical_tags", "translation_error", "technical_error", "notes"])
        writer.writeheader(); writer.writerows(rows)


def write_review_manifest(path: Path, rows: tuple[dict[str, object], ...]) -> None:
    """Store the sample rule and IDs before any model output is reviewed."""
    payload = {
        "rows": len(rows),
        "selection_rule": "All available tagged validation rows containing path or command are prioritized; remaining rows are selected deterministically by SHA-256(phase02-review:row_id).",
        "row_ids": [str(row["row_id"]) for row in rows],
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
