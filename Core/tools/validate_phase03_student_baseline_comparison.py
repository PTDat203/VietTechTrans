"""Validate and finalize the official Student v1 versus baseline comparison."""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from build_phase03_student_baseline_comparison import (
    CONFIG_PATH,
    EVIDENCE_ROOT,
    canonical_hash,
    cohort_manifest,
    eligible_rows,
    read_json,
    read_jsonl,
    sha256_file,
    verify_source_run,
    verify_student_reference,
)
from core_mt.contracts import Direction
from core_mt.metrics import score_predictions


def write_or_verify(path: Path, value: object) -> None:
    content = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    if path.exists():
        if path.read_text(encoding="utf-8") != content:
            raise RuntimeError(f"Existing final evidence differs from validated result: {path}")
        return
    path.write_text(content, encoding="utf-8")


def validate() -> dict[str, object]:
    config = read_json(CONFIG_PATH)
    snapshot = EVIDENCE_ROOT / "protocol_snapshot" / CONFIG_PATH.name
    if not snapshot.is_file() or canonical_hash(read_json(snapshot)) != canonical_hash(config):
        raise RuntimeError("Protocol snapshot is missing or differs from the approved protocol.")
    decision = ROOT / config["finalization"]["main_core_decision"]
    if sha256_file(decision) != config["finalization"]["main_core_decision_sha256"]:
        raise RuntimeError("Frozen Main-Core decision hash does not match the approved protocol.")
    if sha256_file(EVIDENCE_ROOT / "source_snapshot" / "build_phase03_student_baseline_comparison.py") != sha256_file(
        ROOT / "tools/build_phase03_student_baseline_comparison.py"
    ):
        raise RuntimeError("Builder source snapshot differs from current builder.")

    cohort = eligible_rows(config)
    expected_manifest = {
        "comparison_id": config["comparison_id"],
        "dataset_path": config["dataset"]["path"],
        "dataset_sha256": config["dataset"]["sha256"],
        "source_rows": config["dataset"]["source_rows"],
        "eligible_rows": config["dataset"]["eligible_rows"],
        "excluded_rows": config["dataset"]["excluded_rows"],
        "cohorts": {name: cohort_manifest(cohort, Direction(name)) for name in config["directions"]},
    }
    if read_json(EVIDENCE_ROOT / "input_manifest.json") != expected_manifest:
        raise RuntimeError("Input manifest does not match the current approved cohort.")

    results: dict[str, object] = {}
    for direction_name, direction_config in config["directions"].items():
        direction = Direction(direction_name)
        baseline = direction_config["baseline"]
        historical = baseline["historical_prediction_source"]
        historical_predictions = ROOT / historical["path"]
        historical_run = ROOT / historical["run_path"]
        if sha256_file(historical_predictions) != historical["sha256"]:
            raise RuntimeError(f"{direction_name}: historical baseline prediction hash mismatch.")
        if sha256_file(historical_run) != historical["run_sha256"]:
            raise RuntimeError(f"{direction_name}: historical baseline run hash mismatch.")
        verify_source_run(read_json(historical_run), baseline, direction_name)

        output = EVIDENCE_ROOT / "baseline" / direction_name
        predictions_path = output / "predictions.jsonl"
        predictions = read_jsonl(predictions_path)
        if len(predictions) != len(cohort):
            raise RuntimeError(f"{direction_name}: baseline prediction count is invalid.")
        expected_pairs = cohort_manifest(cohort, direction)
        actual_pairs = [{"row_id": row["row_id"], "source": row["source"], "reference": row["reference"]} for row in predictions]
        if canonical_hash(actual_pairs) != expected_pairs["parallel_pairs_sha256"]:
            raise RuntimeError(f"{direction_name}: baseline prediction cohort differs from approved cohort.")
        recomputed = score_predictions(
            [str(row["prediction"]) for row in predictions], [str(row["reference"]) for row in predictions]
        )
        metrics = read_json(output / "metrics.json")
        for metric in ("chrF++", "sacreBLEU"):
            if metrics.get(metric) != recomputed.get(metric):
                raise RuntimeError(f"{direction_name}: baseline {metric} does not reproduce.")
        manifest = read_json(output / "predictions_manifest.json")
        if manifest.get("predictions_sha256") != sha256_file(predictions_path) or manifest.get("cohort") != expected_pairs:
            raise RuntimeError(f"{direction_name}: baseline manifest is invalid.")
        student = verify_student_reference(direction_config["student_reference"], cohort, direction)
        results[direction_name] = {
            "cohort_rows": len(cohort),
            "baseline": {
                "model_id": baseline["model_id"],
                "revision": baseline["revision"],
                "metrics": {metric: metrics[metric] for metric in ("chrF++", "sacreBLEU")},
            },
            "student_v1": {
                "run_id": student["run_id"],
                "checkpoint": student["checkpoint"],
                "metrics": {metric: student["metrics"][metric] for metric in ("chrF++", "sacreBLEU")},
            },
            "student_v1_minus_baseline": {
                metric: student["metrics"][metric] - metrics[metric] for metric in ("chrF++", "sacreBLEU")
            },
        }

    result = {
        "comparison_id": config["comparison_id"],
        "status": "pass",
        "method": "reuse_frozen_phase02_predictions_then_filter_to_student_cohort",
        "results": results,
        "main_core_decision_unchanged": True,
    }
    write_or_verify(EVIDENCE_ROOT / "comparison_result.json", result)
    write_or_verify(EVIDENCE_ROOT / "validation_result.json", {
        "comparison_id": config["comparison_id"],
        "status": "pass",
        "validated_rows_per_direction": len(cohort),
        "checks": [
            "approved protocol snapshot", "data and cohort identity", "historical Phase 02 baseline identity",
            "baseline metrics reproduce", "Student v1 reference hashes and metrics reproduce", "Main-Core decision hash unchanged"
        ],
    })
    write_or_verify(EVIDENCE_ROOT / "acceptance_record.json", {
        "comparison_id": config["comparison_id"],
        "status": "accepted_as_phase03_supplementary_evidence",
        "scope": "Student v1 minus its exact pretrained OPUS-MT baseline on the approved 15,252-row IT validation cohort.",
        "does_not_change": ["Phase 03 Main-Core checkpoints", "main_core_decision.json", "Phase 04 handoff"],
    })
    return result


def main() -> None:
    result = validate()
    print("PHASE03_STUDENT_BASELINE_COMPARISON=PASS " + ",".join(result["results"]))


if __name__ == "__main__":
    main()
