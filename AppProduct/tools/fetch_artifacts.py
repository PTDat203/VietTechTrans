"""Fetch, verify and install the app artifacts pinned in ``artifacts.lock.json``.

Artifacts: the sherpa-onnx AAR (into ``third_party/``) and the VAD, STT, TTS and
denoiser models (into ``app/src/main/assets/models/``). Archives are cached in
``.cache/downloads/``; only files listed in the lock are extracted, test WAVs go
to ``.cache/test_wavs/<role>/``, and ``espeak-ng-data`` keeps only the listed
``*_dict`` files. The run ends by writing ``app/src/main/assets/models/manifest.json``,
from which the app reads model roles and file names.

Standard library only (Python 3.9+). Run from ``AppProduct`` with the repo .venv::

    ..\\.venv\\Scripts\\python.exe tools\\fetch_artifacts.py            # fetch + install
    ..\\.venv\\Scripts\\python.exe tools\\fetch_artifacts.py --record   # also fill null hashes in the lock
    ..\\.venv\\Scripts\\python.exe tools\\fetch_artifacts.py --verify   # offline check, no network

Prints ``APP_ARTIFACTS=PASS``, or ``APP_ARTIFACTS=FAIL <reason>`` and exits with code 1.
"""
from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import os
import re
import shutil
import sys
import tarfile
import time
import traceback
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

DEFAULT_ROOT = Path(__file__).resolve().parents[1]
MARKER = "APP_ARTIFACTS"
LOCK_NAME = "artifacts.lock.json"
SCHEMA_VERSION = "1.0"
ASSETS_DIR = "app/src/main/assets"
MANIFEST_REL = ASSETS_DIR + "/models/manifest.json"
DOWNLOADS_REL = ".cache/downloads"
TEST_WAVS_DIR = ".cache/test_wavs"
USER_AGENT = "VietTechTrans-AppProduct-fetch_artifacts/1.0 (Python urllib)"
CHUNK = 1 << 20
RETRIES = 3
TIMEOUT_S = 60
SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")
ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*\Z")
UTC_RE = re.compile(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\+00:00\Z")


class FetchError(Exception):
    """Expected failure, reported as ``APP_ARTIFACTS=FAIL <reason>``."""


@dataclass
class Context:
    root: Path
    from_dir: Path | None
    record: bool
    changes: list = field(default_factory=list)


# ---------------------------------------------------------------- hashing

def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(CHUNK), b""):
            digest.update(block)
    return digest.hexdigest()


def sha256_tree(directory: Path) -> str:
    """Digest of Core ``core_mt.adaptation.sha256_tree``, independent of the OS.

    For each file, in sorted order of relative POSIX paths (compared part by
    part, case-sensitive, as ``PurePosixPath`` sorts), the digest takes the path
    in UTF-8, NUL, the file bytes, NUL. Core sorts ``Path`` objects, which gives
    the same order on Linux/macOS but a case-insensitive order on Windows.
    """
    if not directory.is_dir():
        raise FetchError(f"missing directory {directory}")
    rels = [p.relative_to(directory).as_posix() for p in directory.rglob("*") if p.is_file()]
    digest = hashlib.sha256()
    for rel in sorted(rels, key=lambda value: value.split("/")):
        digest.update(rel.encode("utf-8"))
        digest.update(b"\0")
        with (directory / rel).open("rb") as handle:
            for block in iter(lambda: handle.read(CHUNK), b""):
                digest.update(block)
        digest.update(b"\0")
    return digest.hexdigest()


def tree_stats(directory: Path) -> dict:
    files = [p for p in directory.rglob("*") if p.is_file()]
    return {"file_count": len(files), "bytes": sum(p.stat().st_size for p in files),
            "sha256_tree": sha256_tree(directory)}


def lock_sha256(raw: bytes) -> str:
    """SHA-256 of the lock as LF bytes, so a CRLF checkout gives the same value."""
    return hashlib.sha256(raw.replace(b"\r\n", b"\n")).hexdigest()


