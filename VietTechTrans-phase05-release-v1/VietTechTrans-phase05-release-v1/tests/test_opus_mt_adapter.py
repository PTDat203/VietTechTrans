"""Unit tests cho phần riêng OPUS-MT; không tải checkpoint hoặc gọi mạng."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from core_mt.adapters.opus_mt import OpusMTAdapter
from core_mt.contracts import Direction


class OpusMTAdapterTests(unittest.TestCase):
    def test_en_to_vi_adds_required_vietnamese_target_token(self) -> None:
        adapter = OpusMTAdapter(Direction.EN_TO_VI, ROOT / "configs" / "opus_mt_baseline_v1.json", "cpu")
        self.assertEqual(adapter.prepare_input("Install the package."), ">>vie<< Install the package.")

    def test_vi_to_en_does_not_invent_a_prefix(self) -> None:
        adapter = OpusMTAdapter(Direction.VI_TO_EN, ROOT / "configs" / "opus_mt_baseline_v1.json", "cpu")
        self.assertEqual(adapter.prepare_input("Cài đặt gói."), "Cài đặt gói.")

    def test_empty_source_is_rejected_before_tokenization(self) -> None:
        adapter = OpusMTAdapter(Direction.EN_TO_VI, ROOT / "configs" / "opus_mt_baseline_v1.json", "cpu")
        with self.assertRaises(ValueError):
            adapter.prepare_input("   ")


if __name__ == "__main__":
    unittest.main()
