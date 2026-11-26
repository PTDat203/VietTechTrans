"""Tests for tools/check_apk.py with small fake APKs; the last test reads the real release APK when it is built.

Run from AppProduct with the repo .venv:

    ..\\.venv\\Scripts\\python.exe -m unittest discover -s tools/tests -v
"""
from __future__ import annotations

import contextlib
import hashlib
import io
import json
import os
import struct
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import check_apk as ca  # noqa: E402
import fetch_artifacts as fa  # noqa: E402

PACKAGE = "vn.viettechtrans.app"
RECEIVER = PACKAGE + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
ANDROID_NS = "http://schemas.android.com/apk/res/android"
RES_IDS = {"name": 0x01010003, "debuggable": 0x0101000F, "versionCode": 0x0101021B, "versionName": 0x0101021C,
           "minSdkVersion": 0x0101020C, "targetSdkVersion": 0x01010270}
REAL_APK = Path(__file__).resolve().parents[2] / ca.DEFAULT_APK
Q4R = {"method": "matmul_nbits", "bits": 4, "block_size": 16, "symmetric": False, "clip_search": True,
       "accuracy_level": 4, "lm_head": "4-bit + 4-bit residual (two MatMulNBits, summed)"}
ESPEAK = "models/espeak-ng-data/"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


# ---------------------------------------------------------------- binary XML and ELF, laid out as aapt2 and the NDK write them

def _length8(n: int) -> bytes:
    return bytes([n]) if n < 0x80 else bytes([0x80 | n >> 8, n & 0xFF])


def _length16(n: int) -> bytes:
    return struct.pack("<H", n) if n < 0x8000 else struct.pack("<HH", 0x8000 | n >> 16, n & 0xFFFF)