# ---------------------------------------------------------------- lock file

def safe_rel(value, what: str) -> str:
    """Accept only a relative POSIX path that cannot leave its base directory."""
    if not isinstance(value, str) or not value:
        raise FetchError(f"{what}: expected a non-empty relative path")
    if (value.startswith("/") or any(ch in '\\:*?"<>|' or ord(ch) < 32 for ch in value)
            or any(part in ("", ".", "..") for part in value.split("/"))):
        raise FetchError(f"{what}: unsafe path {value!r}")
    return value


def is_under(path: str, base: str) -> bool:
    return path == base or path.startswith(base + "/")


def asset_rel(dest: str) -> str | None:
    """Path relative to ``assets/``, or None when ``dest`` is not an app asset."""
    return dest[len(ASSETS_DIR) + 1:] if dest.startswith(ASSETS_DIR + "/") else None


def archive_name(art: dict) -> str:
    name = art["url"].rsplit("/", 1)[-1]
    safe_rel(name, f"{art['id']}: archive name")
    return name


def _check_pair(holder, keys: tuple, what: str) -> None:
    if not isinstance(holder, dict):
        raise FetchError(f"{what}: expected an object")
    for key in keys:
        value = holder.get(key, "missing")
        if value is None:
            continue
        if key == "sha256" or key == "sha256_tree":
            if not isinstance(value, str) or not SHA256_RE.match(value):
                raise FetchError(f"{what}.{key}: expected 64 lowercase hex characters or null")
        elif isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise FetchError(f"{what}.{key}: expected a non-negative integer or null")


