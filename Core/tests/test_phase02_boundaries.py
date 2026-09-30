from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from core_mt.data import load_it_role
from core_mt.protocol import load_phase02_protocol
from core_mt.review import select_review_rows


class Phase02BoundaryTests(unittest.TestCase):
    def test_protocol_locks_final_sets(self) -> None:
        protocol = load_phase02_protocol(ROOT / "configs/phase02_protocol.json")
        self.assertEqual(len(protocol.locked_final_sets), 2)
        self.assertEqual(protocol.directions, ("en_to_vi", "vi_to_en"))

    def test_final_set_names_are_not_phase02_roles(self) -> None:
        with self.assertRaises(ValueError):
            load_it_role("it_test", ROOT)

    def test_review_selection_needs_a_real_count(self) -> None:
        dataset = load_it_role("selection_validation", ROOT)
        with self.assertRaises(ValueError):
            select_review_rows(dataset, 0)

    def test_phase02_input_bundle_has_no_final_evaluation_path(self) -> None:
        source = (ROOT / "tools" / "build_phase02_input_bundle.py").read_text(encoding="utf-8")
        self.assertNotIn('ROOT / "data/final_report/it_en_vi",', source)


if __name__ == "__main__":
    unittest.main()
