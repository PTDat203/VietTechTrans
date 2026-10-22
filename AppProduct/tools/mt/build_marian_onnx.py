"""Build the on-device ONNX graph of a frozen Phase 03 Main-Core (MarianMT Student).

One model file per direction. An `If(is_encoder)` node runs either
  * the encoder: source ids -> cross-attention keys/values for every decoder layer, or
  * one decoder step with KV-cache: last tokens + past -> log-probs and the new past.
Both branches read the same outer-scope initializers, so the token embedding (shared by the
encoder input, the decoder input and the tied LM head) is stored once.

Variants:
  fp32  exact re-implementation, used to prove parity with Hugging Face.
  q8    8-bit weights, asymmetric per block of 32 input features (com.microsoft.MatMulNBits).
        accuracy_level 4 (default): activations are quantized per block of 32 at compute time
        and the int8 kernels run; accuracy_level 0 keeps fp32 activations but ONNX Runtime 1.30
        has no fast 8-bit kernel for it (250 ms vs 10 ms per 4-beam step on an i5-12500H). On ARM
        the 8-bit int8 kernel needs dotprod/i8mm, so phones without them take that slow path.
  q4    the same with 4-bit weights (ARM kernels for every CPU).
  q4e8  4-bit Linear layers, 8-bit embedding/LM head.
  q4g8  4-bit everywhere, plus a separate 8-bit table for the embedding lookup (experiment).
  q4r   4-bit Linear layers; the tied embedding / LM head as 4-bit + 4-bit residual (W ~ Q1 + Q2, two
        MatMulNBits summed): 8-bit-like precision with 4-bit kernels only. The app ships q4r --block 16 --clip.
Options for the n-bit variants: --block (16/32/64 input features per scale; default 32) and --clip
(per block, search the clipping range in 16 steps from 100 % down to 70 % that minimizes the squared
rounding error, instead of plain min/max; same file size).
Every Linear and the embedding/LM head are quantized; LayerNorm, biases and the sinusoidal
position table stay fp32. The embedding lookup dequantizes rows straight from the LM head's
packed weights (row v of the LM head = token v), so the matrix exists once.
Rejected: int8 with per-call dynamically quantized activations (DynamicQuantizeMatMul) —
only 7/30 General Validation sentences matched Hugging Face, with repetitions.

The decoding rules of the checkpoint's generation_config are applied in the graph where they
are per-step constants: `bad_words_ids=[[pad]]` (pad logit = -inf) and `renormalize_logits`
(log-softmax). Beam bookkeeping and forced EOS at max_length live in the app (see marian_ref.py).

Usage:
  python -I tools/mt/build_marian_onnx.py --checkpoint <dir> --out <model.onnx> --variant q8
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

LAYERS, HEADS, D_MODEL, HEAD_DIM = 6, 8, 512, 64
LN_EPS = 1e-5
BLOCK = 32  # set by build(block=...)
CLIP = False  # set by build(clip=...)
GPTQ: dict | None = None  # set by build(gptq=...): key -> (levels [N, K], scale [N, K/BLOCK], zero [N, K/BLOCK])
CLIP_STEPS = np.linspace(1.0, 0.70, 16, dtype=np.float32)
BITS = {"q8": (8, 8), "q4": (4, 4), "q4e8": (4, 8), "q4g8": (4, 4), "q4r": (4, 4)}  # variant -> (Linear bits, embedding/LM head bits)
# q4r: the tied embedding / LM head is stored as two 4-bit tensors, the second quantizing what the first misses
# (W ~ Q1 + Q2): close to 8-bit precision but computed with 4-bit MatMulNBits kernels only (two of them).
# q4g8: the LM head stays 4-bit (MatMulNBits, fast on every ARM CPU) but the embedding lookup reads a separate
# 8-bit table (dequantized in the graph, so no 8-bit kernel is needed); zero points stored uint8.
GATHER8_BLOCK = 64


def any_shape(name: str, rank: int) -> onnx.ValueInfoProto:
    """Float value of known rank and symbolic dims (If branches return different shapes)."""
    return helper.make_tensor_value_info(name, TensorProto.FLOAT, [f"{name}_d{i}" for i in range(rank)])


class Builder:
    """Collects outer-scope initializers; `Graph` objects collect the nodes of one branch."""

    def __init__(self, state: dict[str, np.ndarray], variant: str, pad_id: int, accuracy_level: int):
        self.sd = state
        self.variant = variant
        self.accuracy_level = accuracy_level
        self.inits: dict[str, onnx.TensorProto] = {}
        self._prepare_embedding(pad_id)

    def add(self, name: str, array: np.ndarray) -> str:
        if name not in self.inits:
            # np.array(order="C") keeps 0-d scalars 0-d (ascontiguousarray would make them 1-d).
            self.inits[name] = numpy_helper.from_array(np.array(array, order="C"), name)
        return name

    def i64(self, *values: int) -> str:
        return self.add("const_i64_" + "_".join(str(v).replace("-", "m") for v in values), np.array(values, dtype=np.int64))

    def scalar_i64(self, value: int) -> str:
        return self.add(f"const_scalar_i64_{value}", np.array(value, dtype=np.int64))

    def f32(self, value: float, tag: str) -> str:
        return self.add(f"const_f32_{tag}", np.array(value, dtype=np.float32))

    def _prepare_embedding(self, pad_id: int) -> None:
        emb = self.sd["model.shared.weight"].astype(np.float32)  # [V, D]; tied with lm_head
        bias = self.sd["final_logits_bias"].reshape(-1).astype(np.float32).copy()
        bias[pad_id] = -np.inf  # bad_words_ids=[[pad]] from generation_config
        self.add("lm_bias", bias)
        self.add("pos_table", self.sd["model.decoder.embed_positions.weight"].astype(np.float32))
        self.add("const_f32_sqrt_d", np.array(math.sqrt(D_MODEL), dtype=np.float32))
        if self.variant in BITS:
            if GPTQ is not None and "model.shared" in GPTQ:
                levels, s, z = GPTQ["model.shared"]
                packed, scale, zp, zp_rows = pack_blocks(levels.reshape(emb.shape[0], -1, BLOCK), s, z, BITS[self.variant][1])
            elif self.variant == "q4r":
                q1, s1, z1 = rtn_levels(emb, 4)
                packed, scale, zp, zp_rows = pack_blocks(q1, s1, z1, 4)
                p2, s2, zz2, z2_rows = quantize_blocks(emb - dequantize_levels(q1, s1, z1), 4)
                self.add("emb2.q", p2)
                self.add("emb2.s", s2)
                self.add("emb2.z", zz2)
                self.add("emb2.zu", z2_rows)  # [V, nb] uint8; the lookup reshapes emb2.s instead of a copy
            else:
                packed, scale, zp, zp_rows = quantize_blocks(emb, BITS[self.variant][1])
            self.add("emb.q", packed)  # [V, D/BLOCK, BLOCK*bits/8]: LM head B operand, row v = token v
            self.add("emb.s", scale)
            self.add("emb.z", zp)
            nb = D_MODEL // BLOCK
            if self.variant == "q4r":  # compact lookup: no second copy of the scales, zero points uint8
                self.add("emb.zu", zp_rows)
            elif self.variant != "q4g8":  # q4g8 looks rows up in its own 8-bit table
                self.add("emb.s_rows", scale.reshape(-1, nb, 1))  # same values, gather-friendly layout
                self.add("emb.z_rows", zp_rows.astype(np.float32).reshape(-1, nb, 1))
            else:
                q8, s8, _, z8 = quantize_blocks(emb, 8, GATHER8_BLOCK)
                nb8 = D_MODEL // GATHER8_BLOCK
                self.add("embg.q", q8)  # [V, D/64, 64] uint8
                self.add("embg.s_rows", s8.reshape(-1, nb8, 1))
                self.add("embg.z_rows", z8.reshape(-1, nb8, 1))  # uint8, cast after the gather
        else:
            self.add("emb", emb)

    def linear_weights(self, key: str) -> tuple[str, str, str | None, str | None]:
        w = self.sd[key + ".weight"].astype(np.float32)  # [out, in] = MatMulNBits [N, K]
        b = self.add(key + ".bias", self.sd[key + ".bias"].astype(np.float32))
        if self.variant in BITS:
            if GPTQ is not None and key in GPTQ:
                levels, s, z = GPTQ[key]
                n, k = w.shape
                packed, scale, zp, _ = pack_blocks(levels.reshape(n, k // BLOCK, BLOCK), s, z, BITS[self.variant][0])
            else:
                packed, scale, zp, _ = quantize_blocks(w, BITS[self.variant][0])
            return self.add(key + ".q", packed), b, self.add(key + ".s", scale), self.add(key + ".z", zp)
        return self.add(key + ".wt", w.T), b, None, None


def quantize_blocks(w: np.ndarray, bits: int, block: int | None = None) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    return pack_blocks(*rtn_levels(w, bits, block), bits)


def dequantize_levels(q: np.ndarray, scale: np.ndarray, zp: np.ndarray) -> np.ndarray:
    """Levels [N, nb, block] -> float [N, nb * block] as MatMulNBits computes them."""
    return ((q.astype(np.float32) - zp[..., None]) * scale[..., None]).reshape(q.shape[0], -1)


def rtn_levels(w: np.ndarray, bits: int, block: int | None = None) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Asymmetric per-block quantization in MatMulNBits layout.

    Returns (packed weights [N, K/BLOCK, BLOCK*bits/8] uint8, scales [N*K/BLOCK] f32,
    zero points as MatMulNBits expects them, zero points unpacked [N, K/BLOCK] uint8).
    """
    n, k = w.shape
    block = block or BLOCK
    nb = k // block
    blocks = w.reshape(n, nb, block)
    lo0 = np.minimum(blocks.min(-1), 0.0)
    hi0 = np.maximum(blocks.max(-1), 0.0)
    qmax = 2 ** bits - 1

    def grid(lo, hi):
        scale = np.where(hi > lo, (hi - lo) / qmax, 1.0).astype(np.float32)
        zp = np.clip(np.rint(-lo / scale), 0, qmax).astype(np.uint8)
        q = np.clip(np.rint(blocks / scale[..., None]) + zp[..., None], 0, qmax).astype(np.uint8)
        return scale, zp, q

    scale, zp, q = grid(lo0, hi0)
    if CLIP:
        best = (((q.astype(np.float32) - zp[..., None]) * scale[..., None] - blocks) ** 2).sum(-1)
        for alpha in CLIP_STEPS[1:]:
            s2, z2, q2 = grid(lo0 * alpha, hi0 * alpha)
            err = (((q2.astype(np.float32) - z2[..., None]) * s2[..., None] - blocks) ** 2).sum(-1)
            better = err < best
            best = np.where(better, err, best)
            scale, zp = np.where(better, s2, scale), np.where(better, z2, zp)
            q = np.where(better[..., None], q2, q)
    return q, scale, zp


