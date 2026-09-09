"""Kiểm tra runner chung với adapter giả, không tải model và không gọi mạng."""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from core_mt.contracts import Direction
from core_mt.data import LockedEvaluationSet, TestExample
from core_mt.protocol import load_frozen_protocol
from core_mt.benchmark import deterministic_sample
from core_mt.runner import run_baseline


class FakeAdapter:
    """Adapter tối thiểu để test runner, không đại diện cho chất lượng dịch."""

    model_key = "fake"
    direction = Direction.EN_TO_VI

    def __init__(self) -> None:
        self.calls = 0

    def load(self) -> None:
        return None

    def translate(self, source_text: str, *, num_beams: int, max_new_tokens: int) -> str:
        self.calls += 1
        return f"vi:{source_text}"

    def metadata(self) -> dict[str, object]:
        return {"model_id": "fake", "resolved_revision": "test"}


class RunnerContractTests(unittest.TestCase):
    def test_runner_keeps_two_test_sets_separate_and_writes_six_artifacts(self) -> None:
        protocol = load_frozen_protocol(ROOT / "configs" / "evaluation_protocol_v2.json")
        datasets = (
            LockedEvaluationSet("general_test", Path("general.jsonl"), (TestExample("g1", "hello", "xin chào"),)),
            LockedEvaluationSet("it_test", Path("it.jsonl"), (TestExample("i1", "install", "cài đặt"),)),
        )
        with tempfile.TemporaryDirectory() as folder, patch("core_mt.runner.score_predictions", return_value={"chrF++": 1.0, "sacreBLEU": 2.0}):
            output = Path(folder) / "run"
            adapter = FakeAdapter()
            run_baseline(adapter, protocol, datasets, output, "cpu", lambda: 100, lambda: None)
            self.assertEqual({item.name for item in output.iterdir()}, {"resolved_config.json", "predictions.jsonl", "metrics.json", "benchmark.json", "environment.txt", "run.log"})
            # Mỗi set có 1 lần quality, 1 warm-up và 5 lần đo trên mẫu một câu.
            self.assertEqual(adapter.calls, 14)

    def test_deterministic_sample_is_stable_and_bounded(self) -> None:
        rows = (("r3", "", ""), ("r1", "", ""), ("r2", "", ""))
        first = deterministic_sample(rows, lambda row: row[0], 2, 42)
        second = deterministic_sample(rows, lambda row: row[0], 2, 42)
        self.assertEqual(first, second)
        self.assertEqual(len(deterministic_sample(rows, lambda row: row[0], 128, 42)), 3)


if __name__ == "__main__":
    unittest.main()
