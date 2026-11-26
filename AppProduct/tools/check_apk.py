"""Check a built APK against the artifacts it must carry. Reads the APK, changes nothing.

``app/src/main/assets/mt`` (tools/mt/package_mt.py) and ``assets/models``
(tools/fetch_artifacts.py) are not in git, so each machine packages what it has
installed. A build can pass every JVM test and still ship another model, or no MT
package at all (the app then only shows the source text). This tool opens the APK
as a ZIP and checks:

1. MT (``assets/mt/``): the manifest is package ``vtt-main-core-phase03-q4r``,
   schema 2.x, with the quantization of the q4r evidence and the APK's ABI; every
   file of ``files[]`` is in the APK with its size and SHA-256; each direction has
   its model, ``source.spm``, ``vocab.txt`` (one line per id) and golden file, and
   the package has its license notes; nothing else is there. Each ``model.onnx`` is
   the graph scored in evidence/phase04/mt_export_q4r/run.json, the quality block
   points to that run's summaries, and the checkpoints and decision hash match
   Core/evidence/phase03/main_core_decision.json.
2. Speech models (``assets/models/``): the manifest was written from this repo's
   artifacts.lock.json; its files and espeak-ng-data match; it has exactly the
   roles the app loads (speech/ModelManifest.kt).
3. No other model file in the APK (an old MT package, a leftover model).
4. Native code: 64-bit ABIs only; the expected ABI has the ONNX Runtime and
   sherpa-onnx libraries, built for that ABI and aligned for 16 KB pages.
5. AndroidManifest.xml (binary XML): only the permissions the app needs (no
   INTERNET), not debuggable.

It answers "is this APK the right artifact"; the JVM tests (is the Kotlin right),
DeviceCheck (how a phone runs it) and the cohort evaluation (how far q4r + rules
are from Student v1) answer other questions.

Standard library only (Python 3.9+). Run from ``AppProduct`` with the repo .venv::

    ..\\.venv\\Scripts\\python.exe tools\\check_apk.py             # app-arm64-v8a-release.apk
    ..\\.venv\\Scripts\\python.exe tools\\check_apk.py app\\build\\outputs\\apk\\release\\app-x86_64-release.apk
    ..\\.venv\\Scripts\\python.exe tools\\check_apk.py --record    # on PASS, also write evidence/builds/<apk>_<sha12>.json

Prints ``APP_APK_CHECK=PASS``, or ``APP_APK_CHECK=FAIL <reason>`` and exits with code 1.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import struct
import sys
import traceback
import zipfile
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

APP = Path(__file__).resolve().parents[1]
REPO = APP.parent
MARKER = "APP_APK_CHECK"
DEFAULT_APK = "app/build/outputs/apk/release/app-arm64-v8a-release.apk"
BUILD_HINT = 'gradlew.bat :app:assembleRelease "-Pvtt.abis=arm64-v8a,x86_64"'
BUILDS_DIR = "evidence/builds"
LOCK_NAME = "artifacts.lock.json"
Q4R_EVIDENCE = "evidence/phase04/mt_export_q4r/run.json"
DECISION_REL = "Core/evidence/phase03/main_core_decision.json"
REPORT_SCHEMA = "1.0"
CHUNK = 1 << 20
SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")

MT_DIR = "assets/mt/"
MT_MANIFEST = MT_DIR + "manifest.json"
PACKAGE_ID = "vtt-main-core-phase03-q4r"
MT_SCHEMA_MAJOR = 2  # MtPackageValidator.SUPPORTED_MAJOR
DIRECTIONS = ("en_to_vi", "vi_to_en")
SPLITS = ("selection_validation", "general_validation")
MODELS_DIR = "assets/models/"
MODELS_MANIFEST = MODELS_DIR + "manifest.json"
APP_ROLES = ("vad", "stt_vi", "stt_en", "tts_vi", "tts_en")  # ModelManifest.kt: the roles the app loads
# Model weights and tokenizers; one outside the two manifests is a leftover (.bin is left out: DebugProbesKt.bin).
MODEL_SUFFIXES = (".onnx", ".ort", ".spm", ".model", ".tflite", ".pt", ".pth", ".ckpt", ".safetensors", ".gguf")
# Dịch ảnh: ML Kit's bundled Latin text recognizer (com.google.mlkit:text-recognition, DR-10). The AAR puts its
# models here; the engine config of the Latin pipeline must be present for the recognizer to start offline.
OCR_DIR = "assets/mlkit-google-ocr-models/"
OCR_LATIN_ENGINE = OCR_DIR + "taser_tflite_gocrlatin_mbv2_scriptid_aksara_layout_gcn_mobile_engine.binarypb"

ABI_MACHINE = {"arm64-v8a": 183, "x86_64": 62}  # ELF e_machine EM_AARCH64, EM_X86_64; no 32-bit ABI
# MT runtime + JNI, speech, and the OCR pipeline of Dịch ảnh (ML Kit text recognition, DR-10).
REQUIRED_LIBS = ("libonnxruntime.so", "libonnxruntime4j_jni.so", "libsherpa-onnx-jni.so", "libmlkit_google_ocr_pipeline.so")
PAGE_16K = 16384
ABI_IN_NAME = re.compile(r"-(arm64-v8a|x86_64)-")

# AndroidX core adds <package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION (signature level, used inside the app).
ALLOWED_PERMISSIONS = {"android.permission.RECORD_AUDIO"}
NETWORK_PERMISSIONS = {"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
                       "android.permission.ACCESS_WIFI_STATE", "android.permission.CHANGE_NETWORK_STATE",
                       "android.permission.CHANGE_WIFI_STATE"}
PERMISSION_TAGS = ("uses-permission", "uses-permission-sdk-23")

# Binary XML (frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h)
RES_STRING_POOL_TYPE = 0x0001
RES_XML_TYPE = 0x0003
RES_XML_START_ELEMENT_TYPE = 0x0102
RES_XML_RESOURCE_MAP_TYPE = 0x0180
UTF8_FLAG = 0x100
NO_INDEX = 0xFFFFFFFF
TYPE_STRING = 0x03
TYPE_INT_BOOLEAN = 0x12
# Attribute names by framework resource id, for manifests whose name strings were stripped.
ATTR_BY_RES_ID = {0x01010003: "name", 0x0101000F: "debuggable", 0x0101020C: "minSdkVersion",
                  0x0101021B: "versionCode", 0x0101021C: "versionName", 0x01010270: "targetSdkVersion"}


class CheckError(Exception):
    """Expected failure that stops the check, reported as ``APP_APK_CHECK=FAIL <reason>``."""


# ---------------------------------------------------------------- hashing and helpers

def sha256_lf(data: bytes) -> str:
    """SHA-256 of text as LF bytes, so a CRLF checkout gives the same value (like fetch_artifacts.lock_sha256)."""
    return hashlib.sha256(data.replace(b"\r\n", b"\n")).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(CHUNK), b""):
            digest.update(block)
    return digest.hexdigest()


def sha256_entry(apk: zipfile.ZipFile, name: str) -> str:
    """Digest of the inflated entry; zipfile also checks its CRC-32."""
    digest = hashlib.sha256()
    with apk.open(name) as handle:
        for block in iter(lambda: handle.read(CHUNK), b""):
            digest.update(block)
    return digest.hexdigest()


def sha256_tree_entries(apk: zipfile.ZipFile, prefix: str, names: list) -> str:
    """fetch_artifacts.sha256_tree over the APK entries under ``prefix`` instead of a folder."""
    digest = hashlib.sha256()
    for name in sorted(names, key=lambda value: value[len(prefix):].split("/")):
        digest.update(name[len(prefix):].encode("utf-8"))
        digest.update(b"\0")
        with apk.open(name) as handle:
            for block in iter(lambda: handle.read(CHUNK), b""):
                digest.update(block)
        digest.update(b"\0")
    return digest.hexdigest()


def dig(data, *keys):
    """``data[k1][k2]...``, or None when a level is missing or not an object."""
    for key in keys:
        if not isinstance(data, dict) or key not in data:
            return None
        data = data[key]
    return data


def short(digest) -> str:
    return f"{digest[:12]}..." if isinstance(digest, str) else repr(digest)


def parse_json(raw: bytes, what: str, problems: list):
    try:
        data = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        problems.append(f"{what}: not UTF-8 JSON ({exc})")
        return None
    if not isinstance(data, dict):
        problems.append(f"{what}: not a JSON object")
        return None
    return data


def read_repo_json(path: Path, what: str, problems: list):
    if not path.is_file():
        problems.append(f"{what}: missing")
        return None
    return parse_json(path.read_bytes(), what, problems)


def file_entries(files, what: str, problems: list) -> dict:
    """``files[]`` of a manifest as {path: entry}; malformed or repeated entries become problems."""
    listed = {}
    if not isinstance(files, list) or not files:
        problems.append(f"{what}: files[] is missing or empty")
        return listed
    for index, entry in enumerate(files):
        path, size, digest = dig(entry, "path"), dig(entry, "bytes"), dig(entry, "sha256")
        if not (isinstance(path, str) and path and isinstance(size, int) and isinstance(digest, str)
                and SHA256_RE.match(digest)):
            problems.append(f"{what}: files[{index}] is not {{path, bytes, sha256}}")
        elif path in listed:
            problems.append(f"{what}: files[] lists {path} twice")
        else:
            listed[path] = entry
    return listed


def entry_problems(apk: zipfile.ZipFile, entries: dict, name: str, size: int, digest: str, what: str) -> list:
    info = entries.get(name)
    if info is None:
        return [f"{name}: missing (listed in {what})"]
    if info.file_size != size:
        return [f"{name}: {info.file_size} bytes, {what} says {size}"]
    actual = sha256_entry(apk, name)
    if actual != digest:
        return [f"{name}: sha256 {short(actual)}, {what} says {short(digest)}"]
    return []


def count_lines(data: bytes) -> int:
    return data.count(b"\n") + (0 if not data or data.endswith(b"\n") else 1)


# ---------------------------------------------------------------- binary XML

def _string_pool(data: bytes, pos: int) -> list:
    _kind, header, _size = struct.unpack_from("<HHI", data, pos)
    count, _styles, flags, strings_start, _styles_start = struct.unpack_from("<5I", data, pos + 8)
    offsets = struct.unpack_from(f"<{count}I", data, pos + header)
    base, strings = pos + strings_start, []
    for offset in offsets:
        p = base + offset
        if flags & UTF8_FLAG:
            p += 2 if data[p] & 0x80 else 1  # length in UTF-16 units, not needed
            n = data[p]
            if n & 0x80:
                n, p = (n & 0x7F) << 8 | data[p + 1], p + 2
            else:
                p += 1
            strings.append(data[p:p + n].decode("utf-8"))
        else:
            n, p = struct.unpack_from("<H", data, p)[0], p + 2
            if n & 0x8000:
                n, p = (n & 0x7FFF) << 16 | struct.unpack_from("<H", data, p)[0], p + 2
            strings.append(data[p:p + 2 * n].decode("utf-16-le"))
    return strings


def parse_axml(data: bytes) -> list:
    """Elements of a compiled XML file (the APK's AndroidManifest.xml) as (tag, {attribute: value}).

    Elements come in document order. Attribute names are local names (``android:name`` -> ``name``);
    values are str for strings, bool for booleans and int for other typed values. Raises ValueError
    (or struct.error) when the data is not binary XML.
    """
    kind, header, size = struct.unpack_from("<HHI", data, 0)
    if kind != RES_XML_TYPE or header < 8 or size > len(data):
        raise ValueError("not a binary XML file")
    strings, res_ids, elements = [], [], []
    pos = header
    while pos + 8 <= size:
        kind, header, chunk = struct.unpack_from("<HHI", data, pos)
        if chunk < max(header, 8) or pos + chunk > size:
            raise ValueError(f"bad chunk at offset {pos}")
        if kind == RES_STRING_POOL_TYPE:
            strings = _string_pool(data, pos)
        elif kind == RES_XML_RESOURCE_MAP_TYPE:
            res_ids = list(struct.unpack_from(f"<{(chunk - header) // 4}I", data, pos + header))
        elif kind == RES_XML_START_ELEMENT_TYPE:
            ext = pos + header
            _ns, name, attr_start, attr_size, attr_count = struct.unpack_from("<IIHHH", data, ext)
            attrs = {}
            for index in range(attr_count):
                _attr_ns, attr_name, raw, _value_size, _res0, value_type, value = struct.unpack_from(
                    "<IIIHBBI", data, ext + attr_start + index * attr_size)
                key = strings[attr_name] if attr_name < len(strings) else ""
                if not key and attr_name < len(res_ids):
                    key = ATTR_BY_RES_ID.get(res_ids[attr_name], "")
                if value_type == TYPE_STRING:
                    attrs[key] = strings[value]
                elif raw != NO_INDEX:
                    attrs[key] = strings[raw]
                elif value_type == TYPE_INT_BOOLEAN:
                    attrs[key] = value != 0
                else:
                    attrs[key] = value
            elements.append((strings[name], attrs))
        pos += chunk
    return elements


# ---------------------------------------------------------------- sections

def check_mt(apk, entries, abis, app_root: Path, repo_root: Path, package_id: str, evidence_rel: str) -> dict:
    problems, report = [], {}
    if MT_MANIFEST not in entries:
        problems.append(f"{MT_MANIFEST}: missing; the app would only show the source text "
                        f"(run tools/mt/package_mt.py, then rebuild)")
        return {"summary": "no MT package", "problems": problems, "report": report}
    raw = apk.read(MT_MANIFEST)
    report["manifest_sha256"] = hashlib.sha256(raw).hexdigest()
    manifest = parse_json(raw, MT_MANIFEST, problems)
    if manifest is None:
        return {"summary": "unreadable manifest", "problems": problems, "report": report}
    report.update(package_id=manifest.get("package_id"), package_version=manifest.get("package_version"),
                  evidence=evidence_rel)

    # Which package: id, schema, quantization (compared with the q4r evidence below), ABIs.
    if manifest.get("package_id") != package_id:
        problems.append(f"{MT_MANIFEST}: package_id {manifest.get('package_id')!r}, expected {package_id!r} "
                        f"(an old or other MT package)")
    major = re.match(r"(\d+)\.\d+\Z", str(manifest.get("schema_version")))
    if not major or int(major.group(1)) != MT_SCHEMA_MAJOR:
        problems.append(f"{MT_MANIFEST}: schema_version {manifest.get('schema_version')!r}, "
                        f"the app reads {MT_SCHEMA_MAJOR}.x")
    declared_abis = dig(manifest, "runtime", "abis")
    for abi in abis:
        if not isinstance(declared_abis, list) or abi not in declared_abis:
            problems.append(f"{MT_MANIFEST}: runtime.abis {declared_abis} lacks {abi}")

    # Every listed file, byte for byte; nothing else in assets/mt/.
    listed = file_entries(manifest.get("files"), MT_MANIFEST, problems)
    for path, entry in listed.items():
        problems += entry_problems(apk, entries, MT_DIR + path, entry["bytes"], entry["sha256"], MT_MANIFEST)
    for name in sorted(entries):
        if name.startswith(MT_DIR) and name != MT_MANIFEST and name[len(MT_DIR):] not in listed:
            problems.append(f"{name}: not in {MT_MANIFEST} files[] (old package or leftover file)")
    report["files"] = [{"path": p, "bytes": e["bytes"], "sha256": e["sha256"]} for p, e in sorted(listed.items())]

    # Required files: model, tokenizer, vocab and golden per direction, plus the license notes.
    directions = manifest.get("directions") if isinstance(manifest.get("directions"), dict) else {}
    if sorted(directions) != sorted(DIRECTIONS):
        problems.append(f"{MT_MANIFEST}: directions {sorted(directions)}, expected {list(DIRECTIONS)}")
    notes = manifest.get("license_notes")
    if not isinstance(notes, str) or notes not in listed:
        problems.append(f"{MT_MANIFEST}: license_notes {notes!r} is not a file of files[]")
    for direction in DIRECTIONS:
        spec = directions.get(direction)
        required = {"model": dig(spec, "model"), "tokenizer.source_spm": dig(spec, "tokenizer", "source_spm"),
                    "tokenizer.vocab": dig(spec, "tokenizer", "vocab"), "golden_file": dig(spec, "golden_file")}
        for what, path in required.items():
            if not isinstance(path, str) or path not in listed:
                problems.append(f"{MT_MANIFEST}: {direction}.{what} {path!r} is not a file of files[]")
        vocab, size = required["tokenizer.vocab"], dig(spec, "tokenizer", "vocab_size")
        if vocab in listed and MT_DIR + vocab in entries:
            lines = count_lines(apk.read(MT_DIR + vocab))
            if lines != size:
                problems.append(f"{MT_DIR}{vocab}: {lines} lines, tokenizer.vocab_size is {size!r}")
        golden = required["golden_file"]
        if golden in listed and MT_DIR + golden in entries:
            problems += golden_problems(apk.read(MT_DIR + golden), MT_DIR + golden)

    problems += mt_evidence_problems(manifest, directions, listed, app_root, evidence_rel, package_id)
    problems += mt_core_problems(manifest, directions, repo_root)
    total = sum(entry["bytes"] for entry in listed.values())
    summary = f"{manifest.get('package_id')} {manifest.get('package_version')}, {len(listed)} files, {total / 1e6:.1f} MB"
    return {"summary": summary, "problems": problems, "report": report}


def golden_problems(data: bytes, name: str) -> list:
    rows = 0
    for number, line in enumerate(data.decode("utf-8", "replace").splitlines(), 1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except ValueError:
            return [f"{name}: line {number} is not JSON"]
        if not isinstance(row, dict) or not {"source", "input_ids", "output_ids"} <= set(row):
            return [f"{name}: line {number} lacks source, input_ids or output_ids"]
        rows += 1
    return [] if rows else [f"{name}: no golden rows"]


def mt_evidence_problems(manifest: dict, directions: dict, listed: dict, app_root: Path, evidence_rel: str,
                         package_id: str) -> list:
    """The APK graphs are the ones scored in the export evidence, and the quality block comes from it."""
    problems = []
    evidence = read_repo_json(app_root / evidence_rel, evidence_rel, problems)
    if evidence is None:
        return [f"{problems[0]}; cannot link the APK graphs to the export evidence"]
    if evidence.get("package_id") != package_id:
        problems.append(f"{evidence_rel}: scored package {evidence.get('package_id')!r}, not {package_id!r} "
                        f"(pass the matching --evidence)")
    if manifest.get("quantization") != evidence.get("quantization"):
        problems.append(f"{MT_MANIFEST}: quantization {quantization_text(manifest.get('quantization'))} "
                        f"differs from {evidence_rel} ({quantization_text(evidence.get('quantization'))})")
    folder = (app_root / evidence_rel).parent
    for direction in DIRECTIONS:
        spec = directions.get(direction)
        graph = dig(evidence, "results", f"{direction}/graph")
        model = listed.get(dig(spec, "model"))
        if not isinstance(graph, dict):
            problems.append(f"{evidence_rel}: no results['{direction}/graph']")
        elif model is not None and (model["sha256"] != graph.get("sha256") or model["bytes"] != graph.get("bytes")):
            problems.append(f"{MT_DIR}{model['path']}: sha256 {short(model['sha256'])}, "
                            f"not the graph scored in {evidence_rel} ({short(graph.get('sha256'))})")
        if isinstance(graph, dict) and dig(spec, "source_checkpoint", "sha256_tree") != dig(graph, "source_checkpoint", "sha256_tree"):
            problems.append(f"{MT_MANIFEST}: {direction}.source_checkpoint differs from {evidence_rel}")
        for split in SPLITS:
            want = dig(spec, "quality", "exported", split, "summary_sha256")
            summary = folder / f"{direction}_{split}.summary.json"
            label = f"{Path(evidence_rel).parent.as_posix()}/{summary.name}"
            if not isinstance(want, str):
                problems.append(f"{MT_MANIFEST}: {direction}.quality.exported.{split}.summary_sha256 missing")
            elif not summary.is_file():
                problems.append(f"{label}: missing")
            elif sha256_lf(summary.read_bytes()) != want:
                problems.append(f"{MT_MANIFEST}: {direction}.quality.exported.{split} is not {label}")
    return problems


def quantization_text(quantization) -> str:
    if not isinstance(quantization, dict):
        return repr(quantization)
    head = "4+4-bit residual" if quantization.get("lm_head") else "plain"
    return f"{quantization.get('bits')}-bit, block {quantization.get('block_size')}, LM head {head}"


def mt_core_problems(manifest: dict, directions: dict, repo_root: Path) -> list:
    """The package still matches the Main-Core decision it was built from."""
    problems = []
    refs = manifest.get("core_refs")
    if not isinstance(refs, list) or not refs:
        problems.append(f"{MT_MANIFEST}: core_refs missing")
        refs = []
    for ref in refs:
        path, want = dig(ref, "path"), dig(ref, "sha256")
        target = repo_root / path if isinstance(path, str) else None
        if target is None or not target.is_file():
            problems.append(f"{MT_MANIFEST}: core_refs file {path!r} not found in the repo")
        elif sha256_lf(target.read_bytes()) != want:
            problems.append(f"{path}: sha256 differs from core_refs in the APK ({short(want)}); "
                            f"the package was built from another Core decision")
    decision = read_repo_json(repo_root / DECISION_REL, DECISION_REL, problems)
    if decision is None:
        return problems
    for direction in DIRECTIONS:
        frozen = dig(decision, "decisions", direction)
        packaged = dig(directions.get(direction), "source_checkpoint")
        if (dig(frozen, "run_id"), dig(frozen, "checkpoint", "sha256_tree")) != (dig(packaged, "run_id"), dig(packaged, "sha256_tree")):
            problems.append(f"{MT_MANIFEST}: {direction} checkpoint {dig(packaged, 'run_id')!r} is not the one frozen "
                            f"in {DECISION_REL} ({dig(frozen, 'run_id')!r})")
    return problems


def check_models(apk, entries, app_root: Path) -> dict:
    problems, report = [], {}
    if MODELS_MANIFEST not in entries:
        problems.append(f"{MODELS_MANIFEST}: missing; the app cannot load VAD, STT or TTS "
                        f"(run tools/fetch_artifacts.py, then rebuild)")
        return {"summary": "no speech models", "problems": problems, "report": report}
    raw = apk.read(MODELS_MANIFEST)
    report["manifest_sha256"] = hashlib.sha256(raw).hexdigest()
    manifest = parse_json(raw, MODELS_MANIFEST, problems)
    if manifest is None:
        return {"summary": "unreadable manifest", "problems": problems, "report": report}
    lock = app_root / LOCK_NAME
    report["lock_sha256"] = manifest.get("lock_sha256")
    if not lock.is_file():
        problems.append(f"{LOCK_NAME}: missing; cannot tell which lock the models come from")
    elif manifest.get("lock_sha256") != sha256_lf(lock.read_bytes()):
        problems.append(f"{MODELS_MANIFEST}: lock_sha256 {short(manifest.get('lock_sha256'))} is not {LOCK_NAME} "
                        f"of this repo ({short(sha256_lf(lock.read_bytes()))}); models installed from another lock")

    listed = file_entries(manifest.get("files"), MODELS_MANIFEST, problems)
    for path, entry in listed.items():
        problems += entry_problems(apk, entries, "assets/" + path, entry["bytes"], entry["sha256"], MODELS_MANIFEST)

    roles = manifest.get("roles") if isinstance(manifest.get("roles"), dict) else {}
    report["roles"] = sorted(roles)
    for role in APP_ROLES:
        if role not in roles:
            problems.append(f"{MODELS_MANIFEST}: role {role!r} missing (the app loads it)")
    for role, spec in sorted(roles.items()):
        paths = dig(spec, "files")
        paths = sorted(paths.values()) if isinstance(paths, dict) else []
        for path in paths:
            if path not in listed:
                problems.append(f"{MODELS_MANIFEST}: role {role!r} file {path!r} is not in files[]")
        if role not in APP_ROLES:
            problems.append(f"{MODELS_MANIFEST}: role {role!r} ({', '.join(paths) or 'no files'}) is not loaded by "
                            f"the app (speech/ModelManifest.kt): leftover model")

    espeak, prefix, tree = manifest.get("espeak_data"), None, []
    if isinstance(espeak, dict) and isinstance(espeak.get("dir"), str):
        prefix = "assets/" + espeak["dir"].rstrip("/") + "/"
        tree = sorted(name for name in entries if name.startswith(prefix))
        found = {"file_count": len(tree), "bytes": sum(entries[name].file_size for name in tree)}
        for key, value in found.items():
            if value != espeak.get(key):
                problems.append(f"{prefix}: {key} {value}, {MODELS_MANIFEST} says {espeak.get(key)!r}")
        if tree and sha256_tree_entries(apk, prefix, tree) != espeak.get("sha256_tree"):
            problems.append(f"{prefix}: sha256_tree differs from {MODELS_MANIFEST}")
        report["espeak_data"] = {**found, "sha256_tree": espeak.get("sha256_tree")}
    else:
        problems.append(f"{MODELS_MANIFEST}: espeak_data missing (TTS needs espeak-ng-data)")
    for name in sorted(entries):
        if (name.startswith(MODELS_DIR) and name != MODELS_MANIFEST and name[len("assets/"):] not in listed
                and not (prefix and name.startswith(prefix))):
            problems.append(f"{name}: not in {MODELS_MANIFEST} (leftover file)")
    report["files"] = len(listed)
    summary = f"{len(roles)} roles, {len(listed)} files + {len(tree)} espeak-ng-data files"
    return {"summary": summary, "problems": problems, "report": report}


def check_ocr(entries) -> dict:
    """The bundled OCR models of Dịch ảnh (they come with the ML Kit AAR, so only their presence is checked)."""
    files = [name for name in sorted(entries) if name.startswith(OCR_DIR)]
    problems = [] if OCR_LATIN_ENGINE in entries else [
        f"{OCR_LATIN_ENGINE}: missing; Dịch ảnh needs the bundled ML Kit Latin text recognizer (DR-10)"]
    size = sum(entries[name].file_size for name in files)
    return {"summary": f"{len(files)} files, {size / 1e6:.1f} MB", "problems": problems,
            "report": {"files": len(files), "bytes": size}}


def check_other_models(entries) -> dict:
    """Model files outside assets/mt/, assets/models/ and the OCR folder (the first two are checked file by file)."""
    stray = [name for name in sorted(entries) if name.lower().endswith(MODEL_SUFFIXES)
             and not name.startswith((MT_DIR, MODELS_DIR, OCR_DIR))]
    problems = [f"{name}: model file outside {MT_DIR}, {MODELS_DIR} and {OCR_DIR} (old MT package or leftover)" for name in stray]
    return {"summary": f"{len(stray)} found", "problems": problems, "report": {"files": stray}}


def data_offset(handle, info: zipfile.ZipInfo) -> int:
    handle.seek(info.header_offset)
    head = handle.read(30)
    if head[:4] != b"PK\x03\x04":
        raise CheckError(f"{info.filename}: bad ZIP local header")
    name_len, extra_len = struct.unpack_from("<HH", head, 26)
    return info.header_offset + 30 + name_len + extra_len


def elf_problems(apk, name: str, machine: int) -> list:
    with apk.open(name) as handle:
        head = handle.read(64)
        if len(head) < 64 or head[:4] != b"\x7fELF":
            return [f"{name}: not an ELF file"]
        if head[4] != 2 or head[5] != 1:
            return [f"{name}: not a 64-bit little-endian ELF"]
        found = struct.unpack_from("<H", head, 18)[0]
        if found != machine:
            return [f"{name}: ELF machine {found}, this ABI needs {machine}"]
        phoff = struct.unpack_from("<Q", head, 32)[0]
        entsize, count = struct.unpack_from("<HH", head, 54)
        if phoff < 64 or entsize < 56:
            return [f"{name}: bad ELF program header table"]
        handle.read(phoff - 64)
        table = handle.read(entsize * count)
    if len(table) < entsize * count:
        return [f"{name}: truncated ELF program header table"]
    aligns = [struct.unpack_from("<Q", table, i * entsize + 48)[0] for i in range(count)
              if struct.unpack_from("<I", table, i * entsize)[0] == 1]  # PT_LOAD
    if not aligns:
        return [f"{name}: no loadable segment"]
    if min(aligns) < PAGE_16K:
        return [f"{name}: LOAD segments aligned to {min(aligns)} bytes; 16 KB-page devices need {PAGE_16K}"]
    return []


def check_native(apk_path: Path, apk, entries, abis) -> dict:
    problems = []
    libs = sorted(name for name in entries if name.startswith("lib/") and name.count("/") >= 2)
    present = sorted({name.split("/")[1] for name in libs})
    for abi in present:
        if abi not in ABI_MACHINE:
            problems.append(f"lib/{abi}/: unsupported ABI (the app ships 64-bit {' and '.join(ABI_MACHINE)} only)")
    for abi in abis:
        if abi not in present:
            problems.append(f"lib/{abi}/: missing; this APK has {', '.join(present) or 'no native code'}")
            continue
        for lib in REQUIRED_LIBS:
            if f"lib/{abi}/{lib}" not in entries:
                problems.append(f"lib/{abi}/{lib}: missing")
    with apk_path.open("rb") as handle:
        for name in libs:
            abi = name.split("/")[1]
            if abi not in ABI_MACHINE or not name.endswith(".so"):
                continue
            problems += elf_problems(apk, name, ABI_MACHINE[abi])
            info = entries[name]
            offset = data_offset(handle, info) if info.compress_type == zipfile.ZIP_STORED else 0
            if offset % PAGE_16K:
                problems.append(f"{name}: stored at offset {offset}, not 16 KB aligned (zipalign -P 16)")
    report = {"abis": present, "libraries": [{"path": name, "bytes": entries[name].file_size} for name in libs]}
    return {"summary": f"{', '.join(present) or 'none'}, {len(libs)} libraries", "problems": problems, "report": report}


def check_android_manifest(apk, entries) -> dict:
    problems, report = [], {}
    if "AndroidManifest.xml" not in entries:
        return {"summary": "missing", "problems": ["AndroidManifest.xml: missing"], "report": report}
    try:
        elements = parse_axml(apk.read("AndroidManifest.xml"))
    except (ValueError, IndexError, struct.error) as exc:
        return {"summary": "unreadable", "problems": [f"AndroidManifest.xml: cannot read binary XML ({exc})"],
                "report": report}
    if not elements or elements[0][0] != "manifest":
        return {"summary": "unreadable", "problems": ["AndroidManifest.xml: root element is not <manifest>"],
                "report": report}
    root = elements[0][1]
    package = root.get("package")
    sdk = next((attrs for tag, attrs in elements if tag == "uses-sdk"), {})
    permissions = [attrs.get("name") for tag, attrs in elements if tag in PERMISSION_TAGS]
    report.update(package=package, version_name=root.get("versionName"), version_code=root.get("versionCode"),
                  min_sdk=sdk.get("minSdkVersion"), target_sdk=sdk.get("targetSdkVersion"), permissions=permissions)
    allowed = ALLOWED_PERMISSIONS | {f"{package}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"}
    for permission in permissions:
        if permission in NETWORK_PERMISSIONS:
            problems.append(f"AndroidManifest.xml: requests {permission}; the app must stay offline")
        elif permission not in allowed:
            problems.append(f"AndroidManifest.xml: requests {permission}, not in the allowed list "
                            f"({', '.join(sorted(allowed))})")
    application = next((attrs for tag, attrs in elements if tag == "application"), None)
    if application is None:
        problems.append("AndroidManifest.xml: no <application>")
    elif application.get("debuggable") is True:
        problems.append("AndroidManifest.xml: android:debuggable=true; check the release APK")
    summary = (f"{package} {root.get('versionName')} ({root.get('versionCode')}), "
               f"permissions: {', '.join(permissions) or 'none'}")
    return {"summary": summary, "problems": problems, "report": report}


# ---------------------------------------------------------------- main

def expected_abis(apk_path: Path, given) -> list:
    if given:
        return sorted(set(given))
    match = ABI_IN_NAME.search(apk_path.name)
    return [match.group(1)] if match else ["arm64-v8a"]  # the phone build


def check(apk_path: Path, abis=None, app_root: Path | None = None, repo_root: Path | None = None,
          package_id: str = PACKAGE_ID, evidence_rel: str = Q4R_EVIDENCE) -> dict:
    """Run every check. ``report["problems"]`` empty means PASS; CheckError when the file cannot be read as an APK."""
    app_root, repo_root = app_root or APP, repo_root or REPO
    if not apk_path.is_file():
        raise CheckError(f"{apk_path}: not found; build it with {BUILD_HINT}")
    abis = expected_abis(apk_path, abis)
    try:
        apk = zipfile.ZipFile(apk_path)
    except zipfile.BadZipFile as exc:
        raise CheckError(f"{apk_path}: not an APK (ZIP) file: {exc}") from None
    with apk:
        infos = [info for info in apk.infolist() if not info.is_dir()]
        entries = {info.filename: info for info in infos}
        sections = {}
        duplicates = sorted(name for name, n in Counter(info.filename for info in infos).items() if n > 1)
        sections["zip"] = {"summary": f"{len(entries)} entries",
                           "problems": [f"{name}: duplicate ZIP entry" for name in duplicates], "report": {}}
        sections["mt"] = check_mt(apk, entries, abis, app_root, repo_root, package_id, evidence_rel)
        sections["models"] = check_models(apk, entries, app_root)
        sections["ocr"] = check_ocr(entries)
        sections["other models"] = check_other_models(entries)
        sections["native"] = check_native(apk_path, apk, entries, abis)
        sections["manifest"] = check_android_manifest(apk, entries)
    problems = [problem for section in sections.values() for problem in section["problems"]]
    return {
        "schema_version": REPORT_SCHEMA,
        "tool": "tools/check_apk.py",
        "checked_at_utc": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "result": "FAIL" if problems else "PASS",
        "apk": {"file": apk_path.name, "bytes": apk_path.stat().st_size, "sha256": sha256_file(apk_path),
                "expected_abis": abis},
        "android_manifest": sections["manifest"]["report"],
        "native": sections["native"]["report"],
        "mt": sections["mt"]["report"],
        "models": sections["models"]["report"],
        "ocr": sections["ocr"]["report"],
        "sections": {name: {"summary": s["summary"], "problems": s["problems"]} for name, s in sections.items()},
        "problems": problems,
    }


def write_report(path: Path, report: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    with tmp.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    os.replace(tmp, path)


def run(args) -> dict:
    apk_path = (args.apk if args.apk is not None else APP / DEFAULT_APK).resolve()
    report = check(apk_path, args.abi, package_id=args.package_id, evidence_rel=args.evidence)
    apk = report["apk"]
    print(f"apk: {apk['file']}, {apk['bytes']} bytes, sha256 {short(apk['sha256'])}, ABI {', '.join(apk['expected_abis'])}")
    for name, section in report["sections"].items():
        print(f"[{name}] {section['summary']}: {'ok' if not section['problems'] else 'FAILED'}")
        for problem in section["problems"]:
            print(f"  problem: {problem}")
    if args.record:
        if report["problems"]:
            print("record: not written (the check failed)")
        else:
            target = APP / BUILDS_DIR / f"{apk_path.stem}_{apk['sha256'][:12]}.json"
            write_report(target, {key: value for key, value in report.items() if key != "sections"})
            print(f"record: {target.relative_to(APP).as_posix()}")
    return report


def parse_args(argv):
    parser = argparse.ArgumentParser(description="Check a built APK against its MT package, speech models, "
                                                 "native libraries and permissions (read-only).")
    parser.add_argument("apk", nargs="?", type=Path, help=f"APK to check (default: {DEFAULT_APK}).")
    parser.add_argument("--abi", action="append", choices=sorted(ABI_MACHINE),
                        help="ABI the APK must carry, repeatable (default: from the name app-<abi>-release.apk, "
                             "else arm64-v8a).")
    parser.add_argument("--package-id", default=PACKAGE_ID, help=f"Expected MT package (default: {PACKAGE_ID}).")
    parser.add_argument("--evidence", default=Q4R_EVIDENCE,
                        help=f"Export evidence the MT graphs must match, relative to AppProduct (default: {Q4R_EVIDENCE}).")
    parser.add_argument("--record", action="store_true",
                        help=f"On PASS, write the report to {BUILDS_DIR}/<apk name>_<first 12 of sha256>.json.")
    return parser.parse_args(argv)


def main(argv: list | None = None) -> int:
    reconfigure = getattr(sys.stdout, "reconfigure", None)
    if reconfigure is not None:
        reconfigure(errors="backslashreplace")
    args = parse_args(argv)
    try:
        report = run(args)
    except CheckError as exc:
        reason = str(exc)
    except Exception as exc:  # keep the marker contract for unexpected errors too
        traceback.print_exc()
        reason = f"{type(exc).__name__}: {exc}"
    else:
        if not report["problems"]:
            print(f"{MARKER}=PASS")
            return 0
        reason = f"{len(report['problems'])} problem(s); first: {report['problems'][0]}"
    print(f"{MARKER}=FAIL {' '.join(reason.split())}".encode("ascii", "backslashreplace").decode("ascii"))
    return 1


if __name__ == "__main__":
    sys.exit(main())