def validate_lock(lock) -> None:
    if not isinstance(lock, dict) or lock.get("schema_version") != SCHEMA_VERSION:
        raise FetchError(f"{LOCK_NAME}: schema_version must be {SCHEMA_VERSION!r}")
    artifacts = lock.get("artifacts")
    if not isinstance(artifacts, list) or not artifacts:
        raise FetchError(f"{LOCK_NAME}: 'artifacts' must be a non-empty list")
    ids, roles, names, owned, copies = set(), set(), set(), [], []
    espeak_count = 0
    for art in artifacts:
        if not isinstance(art, dict) or not isinstance(art.get("id"), str) or not ID_RE.match(art["id"]):
            raise FetchError(f"{LOCK_NAME}: every artifact needs an id of letters, digits, '.', '_' or '-'")
        aid = art["id"]
        for key in ("role", "license", "source", "url"):
            if not isinstance(art.get(key), str) or not art[key]:
                raise FetchError(f"{aid}: '{key}' must be a non-empty string")
        if not art["url"].startswith(("https://", "http://")):
            raise FetchError(f"{aid}: url must be http(s)")
        if "sherpa" in art and not isinstance(art["sherpa"], dict):
            raise FetchError(f"{aid}: 'sherpa' must be an object")
        name = archive_name(art)
        for seen, value, what in ((ids, aid, "id"), (roles, art["role"], "role"), (names, name, "archive name")):
            if value in seen:
                raise FetchError(f"{LOCK_NAME}: duplicate {what} {value!r}")
            seen.add(value)
        _check_pair(art.get("archive"), ("bytes", "sha256"), f"{aid}.archive")
        inst = art.get("install")
        if not isinstance(inst, dict) or inst.get("kind") not in ("copy", "extract"):
            raise FetchError(f"{aid}: install.kind must be 'copy' or 'extract'")
        dest = safe_rel(inst.get("dest"), f"{aid}: install.dest")
        files = inst.get("files")
        if not isinstance(files, list) or not files:
            raise FetchError(f"{aid}: install.files must be a non-empty list")
        for entry in files:
            _check_pair(entry, ("bytes", "sha256"), f"{aid}.files")
            safe_rel(entry.get("from"), f"{aid}: files.from")
            safe_rel(entry.get("to"), f"{aid}: files.to")
            if not isinstance(entry.get("key"), str) or not entry["key"]:
                raise FetchError(f"{aid}: every file needs a key")
        for what in ("key", "to", "from"):
            values = [entry[what] for entry in files]
            if len(set(values)) != len(values):
                raise FetchError(f"{aid}: duplicate files.{what}")
        if inst["kind"] == "copy":
            if len(files) != 1 or files[0]["from"] != name or "test_wavs" in inst or "espeak_data" in inst:
                raise FetchError(f"{aid}: a 'copy' install has exactly one file whose 'from' is {name!r}")
            copies.append(f"{dest}/{files[0]['to']}")
            continue
        if asset_rel(dest) is None:
            raise FetchError(f"{aid}: an 'extract' dest must be inside {ASSETS_DIR}/")
        owned.append(dest)
        wavs = inst.get("test_wavs")
        if wavs is not None:
            if not isinstance(wavs, dict):
                raise FetchError(f"{aid}: test_wavs must be an object")
            safe_rel(wavs.get("from"), f"{aid}: test_wavs.from")
            if not safe_rel(wavs.get("dest"), f"{aid}: test_wavs.dest").startswith(TEST_WAVS_DIR + "/"):
                raise FetchError(f"{aid}: test_wavs.dest must be inside {TEST_WAVS_DIR}/")
            owned.append(wavs["dest"])
        espeak = inst.get("espeak_data")
        if espeak is not None:
            _check_pair(espeak, ("file_count", "bytes", "sha256_tree"), f"{aid}.espeak_data")
            safe_rel(espeak.get("from"), f"{aid}: espeak_data.from")
            if asset_rel(safe_rel(espeak.get("dest"), f"{aid}: espeak_data.dest")) is None:
                raise FetchError(f"{aid}: espeak_data.dest must be inside {ASSETS_DIR}/")
            keep = espeak.get("keep_dicts")
            if not isinstance(keep, list) or not all(isinstance(k, str) and k.endswith("_dict") for k in keep):
                raise FetchError(f"{aid}: espeak_data.keep_dicts must list '*_dict' names")
            if not isinstance(espeak.get("license"), str) or not espeak["license"]:
                raise FetchError(f"{aid}: espeak_data.license is required")
            espeak_count += 1
            owned.append(espeak["dest"])
    if espeak_count > 1:
        raise FetchError(f"{LOCK_NAME}: only one artifact may provide espeak_data")
    # Extract targets are replaced as whole directories: they must not overlap.
    for i, a in enumerate(owned):
        for b in owned[i + 1:]:
            if is_under(a, b) or is_under(b, a):
                raise FetchError(f"{LOCK_NAME}: install directories overlap: {a} and {b}")
    for path in copies + [MANIFEST_REL]:
        if any(is_under(path, directory) for directory in owned):
            raise FetchError(f"{LOCK_NAME}: {path} lies inside an extracted directory")


def unrecorded(art: dict) -> list:
    """Lock values that are still null for this artifact."""
    names = [f"archive.{k}" for k in ("bytes", "sha256") if art["archive"][k] is None]
    for entry in art["install"]["files"]:
        names += [f"{entry['key']}.{k}" for k in ("bytes", "sha256") if entry[k] is None]
    espeak = art["install"].get("espeak_data")
    if espeak:
        names += [f"espeak_data.{k}" for k in ("file_count", "bytes", "sha256_tree") if espeak[k] is None]
    return names


def check_value(holder: dict, key: str, actual, what: str, ctx: Context) -> None:
    """Compare with the lock; with --record, fill a null value instead."""
    expected = holder[key]
    if expected is None:
        if not ctx.record:
            raise FetchError(f"{what}.{key} is not recorded in the lock (run with --record)")
        holder[key] = actual
        ctx.changes.append(f"{what}.{key}")
    elif expected != actual:
        raise FetchError(f"{what}.{key} mismatch: lock {expected}, actual {actual}")


