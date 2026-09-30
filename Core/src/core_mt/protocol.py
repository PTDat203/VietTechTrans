"""Protocol Phase 2: chọn Core MT trước, rồi mới mở final evaluation một lần."""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Phase02Protocol:
    dataset_release: str
    directions: tuple[str, ...]
    adaptation_train: Path
    selection_validation: Path
    general_validation: Path
    locked_final_sets: tuple[Path, ...]


def load_phase02_protocol(path: Path) -> Phase02Protocol:
    raw = json.loads(path.read_text(encoding="utf-8"))
    if raw.get("status") != "frozen_before_experiments":
        raise ValueError("Phase 2 protocol must be frozen before experiments.")
    if tuple(raw.get("directions", ())) != ("en_to_vi", "vi_to_en"):
        raise ValueError("Phase 2 requires both EN→VI and VI→EN.")
    roles = raw["data_roles"]
    required = {"adaptation_train", "selection_validation", "general_validation", "locked_final_evaluation"}
    if set(roles) != required:
        raise ValueError("Unexpected Phase 2 data roles.")
    final_role = roles["locked_final_evaluation"]
    if set(final_role) != {"it_test", "general_test", "access_rule"}:
        raise ValueError("Phase 2 final-evaluation role is invalid.")
    final_sets = (Path(final_role["it_test"]), Path(final_role["general_test"]))
    if len(final_sets) != 2:
        raise ValueError("Phase 2 must preserve both final test sets.")
    return Phase02Protocol(
        dataset_release=raw["dataset_release"], directions=tuple(raw["directions"]),
        adaptation_train=Path(roles["adaptation_train"]),
        selection_validation=Path(roles["selection_validation"]),
        general_validation=Path(roles["general_validation"]), locked_final_sets=final_sets,
    )
