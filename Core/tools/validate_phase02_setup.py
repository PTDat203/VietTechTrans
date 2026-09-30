"""Kiểm tra điều kiện bắt đầu Phase 02 trước baseline hoặc adaptation.

Điểm bắt đầu là artifact Phase 01 và protocol đã khóa. Gate chỉ xác nhận input,
mẫu review và data boundary; không tạo prediction hay mở final test.
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from core_mt.data import load_general_validation, load_it_role
from core_mt.protocol import load_phase02_protocol


def main() -> None:
    from validate_phase01_gate import main as phase01_gate
    phase01_gate()
    protocol = load_phase02_protocol(ROOT / "configs/phase02_protocol.json")
    train = load_it_role("adaptation_train", ROOT)
    validation = load_it_role("selection_validation", ROOT)
    try:
        general = load_general_validation(ROOT)
    except FileNotFoundError as error:
        print("PHASE_02_SETUP=WAITING")
        print(f"reason={error}")
        print("next=General Validation must be built and sealed in Phase 1 before Phase 2 starts.")
        raise SystemExit(2) from None
    review = ROOT / "reviews/phase02_it_validation_review_template.csv"
    if not review.is_file():
        print("PHASE_02_SETUP=WAITING")
        print("reason=The pre-output manual-review sample is missing.")
        print("next=Run tools/build_phase02_review_sample.py --count <human-approved-count>.")
        raise SystemExit(2)
    print("PHASE_02_SETUP=PASS")
    print(f"train={len(train.rows)}; it_validation={len(validation.rows)}; general_validation={len(general.rows)}")
    print("final_sets=LOCKED_UNTIL_CORE_DECISION")
    print(f"protocol_dataset={protocol.dataset_release}")


if __name__ == "__main__":
    main()
