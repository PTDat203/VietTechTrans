"""Compare Student v1 (Core) and the app (q4r + text rules) on the four Validation cohorts, split by what the app does.

Input: the per-row rule decisions written by the JVM test TextRulesCohortTest (cohort_rows.jsonl: decision R02–R07,
app output after R11, R12/R08 checks of the app output, of the Student v1 output and of the reference) and the
predictions of evidence/phase04/mt_export_q4r (source, reference, Student v1 output, q4r output with R10).

Three tables per set:
  A  rows the app sends to the model (decision "translate"): Student v1 vs app output, chrF++ / sacreBLEU with Core's
     core_mt.metrics.score_predictions on the same rows; also without the rows R12 rejects (selection depends on
     the app output, so it favours the app and is reported separately);
  B  rows the app does not translate or rejects (R02, R03, R04, R06, R12): counts and checks, no MT metric;
  C  every row by group.

Usage:
  python -I tools/mt/score_rule_cohort.py --rows <cohort_rows.jsonl> --out evidence/phase04/mt_rules_v1_cohort
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
APP = HERE.parents[1]
sys.path.insert(0, str(APP.parent / "Core" / "src"))

from core_mt.metrics import score_predictions  # noqa: E402

SETS = ("en_to_vi/selection_validation", "en_to_vi/general_validation",
        "vi_to_en/selection_validation", "vi_to_en/general_validation")


def read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def compare(rows: list[dict]) -> dict:
    """Student v1 and app on the same rows."""
    if not rows:
        return {"rows": 0}
    refs = [r["reference"] for r in rows]
    student = score_predictions([r["phase03_prediction"] for r in rows], refs)
    app = score_predictions([r["app_output"] for r in rows], refs)
    out = {"rows": len(rows)}
    for k in ("chrF++", "sacreBLEU"):
        out[k] = {"student_v1": round(student[k], 2), "app": round(app[k], 2), "delta": round(app[k] - student[k], 2)}
    out["identical_outputs"] = sum(r["phase03_prediction"] == r["app_output"] for r in rows)
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--rows", type=Path, required=True, help="cohort_rows.jsonl of TextRulesCohortTest")
    ap.add_argument("--preds", type=Path, default=APP / "evidence/phase04/mt_export_q4r")
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()

    decisions = {(r["set"], r["row_id"]): r for r in read_jsonl(args.rows)}
    results, inputs = {}, {"rows": {"path": str(args.rows.name), "sha256": sha256(args.rows)}}
    cases = []
    for name in SETS:
        path = args.preds / f"{name.replace('/', '_')}.predictions.jsonl"
        inputs[name] = {"path": f"evidence/phase04/mt_export_q4r/{path.name}", "sha256": sha256(path)}
        rows = []
        for p in read_jsonl(path):
            d = decisions[(name, p["row_id"])]
            rows.append({**p, **d})
        if len(rows) != sum(1 for k in decisions if k[0] == name):
            raise SystemExit(f"{name}: rows differ between {path} and {args.rows}")

        translate = [r for r in rows if r["decision"] == "translate"]
        shown = [r for r in translate if "r12" not in r["app_check"]]
        rejected = [r for r in translate if "r12" in r["app_check"]]
        # Table B rows (and R11 changes) for review: what the app did, Student v1 and the reference side by side.
        for r in rows:
            group = r["decision"] if r["decision"] != "translate" else (
                "reject_R12" if "r12" in r["app_check"] else "collapse_R11" if r["r11_changed"] else None)
            if group:
                cases.append({"set": name, "group": group, "row_id": r["row_id"], "literal": r.get("literal"),
                              "source": r["source"], "reference": r["reference"], "student_v1": r["phase03_prediction"],
                              "app_output": r["app_output"] if r["decision"] == "translate" else None,
                              "missing": r["app_check"].get("missing"),
                              "student_v1_r12": "r12" in r["student_check"], "reference_r12": "r12" in r["reference_check"]})

        def kept(rule: str) -> dict:
            rs = [r for r in rows if r["decision"] == f"keep_{rule}"]
            src = [r["source"].strip() for r in rs]
            return {
                "rows": len(rs),
                "literal_kinds": dict(Counter(r.get("literal", "-") for r in rs).most_common()),
                "reference_identical": sum(r["reference"].strip() == s for r, s in zip(rs, src)),
                "reference_contains_source": sum(s in r["reference"] for r, s in zip(rs, src)),
                "student_v1_identical": sum(r["phase03_prediction"].strip() == s for r, s in zip(rs, src)),
                "student_v1_contains_source": sum(s in r["phase03_prediction"] for r, s in zip(rs, src)),
            }

        table_b = {
            "R02_too_long": sum(r["decision"] == "too_long_R02" for r in rows),
            "R02_empty": sum(r["decision"] == "empty_R02" for r in rows),
            "R03_keep": kept("R03"),
            "R04_keep": kept("R04"),
            "R06_ask": {
                "rows": sum(r["decision"] == "mismatch_R06" for r in rows),
                "source_equals_reference": sum(r["decision"] == "mismatch_R06" and r["source"].strip() == r["reference"].strip() for r in rows),
            },
            "R12_reject": {
                "rows": len(rejected),
                "reasons": dict(Counter(r["app_check"]["r12"] for r in rejected)),
                "student_v1_also_rejected": sum("r12" in r["student_check"] for r in rejected),
                "reference_also_fails_check": sum("r12" in r["reference_check"] for r in rejected),
                "student_v1_rejected_on_translate_rows": sum("r12" in r["student_check"] for r in translate),
            },
            "R08_flag": {
                "app": sum(bool(r["app_check"].get("r08")) for r in translate),
                "student_v1": sum(bool(r["student_check"].get("r08")) for r in translate),
            },
            "R11_changed": sum(r["r11_changed"] for r in translate),
            "R01_changed": sum(r["r01_changed"] for r in rows),
        }
        notes = Counter(n for r in translate for n in r.get("notes", []))
        table_c = {
            "rows": len(rows),
            "translate_shown": len(shown),
            "translate_rejected_R12": len(rejected),
            "keep_R03": table_b["R03_keep"]["rows"],
            "keep_R04": table_b["R04_keep"]["rows"],
            "ask_R06": table_b["R06_ask"]["rows"],
            "blocked_R02": table_b["R02_too_long"] + table_b["R02_empty"],
            "translate_notes": dict(notes.most_common()),
            "translate_flag_R08": table_b["R08_flag"]["app"],
            "translate_R11": table_b["R11_changed"],
        }
        results[name] = {
            "A_translate_rows": compare(translate),
            "A_shown_rows": compare(shown),
            "A_rejected_rows": compare(rejected),
            "all_rows_model_only": compare([{**r, "app_output": r["prediction"]} for r in rows]),
            "B": table_b,
            "C": table_c,
        }
        a = results[name]["A_translate_rows"]
        print(name, "A:", a["rows"], a["chrF++"], "| C:", table_c)

    args.out.mkdir(parents=True, exist_ok=True)
    run = {
        "stage": "phase04_app_rules_cohort",
        "question": "Student v1 (Core) vs app q4r + text rules, on the rows the app really translates (A), "
                    "the rows it keeps, asks about, blocks or rejects (B), and all rows by group (C)",
        "app_output": "q4r with R10 (mt_export_q4r prediction) then R11 collapse_repeats; R09 never splits a row of "
                      "at most 500 characters; R01 is the Phase 01 norm() the cohorts already went through",
        "metrics": "core_mt.metrics.score_predictions (chrF++ beta=2 word_order=2; sacreBLEU tokenize=none), "
                   "both systems scored on the same rows",
        "note": "A_shown_rows drops the rows R12 rejects; that choice depends on the app output, so it favours the app. "
                "A_translate_rows is the fair comparison.",
        "inputs": inputs,
        "results": results,
        "created_at_utc": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
    }
    (args.out / "run.json").write_text(json.dumps(run, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    with (args.out / "cases.jsonl").open("w", encoding="utf-8", newline="\n") as f:
        for c in cases:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")
    print(f"APP_RULES_COHORT=RECORDED -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
