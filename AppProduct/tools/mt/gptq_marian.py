"""GPTQ 4-bit quantization of a Phase 03 Main-Core (MarianMT) checkpoint for build_marian_onnx.py.

Plain rounding (RTN) of 4-bit blocks of 16 cost 0.06-0.34 chrF++ against the 8-bit package (DR-07 §13).
GPTQ (Frantar et al., 2022) quantizes the input columns of each Linear one by one and spreads the rounding
error over the columns not yet quantized, weighted by the inverse Hessian H = X^T X of the layer inputs X
seen on calibration sentences. Same MatMulNBits layout as RTN (asymmetric, per row, blocks of 16 input
features; the clipping range of every block is still searched for the least squared error), so file size and
speed do not change.

Calibration: 256 sentence pairs drawn with a fixed seed from Core's IT train split (never the Validation sets
used for scoring), teacher-forced through the fp32 checkpoint one sentence at a time.

Output: an .npz with, per quantized weight key, `<key>.q` (levels, uint8 [N, K]), `<key>.scale` and
`<key>.zero` ([N, K/16]); `build_marian_onnx.py --gptq <file>` packs them instead of rounding itself.
The tied embedding / LM head keeps RTN unless --lm-head (then GPTQ uses the LM head inputs, and the same
quantized rows also serve as the embedding table).

    python -I tools/mt/gptq_marian.py --checkpoint <dir> --direction en_to_vi --out <file.npz>
"""
from __future__ import annotations

import argparse
import json
import random
from pathlib import Path

import numpy as np
import torch

HERE = Path(__file__).resolve().parent
TRAIN = HERE.parents[2] / "Core/data/processed/it_en_vi/train.jsonl"
PREFIX = {"en_to_vi": ">>vie<<", "vi_to_en": ""}
FIELDS = {"en_to_vi": ("en_clean", "vi_clean"), "vi_to_en": ("vi_clean", "en_clean")}
BITS, GROUP, BLOCK_COLS, DAMP = 4, 16, 128, 0.01
CLIP_STEPS = torch.linspace(1.0, 0.70, 16)


def calibration_pairs(direction: str, n: int, seed: int) -> list[tuple[str, str]]:
    src, tgt = FIELDS[direction]
    rows = [json.loads(line) for line in TRAIN.read_text(encoding="utf-8").splitlines() if line.strip()]
    rows = [r for r in rows if 3 <= len(r[src].split()) <= 60]
    random.Random(seed).shuffle(rows)
    return [(PREFIX[direction] + r[src], r[tgt]) for r in rows[:n]]


def collect_hessians(model, tok, pairs, lm_head: bool) -> dict[str, torch.Tensor]:
    linears = {name: m for name, m in model.named_modules()
               if isinstance(m, torch.nn.Linear) and (".layers." in name or (lm_head and name == "lm_head"))}
    hess = {name: torch.zeros(m.in_features, m.in_features, dtype=torch.float64) for name, m in linears.items()}
    counts = {name: 0 for name in linears}

    def hook(name):
        def fn(_module, inputs):
            x = inputs[0].detach().reshape(-1, inputs[0].shape[-1]).double()
            hess[name] += x.T @ x
            counts[name] += x.shape[0]
        return fn

    handles = [m.register_forward_pre_hook(hook(name)) for name, m in linears.items()]
    with torch.no_grad():
        for src, tgt in pairs:
            enc = tok(src, text_target=tgt, return_tensors="pt", truncation=True, max_length=256)
            model(input_ids=enc["input_ids"], attention_mask=enc["attention_mask"], labels=enc["labels"])
    for h in handles:
        h.remove()
    return {name: (hess[name] / counts[name]).float() for name in linears}


