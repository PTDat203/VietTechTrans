"""Run one official-pretrained OPUS-MT direction after the Phase 05 gate passes."""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from core_mt.adapters import OpusMTAdapter
from core_mt.contracts import Direction
from core_mt.data import load_locked_evaluation_set
from core_mt.protocol import load_frozen_protocol
from core_mt.runner import run_baseline


def configure_utf8_output() -> None:
    """Giữ log prediction tiếng Việt đọc được khi script chạy từ Notebook trên Windows.

    Console Windows có thể kế thừa CP1252 từ tiến trình Jupyter. CP1252 không
    mã hóa được nhiều ký tự tiếng Việt, nên một lệnh `print()` trong progress
    có thể làm dừng cả baseline dù model vừa sinh prediction hợp lệ. Chỉ thay
    đổi encoding của stdout/stderr; không sửa text dùng để chấm metric.
    """
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure:
            reconfigure(encoding="utf-8", errors="backslashreplace")


def main() -> None:
    configure_utf8_output()
    parser = argparse.ArgumentParser()
    parser.add_argument("--direction", choices=[item.value for item in Direction], required=True)
    parser.add_argument("--device", default="cpu", help="Use cpu or cuda when CUDA is available.")
    parser.add_argument("--progress-interval", type=int, default=250, help="Print a milestone after this many sentences; 0 disables milestones.")
    parser.add_argument("--preview", type=int, default=2, help="Print source/prediction previews from the first repeat; 0 disables previews.")
    args = parser.parse_args()
    from validate_phase05_gate import main as validate_phase05_gate
    validate_phase05_gate()
    try:
        import psutil
        import torch
    except ImportError as error:
        raise RuntimeError("Install OPUS-MT dependencies from requirements.txt before running a baseline.") from error
    if args.device.startswith("cuda") and not torch.cuda.is_available():
        raise RuntimeError("CUDA requested but unavailable; pass --device cpu.")
    protocol = load_frozen_protocol(ROOT / "configs" / "evaluation_protocol_v2.json")
    datasets = tuple(load_locked_evaluation_set(name, ROOT) for name in protocol.evaluation_sets)
    adapter = OpusMTAdapter(Direction(args.direction), ROOT / "configs" / "opus_mt_baseline_v1.json", args.device)
    synchronize = torch.cuda.synchronize if args.device.startswith("cuda") else (lambda: None)
    run_baseline(adapter, protocol, datasets, ROOT / "runs" / "core_mt_baseline_v2" / "opus_mt" / args.direction, args.device, lambda: psutil.Process(os.getpid()).memory_info().rss, synchronize, print, args.progress_interval, args.preview)


if __name__ == "__main__":
    main()
