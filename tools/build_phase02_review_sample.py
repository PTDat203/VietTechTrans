"""Seal a human-review sample before predictions are inspected."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from core_mt.data import load_it_role
from core_mt.review import select_review_rows, write_review_manifest, write_review_template


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, required=True, help="Human-approved review count; no arbitrary default exists.")
    args = parser.parse_args()
    output = ROOT / "reviews/phase02_it_validation_review_template.csv"
    rows = select_review_rows(load_it_role("selection_validation", ROOT), args.count)
    write_review_template(output, rows)
    write_review_manifest(ROOT / "reviews/phase02_it_validation_review_manifest.json", rows)
    print(f"PHASE02_REVIEW_SAMPLE={output}; rows={len(rows)}")


if __name__ == "__main__":
    main()
