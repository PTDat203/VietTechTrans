"""Chỉ mở final reporting sau khi Core decision do người thực hiện ghi đã freeze.

Điểm bắt đầu là core_mt_decision.json. Gate kiểm tra mỗi chiều có model,
checkpoint và lý do; không tự tạo hoặc sửa quyết định.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from core_mt.protocol import load_phase02_protocol


def main() -> None:
    protocol = load_phase02_protocol(ROOT / "configs" / "phase02_protocol.json")
    path = ROOT / "evidence" / "phase02" / "core_mt_decision.json"
    if not path.is_file():
        print("PHASE_02_FINAL_REPORTING=WAITING")
        print("reason=Missing frozen Core-MT decision.")
        raise SystemExit(2)
    decision = json.loads(path.read_text(encoding="utf-8"))
    if decision.get("status") != "frozen_before_final_evaluation":
        raise ValueError("Core-MT decision must be frozen before final reporting.")
    selections = decision.get("selections")
    if not isinstance(selections, dict) or set(selections) != set(protocol.directions):
        raise ValueError("Decision must select exactly one Core MT for each direction.")
    allowed = set(json.loads((ROOT / "configs" / "phase02_models.json").read_text(encoding="utf-8"))["candidates"])
    for direction, item in selections.items():
        if not isinstance(item, dict) or item.get("model") not in allowed:
            raise ValueError(f"Invalid Core-MT selection for {direction}.")
        if not isinstance(item.get("checkpoint"), str) or not item["checkpoint"].strip():
            raise ValueError(f"Missing checkpoint for {direction}.")
        if not isinstance(item.get("rationale"), str) or not item["rationale"].strip():
            raise ValueError(f"Missing rationale for {direction}.")
    print("PHASE_02_FINAL_REPORTING=PASS")
    print("final_sets=OPEN_FOR_REPORTING_ONLY")


if __name__ == "__main__":
    main()
