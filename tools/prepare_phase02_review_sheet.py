"""Ghép một mẫu review đã khóa với prediction IT Validation của một checkpoint.

Điểm bắt đầu là template 50 dòng và file prediction đã có. Script chỉ tạo sheet
trống để người review điền; không chấm lỗi hoặc chọn mẫu theo prediction.
"""
from __future__ import annotations

import argparse
import csv
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--direction", choices=("en_to_vi", "vi_to_en"), required=True)
    parser.add_argument("--checkpoint-id", required=True, help="Stable, filesystem-safe identifier of the evaluated adapted checkpoint.")
    parser.add_argument("--predictions", type=Path, required=True, help="JSONL with row_id and prediction.")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", args.checkpoint_id):
        raise ValueError("checkpoint-id must contain only letters, digits, dot, underscore, or hyphen.")
    sample_path = ROOT / "reviews/phase02_it_validation_review_template.csv"
    if not sample_path.is_file():
        raise FileNotFoundError("The sealed review sample is missing.")
    predictions = {}
    for line in args.predictions.read_text(encoding="utf-8").splitlines():
        row = json.loads(line)
        if not isinstance(row.get("row_id"), str) or not isinstance(row.get("prediction"), str):
            raise ValueError("Each prediction row needs string row_id and prediction.")
        predictions[row["row_id"]] = row["prediction"]
    with sample_path.open(newline="", encoding="utf-8") as handle:
        sample = list(csv.DictReader(handle))
    missing = [row["row_id"] for row in sample if row["row_id"] not in predictions]
    if missing:
        raise ValueError(f"Predictions do not cover the sealed review sample ({len(missing)} rows missing).")
    output = ROOT / "reviews" / f"phase02_{args.model}_{args.direction}_{args.checkpoint_id}_review.csv"
    if output.exists():
        raise FileExistsError(f"Refusing to replace review evidence: {output}")
    fields = ["row_id", "source", "reference", "technical_tags", "prediction", "translation_error", "technical_error", "notes"]
    with output.open("x", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for row in sample:
            source, reference = (row["en"], row["vi"]) if args.direction == "en_to_vi" else (row["vi"], row["en"])
            writer.writerow({"row_id": row["row_id"], "source": source, "reference": reference, "technical_tags": row["technical_tags"], "prediction": predictions[row["row_id"]], "translation_error": "", "technical_error": "", "notes": ""})
    print(f"PHASE02_REVIEW_SHEET={output}")


if __name__ == "__main__":
    main()
