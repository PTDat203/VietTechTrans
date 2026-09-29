"""Build the frozen Phase 01 IT EN-VI release.

``main()`` receives a new release name; ``build()`` reads configured raw sources
and ``save()`` writes that release. Technical strings remain evidence and only
clearly invalid data is rejected. Phase 02 reads this completed release rather
than rebuilding Phase 01.
"""
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
CONFIG = json.loads((ROOT / "configs/data/phase01_it_en_vi.json").read_text(encoding="utf-8"))
DEFAULT_RELEASE, SEED, RATIOS = CONFIG["dataset_version"], CONFIG["split_seed"], CONFIG["split_ratios"]
RELEASE_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*\Z")

CONTROL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f]")
SPACE = re.compile(r"\s+")
PATTERNS = {
    "has_error": re.compile(r"\b(error|failed|failure|exception|cannot|can't|unable|not found|lỗi|không thể|thất bại)\b", re.I),
    "has_command": re.compile(r"(?:^|\s)(?:sudo |pip(?:3)? |apt(?:-get)? |dnf |yum |npm |git |python(?:3)? |cd |ls(?: |$)|mkdir |rm |cp |mv |curl |wget )", re.I),
    "has_ui": re.compile(r"\b(menu|button|dialog|window|settings|option|click|save|cancel|giao diện|trình đơn|nút|hộp thoại|thiết lập)\b", re.I),
    "has_code_or_api": re.compile(r"\b(api|sdk|function|class|variable|python|java(?:script)?|typescript|json|xml|yaml|html|css|mã nguồn|hàm)\b|`[^`]+`", re.I),
    "has_path": re.compile(r"(?:[A-Za-z]:\\\\|/(?:[^\s/]+/)+|\b[^\s/]+\.(?:py|js|json|xml|html|txt|md|csv|ya?ml|ini|conf)\b)"),
    "has_config": re.compile(r"\b(config(?:uration)?|settings?|\.ini|\.conf|yaml|json|cấu hình|thiết lập)\b", re.I),
    "has_version": re.compile(r"\b(?:version|v?\d+(?:\.\d+){1,3}|python \d|cuda \d|phiên bản)\b", re.I),
}


def sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def norm(value: str) -> str:
    return SPACE.sub(" ", unicodedata.normalize("NFC", value)).strip()


def match_key(value: str) -> str:
    return SPACE.sub(" ", unicodedata.normalize("NFKC", value).casefold()).strip()