def group_params(w: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
    """Asymmetric 4-bit scale/zero per row of a [N, GROUP] block, clipping range searched for least error."""
    qmax = 2 ** BITS - 1
    lo0 = torch.clamp(w.min(1).values, max=0)
    hi0 = torch.clamp(w.max(1).values, min=0)
    best_err, best = None, None
    for alpha in CLIP_STEPS:
        lo, hi = lo0 * alpha, hi0 * alpha
        scale = torch.where(hi > lo, (hi - lo) / qmax, torch.ones_like(hi))
        zero = torch.clamp(torch.round(-lo / scale), 0, qmax)
        q = torch.clamp(torch.round(w / scale[:, None]) + zero[:, None], 0, qmax)
        err = (((q - zero[:, None]) * scale[:, None] - w) ** 2).sum(1)
        if best is None:
            best_err, best = err, (scale, zero)
        else:
            better = err < best_err
            best_err = torch.where(better, err, best_err)
            best = (torch.where(better, scale, best[0]), torch.where(better, zero, best[1]))
    return best


def gptq(weight: torch.Tensor, hess: torch.Tensor, damp: float = DAMP) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """weight [N, K] (out, in); returns levels uint8 [N, K], scale and zero [N, K/GROUP]."""
    w = weight.clone().float()
    n, k = w.shape
    h = hess.clone()
    dead = torch.diag(h) == 0
    h[dead, dead] = 1
    w[:, dead] = 0
    h += damp * torch.mean(torch.diag(h)) * torch.eye(k)
    hinv = torch.linalg.cholesky(torch.cholesky_inverse(torch.linalg.cholesky(h)), upper=True)
    qmax = 2 ** BITS - 1
    levels = torch.zeros(n, k, dtype=torch.uint8)
    scales = torch.zeros(n, k // GROUP)
    zeros = torch.zeros(n, k // GROUP)
    for i1 in range(0, k, BLOCK_COLS):
        i2 = min(i1 + BLOCK_COLS, k)
        w1 = w[:, i1:i2].clone()
        err1 = torch.zeros_like(w1)
        hinv1 = hinv[i1:i2, i1:i2]
        for i in range(i2 - i1):
            col = i1 + i
            if col % GROUP == 0:  # params of the block from the error-updated weights
                scale, zero = group_params(w1[:, i:i + GROUP])
                scales[:, col // GROUP], zeros[:, col // GROUP] = scale, zero
            q = torch.clamp(torch.round(w1[:, i] / scale) + zero, 0, qmax)
            levels[:, col] = q.to(torch.uint8)
            deq = (q - zero) * scale
            e = (w1[:, i] - deq) / hinv1[i, i]
            w1[:, i:] -= e[:, None] * hinv1[i, i:][None, :]
            err1[:, i] = e
        w[:, i2:] -= err1 @ hinv[i1:i2, i2:]
    return levels.numpy(), scales.numpy().astype(np.float32), zeros.numpy().astype(np.uint8)


def main() -> int:
    from transformers import AutoTokenizer, MarianMTModel

    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--checkpoint", type=Path, required=True)
    ap.add_argument("--direction", choices=tuple(PREFIX), required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--samples", type=int, default=256)
    ap.add_argument("--seed", type=int, default=20261008)
    ap.add_argument("--damp", type=float, default=DAMP, help="Hessian damping as a fraction of its mean diagonal")
    ap.add_argument("--lm-head", action="store_true", help="also GPTQ the tied LM head / embedding (key model.shared)")
    args = ap.parse_args()

    torch.manual_seed(0)
    torch.set_num_threads(4)
    tok = AutoTokenizer.from_pretrained(args.checkpoint, local_files_only=True)
    model = MarianMTModel.from_pretrained(args.checkpoint, local_files_only=True).eval()
    pairs = calibration_pairs(args.direction, args.samples, args.seed)
    hessians = collect_hessians(model, tok, pairs, args.lm_head)
    out = {}
    modules = dict(model.named_modules())
    for name, hess in hessians.items():
        levels, scale, zero = gptq(modules[name].weight.detach(), hess, args.damp)
        key = "model.shared" if name == "lm_head" else name  # tied: lm_head.weight is model.shared.weight
        out[f"{key}.q"], out[f"{key}.scale"], out[f"{key}.zero"] = levels, scale, zero
    args.out.parent.mkdir(parents=True, exist_ok=True)
    np.savez(args.out, **out)
    print(f"MT_GPTQ=PASS direction={args.direction} weights={len(hessians)} samples={len(pairs)} -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
