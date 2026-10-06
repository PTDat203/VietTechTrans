import importlib.util
import sys
from pathlib import Path


RUNNER_PATH = Path(__file__).resolve().parents[1] / "tools/reuse_phase03_teacher_targets.py"
sys.path.insert(0, str(RUNNER_PATH.parent))
SPEC = importlib.util.spec_from_file_location("phase03_reuse", RUNNER_PATH)
RUNNER = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(RUNNER)


def test_reuse_policy_rejects_broad_or_duplicate_exclusions():
    policy = {
        "source_rows": 121547,
        "eligible_rows": 121544,
        "excluded_rows": [
            {"dataset_row_id": "one", "reason": "empty_teacher_target_in_parent_run"},
            {"dataset_row_id": "one", "reason": "empty_teacher_target_in_parent_run"},
            {"dataset_row_id": "three", "reason": "empty_teacher_target_in_parent_run"},
        ],
    }

    try:
        RUNNER.validate_policy(policy)
    except ValueError as error:
        assert "invalid or duplicate" in str(error)
    else:
        raise AssertionError("Duplicate exclusions must be rejected.")


def test_parent_validation_rejects_any_unapproved_structural_error():
    policy = {
        "excluded_rows": [
            {"dataset_row_id": "a", "reason": "empty_teacher_target_in_parent_run"},
            {"dataset_row_id": "b", "reason": "empty_teacher_target_in_parent_run"},
            {"dataset_row_id": "c", "reason": "empty_teacher_target_in_parent_run"},
        ],
        "parent_run": {},
    }
    report = {
        "status": "failed_structural_integrity",
        "expected_rows": 121547,
        "persisted_target_rows": 121547,
        "structural_errors": {
            "empty_target_dataset_row_ids": ["a", "b", "c"],
            "missing_dataset_row_ids": ["unexpected"],
            "duplicate_dataset_row_ids": [],
            "unexpected_dataset_row_ids": [],
            "source_hash_mismatch_dataset_row_ids": [],
        },
        "over_limit_rows": [],
        "student_training_permitted": False,
    }

    try:
        RUNNER.validate_parent(policy, [{}] * 121547, report)
    except ValueError as error:
        assert "additional structural error" in str(error)
    else:
        raise AssertionError("Unexpected parent structural errors must be rejected.")