def pack_blocks(q: np.ndarray, scale: np.ndarray, zp: np.ndarray, bits: int) -> tuple:
    """Levels [N, nb, BLOCK] + scale/zero [N, nb] -> the MatMulNBits tensors (see quantize_blocks)."""
    n, nb = scale.shape
    q, scale, zp = q.astype(np.uint8), scale.astype(np.float32), zp.astype(np.uint8)
    if bits == 8:
        return q, scale.reshape(-1), zp.reshape(-1), zp
    packed = (q[..., 0::2] | (q[..., 1::2] << 4)).astype(np.uint8)  # low nibble = even element
    zp_even = zp if nb % 2 == 0 else np.concatenate([zp, np.zeros((n, 1), np.uint8)], 1)
    zp_packed = (zp_even[:, 0::2] | (zp_even[:, 1::2] << 4)).astype(np.uint8)
    return packed, scale.reshape(-1), zp_packed.reshape(-1), zp


class Graph:
    def __init__(self, b: Builder, prefix: str):
        self.b = b
        self.prefix = prefix
        self.nodes: list[onnx.NodeProto] = []
        self.count = 0

    def op(self, op_type: str, inputs: list[str], domain: str | None = None, **attrs) -> str:
        self.count += 1
        out = f"{self.prefix}{op_type}_{self.count}"
        self.nodes.append(helper.make_node(op_type, inputs, [out], name=out + "_n", domain=domain, **attrs))
        return out

    # --- building blocks -------------------------------------------------------------
    def nbits(self, x: str, packed: str, scale: str, zp: str, bias: str, n: int, k: int, bits: int) -> str:
        inputs = [x, packed, scale, zp, "", bias] if bias else [x, packed, scale, zp]
        return self.op("MatMulNBits", inputs, domain="com.microsoft",
                       K=k, N=n, bits=bits, block_size=BLOCK, accuracy_level=self.b.accuracy_level)

    def linear(self, x: str, key: str) -> str:
        w, bias, scale, zp = self.b.linear_weights(key)
        if scale is not None:
            n, k = self.b.sd[key + ".weight"].shape
            return self.nbits(x, w, scale, zp, bias, n, k, BITS[self.b.variant][0])
        return self.op("Add", [self.op("MatMul", [x, w]), bias])

    def layer_norm(self, x: str, key: str) -> str:
        gamma = self.b.add(key + ".weight", self.b.sd[key + ".weight"].astype(np.float32))
        beta = self.b.add(key + ".bias", self.b.sd[key + ".bias"].astype(np.float32))
        return self.op("LayerNormalization", [x, gamma, beta], axis=-1, epsilon=LN_EPS)

    def heads(self, x: str, perm: list[int]) -> str:
        """[B, T, D] -> per-head layout given by perm over [B, T, H, Dh]."""
        return self.op("Transpose", [self.op("Reshape", [x, self.b.i64(0, 0, HEADS, HEAD_DIM)])], perm=perm)

    def merge_heads(self, x: str) -> str:
        """[B, H, T, Dh] -> [B, T, D]"""
        return self.op("Reshape", [self.op("Transpose", [x], perm=[0, 2, 1, 3]), self.b.i64(0, 0, D_MODEL)])

    def query(self, x: str, key: str) -> str:
        return self.heads(self.op("Mul", [self.linear(x, key), self.b.f32(HEAD_DIM ** -0.5, "q_scale")]), [0, 2, 1, 3])

    def attend(self, q: str, k_t: str, v: str) -> str:
        """q [B,H,Tq,Dh], k_t [B,H,Dh,Tk], v [B,H,Tk,Dh] -> [B,H,Tq,Dh]"""
        return self.op("MatMul", [self.op("Softmax", [self.op("MatMul", [q, k_t])], axis=-1), v])

    def ffn(self, x: str, prefix: str) -> str:
        h = self.linear(x, prefix + ".fc1")
        return self.linear(self.op("Mul", [h, self.op("Sigmoid", [h])]), prefix + ".fc2")  # swish

    def embed(self, ids: str) -> str:
        """ids [B, T] -> embeddings * sqrt(d_model), [B, T, D]"""
        if self.b.variant not in BITS:
            return self.op("Mul", [self.op("Gather", ["emb", ids]), "const_f32_sqrt_d"])
        if self.b.variant == "q4g8":
            q = self.op("Cast", [self.op("Gather", ["embg.q", ids])], to=TensorProto.FLOAT)  # [B, T, nb, 64]
            z = self.op("Cast", [self.op("Gather", ["embg.z_rows", ids])], to=TensorProto.FLOAT)
            rows = self.op("Mul", [self.op("Sub", [q, z]), self.op("Gather", ["embg.s_rows", ids])])
            return self.op("Mul", [self.op("Reshape", [rows, self.b.i64(0, 0, D_MODEL)]), "const_f32_sqrt_d"])
        rows = self.rows("emb", ids)
        if self.b.variant == "q4r":
            rows = self.op("Add", [rows, self.rows("emb2", ids)])
        return self.op("Mul", [self.op("Reshape", [rows, self.b.i64(0, 0, D_MODEL)]), "const_f32_sqrt_d"])

    def rows(self, prefix: str, ids: str) -> str:
        """Dequantized rows of a packed table, [B, T, nb, BLOCK]."""
        q = self.op("Cast", [self.op("Gather", [prefix + ".q", ids])], to=TensorProto.FLOAT)  # [B, T, nb, blob]
        if BITS[self.b.variant][1] == 4:  # unpack nibbles: low = even element, high = odd element
            high = self.op("Floor", [self.op("Div", [q, self.b.f32(16.0, "16")])])
            low = self.op("Sub", [q, self.op("Mul", [high, self.b.f32(16.0, "16")])])
            pairs = self.op("Concat", [self.op("Unsqueeze", [low, self.b.i64(-1)]), self.op("Unsqueeze", [high, self.b.i64(-1)])], axis=-1)
            q = self.op("Reshape", [pairs, self.b.i64(0, 0, D_MODEL // BLOCK, BLOCK)])
        if self.b.variant == "q4r":  # scales gathered from a [V, nb] view of the MatMulNBits scales
            nb = D_MODEL // BLOCK
            z = self.op("Unsqueeze", [self.op("Cast", [self.op("Gather", [prefix + ".zu", ids])], to=TensorProto.FLOAT), self.b.i64(-1)])
            s = self.op("Unsqueeze", [self.op("Gather", [self.op("Reshape", [prefix + ".s", self.b.i64(-1, nb)]), ids]), self.b.i64(-1)])
            return self.op("Mul", [self.op("Sub", [q, z]), s])
        return self.op("Mul", [self.op("Sub", [q, self.op("Gather", [prefix + ".z_rows", ids])]), self.op("Gather", [prefix + ".s_rows", ids])])

    def lm_head(self, h2d: str) -> str:
        if self.b.variant in BITS:
            vocab = self.b.sd["model.shared.weight"].shape[0]
            logits = self.nbits(h2d, "emb.q", "emb.s", "emb.z", "lm_bias", vocab, D_MODEL, BITS[self.b.variant][1])
            if self.b.variant == "q4r":
                logits = self.op("Add", [logits, self.nbits(h2d, "emb2.q", "emb2.s", "emb2.z", "", vocab, D_MODEL, 4)])
            return logits
        return self.op("Gemm", [h2d, "emb", "lm_bias"], transB=1)


def encoder_branch(b: Builder) -> onnx.GraphProto:
    g = Graph(b, "enc/")
    ids = "input_ids"  # [1, S]
    src_len = g.op("Unsqueeze", [g.op("Gather", [g.op("Shape", [ids]), b.scalar_i64(1)]), b.i64(0)])
    pos = g.op("Slice", ["pos_table", b.i64(0), src_len, b.i64(0)])  # [S, D]
    h = g.op("Add", [g.embed(ids), pos])
    for i in range(LAYERS):
        p = f"model.encoder.layers.{i}"
        q = g.query(h, p + ".self_attn.q_proj")
        k_t = g.heads(g.linear(h, p + ".self_attn.k_proj"), [0, 2, 3, 1])
        v = g.heads(g.linear(h, p + ".self_attn.v_proj"), [0, 2, 1, 3])
        att = g.linear(g.merge_heads(g.attend(q, k_t, v)), p + ".self_attn.out_proj")
        h = g.layer_norm(g.op("Add", [h, att]), p + ".self_attn_layer_norm")
        h = g.layer_norm(g.op("Add", [h, g.ffn(h, p)]), p + ".final_layer_norm")
    cross_k, cross_v = [], []
    for i in range(LAYERS):
        p = f"model.decoder.layers.{i}.encoder_attn"
        k_t = g.heads(g.linear(h, p + ".k_proj"), [0, 2, 3, 1])  # [1, H, Dh, S]
        v = g.heads(g.linear(h, p + ".v_proj"), [0, 2, 1, 3])  # [1, H, S, Dh]
        cross_k.append(g.op("Unsqueeze", [k_t, b.i64(0)]))
        cross_v.append(g.op("Unsqueeze", [v, b.i64(0)]))
    out_k = g.op("Concat", cross_k, axis=0)
    out_v = g.op("Concat", cross_v, axis=0)
    dummy = g.op("Identity", [b.add("const_f32_zero_2d", np.zeros((1, 1), dtype=np.float32))])
    outs = [g.op("Identity", [out_k]), g.op("Identity", [out_v]), dummy]
    return helper.make_graph(g.nodes, "encoder", [], [any_shape(o, r) for o, r in zip(outs, (5, 5, 2))])


def decoder_branch(b: Builder) -> onnx.GraphProto:
    g = Graph(b, "dec/")
    ids = "input_ids"  # [N, 1]
    past_k = g.op("Gather", ["past_k", "beam_idx"], axis=1)  # reorder beams: [L, N, H, T, Dh]
    past_v = g.op("Gather", ["past_v", "beam_idx"], axis=1)
    past_len = g.op("Gather", [g.op("Shape", ["past_k"]), b.scalar_i64(3)])
    h = g.op("Add", [g.embed(ids), g.op("Gather", ["pos_table", past_len])])  # [N, 1, D]
    present_k, present_v = [], []
    for i in range(LAYERS):
        p = f"model.decoder.layers.{i}"
        layer = b.scalar_i64(i)
        q = g.query(h, p + ".self_attn.q_proj")
        k = g.op("Concat", [g.op("Gather", [past_k, layer], axis=0), g.heads(g.linear(h, p + ".self_attn.k_proj"), [0, 2, 1, 3])], axis=2)
        v = g.op("Concat", [g.op("Gather", [past_v, layer], axis=0), g.heads(g.linear(h, p + ".self_attn.v_proj"), [0, 2, 1, 3])], axis=2)
        present_k.append(g.op("Unsqueeze", [k, b.i64(0)]))
        present_v.append(g.op("Unsqueeze", [v, b.i64(0)]))
        att = g.attend(q, g.op("Transpose", [k], perm=[0, 1, 3, 2]), v)
        h = g.layer_norm(g.op("Add", [h, g.linear(g.merge_heads(att), p + ".self_attn.out_proj")]), p + ".self_attn_layer_norm")
        q2 = g.query(h, p + ".encoder_attn.q_proj")
        att2 = g.attend(q2, g.op("Gather", ["cross_k_t", layer], axis=0), g.op("Gather", ["cross_v", layer], axis=0))
        h = g.layer_norm(g.op("Add", [h, g.linear(g.merge_heads(att2), p + ".encoder_attn.out_proj")]), p + ".encoder_attn_layer_norm")
        h = g.layer_norm(g.op("Add", [h, g.ffn(h, p)]), p + ".final_layer_norm")
    logits = g.lm_head(g.op("Reshape", [h, b.i64(-1, D_MODEL)]))
    logprobs = g.op("LogSoftmax", [logits], axis=-1)  # renormalize_logits after the pad mask
    outs = [g.op("Concat", present_k, axis=0), g.op("Concat", present_v, axis=0), logprobs]
    return helper.make_graph(g.nodes, "decoder_step", [], [any_shape(o, r) for o, r in zip(outs, (5, 5, 2))])


def build(checkpoint: Path, variant: str, accuracy_level: int = 4, block: int = 32, clip: bool = False,
          gptq: Path | None = None) -> onnx.ModelProto:
    global BLOCK, CLIP, GPTQ
    BLOCK, CLIP = block, clip
    GPTQ = None
    if gptq is not None:
        data = np.load(gptq)
        GPTQ = {k[:-2]: (data[k], data[k[:-2] + ".scale"], data[k[:-2] + ".zero"]) for k in data.files if k.endswith(".q")}
    import torch  # noqa: F401  (loads the checkpoint exactly as Phase 03 did)
    from transformers import MarianMTModel

    model = MarianMTModel.from_pretrained(checkpoint, local_files_only=True)
    cfg = model.config
    assert (cfg.encoder_layers, cfg.decoder_layers, cfg.d_model, cfg.encoder_attention_heads) == (LAYERS, LAYERS, D_MODEL, HEADS)
    assert cfg.activation_function == "swish" and cfg.scale_embedding and not cfg.normalize_before
    state = {k: v.detach().cpu().numpy() for k, v in model.state_dict().items()}
    b = Builder(state, variant, cfg.pad_token_id, accuracy_level)
    enc, dec = encoder_branch(b), decoder_branch(b)

    inputs = [
        helper.make_tensor_value_info("input_ids", TensorProto.INT64, ["batch", "seq"]),
        helper.make_tensor_value_info("is_encoder", TensorProto.BOOL, []),
        helper.make_tensor_value_info("past_k", TensorProto.FLOAT, [LAYERS, "past_beams", HEADS, "past_len", HEAD_DIM]),
        helper.make_tensor_value_info("past_v", TensorProto.FLOAT, [LAYERS, "past_beams", HEADS, "past_len", HEAD_DIM]),
        helper.make_tensor_value_info("beam_idx", TensorProto.INT64, ["beams"]),
        helper.make_tensor_value_info("cross_k_t", TensorProto.FLOAT, [LAYERS, 1, HEADS, HEAD_DIM, "src_len"]),
        helper.make_tensor_value_info("cross_v", TensorProto.FLOAT, [LAYERS, 1, HEADS, "src_len", HEAD_DIM]),
    ]
    # Encoder: (cross_k_t, cross_v, unused); decoder: (present_k, present_v, logprobs).
    outputs = [any_shape("out_k", 5), any_shape("out_v", 5), any_shape("logprobs", 2)]
    node = helper.make_node("If", ["is_encoder"], ["out_k", "out_v", "logprobs"], name="encode_or_step", then_branch=enc, else_branch=dec)
    graph = helper.make_graph([node], f"marian_{variant}", inputs, outputs, initializer=list(b.inits.values()))
    m = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17), helper.make_opsetid("com.microsoft", 1)], ir_version=9)
    m.producer_name = "AppProduct/tools/mt/build_marian_onnx.py"
    m.doc_string = json.dumps({"checkpoint": str(checkpoint.name), "variant": variant, "accuracy_level": accuracy_level,
                               "block": block, "clip": clip, "gptq": gptq.name if gptq else None})
    return m


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--checkpoint", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--variant", choices=("fp32", *BITS), required=True)
    ap.add_argument("--accuracy-level", type=int, choices=(0, 4), default=4, help="MatMulNBits: 0 = fp32 compute, 4 = int8 compute")
    ap.add_argument("--block", type=int, choices=(16, 32, 64), default=32, help="input features per scale")
    ap.add_argument("--clip", action="store_true", help="per-block clipping search (min squared error)")
    ap.add_argument("--gptq", type=Path, help="levels/scales from tools/mt/gptq_marian.py for the Linear layers")
    args = ap.parse_args()
    model = build(args.checkpoint, args.variant, args.accuracy_level, args.block, args.clip, args.gptq)
    onnx.checker.check_model(model)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    onnx.save_model(model, args.out)
    print(f"MT_ONNX_BUILT variant={args.variant} block={args.block} clip={args.clip} bytes={args.out.stat().st_size} -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
