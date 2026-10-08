"""Compare Piper variants with ONNX Runtime on the PC: k2-fsa dynamic int8 (used until 2026-10-07),
the app's weight-only int8 (tools/tts/piper_weight_only.py) and the original fp32.

For each voice: real-time factor (synthesis time / audio length, 2 threads), log-spectral distance to
fp32 after DTW alignment (dB; lower = closer to the original voice), audio length ratio (rhythm) and
the process memory added by the session. Inputs are fixed random phoneme-id sequences (no espeak in
Python), so the numbers compare variants, not absolute quality. Reads the archives in .cache/downloads.

    python -I tools/tts/compare_piper.py
"""
from __future__ import annotations

import sys
import tarfile
import tempfile
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
import psutil

HERE = Path(__file__).resolve().parent
APP = HERE.parents[1]
sys.path.insert(0, str(HERE))
from piper_weight_only import convert  # noqa: E402

VOICES = {"vi": "vi_VN-vais1000-medium", "en": "en_US-ljspeech-medium"}
DOWNLOADS = APP / ".cache/downloads"


def extract(archive: Path, member: str, out: Path) -> Path:
    with tarfile.open(archive) as tar:
        src = tar.extractfile(member)
        out.write_bytes(src.read())
    return out


def logspec(a: np.ndarray, n_fft: int = 1024, hop: int = 256) -> np.ndarray:
    win = np.hanning(n_fft).astype(np.float32)
    frames = np.array([a[i:i + n_fft] * win for i in range(0, len(a) - n_fft, hop)])
    return 20 * np.log10(np.abs(np.fft.rfft(frames, axis=1)) + 1e-5)


def dtw_lsd(x: np.ndarray, y: np.ndarray) -> float:
    X, Y = logspec(x), logspec(y)
    d = np.sqrt(((X[:, None, :] - Y[None, :, :]) ** 2).mean(axis=2))
    n, m = d.shape
    acc = np.full((n + 1, m + 1), np.inf)
    steps = np.zeros((n + 1, m + 1))
    acc[0, 0] = 0
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            prev = min(((i - 1, j - 1), (i - 1, j), (i, j - 1)), key=lambda p: acc[p])
            acc[i, j] = d[i - 1, j - 1] + acc[prev]
            steps[i, j] = steps[prev] + 1
    return float(acc[n, m] / steps[n, m])


def run(path: Path, inputs: list) -> tuple:
    proc = psutil.Process()
    rss0 = proc.memory_info().rss
    so = ort.SessionOptions()
    so.intra_op_num_threads = 2
    sess = ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])
    outs, seconds, audio = [], 0.0, 0.0
    for x in inputs:
        feeds = {"input": x[None, :], "input_lengths": np.array([x.size], np.int64),
                 "scales": np.array([0.0, 1.0, 0.0], np.float32)}  # no noise: deterministic
        sess.run(None, feeds)
        t0 = time.perf_counter()
        a = sess.run(None, feeds)[0].reshape(-1)
        seconds += time.perf_counter() - t0
        audio += a.size / 22050
        outs.append(a)
    return outs, seconds / audio, (proc.memory_info().rss - rss0) / 1e6


def main() -> int:
    rng = np.random.default_rng(1)
    inputs = [rng.integers(10, 60, size=n).astype(np.int64) for n in (30, 60, 90)]
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        for lang, name in VOICES.items():
            fp32 = extract(DOWNLOADS / f"vits-piper-{name}.tar.bz2", f"vits-piper-{name}/{name}.onnx", tmp / f"{lang}_fp32.onnx")
            variants = {
                "fp32": fp32,
                "k2-fsa int8 (dynamic)": extract(DOWNLOADS / f"vits-piper-{name}-int8.tar.bz2",
                                                 f"vits-piper-{name}-int8/{name}.onnx", tmp / f"{lang}_k2.onnx"),
                "app weight-only int8": tmp / f"{lang}_w8.onnx",
            }
            convert(fp32, variants["app weight-only int8"])
            ref = None
            for label, path in variants.items():
                outs, rtf, mb = run(path, inputs)
                if ref is None:
                    ref = outs
                    print(f"{lang} {label}: {path.stat().st_size / 1e6:.1f} MB, RTF {rtf:.3f}, memory +{mb:.0f} MB")
                    continue
                lsd = np.mean([dtw_lsd(r, o) for r, o in zip(ref, outs)])
                ratio = sum(o.size for o in outs) / sum(r.size for r in ref)
                print(f"{lang} {label}: {path.stat().st_size / 1e6:.1f} MB, RTF {rtf:.3f}, LSD vs fp32 {lsd:.2f} dB, "
                      f"length x{ratio:.3f}, memory +{mb:.0f} MB", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
