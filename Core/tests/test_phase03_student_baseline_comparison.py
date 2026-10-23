import importlib.util
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BUILDER_PATH = ROOT / "tools/build_phase03_student_baseline_comparison.py"
SPEC = importlib.util.spec_from_file_location("phase03_student_baseline_builder", BUILDER_PATH)
BUILDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILDER)


def test_protocol_uses_exact_student_initializations_and_single_approved_cohort():
    protocol = json.loads(BUILDER.CONFIG_PATH.read_text(encoding="utf-8"))
    assert protocol["dataset"]["source_rows"] == 15253
    assert protocol["dataset"]["eligible_rows"] == 15252
    assert len(protocol["dataset"]["excluded_rows"]) == 1
    assert protocol["directions"]["en_to_vi"]["baseline"]["revision"] == "989c9fb9ec63987901022baf0182dcec3e149be6"
    assert protocol["directions"]["vi_to_en"]["baseline"]["revision"] == "c8d2853e77f5fae31124d993e0b35176b1c8914e"


def test_eligible_cohort_removes_only_the_approved_row():
    protocol = json.loads(BUILDER.CONFIG_PATH.read_text(encoding="utf-8"))
    rows = BUILDER.eligible_rows(protocol)
    excluded = protocol["dataset"]["excluded_rows"][0]["dataset_row_id"]
    assert len(rows) == 15252
    assert excluded not in {row.row_id for row in rows}


def test_builder_reuses_frozen_predictions_instead_of_loading_a_model():
    source = BUILDER_PATH.read_text(encoding="utf-8")
    assert "historical_prediction_reuse" in source
    assert "from_pretrained" not in source
    assert "it_test.jsonl" in json.dumps(json.loads(BUILDER.CONFIG_PATH.read_text(encoding="utf-8")))
