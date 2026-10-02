"""Build the text fixtures that the app derives from Core (Core is only read).

Outputs (generated, then committed):

- ``app/src/test/resources/core_norm_parity.json``: parity cases for the app MT
  input normalizer, policy ``phase01_nfc_whitespace_v1``. Expected values are
  computed with Core's own ``norm()`` from ``Core/tools/build_phase01.py``.
- ``docs/data/it_term_candidates.tsv``: Latin-script tokens in the ``vi_clean``
  field of IT Train that do not look like Vietnamese syllables. The team picks
  TTS lexicon entries from this list. It holds tokens and counts, no sentences.

Policy ``phase01_nfc_whitespace_v1`` = ``core_norm(remove(text))``:

1. ``remove`` deletes every character matched by Core ``CONTROL`` that is not
   Python whitespace (``str.isspace``), plus U+FFFD. Phase 01 rejected rows
   with these characters; an app cannot reject user input, so it deletes them.
2. ``core_norm`` is Core ``norm()``: NFC, each run of Python whitespace
   (29 code points) becomes one U+0020, then strip.

For input without the deleted characters the result equals Core ``norm()``.

Run from ``AppProduct`` with the repo .venv (Core's module imports pandas)::

    ..\\.venv\\Scripts\\python.exe tools\\build_text_fixtures.py

Prints ``APP_TEXT_FIXTURES=PASS``, or ``APP_TEXT_FIXTURES=FAIL <reason>`` and
exits with code 1.
"""
from __future__ import annotations

import argparse
import functools
import hashlib
import importlib.util
import json
import os
import platform
import random
import re
import string
import subprocess
import sys
import unicodedata
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path

APP_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = APP_ROOT.parent
CORE_ROOT = REPO_ROOT / "Core"
CORE_SOURCE = "Core/tools/build_phase01.py"
GENERATOR = "AppProduct/tools/build_text_fixtures.py"
PARITY_OUT = APP_ROOT / "app/src/test/resources/core_norm_parity.json"
TERMS_OUT = APP_ROOT / "docs/data/it_term_candidates.tsv"
# The only IT Train file on the development machine is the legacy v1 release.
DEFAULT_TRAIN = CORE_ROOT / "data/processed/it_en_vi_v1/train.jsonl"
# Official release name: dataset_version in Core/configs/data/phase01_it_en_vi.json.
CANONICAL_RELEASE = "it_en_vi"

SCHEMA_VERSION = "1.0"
POLICY_ID = "phase01_nfc_whitespace_v1"
RANDOM_SEED = 42
RANDOM_CASES = 500
TOP_TERMS = 1000

# Python whitespace (str.isspace, same as re "\s"). The Kotlin port hard-codes
# this list because Kotlin isWhitespace() and Java "(?U)\s" differ from Python.
EXPECTED_WHITESPACE = (
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x85, 0xA0, 0x1680,
    *range(0x2000, 0x200B), 0x2028, 0x2029, 0x202F, 0x205F, 0x3000,
)
# Core CONTROL = [\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f] minus whitespace, plus U+FFFD.
EXPECTED_DELETED = (
    *range(0x00, 0x09), *range(0x0E, 0x1C), *range(0x7F, 0x85), *range(0x86, 0xA0), 0xFFFD,
)