def string_pool(strings: list, utf8: bool) -> bytes:
    data, offsets = b"", []
    for text in strings:
        offsets.append(len(data))
        if utf8:
            raw = text.encode("utf-8")
            data += _length8(len(text)) + _length8(len(raw)) + raw + b"\0"
        else:
            raw = text.encode("utf-16-le")
            data += _length16(len(raw) // 2) + raw + b"\0\0"
    data += b"\0" * (-len(data) % 4)
    start = 28 + 4 * len(strings)
    flags = ca.UTF8_FLAG if utf8 else 0
    return (struct.pack("<HHI5I", ca.RES_STRING_POOL_TYPE, 28, start + len(data), len(strings), 0, flags, start, 0)
            + struct.pack(f"<{len(strings)}I", *offsets) + data)


def axml(tree, utf8: bool = False, strip_names: bool = False) -> bytes:
    """Compile (tag, {attribute: value}, [children]); "android:x" attributes get the framework namespace and id.

    ``strip_names`` writes empty strings for the attribute names that have a resource id, as shrinkers do.
    """
    def walk(node):
        yield node
        for child in node[2]:
            yield from walk(child)

    nodes = list(walk(tree))
    with_ids = []  # aapt2 puts these first, in the order of the resource map
    for _tag, attrs, _children in nodes:
        for key in attrs:
            if key.startswith("android:") and key[8:] not in with_ids:
                with_ids.append(key[8:])
    pool = [""] * len(with_ids) if strip_names else list(with_ids)

    def index(text: str) -> int:
        if text not in pool:
            pool.append(text)
        return pool.index(text)

    def name_index(key: str) -> int:
        return with_ids.index(key[8:]) if key.startswith("android:") else index(key)

    for tag, attrs, _children in nodes:
        index(tag)
        for key, value in attrs.items():
            name_index(key)
            if isinstance(value, str):
                index(value)
    prefix, uri = index("android"), index(ANDROID_NS)

    body = []

    def element(node) -> None:
        tag, attrs, children = node
        packed = b""
        for key, value in attrs.items():
            ns = uri if key.startswith("android:") else ca.NO_INDEX
            if isinstance(value, bool):
                raw, kind, data = ca.NO_INDEX, ca.TYPE_INT_BOOLEAN, 0xFFFFFFFF if value else 0
            elif isinstance(value, int):
                raw, kind, data = ca.NO_INDEX, 0x10, value  # TYPE_INT_DEC
            else:
                raw = data = index(value)
                kind = ca.TYPE_STRING
            packed += struct.pack("<IIIHBBI", ns, name_index(key), raw, 8, 0, kind, data)
        ext = struct.pack("<IIHHHHHH", ca.NO_INDEX, index(tag), 20, 20, len(attrs), 0, 0, 0) + packed
        body.append(struct.pack("<HHIII", ca.RES_XML_START_ELEMENT_TYPE, 16, 16 + len(ext), 1, ca.NO_INDEX) + ext)
        for child in children:
            element(child)
        body.append(struct.pack("<HHIIIII", 0x0103, 16, 24, 1, ca.NO_INDEX, ca.NO_INDEX, index(tag)))

    element(tree)
    ids = [RES_IDS[name] for name in with_ids]
    resource_map = struct.pack(f"<HHI{len(ids)}I", ca.RES_XML_RESOURCE_MAP_TYPE, 8, 8 + 4 * len(ids), *ids)
    namespace = [struct.pack("<HHIIIII", kind, 16, 24, 1, ca.NO_INDEX, prefix, uri) for kind in (0x0100, 0x0101)]
    chunks = string_pool(pool, utf8) + resource_map + namespace[0] + b"".join(body) + namespace[1]
    return struct.pack("<HHI", ca.RES_XML_TYPE, 8, 8 + len(chunks)) + chunks


def elf(machine: int = 183, align: int = ca.PAGE_16K, elf_class: int = 2) -> bytes:
    head = bytearray(64)
    head[:7] = b"\x7fELF" + bytes([elf_class, 1, 1])
    struct.pack_into("<HHI", head, 16, 3, machine, 1)  # ET_DYN
    struct.pack_into("<Q", head, 32, 64)  # e_phoff
    struct.pack_into("<HHH", head, 52, 64, 56, 1)  # e_ehsize, e_phentsize, e_phnum
    return bytes(head) + struct.pack("<IIQQQQQQ", 1, 5, 0, 0, 0, 120, 120, align) + bytes(64)  # one PT_LOAD


def write_zip(path: Path, entries: dict, misaligned: set) -> None:
    """Like AGP: native libraries stored and 16 KB-aligned, padded in the extra field as zipalign -P 16 does."""
    with zipfile.ZipFile(path, "w") as apk:
        for name, data in entries.items():
            info = zipfile.ZipInfo(name, date_time=(2026, 10, 23, 0, 0, 0))
            if name.startswith("lib/"):
                info.compress_type = zipfile.ZIP_STORED
                start = apk.start_dir  # where this entry's local header goes
                pad = -(start + 30 + len(name.encode("utf-8")) + 4) % ca.PAGE_16K + (name in misaligned)
                info.extra = struct.pack("<HH", 0xD935, pad) + bytes(pad)
            else:
                info.compress_type = zipfile.ZIP_DEFLATED
            apk.writestr(info, data)


class FakeBuild:
    """A repo with the files check_apk reads (lock, q4r evidence, Core decision) and the content of one APK."""

    def __init__(self, root: Path):
        self.repo, self.app = root, root / "AppProduct"
        self.mt = {"LICENSE_NOTES.md": b"# MT licenses\n"}
        for direction in ca.DIRECTIONS:
            self.mt[f"{direction}/model.onnx"] = f"graph {direction} ".encode() * 40
            self.mt[f"{direction}/source.spm"] = f"spm {direction}".encode()
            self.mt[f"{direction}/vocab.txt"] = "</s>\n<unk>\n▁a\n<pad>\n".encode("utf-8")
            self.mt[f"{direction}/golden.jsonl"] = (json.dumps({"source": "Hi.", "input_ids": [5, 0],
                                                                "output_ids": [7, 0], "text": "Chào."}) + "\n").encode()
        self.models = {"models/vad/silero_vad.onnx": b"vad", "models/stt_vi/encoder.int8.onnx": b"stt vi",
                       "models/stt_en/encoder_model.ort": b"stt en", "models/tts_vi/vi.onnx": b"tts vi",
                       "models/tts_en/en.onnx": b"tts en"}
        self.espeak = {ESPEAK + "phontab": b"phontab", ESPEAK + "en_dict": b"en", ESPEAK + "lang/vi": b"vi"}
        self.libs = {f"lib/arm64-v8a/{lib}": elf() for lib in ca.REQUIRED_LIBS}
        self.ocr = {ca.OCR_LATIN_ENGINE: b"engine", ca.OCR_DIR + "taser/detector/rpn_text_detector.tflite": b"tflite"}
        self.misaligned = set()
        self.permissions = ["android.permission.RECORD_AUDIO", RECEIVER]
        self.debuggable = None
        self.extra = {}  # more APK entries
        self.drop = set()  # APK entries left out
        self.lock = b'{\n  "schema_version": "1.0",\n  "artifacts": []\n}\n'
        self.decision = {"decisions": {d: {"run_id": f"run-{d}", "checkpoint": {"sha256_tree": sha(d.encode())}}
                                       for d in ca.DIRECTIONS}}
        self.summaries = {f"{d}_{s}.summary.json": json.dumps({"split": s}).encode()
                          for d in ca.DIRECTIONS for s in ca.SPLITS}
        self.evidence = self.app / ca.Q4R_EVIDENCE
        self.evidence.parent.mkdir(parents=True)
        (self.app / ca.LOCK_NAME).write_bytes(self.lock.replace(b"\n", b"\r\n"))  # a CRLF checkout
        decision = self.repo / ca.DECISION_REL
        decision.parent.mkdir(parents=True)
        decision.write_bytes(self.decision_lf().replace(b"\n", b"\r\n"))
        for name, data in self.summaries.items():
            (self.evidence.parent / name).write_bytes(data)
        graphs = {f"{d}/graph": {"path": f"{d}/model.onnx", "sha256": sha(self.mt[f"{d}/model.onnx"]),
                                 "bytes": len(self.mt[f"{d}/model.onnx"]), "source_checkpoint": self.checkpoint(d)}
                  for d in ca.DIRECTIONS}
        self.write_evidence({"variant": "q4r", "package_id": ca.PACKAGE_ID, "quantization": Q4R, "results": graphs})

    def decision_lf(self) -> bytes:
        return json.dumps(self.decision, indent=1).encode()

    def checkpoint(self, direction: str) -> dict:
        frozen = self.decision["decisions"][direction]
        return {"run_id": frozen["run_id"], "sha256_tree": frozen["checkpoint"]["sha256_tree"]}

    def read_evidence(self) -> dict:
        return json.loads(self.evidence.read_text(encoding="utf-8"))

    def write_evidence(self, run: dict) -> None:
        self.evidence.write_text(json.dumps(run), encoding="utf-8")

    def mt_manifest(self) -> dict:
        directions = {d: {"model": f"{d}/model.onnx",
                          "tokenizer": {"source_spm": f"{d}/source.spm", "vocab": f"{d}/vocab.txt", "vocab_size": 4},
                          "golden_file": f"{d}/golden.jsonl", "source_checkpoint": self.checkpoint(d),
                          "quality": {"exported": {s: {"summary_sha256": sha(self.summaries[f"{d}_{s}.summary.json"])}
                                                   for s in ca.SPLITS}}}
                      for d in ca.DIRECTIONS}
        return {"schema_version": "2.0", "package_id": ca.PACKAGE_ID, "package_version": "1.0.0",
                "runtime": {"abis": ["arm64-v8a", "x86_64"]}, "quantization": dict(Q4R), "directions": directions,
                "core_refs": [{"path": ca.DECISION_REL, "sha256": sha(self.decision_lf())}],
                "license_notes": "LICENSE_NOTES.md",
                "files": [{"path": p, "bytes": len(b), "sha256": sha(b)} for p, b in sorted(self.mt.items())]}

    def espeak_tree(self) -> str:
        """fetch_artifacts.sha256_tree of the same files on disk: the value fetch_artifacts writes."""
        folder = Path(tempfile.mkdtemp(dir=self.repo))
        for path, data in self.espeak.items():
            target = folder / path[len(ESPEAK):]
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        return fa.sha256_tree(folder)

    def models_manifest(self) -> dict:
        roles = {path.split("/")[1]: {"artifact_id": path.split("/")[1], "files": {"model": path}} for path in self.models}
        return {"schema_version": "1.0", "lock_sha256": sha(self.lock), "roles": roles,
                "files": [{"path": p, "bytes": len(b), "sha256": sha(b)} for p, b in sorted(self.models.items())],
                "espeak_data": {"dir": ESPEAK.rstrip("/"), "file_count": len(self.espeak),
                                "bytes": sum(map(len, self.espeak.values())), "sha256_tree": self.espeak_tree()}}

    def manifest_tree(self):
        application = {"android:name": PACKAGE + ".VttApplication"}
        if self.debuggable is not None:
            application["android:debuggable"] = self.debuggable
        children = [("uses-sdk", {"android:minSdkVersion": 26, "android:targetSdkVersion": 36}, [])]
        children += [("uses-permission", {"android:name": name}, []) for name in self.permissions]
        children.append(("application", application, [("activity", {"android:name": PACKAGE + ".MainActivity"}, [])]))
        return "manifest", {"android:versionCode": 1, "android:versionName": "0.1.0", "package": PACKAGE}, children

    def write_apk(self, name: str = "app-arm64-v8a-release.apk", mt: dict | None = None, hook=None) -> Path:
        entries = {"AndroidManifest.xml": axml(self.manifest_tree()), "classes.dex": b"dex\n035\0"}
        entries.update({ca.MT_DIR + path: data for path, data in self.mt.items()})
        entries[ca.MT_MANIFEST] = json.dumps(mt if mt is not None else self.mt_manifest()).encode()
        entries.update({"assets/" + path: data for path, data in {**self.models, **self.espeak}.items()})
        entries[ca.MODELS_MANIFEST] = json.dumps(self.models_manifest()).encode()
        entries.update(self.libs)
        entries.update(self.ocr)
        entries.update(self.extra)
        for dropped in self.drop:
            entries.pop(dropped, None)
        if hook is not None:
            hook(entries)
        path = self.repo / name
        write_zip(path, entries, self.misaligned)
        return path


def run_main(argv: list) -> tuple:
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = ca.main(argv)
    return code, out.getvalue()


class CheckApkTest(unittest.TestCase):
    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.build = FakeBuild(Path(tmp.name))

    def report(self, apk: Path | None = None, **kwargs) -> dict:
        return ca.check(apk or self.build.write_apk(), app_root=self.build.app, repo_root=self.build.repo, **kwargs)

    def problems(self, apk: Path | None = None, **kwargs) -> list:
        return self.report(apk, **kwargs)["problems"]

    def assertProblems(self, problems: list, *fragments: str) -> None:
        """One problem per fragment, in order."""
        self.assertEqual(len(problems), len(fragments), problems)
        for problem, fragment in zip(problems, fragments):
            self.assertIn(fragment, problem)

    def test_matching_apk_passes_and_reports_what_it_carries(self) -> None:
        report = self.report()
        self.assertEqual(report["problems"], [])
        self.assertEqual(report["result"], "PASS")
        self.assertEqual(report["apk"]["expected_abis"], ["arm64-v8a"])
        self.assertEqual((report["mt"]["package_id"], len(report["mt"]["files"])), (ca.PACKAGE_ID, 9))
        self.assertEqual(report["models"]["roles"], sorted(ca.APP_ROLES))
        self.assertEqual(report["models"]["espeak_data"]["file_count"], 3)
        self.assertEqual(report["native"]["abis"], ["arm64-v8a"])
        manifest = report["android_manifest"]
        self.assertEqual(manifest["permissions"], ["android.permission.RECORD_AUDIO", RECEIVER])
        self.assertEqual((manifest["package"], manifest["version_name"], manifest["min_sdk"]), (PACKAGE, "0.1.0", 26))

    def test_network_and_unexpected_permissions_fail(self) -> None:
        self.build.permissions += ["android.permission.INTERNET", "android.permission.CAMERA"]
        self.assertProblems(self.problems(), "requests android.permission.INTERNET; the app must stay offline",
                            "requests android.permission.CAMERA, not in the allowed list")

    def test_debuggable_apk_fails(self) -> None:
        self.build.debuggable = True
        self.assertProblems(self.problems(), "android:debuggable=true")
        self.build.debuggable = False
        self.assertEqual(self.problems(), [])

    def test_apk_without_mt_package_fails(self) -> None:
        self.build.drop = {ca.MT_DIR + path for path in self.build.mt} | {ca.MT_MANIFEST}
        self.assertProblems(self.problems(), "assets/mt/manifest.json: missing; the app would only show the source text")

    def test_changed_or_missing_mt_files_fail(self) -> None:
        size = len(self.build.mt["en_to_vi/model.onnx"])
        apk = self.build.write_apk(hook=lambda entries: entries.update({ca.MT_DIR + "en_to_vi/model.onnx": b"X" * size}))
        self.assertProblems(self.problems(apk), "assets/mt/en_to_vi/model.onnx: sha256")
        self.build.drop = {ca.MT_DIR + "vi_to_en/vocab.txt", ca.MT_DIR + "LICENSE_NOTES.md"}
        self.assertProblems(self.problems(), "assets/mt/LICENSE_NOTES.md: missing", "assets/mt/vi_to_en/vocab.txt: missing")

    def test_required_files_must_be_listed_and_well_formed(self) -> None:
        manifest = self.build.mt_manifest()
        manifest["files"] = [entry for entry in manifest["files"] if entry["path"] != "en_to_vi/golden.jsonl"]
        manifest["license_notes"] = None
        self.assertProblems(self.problems(self.build.write_apk(mt=manifest)),
                            "assets/mt/en_to_vi/golden.jsonl: not in assets/mt/manifest.json files[]",
                            "license_notes None is not a file of files[]",
                            "en_to_vi.golden_file 'en_to_vi/golden.jsonl' is not a file of files[]")
        manifest = self.build.mt_manifest()
        manifest["directions"]["vi_to_en"]["tokenizer"]["vocab_size"] = 5
        self.assertProblems(self.problems(self.build.write_apk(mt=manifest)),
                            "assets/mt/vi_to_en/vocab.txt: 4 lines, tokenizer.vocab_size is 5")
        self.build.mt["en_to_vi/golden.jsonl"] = b'{"source": "Hi."}\n'
        self.assertProblems(self.problems(), "assets/mt/en_to_vi/golden.jsonl: line 1 lacks")

    def test_old_package_and_extra_files_fail(self) -> None:
        manifest = self.build.mt_manifest()
        manifest["package_id"] = "vtt-main-core-phase03-q8"
        manifest["quantization"] = {"method": "matmul_nbits", "bits": 8, "block_size": 32}
        self.assertProblems(self.problems(self.build.write_apk(mt=manifest)),
                            "package_id 'vtt-main-core-phase03-q8', expected 'vtt-main-core-phase03-q4r'",
                            "quantization 8-bit, block 32, LM head plain differs from")
        self.build.extra[ca.MT_DIR + "en_to_vi/model_q8.onnx"] = b"old graph"
        self.assertProblems(self.problems(), "assets/mt/en_to_vi/model_q8.onnx: not in assets/mt/manifest.json files[]")

    def test_graphs_and_quality_must_come_from_the_q4r_evidence(self) -> None:
        run = self.build.read_evidence()
        run["results"]["vi_to_en/graph"]["sha256"] = "0" * 64
        self.build.write_evidence(run)
        self.assertProblems(self.problems(), "not the graph scored in evidence/phase04/mt_export_q4r/run.json")
        run["results"]["vi_to_en/graph"]["sha256"] = sha(self.build.mt["vi_to_en/model.onnx"])
        self.build.write_evidence(run)
        (self.build.evidence.parent / "en_to_vi_general_validation.summary.json").write_bytes(b"{}")
        self.assertProblems(self.problems(), "en_to_vi.quality.exported.general_validation is not")
        self.build.evidence.unlink()
        self.assertProblems(self.problems(), "run.json: missing; cannot link the APK graphs")

    def test_package_must_match_the_core_decision(self) -> None:
        apk = self.build.write_apk()  # packaged before Core freezes another checkpoint
        self.build.decision["decisions"]["en_to_vi"]["run_id"] = "run-v3"
        (self.build.repo / ca.DECISION_REL).write_bytes(self.build.decision_lf())
        self.assertProblems(self.problems(apk), "sha256 differs from core_refs in the APK",
                            "en_to_vi checkpoint 'run-en_to_vi' is not the one frozen")

    def test_speech_models_must_come_from_this_lock(self) -> None:
        (self.build.app / ca.LOCK_NAME).write_bytes(b'{"artifacts": [1]}\n')
        self.assertProblems(self.problems(), "lock_sha256")

    def test_changed_espeak_data_and_extra_model_files_fail(self) -> None:
        apk = self.build.write_apk(hook=lambda entries: entries.update({"assets/" + ESPEAK + "lang/vi": b"VI"}))
        self.assertProblems(self.problems(apk), "espeak-ng-data/: sha256_tree differs")
        self.build.extra["assets/models/stt_vi/encoder.fp32.onnx"] = b"old"
        self.assertProblems(self.problems(), "assets/models/stt_vi/encoder.fp32.onnx: not in assets/models/manifest.json")

    def test_roles_must_be_the_ones_the_app_loads(self) -> None:
        self.build.models["models/denoiser/gtcrn_simple.onnx"] = b"gtcrn"
        self.assertProblems(self.problems(), "role 'denoiser' (models/denoiser/gtcrn_simple.onnx) is not loaded by the app")
        del self.build.models["models/denoiser/gtcrn_simple.onnx"], self.build.models["models/tts_en/en.onnx"]
        self.assertProblems(self.problems(), "role 'tts_en' missing")

    def test_model_files_elsewhere_are_leftovers(self) -> None:
        self.build.extra["assets/mt_q8/en_to_vi/model.onnx"] = b"old package"
        self.build.extra["DebugProbesKt.bin"] = b"kotlinx coroutines"  # not a model
        self.assertProblems(self.problems(), "assets/mt_q8/en_to_vi/model.onnx: model file outside")

    def test_ocr_models_are_required_and_not_leftovers(self) -> None:
        report = self.report()
        self.assertEqual(report["ocr"]["files"], 2)  # the .tflite under the OCR folder is not a stray model
        del self.build.ocr[ca.OCR_LATIN_ENGINE]
        self.assertProblems(self.problems(), "taser_tflite_gocrlatin")
        self.build.libs.pop("lib/arm64-v8a/libmlkit_google_ocr_pipeline.so")
        self.assertProblems(self.problems(), "taser_tflite_gocrlatin", "libmlkit_google_ocr_pipeline.so: missing")

    def test_native_libraries(self) -> None:
        cases = {
            "32-bit ABI": (lambda b: b.libs.update({"lib/armeabi-v7a/libonnxruntime.so": elf(40, elf_class=1)}),
                           "lib/armeabi-v7a/: unsupported ABI"),
            "missing library": (lambda b: b.libs.pop("lib/arm64-v8a/libonnxruntime4j_jni.so"),
                                "lib/arm64-v8a/libonnxruntime4j_jni.so: missing"),
            "other machine": (lambda b: b.libs.update({"lib/arm64-v8a/libonnxruntime.so": elf(62)}),
                              "ELF machine 62, this ABI needs 183"),
            "4 KB pages": (lambda b: b.libs.update({"lib/arm64-v8a/libsherpa-onnx-jni.so": elf(align=4096)}),
                           "aligned to 4096 bytes"),
            "zip offset": (lambda b: b.misaligned.add("lib/arm64-v8a/libsherpa-onnx-jni.so"), "not 16 KB aligned"),
        }
        for label, (change, fragment) in cases.items():
            with self.subTest(label):
                build = FakeBuild(Path(tempfile.mkdtemp(dir=self.build.repo)))
                change(build)
                self.assertProblems(ca.check(build.write_apk(), app_root=build.app, repo_root=build.repo)["problems"],
                                    fragment)

    def test_expected_abi_comes_from_the_name_or_the_option(self) -> None:
        self.assertProblems(self.problems(self.build.write_apk("app-x86_64-release.apk")),
                            "lib/x86_64/: missing; this APK has arm64-v8a")
        self.assertProblems(self.problems(abis=["arm64-v8a", "x86_64"]), "lib/x86_64/: missing")
        self.assertEqual(self.report(self.build.write_apk("custom.apk"))["apk"]["expected_abis"], ["arm64-v8a"])

    def test_binary_xml_parser(self) -> None:
        long_name = "vn.viettechtrans.permission." + "X" * 200  # two-byte string lengths in both encodings
        tree = ("manifest", {"package": PACKAGE}, [("uses-permission", {"android:name": long_name}, []),
                                                   ("application", {"android:debuggable": True}, [])])
        expected = [("manifest", {"package": PACKAGE}), ("uses-permission", {"name": long_name}),
                    ("application", {"debuggable": True})]
        for utf8 in (False, True):
            for strip_names in (False, True):
                with self.subTest(utf8=utf8, strip_names=strip_names):
                    self.assertEqual(ca.parse_axml(axml(tree, utf8, strip_names)), expected)
        with self.assertRaises(ValueError):
            ca.parse_axml(b"<?xml version='1.0'?><manifest/>")

    def test_main_prints_the_marker_and_records_only_a_pass(self) -> None:
        apk = self.build.write_apk()
        records = self.build.app / ca.BUILDS_DIR
        with mock.patch.object(ca, "APP", self.build.app), mock.patch.object(ca, "REPO", self.build.repo):
            code, out = run_main([str(apk), "--record"])
            self.assertEqual((code, out.splitlines()[-1]), (0, "APP_APK_CHECK=PASS"), out)
            [record] = records.glob("*.json")
            self.assertEqual(record.name, f"app-arm64-v8a-release_{sha(apk.read_bytes())[:12]}.json")
            data = json.loads(record.read_text(encoding="utf-8"))
            self.assertEqual((data["result"], data["problems"], data["apk"]["sha256"]), ("PASS", [], sha(apk.read_bytes())))
            self.assertNotIn("sections", data)

            self.build.permissions.append("android.permission.INTERNET")
            code, out = run_main([str(self.build.write_apk("app-arm64-v8a-debug.apk")), "--record"])
            self.assertEqual(code, 1)
            self.assertIn("record: not written (the check failed)", out)
            self.assertTrue(out.splitlines()[-1].startswith(
                "APP_APK_CHECK=FAIL 1 problem(s); first: AndroidManifest.xml: requests android.permission.INTERNET"), out)
            self.assertEqual(len(list(records.glob("*.json"))), 1)

            junk = self.build.repo / "junk.apk"
            junk.write_bytes(b"not a zip")
            code, out = run_main([str(junk)])
            self.assertEqual(code, 1)
            self.assertIn("not an APK (ZIP) file", out.splitlines()[-1])
            code, out = run_main([str(self.build.repo / "absent.apk")])
            self.assertIn("not found; build it with", out.splitlines()[-1])

    def test_check_does_not_change_the_apk(self) -> None:
        apk = self.build.write_apk()
        before = (apk.read_bytes(), os.stat(apk).st_mtime_ns)
        self.report(apk)
        self.assertEqual((apk.read_bytes(), os.stat(apk).st_mtime_ns), before)

    @unittest.skipUnless(REAL_APK.is_file(), "release APK not built")
    def test_parser_reads_the_real_release_manifest(self) -> None:
        with zipfile.ZipFile(REAL_APK) as apk:
            elements = ca.parse_axml(apk.read("AndroidManifest.xml"))
        self.assertEqual(elements[0], ("manifest", elements[0][1]))
        self.assertEqual(elements[0][1]["package"], PACKAGE)
        permissions = {attrs["name"] for tag, attrs in elements if tag == "uses-permission"}
        self.assertIn("android.permission.RECORD_AUDIO", permissions)
        self.assertNotIn("android.permission.INTERNET", permissions)
        application = next(attrs for tag, attrs in elements if tag == "application")
        self.assertIs(application["extractNativeLibs"], False)


if __name__ == "__main__":
    unittest.main()
