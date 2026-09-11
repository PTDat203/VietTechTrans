"""Cổng kiểm tra giữa Phase 05 và các nhánh CORE MT.

Script này cố ý không import adapter và không tải checkpoint. Nó trả lời một
câu hỏi hẹp: liệu dữ liệu sealed, protocol frozen, direction và schema test
đã sẵn sàng để bất kỳ model nào dùng chung chưa? Khi câu trả lời là PASS, bước
sau mới được phép thêm adapter đầu tiên.
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from core_mt.contracts import Direction
from core_mt.data import load_locked_evaluation_set
from core_mt.protocol import load_frozen_protocol


def main() -> None:
    from validate_phase05_gate import main as validate_phase05_gate

    # Gate Phase 05 kiểm tra checksum và leakage trước khi code Phase 06 đọc data.
    validate_phase05_gate()
    protocol = load_frozen_protocol(ROOT / "configs" / "evaluation_protocol_v2.json")
    datasets = {name: load_locked_evaluation_set(name, ROOT) for name in protocol.evaluation_sets}
    print("CORE_SHARED=READY")
    print(f"protocol={protocol.protocol_version}; dataset_release={protocol.dataset_release}")
    print(f"inference=seed:{protocol.inference.seed}, beams:{protocol.inference.num_beams}, batch:{protocol.inference.batch_size}")
    for direction in Direction:
        print(f"direction={direction.value}; source={direction.source_language}; target={direction.target_language}")
    for name, dataset in datasets.items():
        print(f"test_set={name}; rows={len(dataset.rows)}; path={dataset.path.relative_to(ROOT).as_posix()}")
    print("model_adapters=NOT_IMPLEMENTED")


if __name__ == "__main__":
    main()
