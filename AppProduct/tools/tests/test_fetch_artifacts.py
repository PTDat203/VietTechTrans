"""Tests for tools/fetch_artifacts.py with small fake archives; no internet needed.

Run from AppProduct with the repo .venv:

    ..\\.venv\\Scripts\\python.exe -m unittest discover -s tools/tests -v
"""
from __future__ import annotations

import contextlib
import copy
import hashlib
import http.server
import importlib
import io
import json
import sys
import tarfile
import tempfile
import threading
import unittest
from pathlib import Path, PurePosixPath

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import fetch_artifacts as fa  # noqa: E402

CORE_SRC = Path(__file__).resolve().parents[3] / "Core" / "src"
MODELS = "app/src/main/assets/models"
AAR = b"PK\x03\x04 fake aar"
VAD = b"fake silero vad onnx"
STT_FILES = {
    "fake-stt/encoder.int8.onnx": b"E" * 3000,
    "fake-stt/tokens.txt": "▁XIN 3\n▁CHÀO 4\n".encode("utf-8"),
    "fake-stt/README.md": b"not installed\n",
    "fake-stt/test_wavs/0.wav": b"RIFF\x00\x00\x00\x00WAVEfmt ",
    "fake-stt/test_wavs/README.md": b"test wav source\n",
}
# "lang/a-b" vs "lang/a/b" and "Alex" vs "adam" pin the path order of Core's sha256_tree on POSIX.
ESPEAK = {
    "phontab": b"phontab", "phonindex": b"phonindex", "phondata": b"phondata", "intonations": b"into",
    "en_dict": b"en" * 50, "vi_dict": b"vi" * 40, "fr_dict": b"fr" * 30, "de_dict": b"de" * 20,
    "lang/gmw/en": b"name english\n", "lang/aav/vi": b"name vietnamese\n",
    "lang/a-b": b"dash", "lang/a/b": b"slash", "voices/!v/Alex": b"Alex", "voices/!v/adam": b"adam",
}
KEPT_ESPEAK = {rel: data for rel, data in ESPEAK.items() if rel not in ("fr_dict", "de_dict")}
TTS_FILES = {
    "fake-tts/voice.onnx": b"V" * 5000,
    "fake-tts/tokens.txt": b"_ 0\n^ 1\n",
    "fake-tts/MODEL_CARD": "# Model card\n* Dữ liệu: CC BY 4.0\n".encode("utf-8"),
    "fake-tts/voice.onnx.json": b"{}",
    **{f"fake-tts/espeak-ng-data/{rel}": data for rel, data in ESPEAK.items()},
}


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def reference_tree(files: dict) -> str:
    """Independent rewrite of Core sha256_tree: PurePosixPath order; path, NUL, bytes, NUL."""
    digest = hashlib.sha256()
    for rel in sorted(files, key=PurePosixPath):
        digest.update(rel.encode("utf-8") + b"\0" + files[rel] + b"\0")
    return digest.hexdigest()


def make_tar(path: Path, members: dict, extra: tuple = ()) -> bytes:
    with tarfile.open(path, "w:bz2") as tar:
        for name, data in members.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
        for info in extra:
            tar.addfile(info)
    return path.read_bytes()


def entry(src: str, to: str, key: str, data: bytes) -> dict:
    return {"from": src, "to": to, "key": key, "bytes": len(data), "sha256": sha(data)}


