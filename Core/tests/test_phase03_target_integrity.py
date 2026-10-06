import importlib.util
from pathlib import Path


RUNNER_PATH = Path(__file__).resolve().parents[1] / "tools/run_phase03_teacher_targets.py"
SPEC = importlib.util.spec_from_file_location("phase03_targets", RUNNER_PATH)
RUNNER = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(RUNNER)


def test_target_integrity_collects_every_over_limit_row_and_blocks_training():
    expected = [
        {"dataset_row_id": "a", "source": "one", "source_sha256": RUNNER.source_hash("one")},
        {"dataset_row_id": "b", "source": "two", "source_sha256": RUNNER.source_hash("two")},
        {"dataset_row_id": "c", "source": "three", "source_sha256": RUNNER.source_hash("three")},
    ]
    targets = [
        {"dataset_row_id": "a", "source_sha256": RUNNER.source_hash("one"), "target": "ok"},
        {"dataset_row_id": "b", "source_sha256": RUNNER.source_hash("two"), "target": "longer"},
        {"dataset_row_id": "c", "source_sha256": RUNNER.source_hash("three"), "target": "very long"},
    ]
    report = RUNNER.validate_targets(expected, targets, len, 3)

    assert report["status"] == "blocked_target_over_limit"
    assert report["student_training_permitted"] is False
    assert report["over_limit_rows"] == [
        {"dataset_row_id": "b", "student_target_tokens": 6},
        {"dataset_row_id": "c", "student_target_tokens": 9},
    ]


def test_target_integrity_reports_structural_errors_without_mutating_targets():
    expected = [{"dataset_row_id": "a", "source": "one", "source_sha256": RUNNER.source_hash("one")}]
    targets = [
        {"dataset_row_id": "a", "source_sha256": "wrong", "target": "ok"},
        {"dataset_row_id": "a", "source_sha256": RUNNER.source_hash("one"), "target": "ok"},
        {"dataset_row_id": "other", "source_sha256": "x", "target": ""},
    ]
    report = RUNNER.validate_targets(expected, targets, len, 512)

    assert report["status"] == "failed_structural_integrity"
    assert report["student_training_permitted"] is False
    assert report["structural_errors"]["duplicate_dataset_row_ids"] == ["a"]
    assert report["structural_errors"]["unexpected_dataset_row_ids"] == ["other"]
    assert report["structural_errors"]["source_hash_mismatch_dataset_row_ids"] == ["a"]
