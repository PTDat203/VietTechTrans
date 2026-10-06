"""Validate a completed EN-to-VI Phase 03 manual-review sheet without scoring it."""
from __future__ import annotations

import argparse
import csv
from pathlib import Path

from prepare_phase03_review_sheet import FIELDS, ROOT, build_review_rows


ALLOWED = {"yes", "no", "unclear"}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--review", type=Path, required=True, help="Completed CSV, relative to the repository or absolute.")
    parser.add_argument("--direction", choices=("en_to_vi", "vi_to_en"), required=True)
    args = parser.parse_args()
    path = args.review if args.review.is_absolute() else ROOT / args.review
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames != FIELDS:
            raise ValueError("Review sheet columns differ from the required Phase 03 review contract.")
        rows = list(reader)
    expected = build_review_rows(args.direction)
    if len(rows) != 50:
        raise ValueError(f"Review sheet must contain exactly the sealed 50 rows, found {len(rows)}.")
    expected_by_id = {row["dataset_row_id"]: row for row in expected}
    reviewed_ids = [row["dataset_row_id"] for row in rows]
    if len(set(reviewed_ids)) != 50 or set(reviewed_ids) != set(expected_by_id):
        raise ValueError("Review row IDs do not match the sealed Phase 02 review sample.")
    for index, row in enumerate(rows, 2):
        expected_row = expected_by_id[row["dataset_row_id"]]
        for field in ("direction", "source", "reference", "teacher_prediction", "student_prediction"):
            if row[field] != expected_row[field]:
                raise ValueError(f"{path}:{index} modifies locked evidence column {field}.")
        for field in ("translation_error", "technical_error"):
            if row[field] not in ALLOWED:
                raise ValueError(f"{path}:{index} {field} must be yes, no, or unclear.")
    print("PHASE03_REVIEW=PASS")
    print(f"rows=50; review={path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
