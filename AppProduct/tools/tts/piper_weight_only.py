"""Weight-only int8 Piper VITS model for the app (2026-10-07, replaces k2-fsa's dynamic int8 models).

k2-fsa's "-int8" Piper models quantize activations at run time (129 ConvInteger + 110
DynamicQuantizeLinear): on ONNX Runtime they are 1.7-3.9x slower than this file and far from the
original voice (log-spectral distance to fp32 after DTW: vi 20.3 dB, en 9.1 dB; here 4.9 / 5.4 dB).
Here only the weights are int8 (symmetric, per output channel; ConvTranspose per axis 1), restored to
float by DequantizeLinear, so every Conv still runs in float. The duration predictor (`/dp/`) stays
float so that durations, i.e. speaking rhythm, match the original. Deterministic: the same input
gives the same bytes (fetch_artifacts.py pins the output's sha256).

Needs numpy + onnx (the repo .venv). Called by tools/fetch_artifacts.py for lock entries with
"derive"; can also be run by hand:

    python -I tools/tts/piper_weight_only.py <fp32 model.onnx> <out.onnx>
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import onnx
from onnx import helper, numpy_helper

MIN_ELEMENTS = 4096  # small tensors (biases, norms) stay float
KEEP_FLOAT = ("/dp/",)  # duration predictor


def convert(src: Path, dst: Path) -> int:
    model = onnx.load(str(src))
    graph = model.graph
    consumers: dict[str, list] = {}
    for node in graph.node:
        for k, name in enumerate(node.input):
            consumers.setdefault(name, []).append((node, k))
    new_inits, new_nodes, count = [], [], 0
    for init in list(graph.initializer):
        uses = consumers.get(init.name, [])
        if not uses or not all(n.op_type in ("Conv", "ConvTranspose") and k == 1
                               and not any(s in n.name for s in KEEP_FLOAT) for n, k in uses):
            continue
        w = numpy_helper.to_array(init)
        if w.dtype != np.float32 or w.size < MIN_ELEMENTS:
            continue
        axis = 1 if uses[0][0].op_type == "ConvTranspose" else 0
        reduce = tuple(i for i in range(w.ndim) if i != axis)
        scale = (np.abs(w).max(axis=reduce) / np.float32(127)).astype(np.float32)
        scale[scale == 0] = 1
        shape = [1] * w.ndim
        shape[axis] = -1
        q = np.clip(np.rint(w / scale.reshape(shape)), -127, 127).astype(np.int8)
        graph.initializer.remove(init)
        new_inits += [numpy_helper.from_array(q, init.name + "_q"),
                      numpy_helper.from_array(scale, init.name + "_scale"),
                      numpy_helper.from_array(np.zeros(scale.shape, np.int8), init.name + "_zero")]
        new_nodes.append(helper.make_node("DequantizeLinear", [init.name + "_q", init.name + "_scale", init.name + "_zero"],
                                          [init.name], name=init.name + "_dequantize", axis=axis))
        count += 1
    graph.initializer.extend(new_inits)
    for node in reversed(new_nodes):
        graph.node.insert(0, node)
    model.metadata_props.append(onnx.StringStringEntryProto(key="vtt_weight_only_int8", value="tools/tts/piper_weight_only.py"))
    # No onnx.checker: the vi voice ships with duplicate metadata keys, kept as is (sherpa-onnx reads them).
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_bytes(model.SerializeToString(deterministic=True))
    return count


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    count = convert(Path(sys.argv[1]), Path(sys.argv[2]))
    print(f"PIPER_WEIGHT_ONLY=PASS weights={count} -> {sys.argv[2]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
