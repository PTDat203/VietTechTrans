"""Build a sealed General Test from reviewed, parallel source files."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import unicodedata
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "configs" / "data" / "general_test_flores200_devtest_v1.json"
OUT = ROOT / "data" / "processed" / "general_test_flores200_devtest_v1"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def key(text: str) -> str:
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", text).casefold()).strip()


def lines(path: Path) -> list[str]:
    result = path.read_text(encoding="utf-8").splitlines()
    if not result or any(not line.strip() for line in result):
        raise ValueError(f"{path} contains an empty line; do not repair evaluation data in-place.")
    return result


def locked_split(split: str) -> pd.DataFrame:
    path = ROOT / "data" / "processed" / "it_en_vi_v1" / f"{split}.jsonl"
    if not path.is_file():
        raise FileNotFoundError(f"Missing locked IT test: {path}")
    frame = pd.read_json(path, lines=True)
    if not {"en_clean", "vi_clean"} <= set(frame.columns):
        raise ValueError(f"Locked {split} split has an unexpected schema.")
    return frame


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--en", type=Path, required=True, help="FLORES eng_Latn.devtest")
    parser.add_argument("--vi", type=Path, required=True, help="FLORES vie_Latn.devtest")
    parser.add_argument("--force", action="store_true", help="Replace a prior unsealed build only.")
    args = parser.parse_args()
    config = json.loads(CONFIG.read_text(encoding="utf-8"))
    if OUT.exists() and any(OUT.iterdir()) and not args.force:
        raise FileExistsError(f"Refusing to overwrite release: {OUT}. Use a new version or --force before sealing.")

    en, vi = lines(args.en), lines(args.vi)
    if len(en) != len(vi):
        raise ValueError(f"Parallel-file length mismatch: en={len(en)}, vi={len(vi)}")
    if len(en) != config["source"]["expected_rows"]:
        raise ValueError(f"Expected {config['source']['expected_rows']} rows, received {len(en)}")
    data = pd.DataFrame({"row_id": range(len(en)), "en": en, "vi": vi})
    data["pair_sha256"] = [hashlib.sha256(f"{key(a)}\u241f{key(b)}".encode()).hexdigest() for a, b in zip(en, vi)]
    if not data.pair_sha256.is_unique:
        raise ValueError("General Test contains normalized duplicate pairs; reject the source rather than deduplicating it.")

    general_pairs = set(zip(data.en.map(key), data.vi.map(key)))
    general_en = {key(x) for x in data.en}
    report = {"splits": {}}
    for split in ("train", "validation", "it_test"):
        locked = locked_split(split)
        report["splits"][split] = {
            "normalized_exact_pair_overlap": len(general_pairs & set(zip(locked.en_clean.map(key), locked.vi_clean.map(key)))),
            "normalized_english_overlap": len(general_en & {key(x) for x in locked.en_clean}),
        }
    if any(value for split in report["splits"].values() for value in split.values()):
        raise ValueError(f"Leakage gate failed: {report}")

    OUT.mkdir(parents=True, exist_ok=True)
    csv, jsonl, report_path = OUT / "general_test.csv", OUT / "general_test.jsonl", OUT / "leakage_report.json"
    data.to_csv(csv, index=False, encoding="utf-8")
    data.to_json(jsonl, orient="records", lines=True, force_ascii=False)
    report.update({"checked_at_utc": datetime.now(timezone.utc).isoformat(), "status": "pass"})
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    manifest = {
        "schema_version": "1.0", "dataset_version": config["dataset_version"], "role": "general_test",
        "source": config["source"], "built_at_utc": datetime.now(timezone.utc).isoformat(),
        "input_sha256": {"en": sha256(args.en), "vi": sha256(args.vi)},
        "artifacts": [{"path": p.relative_to(ROOT).as_posix(), "bytes": p.stat().st_size, "sha256": sha256(p)} for p in (csv, jsonl, report_path)],
    }
    (OUT / "dataset_manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Built sealed General Test: {OUT}")


if __name__ == "__main__":
    main()