def build_lock(base: str, stt_tar: bytes, tts_tar: bytes) -> dict:
    """Lock with every value filled, as --record would write it."""
    return {
        "schema_version": "1.0",
        "artifacts": [
            {"id": "fake-aar", "role": "runtime_aar", "version": "1.0", "license": "Apache-2.0",
             "source": "https://example.invalid/aar", "url": f"{base}/fake.aar",
             "archive": {"bytes": len(AAR), "sha256": sha(AAR)},
             "install": {"kind": "copy", "dest": "third_party/fake", "files": [entry("fake.aar", "fake.aar", "aar", AAR)]}},
            {"id": "fake-vad", "role": "vad", "license": "MIT", "source": "https://example.invalid/vad",
             "url": f"{base}/vad.onnx", "sherpa": {"kind": "silero_vad"},
             "archive": {"bytes": len(VAD), "sha256": sha(VAD)},
             "install": {"kind": "copy", "dest": f"{MODELS}/vad", "files": [entry("vad.onnx", "silero_vad.onnx", "model", VAD)]}},
            {"id": "fake-stt", "role": "stt_vi", "license": "Apache-2.0", "source": "https://example.invalid/stt",
             "url": f"{base}/fake-stt.tar.bz2", "sherpa": {"kind": "offline_transducer", "output_style": "upper_no_punct"},
             "archive": {"bytes": len(stt_tar), "sha256": sha(stt_tar)},
             "install": {"kind": "extract", "dest": f"{MODELS}/stt_vi",
                         "files": [entry("fake-stt/encoder.int8.onnx", "encoder.int8.onnx", "encoder",
                                         STT_FILES["fake-stt/encoder.int8.onnx"]),
                                   entry("fake-stt/tokens.txt", "tokens.txt", "tokens", STT_FILES["fake-stt/tokens.txt"])],
                         "test_wavs": {"from": "fake-stt/test_wavs", "dest": ".cache/test_wavs/stt_vi"}}},
            {"id": "fake-tts", "role": "tts_vi", "license": "MIT; dữ liệu CC BY 4.0", "source": "https://example.invalid/tts",
             "url": f"{base}/fake-tts.tar.bz2",
             "sherpa": {"kind": "offline_tts_vits", "noise_scale": 0.667, "noise_scale_w": 0.8, "length_scale": 1.0},
             "archive": {"bytes": len(tts_tar), "sha256": sha(tts_tar)},
             "install": {"kind": "extract", "dest": f"{MODELS}/tts_vi",
                         "files": [entry("fake-tts/voice.onnx", "voice.onnx", "model", TTS_FILES["fake-tts/voice.onnx"]),
                                   entry("fake-tts/tokens.txt", "tokens.txt", "tokens", TTS_FILES["fake-tts/tokens.txt"]),
                                   entry("fake-tts/MODEL_CARD", "MODEL_CARD", "model_card", TTS_FILES["fake-tts/MODEL_CARD"])],
                         "espeak_data": {"from": "fake-tts/espeak-ng-data", "dest": f"{MODELS}/espeak-ng-data",
                                         "keep_dicts": ["en_dict", "vi_dict"], "license": "GPL-3.0-or-later",
                                         "file_count": len(KEPT_ESPEAK),
                                         "bytes": sum(len(data) for data in KEPT_ESPEAK.values()),
                                         "sha256_tree": reference_tree(KEPT_ESPEAK)}}},
        ],
    }


def null_values(lock: dict) -> dict:
    """The same lock before --record: archive, file and espeak values unknown."""
    lock = copy.deepcopy(lock)
    for art in lock["artifacts"]:
        art["archive"].update(bytes=None, sha256=None)
        for item in art["install"]["files"]:
            item.update(bytes=None, sha256=None)
        if "espeak_data" in art["install"]:
            art["install"]["espeak_data"].update(file_count=None, bytes=None, sha256_tree=None)
    return lock


def lock_text(lock: dict) -> str:
    return json.dumps(lock, ensure_ascii=False, indent=2) + "\n"


def run_main(*args) -> tuple:
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = fa.main([str(arg) for arg in args])
    return code, out.getvalue()


def files_under(directory: Path) -> dict:
    return {p.relative_to(directory).as_posix(): p.read_bytes() for p in directory.rglob("*") if p.is_file()}


