"""Package the frozen Phase 03 Main-Core into app/src/main/assets/mt/ (Phase 04, app side).

For each direction:
  1. check the checkpoint tree hash against Core's main_core_decision.json,
  2. build the graph (build_marian_onnx.py, default q4r: 4-bit block 16 + 4+4-bit LM head),
  3. copy source.spm and write vocab.txt (line i = token id i),
  4. translate golden_prompts.json with the packaged graph (marian_ref.beam_search)
     -> golden.jsonl, which the app re-runs to prove its Kotlin port gives the same ids,
  5. write tokenizer parity fixtures (Hugging Face ids) to .cache/mt/fixtures/ for the JVM tests.
Then writes assets/mt/manifest.json (docs/contracts/mt_package_v2.md) with sizes and SHA-256.
Nothing here is committed: the checkpoints stay in Core/models (ignored), like the speech models.

Usage (repo venv):
  python -I tools/mt/package_mt.py [--variant q4r] [--quality evidence/phase04/mt_export_q4r]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
APP = HERE.parents[1]
REPO = APP.parent
CORE = REPO / "Core"
sys.path.insert(0, str(HERE))

from build_marian_onnx import build  # noqa: E402
from marian_ref import PREFIX, OnnxMarian, beam_search, read_jsonl  # noqa: E402

DECISION_REL = "Core/evidence/phase03/main_core_decision.json"
EXPECTED_GENERATION = {"max_length": 512, "renormalize_logits": True}  # num_beams is per direction (4 / 6)
# Package variant -> build_marian_onnx.build(variant, block, clip). q4b16c since 2026-10-08 (DR-07 §13): ONNX
# Runtime 1.30 has a fast 8-bit kernel on ARM only with dotprod/i8mm; without them q8 dequantizes every
# weight matrix on every decoder step (15-30 s per sentence on such phones). 4-bit has ARM kernels for all.
# q4r since 2026-10-08 (DR-07 §14): the 4-bit LM head cost most of the remaining quality; storing it as 4-bit +
# 4-bit residual keeps 8-bit precision there with 4-bit kernels only.
VARIANTS = {"fp32": ("fp32", 32, False), "q8": ("q8", 32, False), "q4b16c": ("q4", 16, True), "q4r": ("q4r", 16, True)}
QUANTIZATION = {
    "q8": {"method": "matmul_nbits", "bits": 8, "block_size": 32, "symmetric": False, "clip_search": False, "accuracy_level": 4,
           "note": "8-bit weights per block of 32; activations quantized per block at compute time"},
    "q4b16c": {"method": "matmul_nbits", "bits": 4, "block_size": 16, "symmetric": False, "clip_search": True, "accuracy_level": 4,
               "note": "4-bit weights per block of 16, clipping range per block chosen for the least squared error; "
                       "int8 compute where the CPU has it (dotprod), fp32 compute otherwise"},
    "q4r": {"method": "matmul_nbits", "bits": 4, "block_size": 16, "symmetric": False, "clip_search": True, "accuracy_level": 4,
            "lm_head": "4-bit + 4-bit residual (two MatMulNBits, summed); embedding lookup uses both",
            "note": "as q4b16c for every Linear; the tied embedding / LM head is W ~ Q1 + Q2 with Q2 quantizing W - Q1"},
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def sha256_tree(directory: Path) -> str:
    """Same algorithm as Core/tools/run_phase03_teacher_targets.py."""
    digest = hashlib.sha256()
    for path in sorted(item for item in directory.rglob("*") if item.is_file()):
        digest.update(path.relative_to(directory).as_posix().encode("utf-8"))
        digest.update(b"\0")
        with path.open("rb") as handle:
            for block in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(block)
        digest.update(b"\0")
    return digest.hexdigest()


def load_decision(ref: str) -> tuple[dict, str, str]:
    """From the working tree if present, else from git (the decision lives on `main`)."""
    path = REPO / DECISION_REL
    if path.is_file():
        data, source = path.read_bytes(), DECISION_REL
    else:
        data = subprocess.run(["git", "-C", str(REPO), "show", f"{ref}:{DECISION_REL}"], capture_output=True, check=True).stdout
        commit = subprocess.run(["git", "-C", str(REPO), "rev-parse", ref], capture_output=True, check=True, text=True).stdout.strip()
        source = f"git {commit}:{DECISION_REL}"
    return json.loads(data), hashlib.sha256(data).hexdigest(), source


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")


def fixture_texts(direction: str, prompts: dict) -> list[str]:
    """Edge cases + all General Validation + every 4th IT Validation source (Core data, not committed)."""
    src = "en" if direction == "en_to_vi" else "vi"
    texts = list(prompts["tokenizer_edge_cases"]) + list(prompts[direction])
    general = CORE / "data/processed/general_validation_en_vi/general_validation.jsonl"
    it_val = CORE / "data/processed/it_en_vi/validation.jsonl"
    if general.is_file():
        texts += [r[src] for r in read_jsonl(general)]
    if it_val.is_file():
        texts += [r[f"{src}_clean"] for r in read_jsonl(it_val)[::4]]
    return texts


def package_direction(direction: str, d: dict, args, prompts: dict) -> dict:
    from transformers import AutoTokenizer

    if d["status"] != "frozen_for_phase04":
        raise SystemExit(f"{direction}: decision status is {d['status']}, not frozen_for_phase04")
    ckpt = CORE / d["checkpoint"]["path"]
    tree = sha256_tree(ckpt)
    if tree != d["checkpoint"]["sha256_tree"]:
        raise SystemExit(f"{direction}: checkpoint tree hash {tree} differs from the decision")
    gen_cfg = json.loads((ckpt / "generation_config.json").read_text(encoding="utf-8"))
    for key, value in EXPECTED_GENERATION.items():
        if gen_cfg.get(key) != value:
            raise SystemExit(f"{direction}: generation_config {key}={gen_cfg.get(key)} is not supported by the app")
    pad, eos, start = gen_cfg["pad_token_id"], gen_cfg["eos_token_id"], gen_cfg["decoder_start_token_id"]
    if gen_cfg.get("bad_words_ids") != [[pad]] or gen_cfg.get("forced_eos_token_id") != eos:
        raise SystemExit(f"{direction}: unexpected bad_words_ids/forced_eos_token_id")

    out = args.out / direction
    out.mkdir(parents=True, exist_ok=True)
    import onnx
    variant, block, clip = VARIANTS[args.variant]
    onnx.save_model(build(ckpt, variant, block=block, clip=clip), out / "model.onnx")
    shutil.copyfile(ckpt / "source.spm", out / "source.spm")
    vocab = json.loads((ckpt / "vocab.json").read_text(encoding="utf-8"))
    tokens = sorted(vocab, key=vocab.get)
    if [vocab[t] for t in tokens] != list(range(len(tokens))) or any(("\n" in t or "\r" in t) for t in tokens):
        raise SystemExit(f"{direction}: vocab.json ids are not 0..V-1 or a token contains a line break")
    (out / "vocab.txt").write_text("\n".join(tokens) + "\n", encoding="utf-8", newline="\n")

    tok = AutoTokenizer.from_pretrained(ckpt, local_files_only=True)
    model = OnnxMarian(out / "model.onnx")
    golden = []
    for prompt in prompts[direction]:
        ids = tok(PREFIX[direction] + prompt)["input_ids"]
        gen = beam_search(model, ids, start, eos, num_beams=gen_cfg["num_beams"], max_length=gen_cfg["max_length"])
        golden.append({"source": prompt, "input_ids": ids, "output_ids": gen, "text": tok.decode(gen, skip_special_tokens=True)})
    write_jsonl(out / "golden.jsonl", golden)
    fixtures = [{"text": t, "ids": tok(PREFIX[direction] + t)["input_ids"]} for t in fixture_texts(direction, prompts)]
    fixtures += [{"decode_ids": g["output_ids"], "text": g["text"]} for g in golden]
    write_jsonl(APP / ".cache/mt/fixtures" / f"{direction}_tokenizer.jsonl", fixtures)
    print(f"{direction}: model {(out / 'model.onnx').stat().st_size / 1e6:.1f} MB, golden {len(golden)}, fixtures {len(fixtures)}")

    quality = {"phase03_checkpoint": {
        role: {"rows": e["rows"], "chrF++": e["student_metrics"]["chrF++"], "sacreBLEU": e["student_metrics"]["sacreBLEU"]}
        for role, e in d["evaluation"].items()}}
    if args.quality:
        exported = {}
        for role in ("selection_validation", "general_validation"):
            path = args.quality / f"{direction}_{role}.summary.json"
            if path.is_file():
                exported[role] = json.loads(path.read_text(encoding="utf-8")) | {"summary_sha256": sha256_file(path)}
        quality["exported"] = exported or None
    return {
        "model": f"{direction}/model.onnx",
        "tokenizer": {
            "type": "marian_sentencepiece_unigram", "source_spm": f"{direction}/source.spm", "vocab": f"{direction}/vocab.txt",
            "vocab_size": len(tokens), "eos_id": eos, "unk_id": vocab["<unk>"], "pad_id": pad,
        },
        "input_prefix": PREFIX[direction],
        "generation": {
            "strategy": "beam_search", "num_beams": gen_cfg["num_beams"], "max_length": gen_cfg["max_length"],
            "length_penalty": gen_cfg.get("length_penalty", 1.0), "early_stopping": gen_cfg.get("early_stopping", False),
            "decoder_start_token_id": start, "eos_token_id": eos, "forced_eos_token_id": eos,
            "bad_words_ids": [[pad]], "renormalize_logits": True,
        },
        "input_length_policy": {"max_input_tokens": json.loads((ckpt / "config.json").read_text())["max_position_embeddings"],
                                "on_exceed": "split_sentences"},
        "source_checkpoint": {"run_id": d["run_id"], "path": d["checkpoint"]["path"], "sha256_tree": tree, "student": d["student"]},
        "golden_file": f"{direction}/golden.jsonl",
        "quality": quality,
    }


def main() -> int:
    import onnxruntime

    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--variant", default="q4r", choices=tuple(VARIANTS))
    ap.add_argument("--out", type=Path, default=APP / "app/src/main/assets/mt")
    ap.add_argument("--decision-ref", default="main", help="git ref holding the decision when it is not in the working tree")
    ap.add_argument("--quality", type=Path, help="directory with <direction>_<role>.summary.json from marian_ref.py eval")
    args = ap.parse_args()

    decision, decision_sha, decision_source = load_decision(args.decision_ref)
    prompts = json.loads((HERE / "golden_prompts.json").read_text(encoding="utf-8"))
    if args.out.exists():
        shutil.rmtree(args.out)
    directions = {direction: package_direction(direction, decision["decisions"][direction], args, prompts) for direction in PREFIX}
    shutil.copyfile(HERE / "LICENSE_NOTES.md", args.out / "LICENSE_NOTES.md")

    files = [{"path": p.relative_to(args.out).as_posix(), "bytes": p.stat().st_size, "sha256": sha256_file(p)}
             for p in sorted(args.out.rglob("*")) if p.is_file()]
    manifest = {
        "schema_version": "2.0",
        "package_id": f"vtt-main-core-phase03-{args.variant}",
        "package_version": "1.0.0",
        "created_at_utc": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "model_role": "main_core",
        "architecture": "MarianMT",
        "runtime": {"engine": "onnxruntime", "engine_version": onnxruntime.__version__, "execution_provider": "cpu",
                    "abis": ["arm64-v8a", "x86_64"], "opset": 17, "graph": "tools/mt/build_marian_onnx.py"},
        "quantization": QUANTIZATION.get(args.variant, {"method": "none"}),
        "input_normalization": "phase01_nfc_whitespace_v1",
        "directions": directions,
        "files": files,
        "core_refs": [{"path": DECISION_REL, "sha256": decision_sha, "source": decision_source}],
        "license_notes": "LICENSE_NOTES.md",
    }
    (args.out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    total = sum(f["bytes"] for f in files) / 1e6
    print(f"APP_MT_PACKAGE=PASS variant={args.variant} files={len(files)} total={total:.1f} MB -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
