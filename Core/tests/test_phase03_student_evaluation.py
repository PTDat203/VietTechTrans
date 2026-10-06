import importlib.util
import sys
from pathlib import Path

from core_mt.contracts import Direction


RUNNER_PATH = Path(__file__).resolve().parents[1] / "tools/run_phase03_student_evaluation.py"
sys.path.insert(0, str(RUNNER_PATH.parent))
SPEC = importlib.util.spec_from_file_location("phase03_evaluation", RUNNER_PATH)
RUNNER = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(RUNNER)


def test_only_validation_roles_are_permitted():
    try:
        RUNNER.load_permitted_dataset("it_test")
    except ValueError as error:
        assert "only selection_validation or general_validation" in str(error)
    else:
        raise AssertionError("Final-test data must not be accepted by the Phase 03 evaluator.")


def test_teacher_and_student_comparison_cohort_has_the_fixed_exclusion():
    amendment = RUNNER.read_json(RUNNER.EVALUATION_RUNS["en_to_vi"]["amendment_path"])
    dataset = RUNNER.load_permitted_dataset("selection_validation")
    eligible_rows, teacher_rows = RUNNER.load_comparison_rows(dataset, amendment["datasets"]["selection_validation"], Direction.EN_TO_VI)

    assert len(dataset.rows) == 15253
    assert len(eligible_rows) == len(teacher_rows) == 15252
    assert "948273d4ab1d4f0978ca1ad3b664b36ecc0e78b704514d7598e4deafd606e5ad" not in {row.row_id for row in eligible_rows}


def test_vi_to_en_comparison_uses_the_same_excluded_row_with_vi_source_length():
    amendment = RUNNER.read_json(RUNNER.EVALUATION_RUNS["vi_to_en"]["amendment_path"])
    dataset = RUNNER.load_permitted_dataset("selection_validation")
    eligible_rows, teacher_rows = RUNNER.load_comparison_rows(dataset, amendment["datasets"]["selection_validation"], Direction.VI_TO_EN)

    assert len(eligible_rows) == len(teacher_rows) == 15252
    assert amendment["datasets"]["selection_validation"]["excluded_rows"][0]["student_input_tokens"] == 935