WS29 = "".join(map(chr, EXPECTED_WHITESPACE))
# Hand-written cases: (id, input, expected). None means the input is already
# normalized. Invisible and combining characters are written as escapes.
HAND_CASES = (
    ("empty", "", ""),
    ("whitespace_only", " \t\n\u00a0\u3000\u2028", ""),
    ("leading_trailing_spaces", "   Mở terminal   ", "Mở terminal"),
    ("tab_newline_runs", "Bước 1:\t\tmở\n\n\nfile\t \ncấu hình", "Bước 1: mở file cấu hình"),
    ("crlf_line_endings", "dòng 1\r\ndòng 2\r\n", "dòng 1 dòng 2"),
    ("nbsp_u00a0", "Tiếng\u00a0Việt", "Tiếng Việt"),
    ("ideographic_space_u3000", "Xin\u3000chào", "Xin chào"),
    ("line_separator_u2028", "dòng một\u2028dòng hai", "dòng một dòng hai"),
    ("paragraph_separator_u2029", "đoạn một\u2029đoạn hai", "đoạn một đoạn hai"),
    ("narrow_nbsp_u202f", "10\u202fGB", "10 GB"),
    ("en_quad_u2000", "a\u2000b", "a b"),
    ("all_29_whitespace", "A" + WS29 + "B" + WS29, "A B"),
    ("info_separators_u001c_u001f", "x\x1cy\x1dz\x1ew\x1fv", "x y z w v"),
    ("next_line_u0085", "một\x85hai", "một hai"),
    ("vertical_tab_form_feed", "một\x0bhai\x0cba", "một hai ba"),
    ("replacement_char_ufffd", "Tiếng Vi\ufffdệt", "Tiếng Việt"),
    ("c0_control_u0001", "run\x01 test", "run test"),
    ("null_u0000", "a\x00b", "ab"),
    ("delete_u007f", "abc\x7fdef", "abcdef"),
    ("c1_controls_u0080_u009f", "a\x80b\x9fc", "abc"),
    ("ansi_escape_u001b", "\x1b[31mLỗi\x1b[0m build", "[31mLỗi[0m build"),
    ("only_deleted_chars", "\x01\x02\ufffd", ""),
    ("deleted_char_between_spaces", "a \x01 b", "a b"),
    ("deleted_char_before_combining_mark", "e\x01\u0301", "\u00e9"),
    ("nfd_vietnamese", unicodedata.normalize("NFD", "Tiếng Việt có dấu khởi động"), "Tiếng Việt có dấu khởi động"),
    ("nfd_vietnamese_uppercase", unicodedata.normalize("NFD", "ĐƯỜNG DẪN TỆP"), "ĐƯỜNG DẪN TỆP"),
    ("nfc_vietnamese_unchanged", "Tiếng Việt có dấu khởi động", None),
    ("marks_out_of_canonical_order", "Ca\u0302\u0323p nha\u0323\u0302t Mo\u0301\u031bi", "Cập nhật Mới"),
    ("combining_mark_after_space", "a \u0301b", None),
    ("no_lowercasing", "API Docker GPU TIẾNG VIỆT iPhone", None),
    ("curly_quotes_guillemets", "Chọn “Lưu” và ‘Thoát’ «OK»", None),
    ("nfc_not_nfkc", "\ufb01le \uff37\uff49\uff4e x\u00b2 \u2460", None),
    ("emoji", "Xong rồi \U0001f44d\U0001f3fd\U0001f389", None),
    ("emoji_zwj_sequence", "Gia đình \U0001f468\u200d\U0001f469\u200d\U0001f467", None),
    ("zero_width_space_u200b_kept", "Node\u200b.js", None),
    ("bom_ufeff_kept", "\ufeffmở file", None),
    ("mixed_en_vi_command", "Chạy lệnh  `git pull`  rồi   restart   service nginx.",
     "Chạy lệnh `git pull` rồi restart service nginx."),
    ("mixed_en_vi_error", "Em đã deploy lên server staging, nhưng API trả về lỗi 500 Internal Server Error.", None),
    ("mixed_en_vi_ui", "Open\u00a0 Settings → Bảo mật →  bật 2FA", "Open Settings → Bảo mật → bật 2FA"),
    ("windows_path", "Mở C:\\Program Files\\App\\config.ini\tđể sửa", "Mở C:\\Program Files\\App\\config.ini để sửa"),
    ("unix_command", "sudo apt-get install -y python3-pip && pip3 install \"numpy>=1.26\"", None),
    ("url_and_version", "Tải Python 3.12.1 tại https://www.python.org/downloads/", None),
    ("placeholders", "Đã xoá %1 tệp trong thư mục %2", None),
    ("multiline_code_collapsed", "def main():\n    print(\"xin chào\")\n", "def main(): print(\"xin chào\")"),
)

# Pools for the pseudo-random cases.
VI_LETTERS = "àáảãạăằắẳẵặâầấẩẫậđèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵ"
VI_WORD_CHARS = VI_LETTERS + VI_LETTERS.upper() + string.ascii_letters
ASCII_CHARS = string.ascii_letters + string.digits + string.punctuation
VOWELS = "aeiouyAEIOUY"
SHAPE_MARKS = (0x0302, 0x0306, 0x031B)  # circumflex, breve, horn
TONE_MARKS = (0x0300, 0x0301, 0x0303, 0x0309, 0x0323)  # grave, acute, tilde, hook above, dot below

