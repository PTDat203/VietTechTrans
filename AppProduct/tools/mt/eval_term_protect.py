"""Measure IT term protection (term_protect.py) on the Phase 03 Validation rows that contain a listed term.

  translate  re-translate the rows whose source protection changes (canonical spelling or placeholders; packaged
             graph, app decoding policy, same order as the app: collapse_repeats, restore, fallback without
             placeholders); other rows keep the app's translation from evidence/phase04/mt_export_q4r (same input).
  summarize  chrF++/sacreBLEU on the rows with terms and on the whole set, and how often each protected term
             appears in the output (canonical spelling, case-insensitive), with and without protection.

  python -I tools/mt/eval_term_protect.py translate --direction vi_to_en --role selection_validation --shard 0/6 --out <part.jsonl>
  python -I tools/mt/eval_term_protect.py summarize --parts <dir> --out evidence/phase04/mt_term_protect
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
APP = HERE.parents[1]
sys.path.insert(0, str(HERE))
from marian_ref import OnnxMarian, app_max_length, beam_search, collapse_repeats, read_jsonl  # noqa: E402
from term_protect import TermProtector  # noqa: E402

CKPT = {"en_to_vi": "phase03-seqkd-en-to-vi-v2", "vi_to_en": "phase03-seqkd-vi-to-en-v3"}
PREFIX = {"en_to_vi": ">>vie<<", "vi_to_en": ""}
BASELINE = APP / "evidence/phase04/mt_export_q4r"


def cmd_translate(args) -> int:
    from transformers import AutoTokenizer

    ckpt = APP.parent / "Core/models/phase03" / CKPT[args.direction] / "final-epoch-1"
    tok = AutoTokenizer.from_pretrained(ckpt, local_files_only=True)
    gen = json.loads((ckpt / "generation_config.json").read_text(encoding="utf-8"))
    model = OnnxMarian(APP / f"app/src/main/assets/mt/{args.direction}/model.onnx", args.threads)
    tp = TermProtector()
    d = args.direction
    rows = [r for r in read_jsonl(BASELINE / f"{d}_{args.role}.predictions.jsonl") if tp.protect(r["source"], d)[0] != r["source"]]
    i, n = map(int, args.shard.split("/"))

    def translate(text: str) -> str:
        ids = tok(PREFIX[d] + text)["input_ids"]
        gen_ids = beam_search(model, ids, gen["decoder_start_token_id"], gen["eos_token_id"], num_beams=gen["num_beams"],
                              max_length=app_max_length(len(ids), gen["max_length"]), prefer_finished=True)
        return collapse_repeats(tok.decode(gen_ids, skip_special_tokens=True))

    out = []
    for r in rows[i::n]:
        protected, terms = tp.protect(r["source"], d)
        restored = TermProtector.restore(translate(protected), terms) if terms else translate(protected)
        fallback = restored is None
        if fallback:
            restored = translate(tp.protect(r["source"], d, placeholders=False)[0])
        changed = [m.group(0) for m in tp.pattern.finditer(r["source"]) if m.group(0) not in protected or m.group(0) in terms]
        out.append({"row_id": r["row_id"], "source": r["source"], "reference": r["reference"], "terms": terms,
                    "all_terms": [(t, tp.by_lower[t.lower()].canonical) for t in changed], "protected_source": protected,
                    "baseline": collapse_repeats(r["prediction"]), "protected": restored, "fallback": fallback})
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("".join(json.dumps(x, ensure_ascii=False) + "\n" for x in out), encoding="utf-8", newline="\n")
    print(f"TERM_EVAL part rows={len(out)} -> {args.out}")
    return 0


def cmd_summarize(args) -> int:
    sys.path.insert(0, str(APP.parent / "Core" / "src"))
    from core_mt.metrics import score_predictions

    args.out.mkdir(parents=True, exist_ok=True)
    summary = {"policy": "app decoding policy + collapse_repeats; selective protection from tools/mt/term_protect.py "
                         "(placeholders for terms flagged by term_risk.py, canonical spelling only for spellings it "
                         "flags, URLs / paths / ALL CAPS untouched), "
                         "tools/mt/it_terms.tsv (experiment of DR-07 §15, not used by the app)", "results": {}}
    for d in CKPT:
        for role in ("selection_validation", "general_validation"):
            parts = sorted(args.parts.glob(f"{d}_{role}.part*.jsonl"))
            if not parts:
                continue
            hit = {r["row_id"]: r for p in parts for r in read_jsonl(p)}
            base_rows = read_jsonl(BASELINE / f"{d}_{role}.predictions.jsonl")
            refs = [r["reference"] for r in base_rows]
            base_all = [collapse_repeats(r["prediction"]) for r in base_rows]
            prot_all = [hit[r["row_id"]]["protected"] if r["row_id"] in hit else b for r, b in zip(base_rows, base_all)]
            hrows = list(hit.values())
            if not hrows:
                summary["results"][f"{d}/{role}"] = {"rows": len(base_rows), "rows_with_terms": 0, "note": "no source changed"}
                print(d, role, "no source changed")
                continue

            def kept(rows, field):
                total = ok = 0
                for r in rows:
                    for typed, canonical in r["all_terms"]:
                        total += 1
                        ok += typed.lower() in r[field].lower() or canonical.lower() in r[field].lower()
                return ok, total

            ok_b, n = kept(hrows, "baseline")
            n = max(n, 1)
            ok_p, _ = kept(hrows, "protected")
            s_bh = score_predictions([r["baseline"] for r in hrows], [r["reference"] for r in hrows])
            s_ph = score_predictions([r["protected"] for r in hrows], [r["reference"] for r in hrows])
            s_ba = score_predictions(base_all, refs)
            s_pa = score_predictions(prot_all, refs)
            res = {
                "rows": len(base_rows), "rows_with_terms": len(hrows), "terms": n,
                "fallback_rows": sum(r["fallback"] for r in hrows),
                "term_kept": {"without": ok_b / n, "with": ok_p / n},
                "rows_with_terms_chrF++": {"without": s_bh["chrF++"], "with": s_ph["chrF++"]},
                "rows_with_terms_sacreBLEU": {"without": s_bh["sacreBLEU"], "with": s_ph["sacreBLEU"]},
                "all_rows_chrF++": {"without": s_ba["chrF++"], "with": s_pa["chrF++"]},
                "all_rows_sacreBLEU": {"without": s_ba["sacreBLEU"], "with": s_pa["sacreBLEU"]},
            }
            summary["results"][f"{d}/{role}"] = res
            with (args.out / f"{d}_{role}.rows.jsonl").open("w", encoding="utf-8", newline="\n") as f:
                for r in hrows:
                    f.write(json.dumps(r, ensure_ascii=False) + "\n")
            print(d, role, json.dumps(res, ensure_ascii=False))
    (args.out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"APP_MT_TERMS=RECORDED -> {args.out}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    t = sub.add_parser("translate")
    t.add_argument("--direction", choices=tuple(CKPT), required=True)
    t.add_argument("--role", default="selection_validation")
    t.add_argument("--shard", default="0/1")
    t.add_argument("--threads", type=int, default=1)
    t.add_argument("--out", type=Path, required=True)
    s = sub.add_parser("summarize")
    s.add_argument("--parts", type=Path, required=True)
    s.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()
    return {"translate": cmd_translate, "summarize": cmd_summarize}[args.cmd](args)


if __name__ == "__main__":
    raise SystemExit(main())
