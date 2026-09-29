"""Nền tảng Phase 2: screening, IT adaptation và chọn Core MT."""

from .contracts import Direction, TranslationAdapter
from .data import ParallelExample, Phase02Dataset, load_general_validation, load_it_role
from .protocol import Phase02Protocol, load_phase02_protocol
from .baseline import CandidateSpec, PretrainedAdapter, load_candidate_spec, run_pretrained_evaluation

__all__ = [
    "Direction",
    "CandidateSpec",
    "Phase02Protocol",
    "ParallelExample",
    "Phase02Dataset",
    "TranslationAdapter",
    "PretrainedAdapter",
    "load_candidate_spec",
    "load_phase02_protocol",
    "load_it_role",
    "load_general_validation",
    "run_pretrained_evaluation",
]
