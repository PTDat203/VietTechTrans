"""Validate a completed Phase 2 manual-review sheet without scoring it."""
from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "reviews" / "phase02_it_validation_review_manifest.json"
ALLOWED = {"yes", "no", "unclear"}
REQUIRED = {"row_id", "source", "reference", "technical_tags", "prediction", "translation_error", "technical_error", "notes"}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--review", type=Path, required=True, help="Completed 50-row CSV, relative to the repository or absolute.")
    args = parser.parse_args()
    path = args.review if args.review.is_absolute() else ROOT / args.review
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames is None or not REQUIRED.issubset(reader.fieldnames):
            raise ValueError("Review sheet does not have the required Phase 2 columns.")
        rows = list(reader)
    if len(rows) != 50:
        raise ValueError(f"Review sheet must contain exactly the sealed 50 rows, found {len(rows)}.")
    row_ids = [row["row_id"] for row in rows]
    if len(set(row_ids)) != 50 or any(not value.strip() for value in row_ids):
        raise ValueError("Review row_id values must be present and unique.")
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    locked_row_ids = manifest.get("row_ids")
    if not isinstance(locked_row_ids, list) or len(locked_row_ids) != 50:
        raise ValueError("Locked Phase 2 review manifest is invalid.")
    if set(row_ids) != set(locked_row_ids):
        raise ValueError("Review sheet row_id values do not match the sealed Phase 2 review sample.")
    for index, row in enumerate(rows, 2):
        for field in ("translation_error", "technical_error"):
            if row[field] not in ALLOWED:
                raise ValueError(f"{path}:{index} {field} must be yes, no, or unclear.")
    print("PHASE_02_REVIEW=PASS")
    print(f"rows=50; review={path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
