# Kiểm thử

Bản khung; mỗi mốc bổ sung test của mình. Lệnh chạy trong `AppProduct/`.

## 1. Test JVM (không cần emulator)

```bat
gradlew.bat test
```

| Package | Nội dung | Mốc |
|---|---|---|
| `mt/` | Parity `MtInputNormalizer` với `core_norm_parity.json`; `OutputPrefixStripper` (ca của Core); `MtPackageManifest` và validator; token ID vàng ([mt_package_v1.md](contracts/mt_package_v1.md) mục 8) | M1 |
| `text/` | `TtsTextNormalizer`, `Lexicon`, `TtsChunker` | M3 |
| `text/`, `audio/` | `SttPostProcessor`, `MeaningfulFilter`, `SampleRing` | M4 |
| `evidence/` | JSON evidence: schema, UTC, LF | M3 |
| `turn/` | Bảng reducer, kịch bản; fuzz 10 000 chuỗi với 4 bất biến | M5–M6 |

## 2. Test instrumentation trên AVD

Cần AVD `VTT_API35_4GB` đang chạy:

```bat
gradlew.bat :app:connectedOfflineDebugAndroidTest
```

| Test | Mốc |
|---|---|
| Asset smoke: nạp đủ model | M2 |
| Căn thời gian VAD | M4 |
| DAO Room | M7 |
| Luồng UI | M7 |

## 3. Test tool Python

```bat
..\.venv\Scripts\python.exe -m unittest discover -s tools\tests -v
```

| Tool | Marker | Mốc |
|---|---|---|
| `fetch_artifacts.py` | `APP_ARTIFACTS` | M-pre |
| `build_text_fixtures.py` | `APP_TEXT_FIXTURES` | M-pre |
| `check_apk.py` | `APP_OFFLINE_APK` | M2 |
| `validate_app_evidence.py` | `APP_EVIDENCE`, `APP_OFFLINE_E2E` | M3 |
| `mix_noise.py` | | M8 |

## 4. Kiểm tra khi xong mốc

- [ ] **M-pre:** `fetch_artifacts.py --verify` ra `APP_ARTIFACTS=PASS`;
  `build_text_fixtures.py` ra `APP_TEXT_FIXTURES=PASS` (fixture parity sinh từ Core).
- [ ] **M0:** `gradlew.bat assembleOfflineDebug assembleDevDebug` chạy được; hai
  bản cài song song lên `VTT_API35_4GB`.
- [ ] **M1:** test parity pass toàn bộ ca.
- [ ] **M2:** bản offlineRelease nạp đủ 5 engine trên AVD, không lỗi JNI hay R8;
  `check_apk.py` ra `APP_OFFLINE_APK=PASS` với APK offline và FAIL với APK dev.
- [ ] **M3:** giọng VI và EN đọc được, dừng đọc tức thì; run kéo về ra
  `APP_EVIDENCE=PASS`.
- [ ] **M4:** loopback cả hai ngôn ngữ ra text và có CER/WER; `test_wavs` decode
  ra text; test căn VAD pass.
- [ ] **M5:** bản offline trên AVD với host mic: nói VI/EN ra transcript (có
  partial) và banner. Bản dev: tải ML Kit, bật Airplane, nói → dịch → đọc bằng
  giọng đích, cả hai chiều.
- [ ] **M6:** 10 000 chuỗi giữ đủ bất biến; thử tay trên AVD: Cắt, Chờ, chen khi
  đang đọc, "Dừng đọc", gõ ở hai nửa.
- [ ] **M7:** 50 lượt từ hai màn hiện đúng ID và hai cột; lượt bị lọc không có;
  nghe lại được cả hai ngôn ngữ; `model_load` cho thấy PSS màn Dịch khác màn Hội
  thoại và RSS giảm khi app vào nền.
- [ ] **M8:** mọi run đã commit ra `APP_EVIDENCE=PASS`; `APP_OFFLINE_E2E=WAITING`
  có lý do; mỗi TN có protocol và run ID, hoặc WAITING kèm lý do.

## 5. Kiểm tra offline trên emulator

```bat
adb shell cmd connectivity airplane-mode enable
```

Cài bản offlineRelease, chạy `model_load`, `tts_prompts`, `loopback`,
`wav_turns`, kéo run về và chạy validator
([evidence_app_v1.md](contracts/evidence_app_v1.md) mục 9). Kết quả cần:
`APP_EVIDENCE=PASS`, `APP_OFFLINE_E2E=WAITING`. Kết quả emulator chỉ kiểm tra
chức năng.

## 6. Bộ nhớ và dung lượng

- PSS/VmHWM theo engine và theo màn: suite `model_load`.
- Rò rỉ bộ nhớ: xu hướng RSS qua các lần lặp.
- Kiểm tay: `adb shell dumpsys meminfo vn.viettechtrans.app`.
- Kích thước APK: `check_apk.py`.
- Thời gian mở app: `adb shell am start -W -n vn.viettechtrans.app/.MainActivity`.

## 7. Ma trận giao diện

| Cấu hình | Lệnh bật | Lệnh trả lại |
|---|---|---|
| Màn 720p | `adb shell wm size 720x1280` | `adb shell wm size reset` |
| Mật độ 320 dpi | `adb shell wm density 320` | `adb shell wm density reset` |
| Cỡ chữ 1.3 / 2.0 | `adb shell settings put system font_scale 1.3` (hoặc `2.0`) | `adb shell settings put system font_scale 1.0` |
| Dark mode | `adb shell cmd uimode night yes` | `adb shell cmd uimode night no` |
| Xoay ngang | Xoay emulator | Xoay lại |
| TalkBack | Bật trong Settings → Accessibility | Tắt |

Với mỗi cấu hình, xem lần lượt: Dịch, Hội thoại, Lịch sử, Thực nghiệm, Cài
đặt, Hướng dẫn.