def check_file(holder: dict, size: int, digest: str, what: str, ctx: Context) -> None:
    """SHA-256 first: on a mismatch nothing (not even the size) is recorded."""
    check_value(holder, "sha256", digest, what, ctx)
    check_value(holder, "bytes", size, what, ctx)


def select(artifacts: list, only: str | None) -> list:
    if only is None:
        return list(artifacts)
    wanted = [item.strip() for item in only.split(",") if item.strip()]
    if not wanted:
        raise FetchError("--only is empty")
    for item in wanted:
        if not any(item in (art["id"], art["role"]) for art in artifacts):
            raise FetchError(f"--only: unknown artifact id or role {item!r}")
    return [art for art in artifacts if art["id"] in wanted or art["role"] in wanted]


# ---------------------------------------------------------------- filesystem

def write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    with tmp.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    _retry(lambda: os.replace(tmp, path))


def _retry(action, attempts: int = 5):
    """Retry a filesystem action; on Windows a new file can stay locked briefly (e.g. by a scanner)."""
    for attempt in range(1, attempts + 1):
        try:
            return action()
        except PermissionError:
            if attempt == attempts:
                raise
            time.sleep(0.2 * attempt)


def _remove_tree(path: Path) -> None:
    if path.exists():
        _retry(lambda: shutil.rmtree(path))


def _replace_tree(stage: Path, final: Path) -> None:
    _remove_tree(final)
    final.parent.mkdir(parents=True, exist_ok=True)
    _retry(lambda: os.replace(stage, final))


def _staging(final: Path) -> Path:
    return final.parent / f".{final.name}.staging"


# ---------------------------------------------------------------- download

