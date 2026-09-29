"""Build General Validation during Phase 1 from FLORES dev.

The entry point starts from separate public EN–VI files, rejects overlap with
locked IT splits and never changes those splits. Phase 02 reads the sealed
artifact only as an outside-domain check.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "data/processed/general_validation_en_vi"


def key(text: str) -> str:
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", text).casefold()).strip()


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_lines(path: Path) -> list[str]:
    rows = path.read_text(encoding="utf-8").splitlines()
    if not rows or any(not row.strip() for row in rows):
        raise ValueError(f"{path} has blank rows; do not repair evaluation input in place.")
    return rows


def released_pairs(path: Path) -> set[tuple[str, str]]:
    pairs = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        raw = json.loads(line)
        pairs.add((key(raw.get("en_clean", raw.get("en"))), key(raw.get("vi_clean", raw.get("vi")))))
    return pairs


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--en", type=Path, required=True)
    parser.add_argument("--vi", type=Path, required=True)
    parser.add_argument("--source-description", required=True, help="Human-written provenance; it is recorded verbatim.")
    args = parser.parse_args()
    if OUT.exists():
        raise FileExistsError(f"Refusing to replace existing General Validation: {OUT}")
    en, vi = read_lines(args.en), read_lines(args.vi)
    if len(en) != len(vi):
        raise ValueError("General Validation parallel files have different lengths.")
    pairs = [(key(a), key(b)) for a, b in zip(en, vi)]
    if len(set(pairs)) != len(pairs):
        raise ValueError("General Validation has duplicate normalized pairs.")
    protected = [
        ROOT / "data/processed/it_en_vi/train.jsonl",
        ROOT / "data/processed/it_en_vi/validation.jsonl",
        ROOT / "data/processed/it_en_vi/it_test.jsonl",
        ROOT / "data/processed/general_test_flores200_devtest/general_test.jsonl",
    ]
    protected_pairs = set().union(*(released_pairs(path) for path in protected))
    overlap = set(pairs) & protected_pairs
    if overlap:
        raise ValueError("General Validation overlaps a released IT split or General Test.")
    OUT.mkdir(parents=True)
    rows = [{"row_id": f"general_validation:{index}", "en": source, "vi": target} for index, (source, target) in enumerate(zip(en, vi))]
    output = OUT / "general_validation.jsonl"
    output.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
    (OUT / "manifest.json").write_text(json.dumps({"rows": len(rows), "source_description": args.source_description, "input_sha256": {"en": digest(args.en), "vi": digest(args.vi)}, "artifact_sha256": digest(output)}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"GENERAL_VALIDATION={output}")


if __name__ == "__main__":
    main()
