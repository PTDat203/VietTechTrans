from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
import sys

sys.path.insert(0, str(ROOT / "src"))

from core_mt.adaptation import ensure_completed_experiment_record, envit5_source_text, envit5_target_text, validate_identifier
from core_mt.baseline import load_candidate_spec
from core_mt.contracts import Direction


class Phase02AdaptationTests(unittest.TestCase):
    def test_identifiers_reject_paths(self) -> None:
        self.assertEqual(validate_identifier("envit5-en-vi-01", field="experiment-id"), "envit5-en-vi-01")
        with self.assertRaises(ValueError):
            validate_identifier("../outside", field="experiment-id")

    def test_envit5_uses_checkpoint_language_control_for_train_pairs(self) -> None:
        spec = load_candidate_spec(ROOT / "configs/phase02_models.json", "envit5", Direction.EN_TO_VI)
        self.assertEqual(envit5_source_text(spec, "Install the package."), "en:Install the package.")
        self.assertEqual(envit5_target_text(spec, "Cài đặt gói."), "vi: Cài đặt gói.")

    def test_training_record_must_be_filled_before_train(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "experiment_record.md"
            path.write_text("| Experiment ID | exp01 |\n| Checkpoint ID dự kiến | final |\n| Direction | en_to_vi |\n| Candidate | VietAI/envit5-translation |\n| Dữ liệu train | train.jsonl |\n", encoding="utf-8")
            self.assertEqual(len(ensure_completed_experiment_record(path, experiment_id="exp01", checkpoint_id="final", direction=Direction.EN_TO_VI, model_id="VietAI/envit5-translation")), 64)
            path.write_text("| Experiment ID | |\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                ensure_completed_experiment_record(path, experiment_id="exp01", checkpoint_id="final", direction=Direction.EN_TO_VI, model_id="VietAI/envit5-translation")

    def test_runners_do_not_accept_final_data_roles(self) -> None:
        adaptation_runner = (ROOT / "tools/run_phase02_adaptation.py").read_text(encoding="utf-8")
        evaluator = (ROOT / "tools/run_phase02_adapted_evaluation.py").read_text(encoding="utf-8")
        validator = (ROOT / "tools/validate_phase02_adapted_evidence.py").read_text(encoding="utf-8")
        self.assertIn('load_it_role("adaptation_train", ROOT)', adaptation_runner)
        self.assertIn('choices=("selection_validation", "general_validation")', evaluator)
        self.assertNotIn("it_test", adaptation_runner)
        self.assertNotIn("general_test", evaluator)
        self.assertIn("PHASE_02_ADAPTED_EVIDENCE=PASS", validator)


if __name__ == "__main__":
    unittest.main()
