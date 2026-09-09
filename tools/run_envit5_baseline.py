"""Run one official-pretrained EnViT5 direction after the Phase 05 gate passes."""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from core_mt.adapters import EnViT5Adapter
from core_mt.contracts import Direction
from core_mt.data import load_locked_evaluation_set
from core_mt.protocol import load_frozen_protocol
from core_mt.runner import run_baseline


def configure_utf8_output() -> None:
    """Tránh CP1252 làm dừng log tiếng Việt khi script được gọi từ Notebook."""
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure:
            reconfigure(encoding="utf-8", errors="backslashreplace")


def main() -> None:
    configure_utf8_output()
    parser = argparse.ArgumentParser()
    parser.add_argument("--direction", choices=[item.value for item in Direction], required=True)
    parser.add_argument("--device", default="cpu", help="Use cpu or cuda when CUDA is available.")
    parser.add_argument("--progress-interval", type=int, default=250)
    parser.add_argument("--preview", type=int, default=2)
    args = parser.parse_args()
    from validate_phase05_gate import main as validate_phase05_gate
    validate_phase05_gate()
    try:
        import psutil
        import torch
    except ImportError as error:
        raise RuntimeError("Install EnViT5 dependencies from requirements.txt before running a baseline.") from error
    if args.device.startswith("cuda") and not torch.cuda.is_available():
        raise RuntimeError("CUDA requested but unavailable; pass --device cpu.")
    protocol = load_frozen_protocol(ROOT / "configs" / "evaluation_protocol_v2.json")
    datasets = tuple(load_locked_evaluation_set(name, ROOT) for name in protocol.evaluation_sets)
    adapter = EnViT5Adapter(Direction(args.direction), ROOT / "configs" / "envit5_baseline_v1.json", args.device)
    synchronize = torch.cuda.synchronize if args.device.startswith("cuda") else (lambda: None)
    run_baseline(adapter, protocol, datasets, ROOT / "runs" / "core_mt_baseline_v2" / "envit5" / args.direction, args.device, lambda: psutil.Process(os.getpid()).memory_info().rss, synchronize, print, args.progress_interval, args.preview)


if __name__ == "__main__":
    main()
