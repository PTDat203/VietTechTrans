"""Refresh the tracked manifest for all Phase 05 evidence-report artifacts."""
from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / "data" / "final_report" / "it_en_vi_v1"
MANIFEST = REPORT / "final_report_manifest.json"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    artifacts = []
    for path in sorted(REPORT.iterdir()):
        if path.is_file() and path.name != MANIFEST.name:
            artifacts.append({"path": path.relative_to(ROOT).as_posix(), "bytes": path.stat().st_size, "sha256": sha256(path)})
    payload = {"manifest_schema_version": "1.1", "dataset_version": "it_en_vi_v1", "refreshed_at_utc": datetime.now(timezone.utc).isoformat(), "artifacts": artifacts}
    MANIFEST.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Refreshed {MANIFEST} with {len(artifacts)} artifacts")


if __name__ == "__main__":
    main()
