"""Quy ước evidence có thể tái lập cho baseline run.

Mỗi model × direction sau này phải lưu cùng sáu artifact. `resolved_config`
giữ commit checkpoint và generation settings; `predictions` cho phép audit;
metric và benchmark luôn tách riêng. Không ghi đè thư mục run cũ vì một lần
chạy lại có thể dùng checkpoint cache, driver hoặc hardware khác.
"""
from __future__ import annotations

from pathlib import Path


REQUIRED_ARTIFACTS = (
    "resolved_config.json", "predictions.jsonl", "metrics.json", "benchmark.json", "environment.txt", "run.log",
)


def write_json(path: Path, payload: object) -> None:
    """Ghi JSON atomically để run dở không tạo evidence nửa chừng."""
    import json
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(path)


def write_jsonl(path: Path, rows: list[dict[str, object]]) -> None:
    """Ghi prediction theo thứ tự test set, một JSON object trên mỗi dòng."""
    import json
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
    temporary.replace(path)


def assert_new_run_directory(path: Path) -> None:
    """Chặn ghi đè để evidence cũ không bị thay thế âm thầm."""
    if path.exists():
        raise FileExistsError(f"Refusing to overwrite existing baseline run: {path}")
