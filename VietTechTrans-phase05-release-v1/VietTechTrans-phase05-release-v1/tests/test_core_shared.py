"""Các kiểm thử bảo vệ nguyên tắc chung, không kiểm tra chất lượng model.

Nếu sau này sửa data loader để phục vụ một adapter mà vô tình mở đường cho
`train`, test này phải fail. Đây là cách biến policy nghiên cứu thành ràng
buộc có thể chạy lại, thay vì chỉ ghi trong tài liệu.
"""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from core_mt.contracts import Direction
from core_mt.data import load_locked_evaluation_set
from core_mt.protocol import load_frozen_protocol


class SharedCoreTests(unittest.TestCase):
    def test_protocol_is_frozen_and_has_only_final_test_sets(self) -> None:
        """Protocol chỉ có hai test set cuối và batch size frozen bằng một."""
        protocol = load_frozen_protocol(ROOT / "configs" / "evaluation_protocol_v2.json")
        self.assertEqual(protocol.evaluation_sets, ("general_test", "it_test"))
        self.assertEqual(protocol.inference.batch_size, 1)

    def test_only_released_test_set_names_are_accepted(self) -> None:
        """Baseline không được có API đọc train, kể cả do gọi nhầm tên set."""
        with self.assertRaises(ValueError):
            load_locked_evaluation_set("train", ROOT)

    def test_released_data_supports_both_directions(self) -> None:
        """Cùng ID nhưng source/reference đảo theo direction thay vì đổi dataset."""
        dataset = load_locked_evaluation_set("general_test", ROOT)
        en_to_vi = dataset.examples(Direction.EN_TO_VI)[0]
        vi_to_en = dataset.examples(Direction.VI_TO_EN)[0]
        self.assertEqual(en_to_vi[0], vi_to_en[0])
        self.assertNotEqual(en_to_vi[1], vi_to_en[1])


if __name__ == "__main__":
    unittest.main()
