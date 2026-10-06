"""Create an empty Phase 03 review sheet for the sealed IT Validation sample."""
from __future__ import annotations

import csv
import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
MANIFEST_PATH = ROOT / "reviews/phase02_it_validation_review_manifest.json"
REVIEW_RUNS = {
    "en_to_vi": {
        "run_id": "phase03-seqkd-en-to-vi-v2",
        "teacher_predictions": ROOT / "evidence/phase02/envit5/en_to_vi/adapted/envit5-en-to-vi-lora-v1/final-epoch-1/selection_validation/predictions.jsonl",
    },
    "vi_to_en": {
        "run_id": "phase03-seqkd-vi-to-en-v3",
        "teacher_predictions": ROOT / "evidence/phase02/envit5/vi_to_en/adapted/envit5-vi-to-en-lora-v1/final-epoch-1/selection_validation/predictions.jsonl",
    },
}
FIELDS = ["dataset_row_id", "direction", "source", "reference", "teacher_prediction", "student_prediction", "translation_error", "technical_error", "note"]


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_predictions(path: Path) -> dict[str, dict[str, Any]]:
    if not path.is_file():
        raise FileNotFoundError(f"Missing prediction evidence: {path}")
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    by_id = {row.get("row_id"): row for row in rows}
    if len(rows) != len(by_id) or any(not isinstance(row_id, str) for row_id in by_id):
        raise ValueError(f"Prediction evidence has invalid or duplicate row IDs: {path}")
    for row in rows:
        if not all(isinstance(row.get(field), str) for field in ("source", "reference", "prediction")):
            raise ValueError(f"Prediction evidence is missing source/reference/prediction: {path}")
    return by_id


def build_review_rows(direction: str = "en_to_vi") -> list[dict[str, str]]:
    settings = REVIEW_RUNS[direction]
    run_id = settings["run_id"]
    student_predictions = ROOT / f"evidence/phase03/{run_id}.running/evaluation/{direction}/selection_validation/student_predictions.jsonl"
    teacher_predictions = settings["teacher_predictions"]
    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    row_ids = manifest.get("row_ids")
    if not isinstance(row_ids, list) or len(row_ids) != 50 or len(set(row_ids)) != 50 or not all(isinstance(row_id, str) for row_id in row_ids):
        raise ValueError("The sealed Phase 02 review manifest is invalid.")
    student, teacher = load_predictions(student_predictions), load_predictions(teacher_predictions)
    missing = [row_id for row_id in row_ids if row_id not in student or row_id not in teacher]
    if missing:
        raise ValueError(f"Student or Teacher predictions do not cover the sealed review sample ({len(missing)} missing rows).")
    rows = []
    for row_id in row_ids:
        student_row, teacher_row = student[row_id], teacher[row_id]
        if student_row["source"] != teacher_row["source"] or student_row["reference"] != teacher_row["reference"]:
            raise ValueError(f"Student and Teacher prediction inputs differ for sealed row {row_id}.")
        rows.append({
            "dataset_row_id": row_id,
            "direction": direction,
            "source": student_row["source"],
            "reference": student_row["reference"],
            "teacher_prediction": teacher_row["prediction"],
            "student_prediction": student_row["prediction"],
            "translation_error": "",
            "technical_error": "",
            "note": "",
        })
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--direction", choices=tuple(REVIEW_RUNS), required=True)
    args = parser.parse_args()
    direction = args.direction
    settings = REVIEW_RUNS[direction]
    run_id = settings["run_id"]
    student_predictions = ROOT / f"evidence/phase03/{run_id}.running/evaluation/{direction}/selection_validation/student_predictions.jsonl"
    teacher_predictions = settings["teacher_predictions"]
    output = ROOT / f"reviews/phase03_{run_id}_{direction}_review.csv"
    provenance = output.with_suffix(".provenance.json")
    if output.exists() or provenance.exists():
        raise FileExistsError(f"Refusing to replace Phase 03 review evidence: {output}")
    rows = build_review_rows(direction)
    with output.open("x", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)
    with provenance.open("x", encoding="utf-8", newline="\n") as handle:
        json.dump({
            "stage": "phase03_manual_review_sheet",
            "run_id": run_id,
            "direction": direction,
            "sealed_manifest": {"path": MANIFEST_PATH.relative_to(ROOT).as_posix(), "sha256": sha256_file(MANIFEST_PATH), "rows": 50},
            "student_predictions": {"path": student_predictions.relative_to(ROOT).as_posix(), "sha256": sha256_file(student_predictions)},
            "teacher_predictions": {"path": teacher_predictions.relative_to(ROOT).as_posix(), "sha256": sha256_file(teacher_predictions)},
            "review_fields": ["translation_error", "technical_error", "note"],
        }, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print(f"PHASE03_REVIEW_SHEET={output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
