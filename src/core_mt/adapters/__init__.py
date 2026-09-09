"""Các adapter model-specific dùng chung một TranslationAdapter contract."""

from .envit5 import EnViT5Adapter
from .opus_mt import OpusMTAdapter

__all__ = ["EnViT5Adapter", "OpusMTAdapter"]
