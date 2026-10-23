"""Write the fixed case set of the text-rule policy (docs/contracts/mt_app_rule_policy_v1.md §8, copy of
Documents/mt_app_rule_policy_v1.md) to
app/src/main/assets/text/mt_rule_cases_v1.json.

The same file is read by the JVM test (TextRulesTest: the input decisions), the APK ("Bộ case rule dịch" in Settings:
tier 3, the whole app path) and tools/mt/run_rule_tiers.py (tier 1 Student v1 checkpoint, tier 2 q4r graph). "expect"
is the input-side outcome of the policy; what the model then answers is evidence, not an expectation.

  python -I tools/mt/build_rule_cases.py
"""
from __future__ import annotations

import json
from pathlib import Path

APP = Path(__file__).resolve().parents[2]
OUT = APP / "app/src/main/assets/text/mt_rule_cases_v1.json"
NL = "\n"


def exactly(unit: str, n: int) -> str:
    """unit repeated and cut to n characters (ASCII and precomposed letters only: 1 char = 1 displayed character),
    never ending with a space (R01 would trim it)."""
    s = (unit * (n // len(unit) + 2))[:n]
    assert not s.endswith(" ") and not s.startswith(" ") and "  " not in s
    return s


def case(cid: str, direction: str, text: str, expect: str, rule: str, notes: list[str] | None = None,
         answer: str | None = None, check: str | None = None, why: str = "", no_notes: list[str] | None = None) -> dict:
    c = {"id": cid, "rule": rule, "direction": direction, "input": text, "expect": expect}
    if notes:
        c["notes"] = notes  # RuleNote names that must be among the decision's notes
    if no_notes:
        c["no_notes"] = no_notes  # RuleNote names that must not be among them
    if answer:
        c["answer"] = answer  # R06: "switch" (Đổi sang …) or "force" (Vẫn dịch), what the runners do next
    if check:
        c["check"] = check  # output-side rule to look at in the evidence (R08, R09, R11, R12)
    if why:
        c["why"] = why
    return c


V, E = "vi_to_en", "en_to_vi"
long_vi = "Ứng dụng kiểm tra giới hạn độ dài của câu gõ. "
CASES = [
    # R03: a lone technical literal is shown as typed; the model is not called.
    case("r03-javascript", V, "JavaScript", "keep", "R03"),
    case("r03-javascript-lower-s", E, "Javascript", "keep", "R03", why="the spelling of the user report, typed alone"),
    case("r03-api", V, "API", "keep", "R03"),
    case("r03-404", E, "404", "keep", "R03"),
    case("r03-url", V, "https://example.com/docs?page=2", "keep", "R03"),
    case("r03-email", E, "dev@example.com", "keep", "R03"),
    case("r03-code", V, "await fetch('/api/users')", "keep", "R03"),
    case("r03-path", E, "/api/users", "keep", "R03"),
    case("r03-exception", V, "NullPointerException", "keep", "R03"),
    case("r03-cicd", E, "CI/CD", "keep", "R03"),
    case("r03-docker", V, "Docker", "keep", "R03"),
    case("r03-command", E, "git commit -m \"fix login\"", "keep", "R03"),
    # R04: a lone context-dependent technical word is kept with a warning.
    case("r04-timeout", V, "timeout", "keep", "R04"),
    case("r04-production", E, "production", "keep", "R04"),
    case("r04-pool", V, "database connection pool", "keep", "R04"),
    case("r04-production-vi", V, "production", "keep", "R04", why="alone, the model writes Performion"),
    # R05: everyday words are translated, no warning; another single word gets the short-input warning.
    case("r05-yes", E, "yes", "translate", "R05"),
    case("r05-no", E, "no", "translate", "R05"),
    case("r05-hello", E, "hello", "translate", "R05"),
    case("r05-u", V, "ừ", "translate", "R05"),
    case("r05-cam-on", V, "cảm ơn", "translate", "R05"),
    case("r05-khong", V, "không", "translate", "R05", why="translated as the policy says; the model answers none"),
    case("r05-short", E, "laptop", "translate", "R05", notes=["SHORT_INPUT"]),
    # R06: the other language is asked about before sending; mixed Vietnamese with jargon is not.
    case("r06-english-as-vi", V, "Hello, how are you?", "mismatch", "R06", answer="force"),
    case("r06-vietnamese-as-en", E, "Xin chào, bạn khỏe không?", "mismatch", "R06", answer="switch"),
    case("r06-dialogue-word", V, "thanks", "mismatch", "R06", answer="switch"),
    case("r06-mixed-ok", V, "Tôi cần deploy API lên production.", "translate", "R06", no_notes=["LANGUAGE_OVERRIDDEN", "LANGUAGE_UNCERTAIN"], check="R08"),
    # R07: Vietnamese without diacritics warns but is translated; jargon without diacritics does not warn.
    case("r07-no-diacritics", V, "toi can kiem tra loi API", "translate", "R07", notes=["NO_DIACRITICS"]),
    case("r07-not-for-jargon", V, "API timeout 504", "translate", "R07", no_notes=["NO_DIACRITICS"]),
    # R08: a sentence with terms is translated whole; a lost term is flagged, never patched.
    case("r08-sentence", V, "Deploy CI/CD lên staging trước khi merge vào main.", "translate", "R08", check="R08"),
    case("r08-user-report", V, "Tôi đang học Javascript", "translate", "R08", check="R08",
         why="the user report: the model says Vocabulary; the app shows it and flags JavaScript"),
    case("r08-react", E, "Our company is hiring React developers", "translate", "R08", check="R08"),
    # R12: invariant literals of a sentence must reach the output.
    case("r12-url", V, "Xem tài liệu tại https://example.com/docs nhé", "translate", "R12", check="R12"),
    case("r12-status-path", V, "API trả về lỗi 504 khi gọi /api/users", "translate", "R12", check="R12"),
    case("r12-code", E, "Run `npm install` before starting the server", "translate", "R12", check="R12"),
    # R02: 500 displayed characters are sent, 501 are not; nothing is not.
    case("r02-500", V, exactly(long_vi, 500), "translate", "R02"),
    case("r02-501", V, exactly(long_vi, 501), "too_long", "R02"),
    case("r02-empty", E, "   ", "empty", "R02"),
    # R09: within 500 characters but more than 500 source ids: split, nothing lost.
    case("r09-ids", E, "a." * 250, "translate", "R09", check="R09", no_notes=["SHORT_INPUT"]),
    # R11: an output that repeats a unit 4 times is collapsed (Validation rows of DR-07 §12).
    case("r11-repeat", E, "Optimizing database queries is essential for application responsiveness.", "translate", "R11", check="R11"),
    case("r11-repeat-vi", V, "Báo cáo hàng ngày: Nhìn thấy carbon dioxide tan băng trên Sao Hỏa", "translate", "R11", check="R11"),
]


def main() -> int:
    ids = [c["id"] for c in CASES]
    assert len(ids) == len(set(ids))
    out = {
        "schema_version": "1.0",
        "case_set": "mt_rule_cases_v1",
        "policy": "docs/contracts/mt_app_rule_policy_v1.md (section 8, Bộ test bắt buộc)",
        "generator": "python -I tools/mt/build_rule_cases.py",
        "cases": CASES,
    }
    OUT.write_text(json.dumps(out, ensure_ascii=False, indent=1) + NL, encoding="utf-8", newline=NL)
    print(f"APP_MT_RULE_CASES=WRITTEN cases={len(CASES)} -> {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
