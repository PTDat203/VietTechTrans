"""Scores the text the app read from the Dịch ảnh test pictures (DR-10) against truth.json.

  python tools/ocr/score_ocr.py evidence/phase04/ocr_mlkit_v1

Reads app_results.jsonl (one object per picture: id, ocr = the text the app put in the box, translation, ...) and
writes summary.json: CER and WER per picture and per language. Line breaks are compared as spaces (the app keeps one
line per block of the picture, and a picture can be split into blocks differently from its paragraphs).
"""
import json
import sys
from pathlib import Path


def lev(a, b) -> int:
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def flat(text: str) -> str:
    return " ".join(text.split())


def main() -> int:
    folder = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    truth = {t["id"]: t for t in json.loads((folder / "truth.json").read_text(encoding="utf-8"))}
    results = {}
    for line in (folder / "app_results.jsonl").read_text(encoding="utf-8").splitlines():
        if line.strip():
            r = json.loads(line)
            results[r["id"]] = r
    rows, by_lang = [], {}
    for cid, t in truth.items():
        ref, hyp = flat(t["text"]), flat(results[cid]["ocr"])
        char_err, word_err = lev(hyp, ref), lev(hyp.split(), ref.split())
        rows.append({"id": cid, "lang": t["lang"], "style": t["style"], "chars": len(ref), "char_errors": char_err,
                     "cer": round(char_err / len(ref), 4), "wer": round(word_err / len(ref.split()), 4),
                     "ocr_ms": results[cid].get("ocr_ms")})
        agg = by_lang.setdefault(t["lang"], {"pictures": 0, "chars": 0, "char_errors": 0})
        agg["pictures"] += 1
        agg["chars"] += len(ref)
        agg["char_errors"] += char_err
    for agg in by_lang.values():
        agg["cer"] = round(agg["char_errors"] / agg["chars"], 4)
    summary = {"pictures": rows, "by_language": by_lang}
    (folder / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    for r in rows:
        print(f"{r['id']:12s} {r['style']:6s} CER {r['cer'] * 100:5.1f}%  WER {r['wer'] * 100:5.1f}%")
    for lang, agg in by_lang.items():
        print(f"OCR_SCORE lang={lang} pictures={agg['pictures']} chars={agg['chars']} cer={agg['cer'] * 100:.1f}%")
    return 0


if __name__ == "__main__":
    sys.exit(main())
