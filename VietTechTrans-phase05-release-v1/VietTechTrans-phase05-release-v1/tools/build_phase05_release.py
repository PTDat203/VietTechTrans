"""Đóng Phase 05 release thành ZIP độc lập để máy mới chạy Phase 06."""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RELEASE_VERSION = "phase05_release_v1"
INCLUDE = (
    ROOT / "data" / "processed" / "it_en_vi_v1",
    ROOT / "data" / "processed" / "general_test_flores200_devtest_v1",
)


def sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def tracked_release_files() -> list[Path]:
    files = []
    for folder in INCLUDE:
        if not folder.is_dir():
            raise FileNotFoundError(f"Missing Phase 05 release directory: {folder}")
        files.extend(path for path in folder.rglob("*") if path.is_file() and ".ipynb_checkpoints" not in path.parts)
    return sorted(files, key=lambda item: item.relative_to(ROOT).as_posix())


def git_revision() -> str | None:
    try:
        return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=ROOT / "releases" / f"{RELEASE_VERSION}.zip")
    args = parser.parse_args()
    from validate_phase05_gate import main as validate_phase05_gate

    validate_phase05_gate()
    files = tracked_release_files()
    manifest_files = [{"path": path.relative_to(ROOT).as_posix(), "bytes": path.stat().st_size, "sha256": sha256_file(path)} for path in files]
    manifest = {
        "release_schema_version": "1.0",
        "release_version": RELEASE_VERSION,
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
        "source_revision": git_revision(),
        "extraction_root": "repository root",
        "files": manifest_files,
    }
    checksum_lines = [f"{item['sha256']}  {item['path']}" for item in manifest_files]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in files:
            archive.write(path, path.relative_to(ROOT).as_posix())
        archive.writestr(f"{RELEASE_VERSION}/release_manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
        archive.writestr(f"{RELEASE_VERSION}/SHA256SUMS.txt", "\n".join(checksum_lines) + "\n")
    print(f"RELEASE_BUILT={args.output}")
    print(f"SHA256={sha256_file(args.output)}")
    print(f"FILES={len(files)}")


if __name__ == "__main__":
    main()
