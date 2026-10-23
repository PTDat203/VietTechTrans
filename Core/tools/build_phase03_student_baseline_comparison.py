"""Build official Phase 03 Student v1 versus pretrained-baseline evidence.

This tool does not run a model.  It reuses the frozen Phase 02 OPUS-MT
predictions, removes the single row excluded by the fixed Student input limit,
and scores the resulting 15,252-row cohort.  Keeping that historical output
avoids changing the baseline while making the Student-v1 comparison auditable.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
from pathlib import Path
from tempfile import TemporaryDirectory

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from core_mt.contracts import Direction
from core_mt.data import ParallelExample, load_it_role
from core_mt.metrics import score_predictions

CONFIG_PATH = ROOT / "configs/phase03_supplementary/phase03_student_v1_baseline_comparison_v1.json"
EVIDENCE_ROOT = ROOT / "evidence/phase03/supplementary/phase03_student_v1_baseline_comparison_v1"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_hash(value: object) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def read_json(path: Path) -> dict[str, object]:
    return json.loads(path.read_text(encoding="utf-8"))


def read_jsonl(path: Path) -> list[dict[str, object]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    content = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    if path.exists():
        if path.read_text(encoding="utf-8") != content:
            raise RuntimeError(f"Refusing to overwrite existing evidence: {path}")
        return
    path.write_text(content, encoding="utf-8")


def write_jsonl(path: Path, rows: list[dict[str, object]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    content = "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows)
    if path.exists():
        if path.read_text(encoding="utf-8") != content:
            raise RuntimeError(f"Refusing to overwrite existing evidence: {path}")
        return
    path.write_text(content, encoding="utf-8")


def eligible_rows(config: dict[str, object], root: Path = ROOT) -> list[ParallelExample]:
    dataset = load_it_role("selection_validation", root)
    dataset_config = config["dataset"]
    excluded = {item["dataset_row_id"] for item in dataset_config["excluded_rows"]}
    rows = [row for row in dataset.rows if row.row_id not in excluded]
    if len(dataset.rows) != dataset_config["source_rows"] or len(rows) != dataset_config["eligible_rows"]:
        raise RuntimeError("The current validation cohort does not match the approved protocol.")
    return rows


def cohort_manifest(rows: list[ParallelExample], direction: Direction) -> dict[str, object]:
    pairs = []
    for row in rows:
        source, reference = row.for_direction(direction)
        pairs.append({"row_id": row.row_id, "source": source, "reference": reference})
    return {
        "direction": direction.value,
        "rows": len(rows),
        "row_ids_sha256": canonical_hash([pair["row_id"] for pair in pairs]),
        "parallel_pairs_sha256": canonical_hash(pairs),
    }


def verify_source_run(run: dict[str, object], baseline: dict[str, object], direction: str) -> None:
    adapter = run.get("adapter", {})
    if run.get("stage") != "pretrained" or run.get("dataset_role") != "selection_validation":
        raise RuntimeError("Historical baseline run is not a Phase 02 pretrained selection-validation run.")
    expected = {
        "model_id": baseline["model_id"],
        "direction": direction,
        "checkpoint_revision": baseline["revision"],
        "generation_policy": baseline["generation_policy"],
    }
    for key, value in expected.items():
        if adapter.get(key) != value:
            raise RuntimeError(f"Historical baseline run has unexpected {key}: {adapter.get(key)!r}")
    if adapter.get("checkpoint_generation_config", {}).get("num_beams") != baseline["num_beams"]:
        raise RuntimeError("Historical baseline run has unexpected num_beams.")


def select_historical_predictions(
    source_rows: list[dict[str, object]], cohort: list[ParallelExample], direction: Direction
) -> list[dict[str, object]]:
    indexed = {row.get("row_id"): row for row in source_rows}
    if len(indexed) != len(source_rows):
        raise RuntimeError("Historical baseline predictions contain duplicate row_id values.")
    selected = []
    for item in cohort:
        record = indexed.get(item.row_id)
        if record is None:
            raise RuntimeError(f"Historical baseline prediction is missing row {item.row_id}.")
        source, reference = item.for_direction(direction)
        if record.get("source") != source or record.get("reference") != reference:
            raise RuntimeError(f"Historical baseline text does not match current data for row {item.row_id}.")
        selected.append(record)
    return selected


def verify_student_reference(reference: dict[str, object], cohort: list[ParallelExample], direction: Direction) -> dict[str, object]:
    metrics_path = ROOT / reference["metrics_path"]
    predictions_path = ROOT / reference["predictions_path"]
    if sha256_file(metrics_path) != reference["metrics_sha256"]:
        raise RuntimeError("Student metrics hash does not match the approved protocol.")
    if sha256_file(predictions_path) != reference["predictions_sha256"]:
        raise RuntimeError("Student predictions hash does not match the approved protocol.")
    metrics = read_json(metrics_path)
    predictions = read_jsonl(predictions_path)
    selected = select_historical_predictions(predictions, cohort, direction)
    if len(predictions) != len(selected):
        raise RuntimeError("Student predictions contain rows outside the approved cohort.")
    if metrics.get("rows") != len(cohort):
        raise RuntimeError("Student metric row count does not match the approved cohort.")
    recomputed = score_predictions(
        [str(row["prediction"]) for row in selected], [str(row["reference"]) for row in selected]
    )
    for key in ("chrF++", "sacreBLEU"):
        if metrics.get(key) != recomputed.get(key):
            raise RuntimeError(f"Student {key} does not reproduce from its frozen predictions.")
    return {
        "run_id": reference["run_id"],
        "checkpoint": reference["checkpoint"],
        "metrics_path": reference["metrics_path"],
        "metrics_sha256": reference["metrics_sha256"],
        "predictions_path": reference["predictions_path"],
        "predictions_sha256": reference["predictions_sha256"],
        "metrics": {key: metrics[key] for key in ("rows", "chrF++", "sacreBLEU", "metric_config")},
    }


def snapshot_source(path: Path) -> None:
    target = EVIDENCE_ROOT / "source_snapshot" / path.name
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        if sha256_file(target) != sha256_file(path):
            raise RuntimeError(f"Source snapshot differs from current file: {path}")
        return
    shutil.copy2(path, target)


def build(direction_name: str) -> None:
    config = read_json(CONFIG_PATH)
    direction = Direction(direction_name)
    direction_config = config["directions"][direction_name]
    baseline = direction_config["baseline"]
    historical = baseline["historical_prediction_source"]
    cohort = eligible_rows(config)

    source_predictions_path = ROOT / historical["path"]
    source_run_path = ROOT / historical["run_path"]
    if sha256_file(source_predictions_path) != historical["sha256"]:
        raise RuntimeError("Historical baseline prediction hash does not match the approved protocol.")
    if sha256_file(source_run_path) != historical["run_sha256"]:
        raise RuntimeError("Historical baseline run hash does not match the approved protocol.")
    source_run = read_json(source_run_path)
    verify_source_run(source_run, baseline, direction_name)
    selected = select_historical_predictions(read_jsonl(source_predictions_path), cohort, direction)
    metrics = score_predictions(
        [str(row["prediction"]) for row in selected], [str(row["reference"]) for row in selected]
    )
    manifest = cohort_manifest(cohort, direction)

    write_json(EVIDENCE_ROOT / "protocol_snapshot" / CONFIG_PATH.name, config)
    snapshot_source(Path(__file__))
    write_json(EVIDENCE_ROOT / "input_manifest.json", {
        "comparison_id": config["comparison_id"],
        "dataset_path": config["dataset"]["path"],
        "dataset_sha256": config["dataset"]["sha256"],
        "source_rows": config["dataset"]["source_rows"],
        "eligible_rows": config["dataset"]["eligible_rows"],
        "excluded_rows": config["dataset"]["excluded_rows"],
        "cohorts": {name: cohort_manifest(cohort, Direction(name)) for name in config["directions"]},
    })
    write_json(EVIDENCE_ROOT / "comparison_scope.json", {
        "comparison_id": config["comparison_id"],
        "allowed_dataset_role": config["dataset"]["role"],
        "prohibited_datasets": config["prohibited_datasets"],
        "main_core_decision": config["finalization"],
        "baseline_method": "reuse_frozen_phase02_predictions_then_filter_to_student_cohort",
    })

    output = EVIDENCE_ROOT / "baseline" / direction_name
    write_jsonl(output / "predictions.jsonl", selected)
    write_json(output / "predictions_manifest.json", {
        "rows": len(selected),
        "predictions_sha256": sha256_file(output / "predictions.jsonl"),
        "cohort": manifest,
        "historical_prediction_source": historical,
    })
    write_json(output / "metrics.json", {
        "dataset_role": config["dataset"]["role"],
        "rows": len(selected),
        **metrics,
        "metric_config": config["metrics"],
    })
    write_json(output / "run.json", {
        "stage": "phase03_supplementary_baseline_reuse",
        "direction": direction_name,
        "mode": "historical_prediction_reuse",
        "reason": "Reuse frozen Phase 02 baseline output and filter only the row excluded by Student v1 input capacity.",
        "protocol_sha256": sha256_file(CONFIG_PATH),
        "runner_sha256": sha256_file(Path(__file__)),
        "historical_prediction_source": historical,
        "baseline": {key: baseline[key] for key in ("model_id", "revision", "input_prefix", "generation_policy", "num_beams")},
        "cohort": manifest,
    })
    write_json(EVIDENCE_ROOT / "student_reference" / f"{direction_name}.json",
               verify_student_reference(direction_config["student_reference"], cohort, direction))
    print(f"PHASE03_STUDENT_BASELINE_BUILD=PASS direction={direction_name} rows={len(selected)}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--direction", choices=[item.value for item in Direction], required=True)
    args = parser.parse_args()
    build(args.direction)


if __name__ == "__main__":
    main()
