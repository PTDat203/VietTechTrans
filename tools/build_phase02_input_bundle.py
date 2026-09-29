"""Package only Phase 2 inputs; final test sets are intentionally excluded."""
from __future__ import annotations

import hashlib
import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INCLUDE = (
    ROOT / "data/processed/it_en_vi/train.jsonl",
    ROOT / "data/processed/it_en_vi/validation.jsonl",
    ROOT / "data/processed/it_en_vi/dataset_manifest.json",
    ROOT / "data/processed/general_validation_en_vi/general_validation.jsonl",
    ROOT / "data/processed/general_validation_en_vi/manifest.json",
    ROOT / "configs/phase02_protocol.json",
    ROOT / "configs/phase02_models.json",
)
OUT = ROOT / "releases/phase02_input_it_en_vi.zip"


def sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    from validate_phase01_gate import main as gate
    gate()
    files = []
    for item in INCLUDE:
        if item.is_file(): files.append(item)
        elif item.is_dir(): files.extend(path for path in item.rglob("*") if path.is_file())
        else: raise FileNotFoundError(item)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    manifest = [{"path": path.relative_to(ROOT).as_posix(), "bytes": path.stat().st_size, "sha256": sha(path)} for path in sorted(files)]
    forbidden = ("it_test", "general_test", "final_report")
    if any(any(marker in item["path"].lower() for marker in forbidden) for item in manifest):
        raise ValueError("Phase 2 input bundle must not contain a final-evaluation artifact.")
    with zipfile.ZipFile(OUT, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in files: archive.write(path, path.relative_to(ROOT).as_posix())
        archive.writestr("phase02_input_manifest.json", json.dumps({"dataset": "it_en_vi", "final_test_sets": "excluded", "files": manifest}, ensure_ascii=False, indent=2))
    print(f"PHASE_02_INPUT_BUNDLE={OUT}")
    print(f"SHA256={sha(OUT)}")


if __name__ == "__main__":
    main()
