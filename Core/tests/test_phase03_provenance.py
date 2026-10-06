import importlib.util
import sys
from pathlib import Path


RUNNER_PATH = Path(__file__).resolve().parents[1] / "tools/validate_phase03_provenance.py"
sys.path.insert(0, str(RUNNER_PATH.parent))
SPEC = importlib.util.spec_from_file_location("phase03_provenance", RUNNER_PATH)
RUNNER = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(RUNNER)


def test_frozen_phase03_lineage_matches_artifacts_and_declared_limits():
    limits = RUNNER.validate_all()

    assert "source_snapshot/run_phase03_student_training.py" in limits["en_to_vi"]
    assert limits["vi_to_en"] == ["input_manifest.json", "runtime.json"]
