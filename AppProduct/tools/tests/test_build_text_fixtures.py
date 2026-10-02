"""Tests for tools/build_text_fixtures.py and the two files it generates.

Run from AppProduct with the repo .venv (Core's build_phase01.py imports pandas):

    ..\\.venv\\Scripts\\python.exe -m unittest discover -s tools/tests -v
"""
from __future__ import annotations

import contextlib
import hashlib
import io
import json
import sys
import tempfile
import unicodedata
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import build_text_fixtures as btf  # noqa: E402

FIXED_TIME = "2026-01-01T00:00:00+00:00"
FIXTURE_KEYS = ["schema_version", "policy_id", "generated_at_utc", "provenance",
                "python_whitespace_codepoints", "deleted_codepoints_rule", "cases"]


def core_expected(core, text: str) -> str:
    """Recompute independently: delete CONTROL non-whitespace and U+FFFD, then Core norm()."""
    kept = "".join(ch for ch in text if not (ch == "\ufffd" or (core.CONTROL.fullmatch(ch) and not ch.isspace())))
    return core.norm(kept)


def run_main(*args: str) -> tuple[int, str]:
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = btf.main(list(args))
    return code, out.getvalue()


class ParityFixtureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.core = btf.load_core()
        cls.raw = btf.PARITY_OUT.read_bytes()
        cls.fixture = json.loads(cls.raw.decode("utf-8"))
        cls.cases = cls.fixture["cases"]

    def test_file_is_utf8_lf_json_with_documented_keys(self) -> None:
        self.assertFalse(self.raw.startswith(b"\xef\xbb\xbf"))
        self.assertNotIn(b"\r", self.raw)
        self.assertTrue(self.raw.endswith(b"}\n"))
        self.assertEqual(list(self.fixture), FIXTURE_KEYS)
        self.assertEqual(self.fixture["policy_id"], "phase01_nfc_whitespace_v1")
        self.assertRegex(self.fixture["generated_at_utc"], r"\A\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\+00:00\Z")
        provenance = self.fixture["provenance"]
        self.assertRegex(provenance["core_commit"], r"\A[0-9a-f]{40}\Z")
        self.assertRegex(provenance["core_source_blob_sha256"], r"\A[0-9a-f]{64}\Z")
        self.assertEqual(provenance["core_source"], "Core/tools/build_phase01.py")
        self.assertTrue(provenance["python_version"] and provenance["unicodedata_version"])

    def test_every_expected_equals_core_norm_after_removal(self) -> None:
        wrong = [case["id"] for case in self.cases if case["expected"] != core_expected(self.core, case["input"])]
        self.assertEqual(wrong, [])

    def test_core_would_reject_follows_phase01_row_rule(self) -> None:
        for case in self.cases:
            text = case["input"]
            rejected = not text.strip() or "\ufffd" in text or bool(self.core.CONTROL.search(text))
            self.assertEqual(case["core_would_reject"], rejected, case["id"])
            if not rejected:  # nothing to delete: the app result is plain Core norm()
                self.assertEqual(case["expected"], self.core.norm(text), case["id"])

    def test_whitespace_and_deleted_lists_match_this_python(self) -> None:
        self.assertEqual(len(btf.EXPECTED_WHITESPACE), 29)
        self.assertEqual(btf.python_whitespace_codepoints(), list(btf.EXPECTED_WHITESPACE))
        self.assertEqual(self.fixture["python_whitespace_codepoints"], list(btf.EXPECTED_WHITESPACE))
        deleted = [cp for cp in range(sys.maxunicode + 1)
                   if cp == 0xFFFD or (self.core.CONTROL.fullmatch(chr(cp)) and not chr(cp).isspace())]
        rule = self.fixture["deleted_codepoints_rule"]
        self.assertEqual(rule["codepoints"], deleted)
        self.assertEqual(rule["core_control_pattern"], self.core.CONTROL.pattern)

    def test_cases_have_policy_properties_and_hand_examples(self) -> None:
        self.assertEqual(sum(case["id"].startswith("random_") for case in self.cases), btf.RANDOM_CASES)
        self.assertEqual(btf.case_errors(self.cases, btf.Policy(self.core)), [])
        by_id = {case["id"]: case for case in self.cases}
        nfd = by_id["nfd_vietnamese"]
        self.assertNotEqual(nfd["input"], nfd["expected"])
        self.assertEqual(nfd["expected"], "Tiếng Việt có dấu khởi động")
        self.assertEqual(by_id["deleted_char_before_combining_mark"]["expected"], "\u00e9")
        self.assertEqual(by_id["next_line_u0085"]["expected"], "một hai")
        self.assertEqual(by_id["zero_width_space_u200b_kept"]["expected"], "Node\u200b.js")
        self.assertEqual(by_id["no_lowercasing"]["expected"], "API Docker GPU TIẾNG VIỆT iPhone")


