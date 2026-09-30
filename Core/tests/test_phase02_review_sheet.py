from __future__ import annotations

import ast
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class Phase02ReviewSheetTests(unittest.TestCase):
    def test_prepared_review_name_includes_checkpoint_identity(self) -> None:
        source = (ROOT / "tools" / "prepare_phase02_review_sheet.py").read_text(encoding="utf-8")
        self.assertIn("--checkpoint-id", source)
        self.assertIn("{args.checkpoint_id}", source)
        self.assertIn("phase02_it_validation_review_template.csv", source)

    def test_review_validator_is_syntax_valid(self) -> None:
        source = (ROOT / "tools" / "validate_phase02_review_sheet.py").read_text(encoding="utf-8")
        ast.parse(source)
        self.assertIn("phase02_it_validation_review_manifest.json", source)
        self.assertIn("set(row_ids) != set(locked_row_ids)", source)


if __name__ == "__main__":
    unittest.main()