def _download_once(url: str, part: Path, expected_bytes, label: str) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    # urlopen follows the 302 from github.com to the release-asset host.
    with urllib.request.urlopen(request, timeout=TIMEOUT_S) as response, part.open("wb") as out:
        length = response.headers.get("Content-Length")
        total = int(length) if length and length.isdigit() else None
        if expected_bytes is not None and total is not None and total != expected_bytes:
            raise FetchError(f"{label}: server reports {total} B, lock says {expected_bytes} B")
        step = max(total // 5, CHUNK) if total else 32 * CHUNK
        done, mark = 0, step
        for block in iter(lambda: response.read(CHUNK), b""):
            out.write(block)
            done += len(block)
            if done >= mark:
                shown = f"{done / 1e6:.1f}/{total / 1e6:.1f} MB" if total else f"{done / 1e6:.1f} MB"
                print(f"  {shown}", flush=True)
                mark += step
    if total is not None and done != total:
        raise OSError(f"incomplete download: {done} of {total} B")


def download(url: str, target: Path, expected_bytes, label: str) -> None:
    """Download to ``<target>.part`` and rename it only when complete."""
    target.parent.mkdir(parents=True, exist_ok=True)
    part = target.with_name(target.name + ".part")
    for attempt in range(1, RETRIES + 1):
        try:
            _download_once(url, part, expected_bytes, label)
            _retry(lambda: os.replace(part, target))
            return
        except urllib.error.HTTPError as exc:
            if exc.code < 500 or attempt == RETRIES:
                raise FetchError(f"{label}: HTTP {exc.code} for {url}") from None
            error = f"HTTP {exc.code}"
        except (urllib.error.URLError, http.client.HTTPException, OSError) as exc:
            if attempt == RETRIES:
                raise FetchError(f"{label}: download failed: {exc}") from None
            error = str(exc)
        finally:
            part.unlink(missing_ok=True)
        print(f"  retry {attempt}/{RETRIES - 1} after error: {error}", flush=True)
        time.sleep(2 * attempt)


def verify_archive(art: dict, path: Path, ctx: Context) -> None:
    size, digest = path.stat().st_size, sha256_file(path)
    check_file(art["archive"], size, digest, f"{art['id']}.archive", ctx)
    print(f"  archive ok: {size} B, sha256 {digest[:16]}...")


def obtain_archive(art: dict, ctx: Context) -> Path:
    name = archive_name(art)
    if ctx.from_dir is not None:
        path = ctx.from_dir / name
        if not path.is_file():
            raise FetchError(f"{art['id']}: {name} not found in --from-dir {ctx.from_dir}")
        print(f"  using {path}")
        verify_archive(art, path, ctx)
        return path
    path = ctx.root / DOWNLOADS_REL / name
    if path.is_file():
        try:
            print(f"  using cached {DOWNLOADS_REL}/{name}")
            verify_archive(art, path, ctx)
            return path
        except FetchError as exc:
            print(f"  cached copy rejected ({exc}); downloading again")
            path.unlink()
    print(f"  downloading {art['url']}", flush=True)
    download(art["url"], path, art["archive"]["bytes"], art["id"])
    try:
        verify_archive(art, path, ctx)
    except FetchError:
        path.unlink(missing_ok=True)  # never keep a bad download in the cache
        raise
    return path


# ---------------------------------------------------------------- install

def _check_member(member: tarfile.TarInfo, stage: Path, aid: str) -> None:
    safe_rel(member.name, f"{aid}: archive member")
    if not member.isfile():
        raise FetchError(f"{aid}: archive member {member.name!r} is not a regular file")
    data_filter = getattr(tarfile, "data_filter", None)  # Python 3.12+, 3.9.17+, 3.10.12+, 3.11.4+
    if data_filter is not None:
        try:
            data_filter(member, str(stage))
        except tarfile.TarError as exc:
            raise FetchError(f"{aid}: archive member {member.name!r} rejected: {exc}") from None


def _write_member(tar: tarfile.TarFile, member: tarfile.TarInfo, target: Path) -> tuple:
    source = tar.extractfile(member)
    if source is None:
        raise FetchError(f"cannot read archive member {member.name!r}")
    target.parent.mkdir(parents=True, exist_ok=True)
    digest, size = hashlib.sha256(), 0
    with target.open("wb") as out:
        for block in iter(lambda: source.read(CHUNK), b""):
            out.write(block)
            digest.update(block)
            size += len(block)
    return size, digest.hexdigest()


def install_copy(art: dict, archive: Path, ctx: Context) -> None:
    inst, entry = art["install"], art["install"]["files"][0]
    target = ctx.root / inst["dest"] / entry["to"]
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_name(target.name + ".tmp")
    try:
        shutil.copyfile(archive, tmp)
        check_file(entry, tmp.stat().st_size, sha256_file(tmp), f"{art['id']}.{entry['key']}", ctx)
        _retry(lambda: os.replace(tmp, target))
    finally:
        tmp.unlink(missing_ok=True)


def install_extract(art: dict, archive: Path, ctx: Context) -> None:
    """Stream the archive once; stage every output directory, then swap it in."""
    aid, inst = art["id"], art["install"]
    wanted = {entry["from"]: entry for entry in inst["files"]}
    main_final = ctx.root / inst["dest"]
    sections = []  # (member prefix, staging dir, final dir, kept dicts or None)
    wavs, espeak = inst.get("test_wavs"), inst.get("espeak_data")
    if espeak:
        final = ctx.root / espeak["dest"]
        sections.append((espeak["from"] + "/", _staging(final), final, set(espeak["keep_dicts"])))
    if wavs:
        final = ctx.root / wavs["dest"]
        sections.append((wavs["from"] + "/", _staging(final), final, None))
    stages = [_staging(main_final)] + [section[1] for section in sections]
    found = {}
    try:
        for stage in stages:
            _remove_tree(stage)
            stage.mkdir(parents=True)
        with tarfile.open(archive, mode="r|*") as tar:
            for member in tar:
                if member.name in wanted:
                    _check_member(member, stages[0], aid)
                    target = stages[0] / wanted[member.name]["to"]
                    found[member.name] = _write_member(tar, member, target)
                    continue
                for prefix, stage, _final, keep in sections:
                    if not member.name.startswith(prefix) or member.isdir():
                        continue
                    _check_member(member, stage, aid)
                    rel = member.name[len(prefix):]
                    if keep is not None and "/" not in rel and rel.endswith("_dict") and rel not in keep:
                        break  # pruned espeak-ng dictionary
                    _write_member(tar, member, stage / rel)
                    break
        missing = [name for name in wanted if name not in found]
        if missing:
            raise FetchError(f"{aid}: missing in archive: {', '.join(missing)}")
        for name, (size, digest) in found.items():
            check_file(wanted[name], size, digest, f"{aid}.{wanted[name]['key']}", ctx)
        for prefix, stage, _final, _keep in sections:
            if not any(stage.iterdir()):
                raise FetchError(f"{aid}: no archive member under {prefix}")
        if espeak:
            absent = [name for name in espeak["keep_dicts"] if not (sections[0][1] / name).is_file()]
            if absent:
                raise FetchError(f"{aid}: espeak-ng-data lacks {', '.join(absent)}")
            stats = tree_stats(sections[0][1])
            for key in ("file_count", "bytes", "sha256_tree"):
                check_value(espeak, key, stats[key], f"{aid}.espeak_data", ctx)
        _replace_tree(stages[0], main_final)
        for _prefix, stage, final, _keep in sections:
            _replace_tree(stage, final)
    except (tarfile.TarError, EOFError) as exc:
        raise FetchError(f"{aid}: cannot read {archive.name}: {exc}") from None
    finally:
        for stage in stages:
            if stage.exists():
                shutil.rmtree(stage, ignore_errors=True)


def installed_problems(art: dict, root: Path) -> list:
    """Why the installed files differ from the lock; an empty list means installed."""
    inst, problems = art["install"], []
    for entry in inst["files"]:
        rel = f"{inst['dest']}/{entry['to']}"
        path = root / rel
        if entry["bytes"] is None or entry["sha256"] is None:
            problems.append(f"{rel}: not recorded in the lock")
        elif not path.is_file():
            problems.append(f"{rel}: missing")
        elif path.stat().st_size != entry["bytes"]:
            problems.append(f"{rel}: {path.stat().st_size} B, lock {entry['bytes']} B")
        elif sha256_file(path) != entry["sha256"]:
            problems.append(f"{rel}: sha256 differs from the lock")
    espeak = inst.get("espeak_data")
    if espeak:
        directory = root / espeak["dest"]
        if None in (espeak["file_count"], espeak["bytes"], espeak["sha256_tree"]):
            problems.append(f"{espeak['dest']}: not recorded in the lock")
        elif not directory.is_dir():
            problems.append(f"{espeak['dest']}: missing")
        else:
            dicts = sorted(p.name for p in directory.iterdir() if p.is_file() and p.name.endswith("_dict"))
            if dicts != sorted(espeak["keep_dicts"]):
                problems.append(f"{espeak['dest']}: dictionaries {dicts}, lock keeps {sorted(espeak['keep_dicts'])}")
            stats = tree_stats(directory)
            problems += [f"{espeak['dest']}: {key} {stats[key]}, lock {espeak[key]}"
                         for key in ("file_count", "bytes", "sha256_tree") if stats[key] != espeak[key]]
    return problems


def _test_wavs_present(art: dict, root: Path) -> bool:
    wavs = art["install"].get("test_wavs")
    if not wavs:
        return True
    directory = root / wavs["dest"]
    return directory.is_dir() and any(p.is_file() for p in directory.rglob("*"))


def process(art: dict, ctx: Context) -> None:
    label = f"[{art['role']}] {art['id']}"
    missing = unrecorded(art)
    if missing and not ctx.record:
        raise FetchError(f"{art['id']}: not recorded in the lock ({', '.join(missing)}); run with --record")
    if not missing and not installed_problems(art, ctx.root) and _test_wavs_present(art, ctx.root):
        print(f"{label}: already installed")
        return
    print(f"{label}:", flush=True)
    archive = obtain_archive(art, ctx)
    if art["install"]["kind"] == "copy":
        install_copy(art, archive, ctx)
    else:
        install_extract(art, archive, ctx)
    problems = installed_problems(art, ctx.root)
    if problems:
        raise FetchError(f"{art['id']}: {problems[0]}")
    print(f"  installed into {art['install']['dest']}")


# ---------------------------------------------------------------- manifest

def build_manifest(lock: dict, lock_digest: str) -> dict:
    """Manifest content without ``generated_at_utc`` (set to None)."""
    roles, files, espeak_data = {}, [], None
    for art in lock["artifacts"]:
        inst = art["install"]
        espeak = inst.get("espeak_data")
        if espeak:
            espeak_data = {"dir": asset_rel(espeak["dest"]), "file_count": espeak["file_count"],
                           "bytes": espeak["bytes"], "sha256_tree": espeak["sha256_tree"],
                           "license": espeak["license"]}
        dest = asset_rel(inst["dest"])
        if dest is None:
            continue  # not an app asset (the AAR)
        role = {"artifact_id": art["id"]}
        if "version" in art:
            role["version"] = art["version"]
        role["license"] = art["license"]
        role["sherpa"] = art.get("sherpa", {})
        role["files"] = {entry["key"]: f"{dest}/{entry['to']}" for entry in inst["files"]}
        roles[art["role"]] = role
        files += [{"path": f"{dest}/{entry['to']}", "bytes": entry["bytes"], "sha256": entry["sha256"]}
                  for entry in inst["files"]]
    manifest = {"schema_version": SCHEMA_VERSION, "generated_at_utc": None, "lock_sha256": lock_digest,
                "roles": roles, "files": files}
    if espeak_data is not None:
        manifest["espeak_data"] = espeak_data
    return manifest


def _read_manifest(root: Path):
    try:
        return json.loads((root / MANIFEST_REL).read_bytes().decode("utf-8"))
    except (OSError, UnicodeDecodeError, ValueError):
        return None


def write_manifest(root: Path, lock: dict, lock_digest: str) -> str:
    """Rewrite only when the content changes, so an unchanged run keeps the bytes (and the APK) stable."""
    manifest = build_manifest(lock, lock_digest)
    old = _read_manifest(root)
    if isinstance(old, dict) and UTC_RE.match(str(old.get("generated_at_utc"))) \
            and {**old, "generated_at_utc": None} == manifest:
        return "unchanged"
    manifest["generated_at_utc"] = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
    write_json(root / MANIFEST_REL, manifest)
    return "written"


def manifest_problems(root: Path, lock: dict, lock_digest: str) -> list:
    if not (root / MANIFEST_REL).is_file():
        return [f"{MANIFEST_REL}: missing"]
    data = _read_manifest(root)
    if not isinstance(data, dict):
        return [f"{MANIFEST_REL}: not a UTF-8 JSON object"]
    problems = []
    if not isinstance(data.get("generated_at_utc"), str) or not UTC_RE.match(data["generated_at_utc"]):
        problems.append(f"{MANIFEST_REL}: generated_at_utc is not ISO-8601 UTC (+00:00)")
    if data.get("lock_sha256") != lock_digest:
        problems.append(f"{MANIFEST_REL}: lock_sha256 does not match {LOCK_NAME}")
    elif {**data, "generated_at_utc": None} != build_manifest(lock, lock_digest):
        problems.append(f"{MANIFEST_REL}: content differs from {LOCK_NAME}")
    return problems


def _asset_summary(lock: dict) -> str:
    count = total = 0
    for art in lock["artifacts"]:
        inst = art["install"]
        if asset_rel(inst["dest"]) is not None:
            count += len(inst["files"])
            total += sum(entry["bytes"] or 0 for entry in inst["files"])
        espeak = inst.get("espeak_data")
        if espeak:
            count += espeak["file_count"] or 0
            total += espeak["bytes"] or 0
    return f"assets: {count} files, {total / 1e6:.1f} MB"


# ---------------------------------------------------------------- main

def verify(root: Path, lock: dict, raw: bytes, selected: list, full: bool) -> None:
    problems = []
    for art in selected:
        found = installed_problems(art, root)
        print(f"[{art['role']}] {art['id']}: {'ok' if not found else 'FAILED'}")
        problems += [f"{art['id']}: {problem}" for problem in found]
    if full:
        found = manifest_problems(root, lock, lock_sha256(raw))
        print(f"manifest: {'ok' if not found else 'FAILED'}")
        problems += found
    for problem in problems:
        print(f"  problem: {problem}")
    if problems:
        raise FetchError(f"{len(problems)} problem(s); first: {problems[0]}")
    print(_asset_summary(lock))


def run(args) -> None:
    root = args.root.resolve()
    lock_path = root / LOCK_NAME
    if not lock_path.is_file():
        raise FetchError(f"{LOCK_NAME} not found in {root}")
    raw = lock_path.read_bytes()
    try:
        lock = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise FetchError(f"{LOCK_NAME}: invalid JSON: {exc}") from None
    validate_lock(lock)
    selected = select(lock["artifacts"], args.only)
    print(f"lock: {LOCK_NAME}, {len(selected)} of {len(lock['artifacts'])} artifacts, sha256 {lock_sha256(raw)[:16]}...")
    if args.verify:
        verify(root, lock, raw, selected, full=args.only is None)
        return
    ctx = Context(root=root, from_dir=args.from_dir.resolve() if args.from_dir else None, record=args.record)
    for art in selected:
        process(art, ctx)
    if ctx.changes:
        write_json(lock_path, lock)  # json keeps the key order of the loaded file
        raw = lock_path.read_bytes()
        print(f"recorded: {len(ctx.changes)} values written to {LOCK_NAME}")
    elif args.record:
        print("recorded: nothing new")
    pending = [art["id"] for art in lock["artifacts"] if art not in selected
               and asset_rel(art["install"]["dest"]) is not None and installed_problems(art, root)]
    if pending:
        print(f"manifest: not written ({', '.join(pending)} not installed)")
    else:
        print(f"manifest: {MANIFEST_REL} ({write_manifest(root, lock, lock_sha256(raw))})")
    print(_asset_summary(lock))


def parse_args(argv):
    parser = argparse.ArgumentParser(description="Fetch, verify and install the artifacts pinned in artifacts.lock.json.")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--record", action="store_true",
                      help="Fill null bytes/sha256 values in the lock from the fetched files (first pinning or an intended update).")
    mode.add_argument("--verify", action="store_true",
                      help="Offline check of the installed files and manifest.json against the lock; no network.")
    parser.add_argument("--from-dir", type=Path,
                        help="Take archives from this folder (file name = last URL segment) instead of the network.")
    parser.add_argument("--only", help="Comma-separated artifact ids or roles to process (default: all).")
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT,
                        help="AppProduct directory that holds artifacts.lock.json (default: parent of tools/).")
    args = parser.parse_args(argv)
    if args.verify and args.from_dir is not None:
        parser.error("--verify reads no archives; drop --from-dir")
    return args


def main(argv: list | None = None) -> int:
    reconfigure = getattr(sys.stdout, "reconfigure", None)
    if reconfigure is not None:
        reconfigure(errors="backslashreplace")
    args = parse_args(argv)
    try:
        run(args)
    except FetchError as exc:
        reason = str(exc)
    except Exception as exc:  # keep the marker contract for unexpected errors too
        traceback.print_exc()
        reason = f"{type(exc).__name__}: {exc}"
    else:
        print(f"{MARKER}=PASS")
        return 0
    print(f"{MARKER}=FAIL {' '.join(reason.split())}".encode("ascii", "backslashreplace").decode("ascii"))
    return 1


if __name__ == "__main__":
    sys.exit(main())
