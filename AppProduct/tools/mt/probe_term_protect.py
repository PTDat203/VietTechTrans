"""Held-out check of IT term protection (DR-07 §15): sentences and spellings term_risk.py did not use.

54 common IT terms, each as listed, all-lowercase and Capitalized, in 2 new sentence templates per direction,
translated with the packaged graph and the app policy, without and with TermProtector (placeholders, restore,
fallback as in the app). A term is "kept" when it appears in the output (case-insensitive, spaces ignored).

  python -I tools/mt/probe_term_protect.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
APP = HERE.parents[1]
sys.path.insert(0, str(HERE))
from marian_ref import OnnxMarian, app_max_length, beam_search, collapse_repeats  # noqa: E402
from term_protect import TermProtector  # noqa: E402

CKPT = {"vi_to_en": "phase03-seqkd-vi-to-en-v3", "en_to_vi": "phase03-seqkd-en-to-vi-v2"}
TERMS = ["JavaScript", "TypeScript", "Python", "Java", "Kotlin", "Swift", "Golang", "Rust", "PHP", "C++", "C#",
         "React", "Angular", "Vue.js", "Node.js", "Next.js", "Spring Boot", "Django", "Flask", "Laravel", "Flutter",
         "Docker", "Kubernetes", "Git", "GitHub", "GitLab", "Jenkins", "Linux", "Ubuntu", "Windows", "Android", "iOS",
         "MySQL", "PostgreSQL", "MongoDB", "Redis", "SQL", "NoSQL", "API", "REST", "GraphQL", "JSON", "HTML", "CSS",
         "AWS", "Azure", "Firebase", "TensorFlow", "PyTorch", "Excel", "Bluetooth", "Wi-Fi", "Chrome", "ChatGPT"]
TEMPLATES = {"vi_to_en": ["Công ty mình đang tuyển lập trình viên {}", "Mình gặp lỗi khi cập nhật {} hôm qua"],
             "en_to_vi": ["Our company is hiring {} developers", "I got an error after updating {} yesterday"]}
OUT = APP / "evidence/phase04/mt_term_protect/heldout_probe.json"


def kept(term: str, text: str) -> bool:
    return term.lower().replace(" ", "") in text.lower().replace(" ", "")


def main() -> int:
    from transformers import AutoTokenizer

    tp = TermProtector()
    results, rows = {}, []
    for d in ("vi_to_en", "en_to_vi"):
        ckpt = APP.parent / "Core/models/phase03" / CKPT[d] / "final-epoch-1"
        tok = AutoTokenizer.from_pretrained(ckpt, local_files_only=True)
        gen = json.loads((ckpt / "generation_config.json").read_text(encoding="utf-8"))
        model = OnnxMarian(APP / f"app/src/main/assets/mt/{d}/model.onnx", 4)
        prefix = ">>vie<<" if d == "en_to_vi" else ""

        def translate(text: str) -> str:
            ids = tok(prefix + text)["input_ids"]
            out = beam_search(model, ids, gen["decoder_start_token_id"], gen["eos_token_id"], num_beams=gen["num_beams"],
                              max_length=app_max_length(len(ids), gen["max_length"]), prefer_finished=True)
            return collapse_repeats(tok.decode(out, skip_special_tokens=True))

        stats = {"canonical": [0, 0, 0], "other_spellings": [0, 0, 0]}
        for t in TERMS:
            for spelled in dict.fromkeys([t, t.lower(), t[:1].upper() + t[1:].lower()]):
                for template in TEMPLATES[d]:
                    source = template.format(spelled)
                    plain = translate(source)
                    protected, terms = tp.protect(source, d)
                    guarded = translate(protected)
                    if terms:
                        guarded = TermProtector.restore(guarded, terms) or translate(tp.protect(source, d, placeholders=False)[0])
                    s = stats["canonical" if spelled == t else "other_spellings"]
                    s[0] += 1
                    s[1] += kept(t, plain)
                    s[2] += kept(t, guarded)
                    rows.append({"direction": d, "term": t, "source": source, "protected": protected, "without": plain, "with": guarded})
        results[d] = {k: {"n": n, "kept_without": a, "kept_with": b} for k, (n, a, b) in stats.items()}
        for k, (n, a, b) in stats.items():
            print(f"{d} {k}: term kept {a}/{n} ({100 * a / n:.0f}%) -> {b}/{n} ({100 * b / n:.0f}%) with TermProtector", flush=True)
    OUT.write_text(json.dumps({"terms": TERMS, "templates": TEMPLATES, "results": results, "rows": rows}, ensure_ascii=False, indent=1)
                   + "\n", encoding="utf-8", newline="\n")
    print(f"APP_MT_TERM_PROBE=WRITTEN -> {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
