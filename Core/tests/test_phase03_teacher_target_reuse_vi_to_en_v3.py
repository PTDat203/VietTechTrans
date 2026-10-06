import importlib.util
import sys
from pathlib import Path


RUNNER_PATH = Path(__file__).resolve().parents[1] / "tools/reuse_phase03_teacher_targets_vi_to_en_v3.py"
sys.path.insert(0, str(RUNNER_PATH.parent))
SPEC = importlib.util.spec_from_file_location("phase03_vi_to_en_reuse_v3", RUNNER_PATH)
RUNNER = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(RUNNER)


def test_parent_validation_rejects_any_unapproved_structural_error():
    report = {
        "status": "blocked_target_over_limit",
        "expected_rows": 121543,
        "persisted_target_rows": 121543,
        "structural_errors": {
            "missing_dataset_row_ids": ["unexpected"],
            "duplicate_dataset_row_ids": [],
            "unexpected_dataset_row_ids": [],
            "source_hash_mismatch_dataset_row_ids": [],
            "empty_target_dataset_row_ids": [],
        },
        "over_limit_rows": [{"dataset_row_id": row_id, "student_target_tokens": tokens} for row_id, tokens in RUNNER.PARENT_OVER_LIMIT.items()],
        "student_training_permitted": False,
    }

    try:
        RUNNER.validate_parent({}, [{}] * 121543, report)
    except ValueError as error:
        assert "structural" in str(error)
    else:
        raise AssertionError("Unexpected parent structural errors must be rejected.")
