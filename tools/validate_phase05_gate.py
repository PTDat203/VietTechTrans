"""Validate the non-negotiable release gate before Phase 06 baseline runs."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
IT = ROOT / "data" / "processed" / "it_en_vi_v1"
GENERAL = ROOT / "data" / "processed" / "general_test_flores200_devtest_v1"
PROTOCOL = ROOT / "configs" / "evaluation_protocol_v1.json"
FINAL_REPORT = ROOT / "data" / "final_report" / "it_en_vi_v1" / "final_report_manifest.json"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def manifest_errors(path: Path) -> list[str]:
    errors = []
    for artifact in json.loads(path.read_text(encoding="utf-8"))["artifacts"]:
        target = ROOT / artifact["path"]
        if not target.is_file():
            errors.append(f"Missing artifact: {target}")
        elif sha256(target) != artifact["sha256"]:
            errors.append(f"Checksum mismatch: {target}")
    return errors


def main() -> None:
    required = (IT / "dataset_manifest.json", GENERAL / "dataset_manifest.json", GENERAL / "leakage_report.json", FINAL_REPORT, PROTOCOL)
    errors = [f"Missing required release file: {path.relative_to(ROOT)}" for path in required if not path.is_file()]
    if not errors:
        errors += manifest_errors(IT / "dataset_manifest.json") + manifest_errors(GENERAL / "dataset_manifest.json") + manifest_errors(FINAL_REPORT)
        report = json.loads((GENERAL / "leakage_report.json").read_text(encoding="utf-8"))
        for split in ("train", "validation", "it_test"):
            checks = report.get("splits", {}).get(split, {})
            for field in ("normalized_exact_pair_overlap", "normalized_english_overlap"):
                if checks.get(field) != 0:
                    errors.append(f"General Test leakage gate failed: {split}.{field}={checks.get(field)}")
        if json.loads(PROTOCOL.read_text(encoding="utf-8")).get("status") != "frozen_before_baseline":
            errors.append("Evaluation protocol is not frozen.")
    if errors:
        print("PHASE_05_GATE=FAIL")
        print("\n".join(f"- {error}" for error in errors))
        raise SystemExit(1)
    print("PHASE_05_GATE=PASS")


if __name__ == "__main__":
    main()