# Term candidates. A token is an ASCII letter followed by letters, digits or
# + # . - (C++, C#, Node.js, e-mail). It must not touch another letter, digit
# or combining mark, so Vietnamese words such as "Tiếng" give no ASCII pieces.
_WORD_CHAR = r"(?:[^\W_]|[\u0300-\u036f])"
TOKEN_RE = re.compile(rf"(?<!{_WORD_CHAR})[A-Za-z][A-Za-z0-9+#.\-]*(?!{_WORD_CHAR})")
# Simplified unaccented Vietnamese syllable: onset? vowels coda?
VI_SYLLABLE_RE = re.compile(r"(?:ngh|ng|nh|ch|gh|gi|kh|ph|qu|th|tr|[bcdghklmnprstvx])?[aeiouy]+(?:ng|nh|ch|[cmnpt])?")


class FixtureError(Exception):
    """A check failed; the message is the FAIL reason."""


@functools.lru_cache(maxsize=None)
def load_core():
    """Import Core/tools/build_phase01.py without writing anything into Core/."""
    path = REPO_ROOT / CORE_SOURCE
    spec = importlib.util.spec_from_file_location("core_build_phase01", path)
    if spec is None or spec.loader is None:
        raise FixtureError(f"cannot load {CORE_SOURCE}")
    module = importlib.util.module_from_spec(spec)
    previous = sys.dont_write_bytecode
    sys.dont_write_bytecode = True  # no __pycache__ in Core/tools
    try:
        spec.loader.exec_module(module)
    except Exception as exc:  # any import-time failure in Core is a FAIL reason
        raise FixtureError(f"cannot import {CORE_SOURCE}: {type(exc).__name__}: {exc} (run with the repo .venv)") from exc
    finally:
        sys.dont_write_bytecode = previous
    return module


def git(*args: str) -> bytes:
    try:
        return subprocess.run(["git", "-C", str(REPO_ROOT), *args], check=True, capture_output=True).stdout
    except (OSError, subprocess.CalledProcessError) as exc:
        raise FixtureError(f"git {' '.join(args)} failed: {exc}") from exc


def core_provenance() -> dict:
    """Core commit and the SHA-256 of the build_phase01.py blob (LF bytes)."""
    commit = git("rev-parse", "HEAD").decode("ascii").strip()
    blob_sha256 = hashlib.sha256(git("cat-file", "blob", f"HEAD:{CORE_SOURCE}")).hexdigest()
    # core.autocrlf makes the working copy CRLF; compare it as LF bytes.
    working = (REPO_ROOT / CORE_SOURCE).read_bytes().replace(b"\r\n", b"\n")
    if hashlib.sha256(working).hexdigest() != blob_sha256:
        raise FixtureError(f"{CORE_SOURCE} differs from HEAD; commit or revert it before generating fixtures")
    return {
        "core_commit": commit,
        "core_source": CORE_SOURCE,
        "core_source_blob_sha256": blob_sha256,
        "core_symbols": ["norm", "CONTROL"],
        "generator": GENERATOR,
        "python_version": platform.python_version(),
        "unicodedata_version": unicodedata.unidata_version,
        "random_seed": RANDOM_SEED,
    }


def python_whitespace_codepoints() -> list[int]:
    return [cp for cp in range(sys.maxunicode + 1) if chr(cp).isspace()]


def regex_whitespace_codepoints() -> list[int]:
    space = re.compile(r"\s")  # the class used by Core SPACE = re.compile(r"\s+")
    return [cp for cp in range(sys.maxunicode + 1) if space.fullmatch(chr(cp))]


def deleted_codepoints(control: re.Pattern) -> list[int]:
    return [cp for cp in range(sys.maxunicode + 1)
            if cp == 0xFFFD or (control.fullmatch(chr(cp)) and not chr(cp).isspace())]


def ranges(codepoints) -> str:
    """Format code points as 'U+0000-U+0008 U+000E ...'."""
    parts, cps = [], sorted(codepoints)
    start = prev = None
    for cp in cps + [None]:
        if start is not None and cp == prev + 1:
            prev = cp
            continue
        if start is not None:
            parts.append(f"U+{start:04X}" if start == prev else f"U+{start:04X}-U+{prev:04X}")
        start = prev = cp
    return " ".join(parts)


