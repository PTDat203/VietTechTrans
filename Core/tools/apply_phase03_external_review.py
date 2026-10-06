"""Apply one fixed-format external VI-to-EN v3 review to its sealed review sheet."""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

from prepare_phase03_review_sheet import FIELDS, ROOT, build_review_rows


EXTERNAL_FIELDS = [
    "dataset_row_id", "source", "reference", "teacher_prediction", "student_prediction",
    "translation_error", "technical_error", "note",
]
OVERRIDES = {
    "db414723815c686f613549db4098c5b4e623b504beb0d0eda5148e0178538961": {
        "translation_error": "yes",
        "technical_error": "no",
        "note": "Student renders “hands-on with Vision Pro” as “Reality Experience with Vision Pro”, changing the intended meaning.",
    },
    "ed5d4dfe80c358fb255af9c83684a89f68477bfae02cf6e7623e28d47eacd092": {
        "translation_error": "yes",
        "technical_error": "yes",
        "note": "Student omits the product version “Series 8”.",
    },
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_external(path: Path) -> list[dict[str, str]]:
    lines = path.read_text(encoding="utf-8-sig").splitlines()
    if len(lines) < 2:
        raise ValueError("External review is empty.")
    rows = list(csv.DictReader(lines[1:], delimiter="\t"))
    if not rows or list(rows[0]) != EXTERNAL_FIELDS:
        raise ValueError("External review columns differ from the approved import format.")
    if len(rows) != 50:
        raise ValueError("External review must contain exactly 50 data rows.")
    for row in rows:
        for field in ("translation_error", "technical_error"):
            if row.get(field) not in {"yes", "no", "unclear"}:
                raise ValueError(f"External review has an invalid {field} value.")
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    args = parser.parse_args()
    source = args.input.resolve()
    if not source.is_file():
        raise FileNotFoundError(f"External review is missing: {source}")
    external_rows = read_external(source)
    expected_rows = build_review_rows("vi_to_en")
    expected_by_id = {row["dataset_row_id"]: row for row in expected_rows}
    imported_ids = [row["dataset_row_id"] for row in external_rows]
    if len(set(imported_ids)) != 50 or set(imported_ids) != set(expected_by_id):
        raise ValueError("External review IDs do not match the sealed VI-to-EN sample.")

    completed = []
    for row in external_rows:
        expected = expected_by_id[row["dataset_row_id"]]
        for field in ("source", "reference", "teacher_prediction", "student_prediction"):
            if row[field] != expected[field]:
                raise ValueError(f"External review modifies locked evidence field {field} for {row['dataset_row_id']}.")
        applied = {**row, "direction": "vi_to_en"}
        applied.update(OVERRIDES.get(row["dataset_row_id"], {}))
        completed.append({field: applied[field] for field in FIELDS})

    output = ROOT / "reviews/phase03_phase03-seqkd-vi-to-en-v3_vi_to_en_review.completed.csv"
    if output.exists():
        raise FileExistsError(f"Refusing to replace completed review evidence: {output}")
    with output.open("x", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(completed)
    record = output.with_suffix(".external_import.json")
    with record.open("x", encoding="utf-8", newline="\n") as handle:
        json.dump({
            "stage": "phase03_manual_review_external_import",
            "run_id": "phase03-seqkd-vi-to-en-v3",
            "direction": "vi_to_en",
            "external_review": {"path": str(source), "sha256": sha256_file(source), "rows": 50},
            "applied_overrides": [{"dataset_row_id": row_id, **values} for row_id, values in OVERRIDES.items()],
            "output_review": {"path": output.relative_to(ROOT).as_posix(), "sha256": sha256_file(output)},
            "created_at_utc": datetime.now(timezone.utc).isoformat(),
        }, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print(f"PHASE03_EXTERNAL_REVIEW_IMPORT=PASS review={output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
