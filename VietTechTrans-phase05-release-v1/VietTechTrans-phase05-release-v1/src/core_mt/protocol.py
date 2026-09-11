"""Đọc luật đánh giá đã khóa trước baseline.

Trong nghiên cứu so sánh, cùng dataset nhưng beam size hoặc batch size khác
nhau đã đủ làm kết quả không còn công bằng. Vì vậy module này chuyển file JSON
đã frozen thành các object bất biến và từ chối protocol sai trạng thái, sai hai
direction hoặc có thêm evaluation set ngoài General Test và IT Test.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class InferenceSettings:
    """Generation settings phải giống nhau giữa mọi model và direction."""
    seed: int
    num_beams: int
    max_new_tokens: int
    batch_size: int


@dataclass(frozen=True)
class BenchmarkSettings:
    """Quy tắc đo hiệu năng; warm-up tách khỏi latency được báo cáo."""
    quality_passes: int
    warmup_sentences: int
    latency_sample_size: int
    sampling_seed: int
    latency_repeats: int
    latency_unit: str


@dataclass(frozen=True)
class EvaluationProtocol:
    """Bản protocol đã kiểm tra, được shared runner dùng làm nguồn duy nhất."""
    protocol_version: str
    dataset_release: str
    directions: tuple[str, ...]
    evaluation_sets: tuple[str, ...]
    inference: InferenceSettings
    benchmark: BenchmarkSettings


def load_frozen_protocol(path: Path) -> EvaluationProtocol:
    """Đọc protocol và biến nguyên tắc so sánh thành các kiểm tra trong code.

    Hàm này không cho phép chạy baseline nếu protocol chưa frozen. Nó cũng giữ
    cố định thứ tự `general_test`, rồi `it_test`, để không có code nhánh nào
    vô tình bỏ một bộ test hay gộp hai bộ thành một điểm.
    """
    raw = json.loads(path.read_text(encoding="utf-8"))
    if raw.get("status") != "frozen_before_baseline":
        raise ValueError("Evaluation protocol must be frozen before a baseline run.")
    if tuple(raw.get("directions", ())) != ("en_to_vi", "vi_to_en"):
        raise ValueError("Unexpected translation directions in evaluation protocol.")
    if tuple(raw.get("evaluation_sets", ())) != ("general_test", "it_test"):
        raise ValueError("Baseline may use only general_test and it_test.")
    inference, benchmark = raw["inference"], raw["benchmark"]
    if inference["batch_size"] != 1 or inference["num_beams"] < 1 or inference["max_new_tokens"] < 1:
        raise ValueError("Invalid frozen inference settings.")
    if benchmark.get("quality_passes") != 1:
        raise ValueError("Baseline quality must use exactly one full prediction pass per test set.")
    if benchmark["warmup_sentences"] < 0 or benchmark["latency_sample_size"] < 1 or benchmark["latency_repeats"] < 1:
        raise ValueError("Invalid frozen benchmark settings.")
    return EvaluationProtocol(
        protocol_version=raw["protocol_version"],
        dataset_release=raw["dataset_release"],
        directions=tuple(raw["directions"]),
        evaluation_sets=tuple(raw["evaluation_sets"]),
        inference=InferenceSettings(
            seed=inference["seed"], num_beams=inference["num_beams"],
            max_new_tokens=inference["max_new_tokens"], batch_size=inference["batch_size"],
        ),
        benchmark=BenchmarkSettings(
            quality_passes=benchmark["quality_passes"], warmup_sentences=benchmark["warmup_sentences"],
            latency_sample_size=benchmark["latency_sample_size"], sampling_seed=inference["seed"], latency_repeats=benchmark["latency_repeats"],
            latency_unit=benchmark["latency_unit"],
        ),
    )