class Policy:
    """phase01_nfc_whitespace_v1, built from Core's own norm() and CONTROL."""

    def __init__(self, core) -> None:
        self.core = core
        self.deleted = frozenset(deleted_codepoints(core.CONTROL))

    def remove(self, text: str) -> str:
        return "".join(ch for ch in text if ord(ch) not in self.deleted)

    def normalize(self, text: str) -> str:
        return self.core.norm(self.remove(text))

    def core_would_reject(self, text: str) -> bool:
        # Phase 01 row rule (build_phase01.source_frame): blank, U+FFFD or CONTROL.
        return not text.strip() or "\ufffd" in text or bool(self.core.CONTROL.search(text))


def random_text(rng: random.Random) -> str:
    """One string of 1-10 random pieces; combining marks may follow anything."""
    def pick(seq):
        return seq[rng.randrange(len(seq))]

    parts = []
    for _ in range(rng.randrange(1, 11)):
        kind = rng.randrange(10)
        if kind < 2:
            parts.append("".join(pick(ASCII_CHARS) for _ in range(rng.randrange(1, 7))))
        elif kind < 4:
            parts.append("".join(pick(VI_WORD_CHARS) for _ in range(rng.randrange(1, 7))))
        elif kind < 6:  # decomposed Vietnamese vowel, marks in either order
            marks = [pick(SHAPE_MARKS)] if rng.randrange(2) else []
            if rng.randrange(4):
                marks.append(pick(TONE_MARKS))
            if len(marks) == 2 and rng.randrange(2):
                marks.reverse()
            parts.append(pick(VOWELS) + "".join(map(chr, marks)))
        elif kind < 8:
            parts.append("".join(chr(pick(EXPECTED_WHITESPACE)) for _ in range(rng.randrange(1, 4))))
        elif kind == 8:
            parts.append(chr(pick(EXPECTED_DELETED)))
        else:
            parts.append(chr(pick(SHAPE_MARKS + TONE_MARKS)))
    return "".join(parts)


def build_cases(policy: Policy) -> list[dict]:
    wrong = [case_id for case_id, text, expected in HAND_CASES
             if policy.normalize(text) != (text if expected is None else expected)]
    if wrong:
        raise FixtureError("Core norm() disagrees with hand-written expected values: " + ", ".join(wrong))
    rng = random.Random(RANDOM_SEED)
    inputs = [(case_id, text) for case_id, text, _ in HAND_CASES]
    inputs += [(f"random_{index:03d}", random_text(rng)) for index in range(RANDOM_CASES)]
    return [{"id": case_id, "input": text, "expected": policy.normalize(text),
             "core_would_reject": policy.core_would_reject(text)} for case_id, text in inputs]


def case_errors(cases: list[dict], policy: Policy) -> list[str]:
    """Properties every expected value must have, plus code point coverage."""
    errors = []
    ids = [case["id"] for case in cases]
    if len(set(ids)) != len(ids):
        errors.append("duplicate case ids")
    for case in cases:
        out = case["expected"]
        if policy.normalize(out) != out:
            errors.append(f"{case['id']}: normalizing expected changes it")
        if any(ch.isspace() and ch != " " for ch in out) or "  " in out or out != out.strip(" "):
            errors.append(f"{case['id']}: expected has other whitespace than single inner spaces")
        if any(ord(ch) in policy.deleted for ch in out) or unicodedata.normalize("NFC", out) != out:
            errors.append(f"{case['id']}: expected keeps a deleted character or is not NFC")
    seen = {ord(ch) for case in cases for ch in case["input"]}
    missing = [cp for cp in EXPECTED_WHITESPACE + EXPECTED_DELETED if cp not in seen]
    if missing:
        errors.append("case inputs do not cover " + ranges(missing))
    return errors


def escape_invisible(json_text: str) -> str:
    """Escape invisible, whitespace and combining characters in JSON text.

    json.dumps(ensure_ascii=False) writes them raw. Escaping keeps the file
    reviewable and stops an editor from normalizing NFD inputs or line
    separators. Only string contents can hold such characters, and decoded
    values are unchanged.
    """
    out = []
    for ch in json_text:
        cp = ord(ch)
        if cp >= 0x7F and (cp == 0xFFFD or unicodedata.category(ch)[0] in "CZM"):
            if cp > 0xFFFF:
                cp -= 0x10000
                out.append("\\u%04x\\u%04x" % (0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF)))
            else:
                out.append("\\u%04x" % cp)
        else:
            out.append(ch)
    return "".join(out)