class FetchArtifactsTests(unittest.TestCase):
    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.tmp = Path(tmp.name)
        self.root = self.tmp / "AppProduct"
        self.src = self.tmp / "downloads"
        self.root.mkdir()
        self.src.mkdir()
        (self.src / "fake.aar").write_bytes(AAR)
        (self.src / "vad.onnx").write_bytes(VAD)
        self.stt_tar = make_tar(self.src / "fake-stt.tar.bz2", STT_FILES)
        self.tts_tar = make_tar(self.src / "fake-tts.tar.bz2", TTS_FILES)
        self.lock = build_lock("https://example.invalid/releases", self.stt_tar, self.tts_tar)

    def write_lock(self, lock: dict, newline: str = "\n") -> Path:
        path = self.root / fa.LOCK_NAME
        with path.open("w", encoding="utf-8", newline=newline) as handle:
            handle.write(lock_text(lock))
        return path

    def fetch(self, *extra) -> tuple:
        return run_main("--root", self.root, "--from-dir", self.src, *extra)

    def verify(self) -> tuple:
        return run_main("--root", self.root, "--verify")

    def assertPass(self, result: tuple) -> None:
        code, out = result
        self.assertEqual(code, 0, out)
        self.assertEqual(out.strip().splitlines()[-1], "APP_ARTIFACTS=PASS")

    def assertFail(self, result: tuple, *fragments: str) -> str:
        code, out = result
        self.assertEqual(code, 1, out)
        last = out.strip().splitlines()[-1]
        self.assertTrue(last.startswith("APP_ARTIFACTS=FAIL "), out)
        for fragment in fragments:
            self.assertIn(fragment, last)
        return last

    def test_from_dir_installs_listed_files_and_skips_when_installed(self) -> None:
        self.write_lock(self.lock)
        self.assertPass(self.fetch())
        self.assertEqual((self.root / "third_party/fake/fake.aar").read_bytes(), AAR)
        self.assertEqual((self.root / MODELS / "vad/silero_vad.onnx").read_bytes(), VAD)
        self.assertEqual(files_under(self.root / MODELS / "stt_vi"),
                         {"encoder.int8.onnx": STT_FILES["fake-stt/encoder.int8.onnx"],
                          "tokens.txt": STT_FILES["fake-stt/tokens.txt"]})
        self.assertEqual(set(files_under(self.root / MODELS / "tts_vi")), {"voice.onnx", "tokens.txt", "MODEL_CARD"})
        self.assertEqual(files_under(self.root / ".cache/test_wavs/stt_vi"),
                         {"0.wav": STT_FILES["fake-stt/test_wavs/0.wav"],
                          "README.md": STT_FILES["fake-stt/test_wavs/README.md"]})
        self.assertEqual([p.name for p in (self.root / MODELS).iterdir() if p.name.endswith(".staging")], [])
        self.assertPass(self.verify())
        for archive in self.src.iterdir():
            archive.unlink()  # a second run must not need the archives
        code, out = self.fetch()
        self.assertPass((code, out))
        self.assertEqual(out.count(": already installed"), 4)

    def test_espeak_pruning_keeps_only_listed_dicts(self) -> None:
        self.write_lock(self.lock)
        self.assertPass(self.fetch())
        installed = files_under(self.root / MODELS / "espeak-ng-data")
        self.assertEqual(installed, KEPT_ESPEAK)
        self.assertEqual(sorted(name for name in installed if name.endswith("_dict")), ["en_dict", "vi_dict"])
        lock = copy.deepcopy(self.lock)
        lock["artifacts"][3]["install"]["espeak_data"]["keep_dicts"] = ["en_dict", "xx_dict"]
        self.write_lock(lock)
        self.assertFail(self.fetch("--only", "tts_vi"), "lacks xx_dict")
        self.assertEqual(files_under(self.root / MODELS / "espeak-ng-data"), KEPT_ESPEAK)  # old install kept

    def test_manifest_content_and_tree_hash(self) -> None:
        lock_path = self.write_lock(self.lock, newline="\r\n")  # CRLF checkout must hash like LF
        self.assertPass(self.fetch())
        manifest_path = self.root / fa.MANIFEST_REL
        raw = manifest_path.read_bytes()
        self.assertNotIn(b"\r", raw)
        self.assertTrue(raw.endswith(b"}\n"))
        self.assertIn("dữ liệu".encode("utf-8"), raw)  # ensure_ascii=False
        manifest = json.loads(raw.decode("utf-8"))
        self.assertEqual(list(manifest), ["schema_version", "generated_at_utc", "lock_sha256", "roles", "files", "espeak_data"])
        self.assertEqual(manifest["schema_version"], "1.0")
        self.assertRegex(manifest["generated_at_utc"], r"\A\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\+00:00\Z")
        self.assertEqual(manifest["lock_sha256"], sha(lock_path.read_bytes().replace(b"\r\n", b"\n")))
        self.assertEqual(list(manifest["roles"]), ["vad", "stt_vi", "tts_vi"])  # the AAR is not an asset
        self.assertEqual(manifest["roles"]["stt_vi"], {
            "artifact_id": "fake-stt", "license": "Apache-2.0",
            "sherpa": {"kind": "offline_transducer", "output_style": "upper_no_punct"},
            "files": {"encoder": "models/stt_vi/encoder.int8.onnx", "tokens": "models/stt_vi/tokens.txt"}})
        self.assertEqual(manifest["roles"]["vad"]["files"], {"model": "models/vad/silero_vad.onnx"})
        expected_files = [("models/vad/silero_vad.onnx", VAD),
                          ("models/stt_vi/encoder.int8.onnx", STT_FILES["fake-stt/encoder.int8.onnx"]),
                          ("models/stt_vi/tokens.txt", STT_FILES["fake-stt/tokens.txt"]),
                          ("models/tts_vi/voice.onnx", TTS_FILES["fake-tts/voice.onnx"]),
                          ("models/tts_vi/tokens.txt", TTS_FILES["fake-tts/tokens.txt"]),
                          ("models/tts_vi/MODEL_CARD", TTS_FILES["fake-tts/MODEL_CARD"])]
        self.assertEqual(manifest["files"], [{"path": path, "bytes": len(data), "sha256": sha(data)}
                                             for path, data in expected_files])
        for item in manifest["files"]:
            self.assertEqual(sha((self.root / "app/src/main/assets" / item["path"]).read_bytes()), item["sha256"])
        tree = reference_tree(KEPT_ESPEAK)
        self.assertEqual(manifest["espeak_data"], {
            "dir": "models/espeak-ng-data", "file_count": len(KEPT_ESPEAK),
            "bytes": sum(len(data) for data in KEPT_ESPEAK.values()), "sha256_tree": tree,
            "license": "GPL-3.0-or-later"})
        self.assertEqual(fa.sha256_tree(self.root / MODELS / "espeak-ng-data"), tree)
        naive = hashlib.sha256()
        for rel in sorted(KEPT_ESPEAK):  # plain string order differs here ("a-b" < "a/b")
            naive.update(rel.encode() + b"\0" + KEPT_ESPEAK[rel] + b"\0")
        self.assertNotEqual(naive.hexdigest(), tree)
        code, out = self.fetch()
        self.assertPass((code, out))
        self.assertIn("(unchanged)", out)
        self.assertEqual(manifest_path.read_bytes(), raw)

    def test_record_fills_null_values_and_keeps_key_order(self) -> None:
        self.write_lock(null_values(self.lock))
        self.assertFail(self.fetch(), "--record")
        self.assertFalse((self.root / "third_party").exists())
        code, out = self.fetch("--record")
        self.assertPass((code, out))
        self.assertIn("recorded: 25 values", out)  # aar 4 + vad 4 + stt 6 + tts 8 + espeak 3
        raw = (self.root / fa.LOCK_NAME).read_bytes()
        self.assertEqual(raw.decode("utf-8"), lock_text(self.lock))  # same values, key order and format
        manifest = json.loads((self.root / fa.MANIFEST_REL).read_text(encoding="utf-8"))
        self.assertEqual(manifest["lock_sha256"], sha(raw))
        self.assertPass(self.verify())

    def test_record_never_overwrites_a_wrong_value(self) -> None:
        lock = null_values(self.lock)
        lock["artifacts"][2]["archive"]["sha256"] = "0" * 64
        self.write_lock(lock)
        self.assertFail(self.fetch("--record"), "fake-stt.archive.sha256 mismatch")
        self.assertEqual((self.root / fa.LOCK_NAME).read_text(encoding="utf-8"), lock_text(lock))

    def test_archive_or_file_sha_mismatch_fails_without_installing(self) -> None:
        lock = copy.deepcopy(self.lock)
        lock["artifacts"][3]["archive"]["sha256"] = "0" * 64
        self.write_lock(lock)
        self.assertFail(self.fetch("--only", "fake-tts"), "fake-tts.archive.sha256 mismatch")
        self.assertFalse((self.root / MODELS / "tts_vi").exists())
        self.assertTrue((self.src / "fake-tts.tar.bz2").is_file())  # --from-dir files are never deleted
        lock = copy.deepcopy(self.lock)
        lock["artifacts"][2]["install"]["files"][1]["sha256"] = "1" * 64
        self.write_lock(lock)
        self.assertFail(self.fetch("--only", "stt_vi"), "fake-stt.tokens.sha256 mismatch")
        self.assertFalse((self.root / MODELS / "stt_vi").exists())
        self.assertFalse((self.root / MODELS / ".stt_vi.staging").exists())

    def test_verify_detects_tampering(self) -> None:
        self.write_lock(self.lock)
        self.assertPass(self.fetch())
        model = self.root / MODELS / "tts_vi/voice.onnx"
        model.write_bytes(model.read_bytes() + b"x")
        self.assertFail(self.verify(), "voice.onnx")
        self.assertPass(self.fetch())  # a normal run repairs it
        self.assertPass(self.verify())
        extra = self.root / MODELS / "espeak-ng-data/fr_dict"
        extra.write_bytes(b"fr")
        self.assertFail(self.verify(), "espeak-ng-data")
        extra.unlink()
        manifest_path = self.root / fa.MANIFEST_REL
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["roles"]["vad"]["files"]["model"] = "models/vad/other.onnx"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        self.assertFail(self.verify(), "manifest.json")

    def test_path_traversal_and_links_are_rejected(self) -> None:
        cases = [
            ((), {"fake-tts/espeak-ng-data/../../evil.txt": b"evil"}, "unsafe path"),
            ((self._link("fake-tts/espeak-ng-data/link", "../../../evil.txt"),), {}, "not a regular file"),
        ]
        for extra, members, reason in cases:
            with self.subTest(reason=reason):
                tts_tar = make_tar(self.src / "fake-tts.tar.bz2", {**TTS_FILES, **members}, extra)
                lock = copy.deepcopy(self.lock)
                lock["artifacts"][3]["archive"].update(bytes=len(tts_tar), sha256=sha(tts_tar))
                self.write_lock(lock)
                self.assertFail(self.fetch("--only", "tts_vi"), reason)
                self.assertEqual([p for p in self.tmp.rglob("evil.txt")], [])
                self.assertFalse((self.root / MODELS / "espeak-ng-data").exists())
        lock = copy.deepcopy(self.lock)
        lock["artifacts"][2]["install"]["files"][0]["from"] = "../evil.txt"
        self.write_lock(lock)
        self.assertFail(self.fetch(), "unsafe path")

    @staticmethod
    def _link(name: str, target: str) -> tarfile.TarInfo:
        info = tarfile.TarInfo(name)
        info.type = tarfile.SYMTYPE
        info.linkname = target
        return info

    def test_only_installs_a_subset_without_manifest(self) -> None:
        self.write_lock(self.lock)
        code, out = self.fetch("--only", "fake-vad")
        self.assertPass((code, out))
        self.assertIn("manifest: not written", out)
        self.assertTrue((self.root / MODELS / "vad/silero_vad.onnx").is_file())
        self.assertFalse((self.root / MODELS / "stt_vi").exists())
        self.assertFalse((self.root / fa.MANIFEST_REL).exists())
        self.assertFail(self.fetch("--only", "nope"), "unknown artifact")

    def test_download_follows_redirect_and_sends_user_agent(self) -> None:
        handler = type("Handler", (_ReleaseHandler,), {"files": {p.name: p.read_bytes() for p in self.src.iterdir()},
                                                       "agents": []})
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        base = f"http://127.0.0.1:{server.server_address[1]}/redirect"
        self.write_lock(build_lock(base, self.stt_tar, self.tts_tar))
        self.assertPass(run_main("--root", self.root))
        downloads = self.root / fa.DOWNLOADS_REL
        self.assertEqual(sorted(p.name for p in downloads.iterdir()),
                         ["fake-stt.tar.bz2", "fake-tts.tar.bz2", "fake.aar", "vad.onnx"])  # no .part left
        self.assertEqual(set(handler.agents), {fa.USER_AGENT})
        self.assertEqual(len(handler.agents), 8)  # 4 redirects + 4 downloads
        self.assertPass(self.verify())
        (downloads / "fake-stt.tar.bz2").write_bytes(b"corrupt")  # a bad cached copy is fetched again
        (self.root / MODELS / "stt_vi/tokens.txt").unlink()
        code, out = run_main("--root", self.root)
        self.assertPass((code, out))
        self.assertIn("cached copy rejected", out)
        self.assertEqual((downloads / "fake-stt.tar.bz2").read_bytes(), self.stt_tar)
        self.assertEqual(len(handler.agents), 10)

    def test_sha256_tree_equals_core_function(self) -> None:
        if not (CORE_SRC / "core_mt" / "adaptation.py").is_file():
            self.skipTest("Core source is not available")
        tree = self.tmp / "tree"
        # Names whose order is the same on every OS (Core sorts Path objects).
        for rel, data in {"a.bin": b"a", "b/c.txt": "chào".encode(), "b/d/e": b"", "z": b"z" * 10}.items():
            (tree / rel).parent.mkdir(parents=True, exist_ok=True)
            (tree / rel).write_bytes(data)
        previous = sys.dont_write_bytecode
        sys.dont_write_bytecode = True  # Core is read-only: no __pycache__ written there
        sys.path.insert(0, str(CORE_SRC))
        try:
            core_adaptation = importlib.import_module("core_mt.adaptation")
        except ImportError as exc:
            self.skipTest(f"Core module not importable: {exc}")
        finally:
            sys.path.remove(str(CORE_SRC))
            sys.dont_write_bytecode = previous
        self.assertEqual(fa.sha256_tree(tree), core_adaptation.sha256_tree(tree))


class _ReleaseHandler(http.server.BaseHTTPRequestHandler):
    """Serves /files/<name>; /redirect/<name> answers 302 like github.com release downloads."""

    files: dict = {}
    agents: list = []

    def do_GET(self) -> None:  # noqa: N802 (http.server API)
        type(self).agents.append(self.headers.get("User-Agent"))
        name = self.path.rsplit("/", 1)[-1]
        if self.path.startswith("/redirect/"):
            self.send_response(302)
            self.send_header("Location", f"/files/{name}")
            self.send_header("Content-Length", "0")
            self.end_headers()
        elif self.path.startswith("/files/") and name in self.files:
            self.send_response(200)
            self.send_header("Content-Length", str(len(self.files[name])))
            self.end_headers()
            self.wfile.write(self.files[name])
        else:
            self.send_error(404)

    def log_message(self, *args) -> None:
        pass


if __name__ == "__main__":
    unittest.main()
