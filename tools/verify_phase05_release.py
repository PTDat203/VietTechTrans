"""Kiểm tra checksum nội bộ của phase05_release_v1.zip trước khi giải nén."""
from __future__ import annotations

import argparse
import hashlib
import json
import zipfile
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", type=Path, required=True)
    args = parser.parse_args()
    with zipfile.ZipFile(args.archive) as archive:
        manifest_name = "phase05_release_v1/release_manifest.json"
        manifest = json.loads(archive.read(manifest_name))
        errors = []
        for item in manifest["files"]:
            try:
                payload = archive.read(item["path"])
            except KeyError:
                errors.append(f"Missing archive member: {item['path']}")
                continue
            if len(payload) != item["bytes"]:
                errors.append(f"Byte mismatch: {item['path']}")
            if hashlib.sha256(payload).hexdigest() != item["sha256"]:
                errors.append(f"SHA-256 mismatch: {item['path']}")
    if errors:
        print("PHASE_05_RELEASE=FAIL")
        print("\n".join(f"- {error}" for error in errors))
        raise SystemExit(1)
    print("PHASE_05_RELEASE=PASS")


if __name__ == "__main__":
    main()
