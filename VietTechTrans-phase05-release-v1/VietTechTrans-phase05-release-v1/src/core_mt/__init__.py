"""Nền tảng dùng chung của Phase 06 CORE MT.

Luồng nghiên cứu được giữ tách thành ba tầng:

``Phase 05 sealed data → shared CORE layer → adapter theo từng model``.

Module này chỉ export các khái niệm chung: direction, protocol đã khóa và hai
test set cuối. OPUS-MT, EnViT5 và M2M-100 không xuất hiện ở đây, để kết quả
khác nhau sau này có thể được quy về model thay vì ba pipeline khác nhau.
"""

from .contracts import Direction, TranslationAdapter
from .data import LockedEvaluationSet, TestExample, load_locked_evaluation_set
from .protocol import EvaluationProtocol, load_frozen_protocol

__all__ = [
    "Direction",
    "EvaluationProtocol",
    "LockedEvaluationSet",
    "TestExample",
    "TranslationAdapter",
    "load_frozen_protocol",
    "load_locked_evaluation_set",
]