class TermCandidateTests(unittest.TestCase):
    def test_committed_file_has_header_and_columns(self) -> None:
        raw = btf.TERMS_OUT.read_bytes()
        self.assertNotIn(b"\r", raw)
        self.assertTrue(raw.endswith(b"\n"))
        lines = raw.decode("utf-8").split("\n")[:-1]
        header_at = next(index for index, line in enumerate(lines) if not line.startswith("#"))
        comments = "\n".join(lines[:header_at])
        self.assertIn("release=it_en_vi_v1", comments)
        self.assertIn("legacy", comments)
        self.assertRegex(comments, r"rows=\d+; sha256=[0-9a-f]{64}")
        self.assertEqual(lines[header_at], "term\tcount\tall_caps")
        rows = [line.split("\t") for line in lines[header_at + 1:]]
        self.assertTrue(0 < len(rows) <= btf.TOP_TERMS)
        counts = [int(count) for _, count, _ in rows]
        self.assertEqual(counts, sorted(counts, reverse=True))
        self.assertEqual(len({term.lower() for term, _, _ in rows}), len(rows))
        for term, _, all_caps in rows:
            self.assertRegex(term, r"\A[A-Za-z][A-Za-z0-9+#.\-]*\Z")
            self.assertTrue(btf.is_candidate(term), term)
            self.assertEqual(all_caps, str(int(btf.is_all_caps(term))), term)

    @unittest.skipUnless(btf.DEFAULT_TRAIN.is_file(), "legacy IT Train file is not on this machine")
    def test_header_matches_local_train_file(self) -> None:
        data = btf.DEFAULT_TRAIN.read_bytes()
        rows = data.count(b"\n")
        expected = f"rows={rows}; sha256={hashlib.sha256(data).hexdigest()}"
        self.assertIn(expected, btf.TERMS_OUT.read_text(encoding="utf-8"))

    def test_tokenizer_keeps_symbols_and_skips_vietnamese_words(self) -> None:
        text = "Cài Node.js, C++ và C# qua e-mail (API) cho Tiếng Việt; file_name v.v."
        self.assertEqual(list(btf.tokens(text)),
                         ["Node.js", "C++", "C#", "qua", "e-mail", "API", "cho", "file", "name", "v.v"])
        self.assertEqual(list(btf.tokens(unicodedata.normalize("NFD", "Tiếng Việt"))), [])

    def test_candidate_rule(self) -> None:
        for token in ("API", "AI", "IT", "Docker", "bug", "file", "web", "Python", "Node.js", "C++", "x86"):
            self.assertTrue(btf.is_candidate(token), token)
        # Unaccented Vietnamese syllables are not candidates; neither are English
        # words that look like one ("chat"), a known limit of the simple pattern.
        for token in ("nguyen", "trong", "khi", "Anh", "ai", "It", "quy", "chat"):
            self.assertFalse(btf.is_candidate(token), token)
        self.assertTrue(btf.is_all_caps("USB-C"))
        self.assertFalse(btf.is_all_caps("C++"))
        self.assertFalse(btf.is_all_caps("iOS"))


class RunTests(unittest.TestCase):
    def test_two_runs_write_identical_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_name:
            tmp = Path(tmp_name)
            train = tmp / "train.jsonl"
            rows = [{"vi_clean": "Cài Docker và Node.js trên Ubuntu"},
                    {"vi_clean": "Lỗi API khi gọi GPU, thử lại với docker"}, {"vi_clean": None}]
            with train.open("w", encoding="utf-8", newline="\n") as handle:
                handle.writelines(json.dumps(row, ensure_ascii=False) + "\n" for row in rows)
            outputs = []
            for name in ("first", "second"):
                parity, terms = tmp / name / "parity.json", tmp / name / "terms.tsv"
                code, out = run_main("--train", str(train), "--parity-out", str(parity),
                                     "--terms-out", str(terms), "--generated-at-utc", FIXED_TIME)
                self.assertEqual(code, 0, out)
                self.assertTrue(out.rstrip().endswith("APP_TEXT_FIXTURES=PASS"), out)
                outputs.append((parity.read_bytes(), terms.read_bytes()))
        self.assertEqual(outputs[0], outputs[1])
        fixture = json.loads(outputs[0][0])
        self.assertEqual(fixture["generated_at_utc"], FIXED_TIME)
        # The committed fixture must come from the committed generator.
        committed = json.loads(btf.PARITY_OUT.read_bytes())
        self.assertEqual(fixture["cases"], committed["cases"])
        tsv = outputs[0][1].decode("utf-8").split("\n")
        for row in ("Docker\t2\t0", "API\t1\t1", "GPU\t1\t1", "Node.js\t1\t0", "Ubuntu\t1\t0"):
            self.assertIn(row, tsv)
        self.assertIn("rows=3;", "\n".join(tsv))

    def test_missing_train_file_fails_without_writing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_name:
            tmp = Path(tmp_name)
            code, out = run_main("--train", str(tmp / "missing.jsonl"), "--parity-out", str(tmp / "p.json"),
                                 "--terms-out", str(tmp / "t.tsv"), "--generated-at-utc", FIXED_TIME)
            self.assertEqual(code, 1)
            self.assertIn("APP_TEXT_FIXTURES=FAIL", out)
            self.assertFalse((tmp / "p.json").exists())
            self.assertFalse((tmp / "t.tsv").exists())

    def test_non_utc_time_is_rejected(self) -> None:
        with self.assertRaises(btf.FixtureError):
            btf.utc_timestamp("2026-01-01T07:00:00+07:00")


if __name__ == "__main__":
    unittest.main()
