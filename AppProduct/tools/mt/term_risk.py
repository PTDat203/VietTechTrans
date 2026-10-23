"""Which IT terms and spellings does the packaged MT break? (feeds it_terms.tsv columns 4 and 5)

Every canonical term of it_terms.tsv, and its all-lowercase and Capitalized spellings (when different), is put
in 6 sentence templates per direction and translated with the packaged graph and the app decoding policy. A
spelling is "kept" when the term (case-insensitive, spaces ignored) appears in the output. Kept in at most 4 of 6:
  * canonical spelling -> column 4 (directions where the term is replaced by a placeholder);
  * lower / cap spelling of a term whose canonical spelling is kept -> column 5 ("lower@vi_to_en", ...): that
    spelling is rewritten in the canonical spelling before translation. Spellings the model keeps are left as typed.

  python -I tools/mt/term_risk.py [--write]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
APP = HERE.parents[1]
sys.path.insert(0, str(HERE))
from marian_ref import OnnxMarian, app_max_length, beam_search, collapse_repeats  # noqa: E402
from term_protect import TERMS_FILE  # noqa: E402

CKPT = {"en_to_vi": "phase03-seqkd-en-to-vi-v2", "vi_to_en": "phase03-seqkd-vi-to-en-v3"}
TEMPLATES = {
    "vi_to_en": ["Tôi đang học {}", "Dự án này dùng {} cho phần backend", "Bạn đã cài {} chưa?",
                 "Lỗi xảy ra khi chạy {} trên máy chủ", "Nhóm mình chuyển sang {} từ tháng trước",
                 "Hướng dẫn sử dụng {} cho người mới"],
    "en_to_vi": ["I am learning {}", "This project uses {} for the backend", "Have you installed {} yet?",
                 "The error happens when running {} on the server", "Our team switched to {} last month",
                 "A beginner's guide to {}"],
}
MAX_KEPT_TO_PROTECT = 4
NL, TAB = "\n", "\t"


def shapes(t: str) -> dict[str, str]:
    """The term as listed, all-lowercase and Capitalized (only the spellings that differ from the listed one)."""
    out = {"canonical": t}
    for name, spelled in (("lower", t.lower()), ("cap", t[:1].upper() + t[1:].lower())):
        if spelled not in out.values():
            out[name] = spelled
    return out


def protect_dirs(kept: dict, t: str, d: str) -> bool:
    return kept[t][d]["canonical"] <= MAX_KEPT_TO_PROTECT


def normalize_shapes(kept: dict, t: str, d: str) -> list[str]:
    if protect_dirs(kept, t, d):
        return []  # the placeholder handles every spelling
    return [s for s in ("lower", "cap") if s in kept[t][d] and kept[t][d][s] <= MAX_KEPT_TO_PROTECT]


def canonical_terms() -> list[str]:
    return [line.split(TAB)[0].strip() for line in TERMS_FILE.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.startswith("#")]


def main() -> int:
    from transformers import AutoTokenizer

    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--write", action="store_true", help="write columns 4 and 5 of it_terms.tsv")
    ap.add_argument("--out", type=Path, default=APP / "evidence/phase04/mt_term_protect/term_risk.json")
    ap.add_argument("--from-json", type=Path, help="reuse the counts of an earlier run instead of translating")
    args = ap.parse_args()

    terms = canonical_terms()
    if args.from_json:
        kept = json.loads(args.from_json.read_text(encoding="utf-8"))["kept_of_6"]
    else:
        kept = {t: {} for t in terms}
        for d, templates in TEMPLATES.items():
            ckpt = APP.parent / "Core/models/phase03" / CKPT[d] / "final-epoch-1"
            tok = AutoTokenizer.from_pretrained(ckpt, local_files_only=True)
            gen = json.loads((ckpt / "generation_config.json").read_text(encoding="utf-8"))
            model = OnnxMarian(APP / f"app/src/main/assets/mt/{d}/model.onnx", 4)
            prefix = ">>vie<<" if d == "en_to_vi" else ""
            for t in terms:
                kept[t][d] = {}
                for shape, spelled in shapes(t).items():
                    ok = 0
                    for tpl in templates:
                        ids = tok(prefix + tpl.format(spelled))["input_ids"]
                        out = beam_search(model, ids, gen["decoder_start_token_id"], gen["eos_token_id"], num_beams=gen["num_beams"],
                                          max_length=app_max_length(len(ids), gen["max_length"]), prefer_finished=True)
                        text = collapse_repeats(tok.decode(out, skip_special_tokens=True))
                        ok += t.lower().replace(" ", "") in text.lower().replace(" ", "")
                    kept[t][d][shape] = ok
            print(f"{d}: done", flush=True)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps({"templates": TEMPLATES, "max_kept_to_protect": MAX_KEPT_TO_PROTECT, "kept_of_6": kept},
                                   ensure_ascii=False, indent=1) + NL, encoding="utf-8", newline=NL)
    for d in TEMPLATES:
        risky = [t for t in terms if protect_dirs(kept, t, d)]
        norm = [f"{t}:{s}" for t in terms for s in normalize_shapes(kept, t, d)]
        print(f"{d}: {len(risky)} terms get placeholders: {', '.join(risky)}")
        print(f"{d}: {len(norm)} spellings get the canonical spelling: {', '.join(norm)}")
    if args.write:
        lines = []
        for line in TERMS_FILE.read_text(encoding="utf-8").splitlines():
            if line.strip() and not line.startswith("#"):
                cols = (line.split(TAB) + ["", "", ""])[:3]
                t = cols[0].strip()
                col4 = ",".join(d for d in TEMPLATES if protect_dirs(kept, t, d))
                col5 = ",".join(f"{s}@{d}" for d in TEMPLATES for s in normalize_shapes(kept, t, d))
                line = TAB.join(cols + [col4, col5]).rstrip(TAB)
            lines.append(line)
        TERMS_FILE.write_text(NL.join(lines) + NL, encoding="utf-8", newline=NL)
        print(f"APP_MT_TERM_RISK=WRITTEN -> {TERMS_FILE}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
