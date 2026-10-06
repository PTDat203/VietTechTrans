import importlib.util
import sys
from pathlib import Path


RUNNER_PATH = Path(__file__).resolve().parents[1] / "tools/prepare_phase03_review_sheet.py"
sys.path.insert(0, str(RUNNER_PATH.parent))
SPEC = importlib.util.spec_from_file_location("phase03_review", RUNNER_PATH)
RUNNER = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(RUNNER)


def test_review_sheet_uses_exactly_the_sealed_sample_and_both_predictions():
    rows = RUNNER.build_review_rows()

    assert len(rows) == 50
    assert len({row["dataset_row_id"] for row in rows}) == 50
    assert all(row["direction"] == "en_to_vi" for row in rows)
    assert all(row["teacher_prediction"] and row["student_prediction"] for row in rows)