def pair_hash(en: str, vi: str) -> str:
    return hashlib.sha256(json.dumps([en, vi], ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()


def tags_for(en: str, vi: str) -> list[str]:
    text = f"{en}\n{vi}"
    return [name for name, pattern in PATTERNS.items() if pattern.search(text)]


def classify(en: str, vi: str, tags: list[str]) -> tuple[str, str]:
    """Return one primary group and how the code selected it.

    The default group is used when no rule matches; it is never called a manual
    semantic label. Technical tags can support group assignment, but
    never decide whether a pair is kept or rejected.
    """
    text = f"{en} {vi}"
    if "has_command" in tags:
        return "CLI / Command", "rule_based"
    if "has_ui" in tags:
        return "Software / UI", "rule_based"
    if "has_error" in tags:
        return "Hỗ trợ kỹ thuật / xử lý sự cố", "rule_based"
    if re.search(r"\b(how to|please|guide|tutorial|install|configure|hướng dẫn|cách |cài đặt|khắc phục)\b", text, re.I):
        return "Hỗ trợ kỹ thuật / xử lý sự cố", "rule_based"
    if any(tag in tags for tag in ("has_code_or_api", "has_path", "has_config", "has_version")):
        return "Code & Technical Reference", "rule_based"
    # The fallback is descriptive, not a manual semantic annotation. Its origin
    # remains visible through group_method.
    return "Code & Technical Reference", "default_group"


def audit_raw(frame: pd.DataFrame) -> dict[str, int]:
    """Count risks in RAW. Audit records signals; it does not alter RAW."""
    en, vi = frame["en_raw"], frame["vi_raw"]
    en_text, vi_text = en.map(lambda value: isinstance(value, str)), vi.map(lambda value: isinstance(value, str))
    text_pair = en_text & vi_text
    en_string, vi_string = en.fillna("").map(str), vi.fillna("").map(str)
    nonempty = en_string.str.strip().ne("") & vi_string.str.strip().ne("")
    normalized = pd.DataFrame({"en": en_string.map(norm), "vi": vi_string.map(norm)})
    length_ratio = normalized.en.str.len().div(normalized.vi.str.len().clip(lower=1))
    has_letters = lambda series: series.str.contains(r"[A-Za-zÀ-ỹ]", regex=True)
    return {
        "rows": len(frame),
        "non_string": int((~text_pair).sum()),
        "null_or_blank": int((text_pair & ~nonempty).sum()),
        "control_or_replacement_character": int((en_string.map(lambda value: "\ufffd" in value or bool(CONTROL.search(value))) | vi_string.map(lambda value: "\ufffd" in value or bool(CONTROL.search(value)))).sum()),
        "identical_en_vi": int((text_pair & normalized.en.eq(normalized.vi)).sum()),
        "extreme_length_ratio": int((text_pair & ((length_ratio > 3.5) | (length_ratio < 1 / 3.5))).sum()),
        "very_long_pair": int((text_pair & ((normalized.en.str.len() > 1000) | (normalized.vi.str.len() > 1000))).sum()),
        "language_suspect_no_latin_letters": int((text_pair & (~has_letters(normalized.en) | ~has_letters(normalized.vi))).sum()),
        "exact_duplicate_in_source": int(frame.duplicated(["en_raw", "vi_raw"], keep="first").sum()),
    }


def source_frame(source: str, spec: dict) -> tuple[pd.DataFrame, dict]:
    folder = ROOT / "data/raw" / source
    path = folder / f"{source}_raw.parquet"
    raw = pd.read_parquet(path)
    frame = pd.DataFrame({"source_short_name": source, "raw_row_index": raw.index.astype("int64"), "en_raw": raw[spec["en"]], "vi_raw": raw[spec["vi"]]})
    audit = audit_raw(frame)
    valid = frame.en_raw.map(lambda x: isinstance(x, str) and bool(x.strip())) & frame.vi_raw.map(lambda x: isinstance(x, str) and bool(x.strip()))
    valid &= ~frame.en_raw.fillna("").map(lambda x: "\ufffd" in str(x) or bool(CONTROL.search(str(x))))
    valid &= ~frame.vi_raw.fillna("").map(lambda x: "\ufffd" in str(x) or bool(CONTROL.search(str(x))))
    frame = frame.loc[valid].copy()
    frame["en_clean"], frame["vi_clean"] = frame.en_raw.map(norm), frame.vi_raw.map(norm)
    frame = frame.drop_duplicates(["en_clean", "vi_clean"], keep="first").copy()
    # This mixed-domain source contributes only configured IT categories. Its
    # source category is inclusion evidence, not a release data group.
    if source == "envitech_reasoning" and "category" in raw.columns:
        allowed = {"tech_ai", "tech_coding", "tech_hardware", "tech_ml_ops"}
        frame = frame.loc[raw.loc[frame.raw_row_index, "category"].isin(allowed).to_numpy()].copy()
    frame["pair_sha256"] = [pair_hash(a, b) for a, b in zip(frame.en_clean, frame.vi_clean)]
    frame["technical_tags"] = [tags_for(a, b) for a, b in zip(frame.en_clean, frame.vi_clean)]
    classified = [classify(a, b, tags) for a, b, tags in zip(frame.en_clean, frame.vi_clean, frame.technical_tags)]
    frame[["primary_group", "group_method"]] = pd.DataFrame(classified, index=frame.index)
    meta = {"source_short_name": source, "raw_rows": len(raw), "rows_after_cleaning": len(frame), "raw_snapshot_sha256": sha(path), "source_revision": spec["source_revision"], "audit": audit}
    return frame, meta


def stable(value: str) -> int:
    return int(hashlib.sha256(f"{SEED}:{value}".encode()).hexdigest()[:16], 16)


def split(frame: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame]:
    x = frame.copy()
    x["en_match_key"], x["vi_match_key"] = x.en_clean.map(match_key), x.vi_clean.map(match_key)
    x["global_pair_key"] = x.en_match_key + "\u241f" + x.vi_match_key
    x = x.sort_values(["source_short_name", "raw_row_index"], kind="stable")
    duplicates = x.loc[x.duplicated("global_pair_key", keep="first")].copy()
    x = x.drop_duplicates("global_pair_key", keep="first").copy()
    x["leakage_group_id"] = x.en_match_key.map(lambda v: hashlib.sha256(v.encode()).hexdigest())
    reps = x.drop_duplicates("leakage_group_id")
    assignments: dict[str, str] = {}
    for _, group in reps.groupby(["source_short_name", "primary_group"], sort=True):
        ids = sorted(group.leakage_group_id, key=stable); n = len(ids)
        a, b = max(1, round(n * RATIOS["train"])), max(1, round(n * (RATIOS["train"] + RATIOS["validation"])))
        for index, gid in enumerate(ids): assignments[gid] = "train" if index < a else "validation" if index < b else "it_test"
    x["split"] = x.leakage_group_id.map(assignments)
    x["dataset_row_id"] = [hashlib.sha256(f"{s}:{i}:{k}".encode()).hexdigest() for s, i, k in zip(x.source_short_name, x.raw_row_index, x.global_pair_key)]
    return x, duplicates


def counts(frame: pd.DataFrame, fields: list[str]) -> list[dict]:
    return frame.groupby(fields).size().rename("pairs").reset_index().sort_values(fields).to_dict("records")


def build(release_name: str) -> dict:
    frames, source_meta = [], []
    for source, spec in CONFIG["sources"].items():
        frame, meta = source_frame(source, spec); frames.append(frame); source_meta.append(meta)
    dataset, global_dups = split(pd.concat(frames, ignore_index=True))
    train = dataset[dataset.split == "train"]
    leakage = {}
    for name in ("validation", "it_test"):
        other = dataset[dataset.split == name]
        leakage[name] = {"normalized_exact_pair_overlap": len(set(train.global_pair_key) & set(other.global_pair_key)), "normalized_english_overlap": len(set(train.leakage_group_id) & set(other.leakage_group_id))}
    assert all(value == 0 for entry in leakage.values() for value in entry.values())
    audit_total = {field: sum(item["audit"][field] for item in source_meta) for field in source_meta[0]["audit"]}
    report = {"dataset_version": release_name, "group_rules_version": CONFIG["group_rules_version"], "created_at_utc": datetime.now(timezone.utc).isoformat(), "split_seed": SEED, "split_ratios": RATIOS, "sources": source_meta, "audit": {"by_source": [{"source_short_name": item["source_short_name"], **item["audit"]} for item in source_meta], "total": audit_total, "policy": "Audit signals are reported. Only blank/non-string/control-character input and exact duplicates are rejected automatically."}, "attrition": {"raw_rows": sum(x["raw_rows"] for x in source_meta), "after_source_cleaning": sum(x["rows_after_cleaning"] for x in source_meta), "after_global_exact_dedup": len(dataset)}, "split_counts": {name: int((dataset.split == name).sum()) for name in RATIOS}, "leakage": leakage, "group_methods": counts(dataset, ["group_method"])}
    return {"dataset": dataset, "duplicates": global_dups, "report": report}


def save(result: dict, release_name: str) -> None:
    out = ROOT / "data/processed" / release_name
    report_dir = ROOT / "data/final_report" / release_name
    if out.exists() or report_dir.exists():
        raise FileExistsError(
            f"Refusing to overwrite an existing dataset release or report: {out} / {report_dir}. "
            "Choose a new --release-name."
        )
    out.mkdir(parents=True); report_dir.mkdir(parents=True)
    data = result["dataset"]; files = []
    columns = ["dataset_row_id", "split", "source_short_name", "raw_row_index", "en_clean", "vi_clean", "primary_group", "technical_tags", "group_method", "pair_sha256", "leakage_group_id"]
    for name in RATIOS:
        part = data.loc[data.split.eq(name), columns].sort_values("dataset_row_id")
        for extension in ("parquet", "jsonl"):
            path = out / f"{name}.{extension}"
            if extension == "parquet": part.to_parquet(path, index=False)
            else: part.to_json(path, orient="records", lines=True, force_ascii=False)
            files.append(path)
    result["duplicates"].to_parquet(out / "global_duplicates_removed.parquet", index=False); files.append(out / "global_duplicates_removed.parquet")
    report_path = out / "dataset_report.json"; report_path.write_text(json.dumps(result["report"], ensure_ascii=False, indent=2), encoding="utf-8"); files.append(report_path)
    manifest = {"schema_version": "2.0", "dataset_version": release_name, "split_seed": SEED, "artifacts": [{"path": p.relative_to(ROOT).as_posix(), "bytes": p.stat().st_size, "sha256": sha(p)} for p in files]}
    (out / "dataset_manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    stale_subcategory_report = report_dir / "subcategory_distribution.csv"
    if stale_subcategory_report.exists():
        stale_subcategory_report.unlink()
    reports = {"source_distribution.csv": counts(data, ["source_short_name"]), "primary_group_distribution.csv": counts(data, ["split", "primary_group"]), "technical_tag_distribution.csv": [{"technical_tag": tag, "pairs": int(data.technical_tags.map(lambda tags: tag in tags).sum())} for tag in CONFIG["technical_tags"]], "audit_report.json": result["report"]["audit"], "data_attrition.json": result["report"]["attrition"], "leakage_report.json": result["report"]["leakage"]}
    for name, value in reports.items():
        path = report_dir / name
        if name.endswith(".csv"): pd.DataFrame(value).to_csv(path, index=False, encoding="utf-8-sig")
        else: path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    card = f"# Dataset Card — {release_name}\n\n- Cặp ngôn ngữ: EN ↔ VI\n- Miền: IT\n- Số cặp cuối: {len(data):,}\n- Split: train {result['report']['split_counts']['train']:,}, validation {result['report']['split_counts']['validation']:,}, IT test {result['report']['split_counts']['it_test']:,}\n- Phân nhóm: 4 primary groups và compact technical tags; không dùng subcategory.\n\n`group_method` cho biết nhóm được gán bằng rule trong code hay group mặc định.\n"
    (report_dir / "DATASET_CARD.md").write_text(card, encoding="utf-8")
    report_files = [path for path in report_dir.iterdir() if path.is_file() and path.name != "final_report_manifest.json"]
    (report_dir / "final_report_manifest.json").write_text(json.dumps({"schema_version": "2.0", "dataset_version": release_name, "artifacts": [{"path": path.relative_to(ROOT).as_posix(), "bytes": path.stat().st_size, "sha256": sha(path)} for path in sorted(report_files)]}, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--release-name", default=DEFAULT_RELEASE, help="New immutable release name; an existing release is never overwritten.")
    args = parser.parse_args()
    if not RELEASE_NAME.fullmatch(args.release_name):
        parser.error("--release-name may contain only letters, digits, dot, underscore, or hyphen.")
    result = build(args.release_name); save(result, args.release_name); print(f"PHASE_01_DATASET={args.release_name}; rows={len(result['dataset'])}")
