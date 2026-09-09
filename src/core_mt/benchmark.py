"""Các phép đo hiệu năng dùng chung, tách biệt hoàn toàn với chất lượng dịch.

Quality cần prediction toàn bộ test set đúng một lần. Latency dùng sample cố
định theo row ID để mọi model chịu cùng workload, không phải dịch lặp cả corpus.
"""
from __future__ import annotations

import hashlib
from typing import Callable, TypeVar


T = TypeVar("T")


def deterministic_sample(rows: tuple[T, ...], row_id: Callable[[T], str], size: int, seed: int) -> tuple[T, ...]:
    """Chọn sample không thay thế, ổn định giữa các lần chạy và model."""
    if size < 1:
        raise ValueError("Benchmark sample size must be positive.")
    ranked = sorted(rows, key=lambda row: hashlib.sha256(f"{seed}:{row_id(row)}".encode("utf-8")).hexdigest())
    return tuple(ranked[:size])


def summarize_latency(samples_ms: list[float]) -> dict[str, float]:
    """Trả median và p95 latency theo câu từ các lần đo hợp lệ."""
    if not samples_ms or any(sample < 0 for sample in samples_ms):
        raise ValueError("Latency samples must be non-empty non-negative milliseconds.")
    ordered = sorted(samples_ms)
    p95_index = min(len(ordered) - 1, round(0.95 * (len(ordered) - 1)))
    return {"latency_median_ms": ordered[len(ordered) // 2], "latency_p95_ms": ordered[p95_index]}
