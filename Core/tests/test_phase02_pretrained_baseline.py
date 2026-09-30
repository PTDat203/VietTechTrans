from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from core_mt.baseline import PretrainedAdapter, load_candidate_spec, summarize_generation_config
from core_mt.contracts import Direction
from core_mt.data import Phase02Dataset, ParallelExample
from core_mt.metrics import score_predictions


class Phase02PretrainedBaselineTests(unittest.TestCase):
    def test_each_candidate_has_both_directions(self) -> None:
        config = ROOT / "configs" / "phase02_models.json"
        for model in ("opus_mt", "envit5", "nllb200_distilled_600m"):
            for direction in Direction:
                spec = load_candidate_spec(config, model, direction)
                self.assertTrue(spec.model_id)

    def test_final_test_cannot_be_disguised_as_evaluation_dataset(self) -> None:
        dataset = Phase02Dataset("it_test", Path("it_test.jsonl"), (ParallelExample("x", "one", "một"),))
        self.assertNotIn(dataset.role, {"selection_validation", "general_validation"})

    def test_evidence_generation_config_drops_unused_null_fields(self) -> None:
        result = summarize_generation_config({"max_length": 512, "num_beams": 4, "top_p": None, "renormalize_logits": True})
        self.assertEqual(result, {"max_length": 512, "num_beams": 4, "renormalize_logits": True})

    def test_envit5_uses_documented_runtime_controls(self) -> None:
        en_to_vi = load_candidate_spec(ROOT / "configs/phase02_models.json", "envit5", Direction.EN_TO_VI)
        vi_to_en = load_candidate_spec(ROOT / "configs/phase02_models.json", "envit5", Direction.VI_TO_EN)
        self.assertEqual(en_to_vi.generation_kwargs, {"max_length": 512})
        self.assertEqual(vi_to_en.generation_kwargs, {"max_length": 512})
        self.assertEqual(en_to_vi.output_control_prefix, "vi:")
        self.assertEqual(vi_to_en.output_control_prefix, "en:")
        self.assertTrue(en_to_vi.runtime_settings_source)

    def test_envit5_removes_only_documented_leading_output_prefix(self) -> None:
        adapter = PretrainedAdapter(load_candidate_spec(ROOT / "configs/phase02_models.json", "envit5", Direction.EN_TO_VI))
        self.assertEqual(adapter._normalize_decoded_output("vi: Bản dịch"), "Bản dịch")
        self.assertEqual(adapter._normalize_decoded_output("Nội dung có vi: ở giữa"), "Nội dung có vi: ở giữa")
        self.assertEqual(adapter._normalize_decoded_output("en: Translation"), "en: Translation")
        self.assertEqual(adapter.normalized_output_segments, 1)

    def test_metrics_record_the_actual_metric_configuration(self) -> None:
        metrics = score_predictions(["translation"], ["translation"])
        self.assertEqual(metrics["metric_config"]["chrF++"], {"beta": 2, "word_order": 2})
        self.assertEqual(
            metrics["metric_config"]["sacreBLEU"],
            {"tokenize": "none", "lowercase": False, "use_effective_order": False},
        )

    def test_long_source_is_split_without_loss(self) -> None:
        class FakeTokenizer:
            model_max_length = 5

            def __call__(self, text, **_kwargs):
                return {"input_ids": list(text)}

        class FakeConfig:
            max_position_embeddings = 5

        class FakeModel:
            config = FakeConfig()

        adapter = PretrainedAdapter(load_candidate_spec(ROOT / "configs/phase02_models.json", "opus_mt", Direction.VI_TO_EN))
        adapter.model, adapter.tokenizer = FakeModel(), FakeTokenizer()
        text = "ab cd ef gh"
        chunks = adapter._split_source(text)
        self.assertEqual("".join(chunks), text)
        self.assertTrue(all(adapter._token_length(chunk) <= 5 for chunk in chunks))

    def test_relative_position_model_is_not_given_an_invented_input_limit(self) -> None:
        class FakeTokenizer:
            model_max_length = 10**30

            def __call__(self, text, **_kwargs):
                return {"input_ids": list(text)}

        class FakeConfig:
            pass

        class FakeModel:
            config = FakeConfig()

        adapter = PretrainedAdapter(load_candidate_spec(ROOT / "configs/phase02_models.json", "envit5", Direction.EN_TO_VI))
        adapter.model, adapter.tokenizer = FakeModel(), FakeTokenizer()
        self.assertIsNone(adapter._input_limit())
        self.assertEqual(adapter._split_source("a long source"), ["a long source"])

    def test_runner_reports_progress_at_one_thousand_row_intervals(self) -> None:
        source = (ROOT / "src" / "core_mt" / "baseline.py").read_text(encoding="utf-8")
        self.assertIn("PHASE_02_PROGRESS", source)
        self.assertIn("next_progress_report = 1_000", source)

    def test_evidence_records_runtime_versions_needed_for_reproduction(self) -> None:
        source = (ROOT / "src" / "core_mt" / "baseline.py").read_text(encoding="utf-8")
        for field in ("torch_version", "tokenizers_version", "huggingface_hub_version", "sentencepiece_version"):
            self.assertIn(field, source)

    def test_empty_staging_directory_is_safe_to_retry(self) -> None:
        source = (ROOT / "tools" / "run_phase02_pretrained_baseline.py").read_text(encoding="utf-8")
        self.assertIn("not any(staging_dir.iterdir())", source)
        self.assertIn("staging_dir.rmdir()", source)

    def test_runner_records_source_checksum_and_runtime_dependencies(self) -> None:
        runner = (ROOT / "tools" / "run_phase02_pretrained_baseline.py").read_text(encoding="utf-8")
        adapter = (ROOT / "src" / "core_mt" / "baseline.py").read_text(encoding="utf-8")
        self.assertIn('"source_data_sha256"', runner)
        self.assertIn('"runner_sha256"', runner)
        self.assertIn('"adapter_sha256"', runner)
        self.assertIn('"protocol_sha256"', runner)
        self.assertIn('"candidate_config_sha256"', runner)
        self.assertIn('"runtime"', adapter)
        self.assertIn('"tokenizers"', adapter)
        self.assertIn('"huggingface_hub"', adapter)


if __name__ == "__main__":
    unittest.main()
