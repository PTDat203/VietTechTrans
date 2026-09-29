"""Validate a completed Phase 01 release before Phase 02 begins.

This entry point reads existing manifests and splits only. It never rebuilds
data; a PASS lets Phase 02 consume the frozen release or its input bundle.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
IT = ROOT / "data" / "processed" / "it_en_vi"
GENERAL = ROOT / "data" / "processed" / "general_test_flores200_devtest"
FINAL_REPORT = ROOT / "data" / "final_report" / "it_en_vi" / "final_report_manifest.json"
GROUP_CONFIG = json.loads((ROOT / "configs/data/phase01_it_en_vi.json").read_text(encoding="utf-8"))
REQUIRED_COLUMNS = {"dataset_row_id", "split", "source_short_name", "raw_row_index", "en_clean", "vi_clean", "primary_group", "technical_tags", "group_method", "pair_sha256", "leakage_group_id"}
LEGACY_DOMAINS = {"programming", "ai_ml", "system_hardware", "networking", "database", "cloud_devops", "cybersecurity", "software_documentation", "software_localization"}


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


def grouping_errors() -> list[str]:
    errors = []
    allowed_groups = set(GROUP_CONFIG["primary_groups"])
    allowed_tags = set(GROUP_CONFIG["technical_tags"])
    allowed_methods = set(GROUP_CONFIG["group_methods"])
    for split in ("train", "validation", "it_test"):
        path = IT / f"{split}.jsonl"
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            row = json.loads(line)
            if not REQUIRED_COLUMNS.issubset(row):
                errors.append(f"Invalid Phase 01 schema: {path.relative_to(ROOT)}:{line_number}")
                break
            if "subcategory" in row:
                errors.append(f"Unexpected subcategory field: {path.relative_to(ROOT)}:{line_number}")
                break
            if row["primary_group"] not in allowed_groups:
                errors.append(f"Invalid primary_group: {path.relative_to(ROOT)}:{line_number}")
                break
            if str(row["primary_group"]).casefold() in LEGACY_DOMAINS:
                errors.append(f"Legacy data-group value: {path.relative_to(ROOT)}:{line_number}")
                break
            if not isinstance(row["technical_tags"], list) or not set(row["technical_tags"]).issubset(allowed_tags):
                errors.append(f"Invalid technical_tags: {path.relative_to(ROOT)}:{line_number}")
                break
            if row["group_method"] not in allowed_methods:
                errors.append(f"Invalid group_method: {path.relative_to(ROOT)}:{line_number}")
                break
    return errors


def main() -> None:
    required = (IT / "dataset_manifest.json", GENERAL / "dataset_manifest.json", GENERAL / "leakage_report.json", FINAL_REPORT, ROOT / "data/final_report/it_en_vi/audit_report.json")
    errors = [f"Missing required release file: {path.relative_to(ROOT)}" for path in required if not path.is_file()]
    if not errors:
        errors += manifest_errors(IT / "dataset_manifest.json") + manifest_errors(GENERAL / "dataset_manifest.json") + manifest_errors(FINAL_REPORT)
        errors += grouping_errors()
        report = json.loads((GENERAL / "leakage_report.json").read_text(encoding="utf-8"))
        for split in ("train", "validation", "it_test"):
            checks = report.get("splits", {}).get(split, {})
            for field in ("normalized_exact_pair_overlap", "normalized_english_overlap"):
                if checks.get(field) != 0:
                    errors.append(f"General Test leakage gate failed: {split}.{field}={checks.get(field)}")
    if errors:
        print("PHASE_01_GATE=FAIL")
        print("\n".join(f"- {error}" for error in errors))
        raise SystemExit(1)
    print("PHASE_01_GATE=PASS")


if __name__ == "__main__":
    main()
