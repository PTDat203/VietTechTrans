"""Tiers 1 and 2 of the text-rule policy evidence (docs/contracts/mt_app_rule_policy_v1.md §8) on the fixed case set
app/src/main/assets/text/mt_rule_cases_v1.json (tier 3 is the APK: Settings → "Bộ case rule dịch").

  tier 1  Student v1 before compression: the Phase 03 PyTorch checkpoint with its own generation_config
          (Hugging Face generate, num_beams / max_length 512 as trained). Shows what the checkpoint itself does.
  tier 2  the packaged q4r graph through tools/mt/marian_ref.py, twice:
          "pure"   the same search as tier 1 (max_length 512, no app rule): export/quantization only;
          "app"    the app's engine policy R10 (min(512, 2 x ids + 20), finished hypothesis preferred) and R11 (repeats).
No UI rule (R03-R08, R12) is applied here: every case with text is translated, also those the app keeps (R03/R04),
so the evidence shows what the model would have produced. Inputs get R01 only (as the app normalizes them).

  python -I tools/mt/run_rule_tiers.py --ckpt-root <dir with phase03-seqkd-*/final-epoch-1>
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import time
import unicodedata
from pathlib import Path

HERE = Path(__file__).resolve().parent
APP = HERE.parents[1]
sys.path.insert(0, str(HERE))
from marian_ref import OnnxMarian, app_max_length, beam_search, collapse_repeats  # noqa: E402

CASES = APP / "app/src/main/assets/text/mt_rule_cases_v1.json"
MANIFEST = APP / "app/src/main/assets/mt/manifest.json"
OUT = APP / "evidence/phase04/mt_rules_v1"
NL = "\n"
# R01 = Core build_phase01.norm() after deleting what Phase 01 rejected (MtInputNormalizer.DELETED_CODEPOINTS).
DELETED = set(range(0x00, 0x09)) | set(range(0x0E, 0x1C)) | set(range(0x7F, 0x85)) | set(range(0x86, 0xA0)) | {0xFFFD}
SPACE = re.compile(r"\s+")


def normalize(text: str) -> str:
    kept = "".join(ch for ch in text if ord(ch) not in DELETED)
    return SPACE.sub(" ", unicodedata.normalize("NFC", kept)).strip()


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ckpt-root", type=Path, default=APP.parent / "Core/models/phase03",
                    help="folder with phase03-seqkd-*/final-epoch-1 (real weights, not Git LFS pointers)")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--out", type=Path, default=OUT)
    args = ap.parse_args()

    import torch
    from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

    torch.set_num_threads(args.threads)
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    cases = json.loads(CASES.read_text(encoding="utf-8"))["cases"]
    args.out.mkdir(parents=True, exist_ok=True)
    rows = {"tier1_student": [], "tier2_q4r_pure": [], "tier2_q4r_app": []}
    for d in ("en_to_vi", "vi_to_en"):
        spec = manifest["directions"][d]
        run_dir = args.ckpt_root / spec["source_checkpoint"]["run_id"]
        ckpt = run_dir / "final-epoch-1" if (run_dir / "final-epoch-1").is_dir() else run_dir
        weights = ckpt / "model.safetensors"
        if weights.stat().st_size < 1024:
            raise SystemExit(f"{weights} is a Git LFS pointer; pass --ckpt-root with the real checkpoint")
        # Provenance: the weights must be the ones Core's Git LFS pointer names.
        pointer = APP.parent / spec["source_checkpoint"]["path"].replace("models/", "Core/models/", 1) / "model.safetensors"
        weights_sha = sha256(weights)
        oid = next((line.split(":", 1)[1].strip() for line in pointer.read_text(encoding="utf-8").splitlines()
                    if line.startswith("oid sha256:")), None) if pointer.is_file() and pointer.stat().st_size < 1024 else None
        if oid is not None and oid != weights_sha:
            raise SystemExit(f"{weights} is not the checkpoint of {pointer} ({weights_sha} != {oid})")
        tok = AutoTokenizer.from_pretrained(ckpt, local_files_only=True)
        model = AutoModelForSeq2SeqLM.from_pretrained(ckpt, local_files_only=True).eval()
        gen = json.loads((ckpt / "generation_config.json").read_text(encoding="utf-8"))
        onnx = OnnxMarian(APP / "app/src/main/assets/mt" / spec["model"], args.threads)
        prefix = spec["input_prefix"] if spec["input_prefix"] != "" else ""
        model_sha = next(f["sha256"] for f in manifest["files"] if f["path"] == spec["model"])
        for c in cases:
            if c["direction"] != d:
                continue
            text = normalize(c["input"])
            if not text:
                continue
            enc = tok(prefix + text, return_tensors="pt")
            ids = enc["input_ids"][0].tolist()
            base = {"id": c["id"], "rule": c["rule"], "direction": d, "input_normalized": text, "source_ids": ids,
                    "source_ids_count": len(ids)}
            t0 = time.perf_counter()
            with torch.no_grad():
                out = model.generate(**enc)[0].tolist()
            ms = (time.perf_counter() - t0) * 1000
            rows["tier1_student"].append({**base, "output": tok.decode(out, skip_special_tokens=True), "mt_ms": round(ms, 1),
                                          "checkpoint": spec["source_checkpoint"]["run_id"],
                                          "checkpoint_sha256_tree": spec["source_checkpoint"]["sha256_tree"],
                                          "weights_sha256": weights_sha, "weights_match_core_lfs_oid": oid == weights_sha,
                                          "generation": {k: gen.get(k) for k in ("num_beams", "max_length", "early_stopping")}})
            for name, cap, prefer in (("tier2_q4r_pure", gen["max_length"], False),
                                      ("tier2_q4r_app", app_max_length(len(ids), gen["max_length"]), True)):
                t0 = time.perf_counter()
                o = beam_search(onnx, ids, gen["decoder_start_token_id"], gen["eos_token_id"], num_beams=gen["num_beams"],
                                max_length=cap, prefer_finished=prefer)
                ms = (time.perf_counter() - t0) * 1000
                raw = tok.decode(o, skip_special_tokens=True)
                row = {**base, "output_raw": raw, "mt_ms": round(ms, 1), "package_id": manifest["package_id"],
                       "package_version": manifest["package_version"], "manifest_sha256": sha256(MANIFEST), "model_sha256": model_sha}
                if name == "tier2_q4r_app":
                    row["output"] = collapse_repeats(raw)
                    row["repeat_cleanup_applied"] = row["output"] != raw
                    row["max_output_ids"] = cap
                else:
                    row["output"] = raw
                rows[name].append(row)
            print(f"{d} {c['id']}: {rows['tier1_student'][-1]['output'][:60]!r} | q4r app {rows['tier2_q4r_app'][-1]['output'][:60]!r}", flush=True)
    for name, data in rows.items():
        (args.out / f"{name}.jsonl").write_text(NL.join(json.dumps(r, ensure_ascii=False) for r in data) + NL, encoding="utf-8", newline=NL)
    t1 = {r["id"]: r["output"] for r in rows["tier1_student"]}
    pure = {r["id"]: r["output"] for r in rows["tier2_q4r_pure"]}
    app = {r["id"]: r for r in rows["tier2_q4r_app"]}
    summary = {
        "case_set": "mt_rule_cases_v1", "cases_with_text": len(t1),
        "tier2_pure_equals_tier1": sum(pure[i] == t1[i] for i in t1),
        "tier2_app_equals_tier2_pure": sum(app[i]["output"] == pure[i] for i in t1),
        "repeat_cleanup_applied": [i for i in t1 if app[i]["repeat_cleanup_applied"]],
        "differences_tier1_vs_tier2_pure": {i: {"tier1": t1[i], "tier2_pure": pure[i]} for i in t1 if pure[i] != t1[i]},
        "note": "No UI rule applied (R03-R08, R12). Tier 3 (APK) is a separate file pulled from the device.",
    }
    (args.out / "tiers_summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1) + NL, encoding="utf-8", newline=NL)
    print(f"APP_MT_RULE_TIERS=RECORDED tier1={len(t1)} tier2_pure_equals_tier1={summary['tier2_pure_equals_tier1']} -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
