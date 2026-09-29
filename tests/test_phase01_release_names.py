from __future__ import annotations

import ast
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class Phase01ReleaseNameTests(unittest.TestCase):
    def test_builder_requires_a_new_name_instead_of_force_overwrite(self) -> None:
        source = (ROOT / "tools" / "build_phase01.py").read_text(encoding="utf-8")
        ast.parse(source)
        self.assertNotIn('parser.add_argument("--force"', source)
        self.assertIn('parser.add_argument("--release-name"', source)
        self.assertIn("Refusing to overwrite an existing dataset release or report", source)

    def test_general_test_builder_does_not_offer_force_overwrite(self) -> None:
        source = (ROOT / "tools" / "build_general_test.py").read_text(encoding="utf-8")
        ast.parse(source)
        self.assertNotIn('parser.add_argument("--force"', source)
        self.assertIn("Refusing to overwrite an existing General Test release", source)


if __name__ == "__main__":
    unittest.main()
