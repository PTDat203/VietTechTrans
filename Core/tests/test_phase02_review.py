from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from core_mt.data import Phase02Dataset, ParallelExample
from core_mt.review import select_review_rows


class Phase02ReviewTests(unittest.TestCase):
    def test_review_is_deterministic_and_uses_only_tagged_rows(self) -> None:
        dataset = Phase02Dataset("selection_validation", Path("validation.jsonl"), (
            ParallelExample("a", "one", "một", ("has_command",)),
            ParallelExample("b", "two", "hai", ()),
            ParallelExample("c", "three", "ba", ("has_path",)),
        ))
        first = select_review_rows(dataset, 2)
        self.assertEqual(first, select_review_rows(dataset, 2))
        self.assertTrue(all(row["technical_tags"] for row in first))


if __name__ == "__main__":
    unittest.main()
