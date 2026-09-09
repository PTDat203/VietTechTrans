"""Unit tests cho rule interface EnViT5; không tải checkpoint hoặc gọi mạng."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from core_mt.adapters.envit5 import EnViT5Adapter
from core_mt.contracts import Direction


class EnViT5AdapterTests(unittest.TestCase):
    def test_en_to_vi_uses_en_input_tag_and_removes_only_vi_output_tag(self) -> None:
        adapter = EnViT5Adapter(Direction.EN_TO_VI, ROOT / "configs" / "envit5_baseline_v1.json", "cpu")
        self.assertEqual(adapter.prepare_input("Install the package."), "en: Install the package.")
        self.assertEqual(adapter.normalize_output("vi: Cài đặt gói."), "Cài đặt gói.")

    def test_vi_to_en_uses_vi_input_tag_and_removes_only_en_output_tag(self) -> None:
        adapter = EnViT5Adapter(Direction.VI_TO_EN, ROOT / "configs" / "envit5_baseline_v1.json", "cpu")
        self.assertEqual(adapter.prepare_input("Cài đặt gói."), "vi: Cài đặt gói.")
        self.assertEqual(adapter.normalize_output("en: Install the package."), "Install the package.")

    def test_empty_source_is_rejected(self) -> None:
        adapter = EnViT5Adapter(Direction.EN_TO_VI, ROOT / "configs" / "envit5_baseline_v1.json", "cpu")
        with self.assertRaises(ValueError):
            adapter.prepare_input(" ")

    def test_checkpoint_is_configured_to_use_project_local_snapshot_directory(self) -> None:
        adapter = EnViT5Adapter(Direction.EN_TO_VI, ROOT / "configs" / "envit5_baseline_v1.json", "cpu")
        self.assertEqual(adapter._project_root, ROOT)


if __name__ == "__main__":
    unittest.main()