def tokens(text: str):
    for match in TOKEN_RE.finditer(unicodedata.normalize("NFC", text)):
        yield match.group().rstrip(".-")


def is_all_caps(token: str) -> bool:
    return sum(ch.isalpha() for ch in token) >= 2 and not any(ch.islower() for ch in token)


def is_candidate(token: str) -> bool:
    lower = token.lower()
    return VI_SYLLABLE_RE.fullmatch(lower) is None or any(ch in "fjwz" for ch in lower) or is_all_caps(token)


def count_terms(train: Path):
    """Rows containing each candidate (case-insensitive key) and surface-form counts."""
    rows, digest = 0, hashlib.sha256()
    doc_freq, forms = Counter(), defaultdict(Counter)
    with train.open("rb") as handle:
        for raw in handle:
            digest.update(raw)
            rows += 1
            try:
                text = json.loads(raw)["vi_clean"]
            except (ValueError, KeyError, TypeError) as exc:
                raise FixtureError(f"{train}:{rows}: no vi_clean field ({exc})") from exc
            if not isinstance(text, str):
                continue
            keys = set()
            for token in tokens(text):
                if is_candidate(token):
                    forms[token.lower()][token] += 1
                    keys.add(token.lower())
            doc_freq.update(keys)
    return rows, digest.hexdigest(), doc_freq, forms


def terms_tsv(train: Path) -> tuple[str, int, int]:
    if not train.is_file():
        raise FixtureError(f"IT Train file not found: {train}")
    rows, sha256, doc_freq, forms = count_terms(train)
    try:
        source = train.resolve().relative_to(REPO_ROOT).as_posix()
    except ValueError:
        source = train.resolve().as_posix()
    release = train.resolve().parent.name
    lines = [
        "# Ứng viên thuật ngữ cho lexicon TTS tiếng Việt: token chữ Latin (ASCII) trong trường vi_clean của IT Train "
        "không khớp mẫu âm tiết tiếng Việt không dấu, hoặc có f/j/w/z, hoặc viết hoa (≥ 2 chữ cái).",
        f"# Nguồn: {source}; release={release}; rows={rows}; sha256={sha256}",
    ]
    if release != CANONICAL_RELEASE:
        note = (f"# Lưu ý: {release} là bản phát hành cũ (legacy), không phải IT Train chính thức của Phase 02 "
                f"(release {CANONICAL_RELEASE})")
        canonical = CORE_ROOT / "data/processed" / CANONICAL_RELEASE / "train.jsonl"
        lines.append(note + ("." if canonical.is_file() else "; bản chính thức không có trên máy sinh file này."))
    lines += [
        "# Cột: term = dạng viết gặp nhiều nhất; count = số dòng chứa token (không phân biệt hoa thường); "
        "all_caps = 1 nếu có ≥ 2 chữ cái và không có chữ thường.",
        f"# Top {TOP_TERMS} theo count giảm dần, bằng nhau thì theo term (không phân biệt hoa thường). "
        f"Sinh bởi {GENERATOR}; không chứa câu dữ liệu.",
        "term\tcount\tall_caps",
    ]
    ranked = sorted(doc_freq.items(), key=lambda item: (-item[1], item[0]))[:TOP_TERMS]
    for key, count in ranked:
        term = min(forms[key].items(), key=lambda item: (-item[1], item[0]))[0]
        lines.append(f"{term}\t{count}\t{int(is_all_caps(term))}")
    return "\n".join(lines) + "\n", len(ranked), len(doc_freq)


def utc_timestamp(value: str | None) -> str:
    if value is None:
        return datetime.now(timezone.utc).replace(microsecond=0).isoformat()
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise FixtureError(f"--generated-at-utc is not ISO-8601: {value}") from exc
    if parsed.utcoffset() != timedelta(0):
        raise FixtureError(f"--generated-at-utc must be UTC (+00:00): {value}")
    return parsed.isoformat()


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    with tmp.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    os.replace(tmp, path)


