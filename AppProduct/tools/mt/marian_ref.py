"""Reference runtime for the on-device MT graph (build_marian_onnx.py) and its checks.

`beam_search` mirrors transformers 4.57 `GenerationMixin._beam_search` for one sentence with the
checkpoint's generation_config (num_beams: EN→VI 4, VI→EN 6; length_penalty=1.0,
early_stopping=False, max_length=512, forced EOS at max_length-1, pad forbidden, logits renormalized). The Kotlin
decoder (app/.../mt/marian/BeamSearch.kt) is a port of this function; golden files produced
here pin both to the same outputs.

Commands:
  verify   fp32/int8 graph vs Hugging Face on N sentences (teacher-forced log-probs + beam ids)
  eval     translate a Phase 03 prediction file with the graph, score it like Core, compare
           with the Phase 03 Student predictions (exact-match rate) and save everything
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort

LAYERS, HEADS, HEAD_DIM = 6, 8, 64
PREFIX = {"en_to_vi": ">>vie<<", "vi_to_en": ""}


class OnnxMarian:
    def __init__(self, path: Path, threads: int = 0):
        so = ort.SessionOptions()
        if threads:
            so.intra_op_num_threads = threads
        self.sess = ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])
        self.vocab = None

    def _feeds(self, ids, is_encoder, past_k, past_v, beam_idx, cross_k_t, cross_v):
        return {
            "input_ids": ids, "is_encoder": np.array(is_encoder), "past_k": past_k, "past_v": past_v,
            "beam_idx": beam_idx, "cross_k_t": cross_k_t, "cross_v": cross_v,
        }

    def encode(self, src_ids: list[int]):
        empty_past = np.zeros((LAYERS, 1, HEADS, 0, HEAD_DIM), np.float32)
        k, v, _ = self.sess.run(None, self._feeds(
            np.array([src_ids], np.int64), True, empty_past, empty_past, np.zeros((1,), np.int64),
            np.zeros((LAYERS, 1, HEADS, HEAD_DIM, 0), np.float32), np.zeros((LAYERS, 1, HEADS, 0, HEAD_DIM), np.float32)))
        return k, v

    def step(self, tokens, past_k, past_v, beam_idx, cross):
        pk, pv, lp = self.sess.run(None, self._feeds(
            np.array(tokens, np.int64).reshape(-1, 1), False, past_k, past_v, np.array(beam_idx, np.int64), cross[0], cross[1]))
        return lp, pk, pv


def _top_k(scores: np.ndarray, k: int) -> np.ndarray:
    """Indices of the k largest values, best first; ties keep the lower index (torch.topk on CPU)."""
    part = np.argpartition(-scores, k - 1)[:k] if k < scores.size else np.arange(scores.size)
    return part[np.lexsort((part, -scores[part]))]


def beam_search(model: OnnxMarian, src_ids: list[int], start_id: int, eos_id: int,
                num_beams: int = 4, max_length: int = 512, length_penalty: float = 1.0,
                prefer_finished: bool = False, stats: dict | None = None) -> list[int]:
    """Returns the generated ids (without the decoder start token, with EOS if produced).

    prefer_finished (app policy with app_max_length): on reaching max_length, return the best finished
    hypothesis if there is one instead of forcing EOS on the running beams (HF). Forced EOS lets a long
    repetitive beam beat a complete short sentence by its average log-prob. With it, every output shorter
    than max_length - 1 tokens is the same as without the cap: the search is identical up to there and
    the best hypothesis is already among the finished ones. stats["steps"] gets the decoder steps run.
    """
    cross = model.encode(src_ids)
    k, keep, prompt_len, cur_len = num_beams, 2 * num_beams, 1, 1
    neg = np.float32(-1.0e9)
    running = [[start_id]]  # first step: one beam (HF's other beams start at -1e9 and never enter the top-k)
    running_scores = np.zeros(1, np.float32)
    fin_seqs = [[start_id] for _ in range(k)]
    fin_scores = np.full(k, neg, np.float32)
    fin_done = np.zeros(k, bool)
    early_unsatisfied = True
    past_k = past_v = np.zeros((LAYERS, 1, HEADS, 0, HEAD_DIM), np.float32)
    beam_idx = [0]
    steps = 0
    while True:
        if prefer_finished and cur_len == max_length - 1 and bool(np.any(fin_done)):
            break
        lp, past_k, past_v = model.step([s[-1] for s in running], past_k, past_v, beam_idx, cross)
        steps += 1
        if cur_len == max_length - 1:  # ForcedEOSTokenLogitsProcessor (+ renormalization)
            lp = np.full_like(lp, -np.inf)
            lp[:, eos_id] = 0.0
        vocab = lp.shape[1]
        acc = (lp + running_scores[:, None]).reshape(-1).astype(np.float32)
        top = _top_k(acc, keep)
        top_lp = acc[top]
        parents, tokens = top // vocab, top % vocab
        top_seqs = [running[p] + [int(t)] for p, t in zip(parents, tokens)]
        hits = (tokens == eos_id) | (cur_len + 1 >= max_length)
        # e. next running beams: best non-finished continuations
        run_lp = (top_lp + hits.astype(np.float32) * neg).astype(np.float32)
        nxt = _top_k(run_lp, k)
        running = [top_seqs[j] for j in nxt]
        running_scores = run_lp[nxt]
        beam_idx = [int(parents[j]) for j in nxt]
        # f. finished hypotheses (only the top num_beams candidates may finish)
        did_finish = hits & (np.arange(keep) < k)
        fin_lp = (top_lp / np.float32((cur_len + 1 - prompt_len) ** length_penalty)).astype(np.float32)
        if not early_unsatisfied:
            fin_lp = fin_lp + neg
        fin_lp = (fin_lp + (~did_finish).astype(np.float32) * neg).astype(np.float32)
        merged_scores = np.concatenate([fin_scores, fin_lp])
        merged_seqs = fin_seqs + top_seqs
        merged_done = np.concatenate([fin_done, did_finish])
        best = _top_k(merged_scores, k)
        fin_seqs = [merged_seqs[j] for j in best]
        fin_scores = merged_scores[best]
        fin_done = merged_done[best]
        # g. stopping
        cur_len += 1
        best_possible = running_scores[0] / np.float32((cur_len - prompt_len) ** length_penalty)
        worst_finished = np.where(fin_done, fin_scores.min(), neg)
        early_unsatisfied = early_unsatisfied and bool(np.any(best_possible > worst_finished))
        if not (early_unsatisfied and not bool(np.all(hits))):
            break
    if stats is not None:
        stats["steps"] = steps
    return fin_seqs[0][1:]


def greedy(model: OnnxMarian, src_ids: list[int], start_id: int, eos_id: int, max_length: int = 512) -> list[int]:
    cross = model.encode(src_ids)
    past_k = past_v = np.zeros((LAYERS, 1, HEADS, 0, HEAD_DIM), np.float32)
    out = [start_id]
    while len(out) < max_length:
        lp, past_k, past_v = model.step([out[-1]], past_k, past_v, [0], cross)
        nxt = eos_id if len(out) == max_length - 1 else int(np.argmax(lp[0]))
        out.append(nxt)
        if nxt == eos_id:
            break
    return out[1:]


# App policy on top of the checkpoint's generation_config (DR-07, 2026-10-07): the output may be at most
# 2 x source tokens + 20 long, and at that length the best finished hypothesis wins (beam_search
# prefer_finished). Without it, beam search with early_stopping=False keeps extending the other beams up
# to max_length=512 when the best one finished early (e.g. VI→EN "chào bạn" -> "Hello": 511 steps), and
# degenerate repetitions run to 512 tokens. On Validation only degenerate outputs are longer.
LENGTH_CAP_RATIO, LENGTH_CAP_SLACK = 2, 20


def app_max_length(src_len: int, max_length: int) -> int:
    """max_length used by the app for a source of src_len ids (prefix and EOS included)."""
    return min(max_length, LENGTH_CAP_RATIO * src_len + LENGTH_CAP_SLACK)


_WORD_KEY = re.compile(r"[\W_]+")
_END_PUNCT = re.compile(r"[\W_]*$")


def collapse_repeats(text: str, min_repeats: int = 4, max_unit: int = 3) -> str:
    """App post-processing (DR-07, 2026-10-07): a unit of 1-3 words repeated >= 4 times in a row is a
    decoding loop ("Please, please, please, ..."); keep the first occurrence. Words compare by their
    letters and digits, case-insensitive. A loop that ends the text gives its last punctuation to the kept
    unit; a trailing ",;:" is dropped. Mirrored by OutputCleanup.collapseRepeats in the app."""
    words = text.split()
    keys = [_WORD_KEY.sub("", w).lower() for w in words]
    out, i, collapsed = [], 0, False
    while i < len(words):
        for n in range(1, max_unit + 1):
            unit = keys[i:i + n]
            if len(unit) < n or not all(unit):
                continue
            reps = 1
            while keys[i + reps * n:i + (reps + 1) * n] == unit:
                reps += 1
            if reps >= min_repeats:
                kept = words[i:i + n]
                end = i + reps * n
                if end == len(words):
                    kept[-1] = _END_PUNCT.sub("", kept[-1]) + _END_PUNCT.search(words[-1]).group(0)
                out += kept
                i = end
                collapsed = True
                break
        else:
            out.append(words[i])
            i += 1
    return " ".join(out).rstrip(",;: ") if collapsed else text


def generation_config(checkpoint: Path) -> dict:
    return json.loads((checkpoint / "generation_config.json").read_text(encoding="utf-8"))


def load_tokenizer(checkpoint: Path):
    from transformers import AutoTokenizer
    return AutoTokenizer.from_pretrained(checkpoint, local_files_only=True)


def read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def cmd_verify(args) -> int:
    import torch
    from transformers import MarianMTModel

    tok = load_tokenizer(args.checkpoint)
    hf = MarianMTModel.from_pretrained(args.checkpoint, local_files_only=True).eval()
    model = OnnxMarian(args.onnx, args.threads)
    rows = read_jsonl(args.predictions)[: args.n]
    start, eos = hf.config.decoder_start_token_id, hf.config.eos_token_id
    gen = generation_config(args.checkpoint)
    max_diff, exact, exact_phase3 = 0.0, 0, 0
    for i, row in enumerate(rows):
        ids = tok(PREFIX[args.direction] + row["source"])["input_ids"]
        with torch.inference_mode():
            ref = hf.generate(torch.tensor([ids]))[0].tolist()
        ref_gen = [t for t in ref[1:] if t != hf.config.pad_token_id]
        ours = beam_search(model, ids, start, eos, num_beams=gen["num_beams"], max_length=gen["max_length"])
        exact += ours == ref_gen
        text = tok.decode(ours, skip_special_tokens=True)
        exact_phase3 += text == row["prediction"]
        if i < 3:  # teacher-forced log-probs along the reference path
            cross = model.encode(ids)
            past_k = past_v = np.zeros((LAYERS, 1, HEADS, 0, HEAD_DIM), np.float32)
            dec = [start] + ref_gen
            with torch.inference_mode():
                logits = hf(input_ids=torch.tensor([ids]), decoder_input_ids=torch.tensor([dec])).logits[0]
                logits[:, hf.config.pad_token_id] = -float("inf")
                ref_lp = torch.log_softmax(logits, -1).numpy()
            for t in range(len(dec)):
                lp, past_k, past_v = model.step([dec[t]], past_k, past_v, [0], cross)
                finite = np.isfinite(ref_lp[t])
                max_diff = max(max_diff, float(np.abs(lp[0][finite] - ref_lp[t][finite]).max()))
        if ours != ref_gen and args.show:
            print("DIFF", row["source"][:80], "\n  hf :", tok.decode(ref_gen, skip_special_tokens=True), "\n  ort:", text)
    print(f"MT_VERIFY direction={args.direction} onnx={args.onnx.name} beams={gen['num_beams']} n={len(rows)} "
          f"beam_ids_equal_hf={exact}/{len(rows)} text_equal_phase03={exact_phase3}/{len(rows)} "
          f"max_teacher_forced_logprob_diff={max_diff:.2e}")
    return 0


def cmd_eval(args) -> int:
    sys.path.insert(0, str(args.core / "src"))
    from core_mt.metrics import score_predictions

    tok = load_tokenizer(args.checkpoint)
    model = OnnxMarian(args.onnx, args.threads)
    start, eos = tok.pad_token_id, tok.eos_token_id
    rows = read_jsonl(args.predictions)
    if args.shard:
        idx, total = map(int, args.shard.split("/"))
        rows = rows[idx::total]
    out_rows, t0 = [], time.perf_counter()
    gen_cfg = generation_config(args.checkpoint)
    beams, max_length = args.beams or gen_cfg["num_beams"], gen_cfg["max_length"]
    def cap(s):
        return app_max_length(len(s), max_length) if args.length_cap else max_length

    stats = {}
    if beams == 1:
        def decode(m, s, a, b):
            return greedy(m, s, a, b, cap(s))
    else:
        def decode(m, s, a, b):
            return beam_search(m, s, a, b, num_beams=beams, max_length=cap(s), prefer_finished=args.length_cap, stats=stats)
    for row in rows:
        ids = tok(PREFIX[args.direction] + row["source"])["input_ids"]
        t = time.perf_counter()
        gen = decode(model, ids, start, eos)
        out_rows.append({"row_id": row["row_id"], "source": row["source"], "reference": row["reference"],
                         "phase03_prediction": row["prediction"], "prediction": tok.decode(gen, skip_special_tokens=True),
                         "src_tokens": len(ids), "out_tokens": len(gen), "steps": stats.get("steps"),
                         "ms": round((time.perf_counter() - t) * 1000, 1)})
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", encoding="utf-8", newline="\n") as f:
        for r in out_rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    if not args.shard:
        summarize([args.out], args.out.with_suffix(".summary.json"), score_predictions)
    print(f"MT_EVAL done rows={len(out_rows)} seconds={time.perf_counter() - t0:.0f} -> {args.out}")
    return 0


def summarize(paths: list[Path], out: Path, score_predictions) -> dict:
    rows = [r for p in paths for r in read_jsonl(p)]
    ours = score_predictions([r["prediction"] for r in rows], [r["reference"] for r in rows])
    phase3 = score_predictions([r["phase03_prediction"] for r in rows], [r["reference"] for r in rows])
    ms = np.array([r["ms"] for r in rows])
    summary = {
        "rows": len(rows),
        "onnx": {"chrF++": ours["chrF++"], "sacreBLEU": ours["sacreBLEU"]},
        "phase03_student": {"chrF++": phase3["chrF++"], "sacreBLEU": phase3["sacreBLEU"]},
        "exact_match_with_phase03": sum(r["prediction"] == r["phase03_prediction"] for r in rows) / len(rows),
        "host_latency_ms": {"mean": float(ms.mean()), "p50": float(np.percentile(ms, 50)), "p95": float(np.percentile(ms, 95))},
        "metric_config": ours["metric_config"], "sacrebleu_version": ours["sacrebleu_version"],
    }
    out.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps(summary, ensure_ascii=False))
    return summary


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("verify", "eval"):
        p = sub.add_parser(name)
        p.add_argument("--checkpoint", type=Path, required=True)
        p.add_argument("--onnx", type=Path, required=True)
        p.add_argument("--direction", choices=tuple(PREFIX), required=True)
        p.add_argument("--predictions", type=Path, required=True, help="Phase 03 student_predictions.jsonl")
        p.add_argument("--threads", type=int, default=0)
    sub.choices["verify"].add_argument("--n", type=int, default=30)
    sub.choices["verify"].add_argument("--show", action="store_true")
    sub.choices["eval"].add_argument("--out", type=Path, required=True)
    sub.choices["eval"].add_argument("--core", type=Path, default=Path(__file__).resolve().parents[3] / "Core")
    sub.choices["eval"].add_argument("--beams", type=int, help="default: num_beams of the checkpoint's generation_config; 1 = greedy")
    sub.choices["eval"].add_argument("--shard", help="i/n: translate every n-th row starting at i")
    sub.choices["eval"].add_argument("--length-cap", action="store_true",
                                     help="app policy as on the phone: app_max_length + prefer_finished")
    sub.add_parser("summarize").add_argument("files", type=Path, nargs="+")
    args = ap.parse_args()
    if args.cmd == "summarize":
        sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "Core" / "src"))
        from core_mt.metrics import score_predictions
        summarize(args.files[:-1], args.files[-1], score_predictions)
        return 0
    return {"verify": cmd_verify, "eval": cmd_eval}[args.cmd](args)


if __name__ == "__main__":
    raise SystemExit(main())
