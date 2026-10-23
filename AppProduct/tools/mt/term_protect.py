"""IT term protection around the MT model: the experiment of DR-07 §15 (2026-10-22).

NOT USED BY THE APP since the text-rule policy v1 (docs/contracts/mt_app_rule_policy_v1.md, DR-07 §16): R01/R08/§9 forbid
changing a term's spelling or patching a translation with a dictionary. The app now keeps a lone term as typed (R03)
and only flags a lost term in a sentence (R08). This module stays to reproduce the §15 measurements
(term_risk.py, eval_term_protect.py, probe_term_protect.py), whose numbers are evidence for Student v2.

The Phase 03 Student breaks some IT names: rare spellings become other words (VI->EN "Javascript" ->
"Vocabulary", "Django" -> "Toyota"), and in EN->VI names that are also English words get translated
("React" -> "phản ứng", "Spring Boot" -> "khởi động mùa xuân"). The rule is to change only what the model
breaks (measured by tools/mt/term_risk.py) and keep everything else exactly as typed:
  * a term the model breaks even in its canonical spelling (column 4) is replaced by a placeholder TERM1, TERM2,
    ... which both models copy unchanged (10/10 test sentences per direction; digits and [n] were not kept),
    and put back afterwards exactly as typed;
  * a lowercase or Capitalized spelling the model breaks (column 5, e.g. "Javascript", "javascript") is rewritten
    in the canonical spelling ("JavaScript"), which the model knows;
  * nothing else changes: other spellings (WiFi, MacOS), ALL CAPS words, and terms inside URLs, domains, paths
    or file names (index.html, php.net) stay as typed. Placeholders for every term, or canonical spelling
    everywhere, lowered chrF++ on the IT Validation rows (DR-07 §15).
If a placeholder is lost or repeated, the caller translates again without placeholders.

Matching: case-insensitive, on word boundaries (no letter, digit or "_" right before or after), longest form
first. A term flagged en_common (also an English word: react, rust, windows...) is handled in an English
source only when written exactly as listed and not as the first word.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

TERMS_FILE = Path(__file__).resolve().parent / "it_terms.tsv"
PLACEHOLDER = re.compile(r"TERM([0-9]+)")


@dataclass(frozen=True)
class Form:
    text: str  # as listed (canonical or alias)
    canonical: str
    en_common: bool
    protect: frozenset  # directions ("en_to_vi", "vi_to_en") where a placeholder is used
    normalize: frozenset  # "lower@vi_to_en", "cap@en_to_vi", ...: spellings rewritten canonically


def load_forms(path: Path = TERMS_FILE) -> list[Form]:
    forms = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        cols = line.split("\t")
        canonical = cols[0].strip()
        aliases = [a.strip() for a in cols[1].split(",") if a.strip()] if len(cols) > 1 else []
        flag = len(cols) > 2 and cols[2].strip() == "en_common"
        protect = frozenset(d.strip() for d in cols[3].split(",") if d.strip()) if len(cols) > 3 else frozenset()
        normalize = frozenset(d.strip() for d in cols[4].split(",") if d.strip()) if len(cols) > 4 else frozenset()
        forms += [Form(f, canonical, flag, protect, normalize if f == canonical else frozenset()) for f in [canonical, *aliases]]
    return forms


def spelling(typed: str, listed: str) -> str:
    """'lower' / 'cap' when typed is that spelling of the listed term, else 'other' (ALL CAPS, mixed...)."""
    if typed == listed.lower():
        return "lower"
    if typed == listed[:1].upper() + listed[1:].lower():
        return "cap"
    return "other"


def in_url_or_path(text: str, start: int, end: int) -> bool:
    """Inside a URL, domain, path or file name: index.html, php.net, /usr/bin/python; also the spaced
    form of the validation data ("index. html", "php. net"). A sentence end such as "Python. Javascript" is not."""
    before = text[start - 1] if start > 0 else ""
    if before and before in "./\\@":
        return True
    if before == " " and start > 1 and text[start - 2] == "." and text[start].islower():
        return True
    after = text[end:end + 3]
    if after[:1] in ("/", "\\"):
        return True
    rest = after[1:].lstrip(" ")[:1] if after[:1] == "." else ""
    return bool(rest) and (rest.islower() or rest.isdigit())


class TermProtector:
    def __init__(self, forms: list[Form] | None = None):
        self.forms = forms if forms is not None else load_forms()
        self.by_lower = {f.text.lower(): f for f in self.forms}
        ordered = sorted(self.forms, key=lambda f: (-len(f.text), f.text.lower()))
        alternation = "|".join(re.escape(f.text) for f in ordered)
        self.pattern = re.compile(rf"(?<!\w)(?:{alternation})(?!\w)", re.IGNORECASE)

    def protect(self, text: str, direction: str, placeholders: bool = True) -> tuple[str, list[str]]:
        """Returns (source to translate, text of TERM1, TERM2, ... as typed); direction is en_to_vi or vi_to_en.
        placeholders=False (fallback after a lost placeholder): spelling fixes only."""
        english = direction == "en_to_vi"
        placeholders_allowed = placeholders and not PLACEHOLDER.search(text)
        terms: list[str] = []
        out, last = [], 0
        for m in self.pattern.finditer(text):
            typed = m.group(0)
            form = self.by_lower.get(typed.lower())
            if form is None:  # Unicode case folding matched a spelling not listed ("İOS")
                continue
            if english and form.en_common and (typed != form.text or not any(c.isalnum() for c in text[:m.start()])):
                continue
            if in_url_or_path(text, m.start(), m.end()):
                continue
            if placeholders_allowed and direction in form.protect:
                terms.append(typed)
                out += [text[last:m.start()], f"TERM{len(terms)}"]
                last = m.end()
            elif typed != form.text and spelling(typed, form.text) + "@" + direction in form.normalize:
                out += [text[last:m.start()], form.text]
                last = m.end()
        out.append(text[last:])
        return "".join(out), terms

    @staticmethod
    def restore(translation: str, terms: list[str]) -> str | None:
        """Placeholders back to terms; None when one is missing or repeated (translate without protection)."""
        found = [int(n) for n in PLACEHOLDER.findall(translation)]
        if sorted(found) != list(range(1, len(terms) + 1)):
            return None
        return PLACEHOLDER.sub(lambda m: terms[int(m.group(1)) - 1], translation)


if __name__ == "__main__":
    import sys

    for line in sys.stdin:
        print(TermProtector().protect(line.rstrip("\n"), sys.argv[1] if len(sys.argv) > 1 else "vi_to_en"))