def previous_generated_at(path: Path, fixture: dict) -> str | None:
    """Return the old timestamp when the existing fixture differs only in generated_at_utc.

    Keeps the tracked parity file byte-stable across re-runs (same idea as the
    manifest in fetch_artifacts.py).
    """
    try:
        old = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if not isinstance(old, dict) or "generated_at_utc" not in old:
        return None
    old_ts = old["generated_at_utc"]
    if {**old, "generated_at_utc": None} == {**fixture, "generated_at_utc": None}:
        return old_ts
    return None


def run(args: argparse.Namespace) -> None:
    generated_at = utc_timestamp(args.generated_at_utc)
    core = load_core()
    provenance = core_provenance()
    print(f"python={provenance['python_version']} unicodedata={provenance['unicodedata_version']} "
          f"core_commit={provenance['core_commit']} core_source_blob_sha256={provenance['core_source_blob_sha256']}")

    whitespace = python_whitespace_codepoints()
    print(f"python_whitespace_codepoints={len(whitespace)} {ranges(whitespace)}")
    if regex_whitespace_codepoints() != whitespace:
        raise FixtureError("re '\\s' and str.isspace() disagree on this Python")
    if whitespace != list(EXPECTED_WHITESPACE):
        raise FixtureError("Python whitespace differs from the 29 code points of the Kotlin port: "
                           f"extra {ranges(set(whitespace) - set(EXPECTED_WHITESPACE)) or '-'}; "
                           f"missing {ranges(set(EXPECTED_WHITESPACE) - set(whitespace)) or '-'}")

    policy = Policy(core)
    print(f"deleted_codepoints={len(policy.deleted)} {ranges(policy.deleted)}")
    if sorted(policy.deleted) != list(EXPECTED_DELETED):
        raise FixtureError(f"Core CONTROL changed; the deleted set is no longer {POLICY_ID}")

    cases = build_cases(policy)
    errors = case_errors(cases, policy)
    if errors:
        raise FixtureError("; ".join(errors[:5]))
    fixture = {
        "schema_version": SCHEMA_VERSION,
        "policy_id": POLICY_ID,
        "generated_at_utc": generated_at,
        "provenance": provenance,
        "python_whitespace_codepoints": whitespace,
        "deleted_codepoints_rule": {
            "definition": "Deleted before NFC: code points matched by Core CONTROL that are not Python "
                          "whitespace (str.isspace), plus U+FFFD.",
            "core_control_pattern": core.CONTROL.pattern,
            "codepoints": sorted(policy.deleted),
        },
        "cases": cases,
    }
    if args.generated_at_utc is None:
        kept = previous_generated_at(args.parity_out, fixture)
        if kept is not None:
            fixture["generated_at_utc"] = kept
            print(f"parity cases unchanged; keeping generated_at_utc={kept}")
    parity_text = escape_invisible(json.dumps(fixture, ensure_ascii=False, indent=2)) + "\n"
    if json.loads(parity_text) != fixture:
        raise FixtureError("escaping changed the parity data")
    terms_text, kept_terms, distinct_terms = terms_tsv(args.train)

    write_text(args.parity_out, parity_text)
    write_text(args.terms_out, terms_text)
    hand = len(HAND_CASES)
    print(f"parity_cases={len(cases)} (hand={hand}, random={len(cases) - hand}, "
          f"core_would_reject={sum(case['core_would_reject'] for case in cases)}) -> {args.parity_out}")
    print(f"term_candidates={kept_terms} (distinct={distinct_terms}) -> {args.terms_out}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build app text fixtures from Core (read-only).")
    parser.add_argument("--train", type=Path, default=DEFAULT_TRAIN, help="IT Train JSONL with a vi_clean field")
    parser.add_argument("--parity-out", type=Path, default=PARITY_OUT)
    parser.add_argument("--terms-out", type=Path, default=TERMS_OUT)
    parser.add_argument("--generated-at-utc", help="fixed time for reproducible output, e.g. 2026-01-01T00:00:00+00:00")
    args = parser.parse_args(argv)
    try:
        run(args)
    except (FixtureError, OSError) as exc:
        print(f"APP_TEXT_FIXTURES=FAIL {exc}".encode("ascii", "backslashreplace").decode("ascii"))
        return 1
    except Exception as exc:  # unexpected bug: still print a machine-checkable marker
        print(f"APP_TEXT_FIXTURES=FAIL unexpected {type(exc).__name__}: {exc}"
              .encode("ascii", "backslashreplace").decode("ascii"))
        return 1
    print("APP_TEXT_FIXTURES=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
