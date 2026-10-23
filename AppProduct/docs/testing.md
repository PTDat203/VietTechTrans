# Kiểm thử

Lệnh chạy trong `AppProduct/` (PowerShell, đặt `JAVA_HOME` và `ANDROID_HOME` như README).
`JAVA_HOME` phải là Temurin 21 như README. JBR 25 của Android Studio làm tiến trình test văng
(`EXCEPTION_ACCESS_VIOLATION` trong `msvcp140.dll` khi ONNX Runtime khởi tạo, ghi lại 2026-10-22).
Từ 2026-10-07 chỉ còn một bản build (DR-09), không còn task theo flavor.

## 1. Test JVM (không cần emulator)

```bat
gradlew.bat :app:testDebugUnitTest
```

Kết quả ngày 2026-10-07: 67/67 test qua. Ngày 2026-10-22: 74/74 (thêm `TermProtectorTest`, DR-07 §15). Ngày 2026-10-23:
85/85 sau chính sách rule dịch text v1 (DR-07 §16): thêm `mt/rules/` và các test policy, bỏ `TermProtectorTest` và
`VietnameseDiacriticsTest` vì đã gỡ code tương ứng. Sau đợt rà soát đối kháng: 90/90.

| Package | Nội dung | Mốc |
|---|---|---|
| `mt/` | `DirectionTest`; parity `MtInputNormalizer` với `core_norm_parity.json`; `MtPackageManifest` v2 và validator | M1 |
| `mt/rules/` | `TextRulesTest` (11 test): bảng "Bộ test bắt buộc" của chính sách rule dịch text v1 (R02–R08, R12), cả 41 case của `assets/text/mt_rule_cases_v1.json`, và các ca tìm thấy khi rà soát (biểu tượng cảm xúc, lệnh trùng từ thường như "brew install node" / "brew coffee", "I love phở" gõ ở chiều Anh). `TextRulesCalibrationTest`: số lần mỗi rule kích hoạt trên 4 tập Validation (nguồn và output q4r), có ngân sách báo thừa; ghi `app/build/mt_rules_calibration.json`, thêm `-Pvtt.rulesEvidence=../evidence/phase04/mt_rules_v1` để ghi vào evidence | 2026-10-23 (DR-07 §16) |
| `mt/marian/` | `MarianTokenizerParityTest` (id khớp Hugging Face, 4 873 câu/chiều), `MarianGoldenTest` (Kotlin + ONNX Runtime desktop ra đúng từng token như `tools/mt/marian_ref.py`; tách câu dài), `BeamSearchTest` (gồm `preferFinished`), `OutputCleanupTest` (gộp lặp, parity với `marian_ref.collapse_repeats` qua `mt_collapse_repeats.json`); `MarianGoldenTest` có thêm câu ngắn "chào bạn", "vâng", "không", "xin chao" với giới hạn độ dài (DR-07 mục 12), `policyCasesOnTheRealGraph` (R05 và R08 trên graph thật: "không" → "none", "Tôi đang học Javascript" → "I'm learning Vocabulary" và bị gắn cờ JavaScript; giống `marian_ref.py`), `overlongInputIsSplitWithoutLoss` (R09: "a." × 250 = 501 id được tách, không mất chữ, URL không bị cắt) (DR-07 mục 16). Hai test đầu cần `package_mt.py` đã chạy, không có thì bỏ qua ([mt_package_v2.md](contracts/mt_package_v2.md) mục 9) | Phase 04 |
| `text/` | `TextProcessingTest`: `SttPostProcessor` (làm sạch, lọc vô nghĩa), `TtsTextNormalizer` (lexicon, tách đoạn). Phát hiện gõ không dấu nay là R07 trong `mt/rules/` | M3–M4 |
| `speech/` | `ListenSessionTest`: hết lượt khi im 1,5 s, 6 s không có giọng, 60 s | M4 |
| `turn/` | `ConversationReducerTest` (19 test): Cắt, ngắt khi đang đọc, sửa câu, chuyển cuộc, lượt nền; câu gõ và câu sửa được dịch nhưng không tự đọc (R13), literal giữ nguyên không gọi model (R03), bản dịch bị R12 từ chối; fuzz 10 000 chuỗi với các bất biến cũ, thêm "câu gõ không bao giờ tự đọc". `TurnTimingsTest` (2 test): câu gõ chỉ hiện "MT n ms", câu nói hiện đủ STT · MT · TTS | M6, 2026-10-07; 2026-10-23 |
| `history/` | `ConversationStoreTest` (4 test): lưu/đọc, mới nhất trước, đổi tên, xoá không hồi sinh, file hỏng, xoá tất cả, quy tắc id và tiêu đề | 2026-10-07 |
| `ui/` | `AppSmokeTest` (Robolectric): mở app ở Hội thoại; ☰ mở Dịch nhanh, Cài đặt, Hướng dẫn; gõ câu thì câu hiện và được lưu vào ☰; R03 + R13 (gõ "JavaScript": giữ nguyên, có nhãn, không có STT/TTS hay thông báo micro); R06 ("Hello, how are you?" gửi tiếng Việt thì hỏi, "Đổi sang English" gửi bằng tiếng Anh); R02 (ô trống báo "Hãy nhập nội dung cần dịch.", 501 ký tự khoá nút gửi); Dịch nhanh theo cùng rule (ô trống báo lỗi, gõ "CI/CD" thì giữ nguyên, có nhãn, không có STT/TTS) | 2026-10-07; 2026-10-23 |

## 2. Test instrumentation trên AVD

Cần AVD `VTT_API35_4GB` đang chạy:

```bat
gradlew.bat :app:connectedDebugAndroidTest
```

| Test | Mốc |
|---|---|
| Asset smoke: nạp đủ model | M2 |
| Căn thời gian VAD | M4 |
| Luồng UI (đã chuyển sang `AppSmokeTest` chạy trên JVM) | M7 |

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
- [x] **M0:** build được và cài lên AVD (từ 2026-10-07: `assembleRelease`, một bản).
- [ ] **M1:** test parity pass toàn bộ ca.
- [ ] **M2:** bản release nạp đủ 5 engine trên AVD, không lỗi JNI hay R8;
  `check_apk.py` ra `APP_OFFLINE_APK=PASS`. Hiện kiểm quyền bằng
  `aapt2 dump permissions`: chỉ `RECORD_AUDIO`.
- [ ] **M3:** giọng VI và EN đọc được, dừng đọc tức thì; run kéo về ra
  `APP_EVIDENCE=PASS`.
- [ ] **M4:** loopback cả hai ngôn ngữ ra text và có CER/WER; `test_wavs` decode
  ra text; test căn VAD pass.
- [ ] **M5:** trên AVD với host mic: nói VI/EN ra transcript (có partial); bật
  Airplane, nói → dịch → đọc bằng giọng đích, cả hai chiều.
- [ ] **M6:** 10 000 chuỗi giữ đủ bất biến; thử tay: Cắt, chen khi đang đọc,
  "Dừng đọc", gõ, sửa câu (✎).
- [ ] **M7:** lịch sử: tạo cuộc, ＋, mở lại cuộc cũ sau khi tắt app, đổi tên, xoá,
  xoá tất cả; nghe lại được cả hai ngôn ngữ; RAM giảm khi app vào nền.
- [ ] **Điện thoại thật:** cài `app-arm64-v8a-release.apk`, chạy Cài đặt → Kiểm tra
  máy này, chép kết quả vào [bao_cao/thong_so_may_toi_thieu.md](bao_cao/thong_so_may_toi_thieu.md)
  mục 4; thử các trường hợp ở [bao_cao/quy_trinh_va_xu_ly_loi.md](bao_cao/quy_trinh_va_xu_ly_loi.md).

## 5. Kiểm tra offline trên emulator

```bat
adb shell cmd connectivity airplane-mode enable
```

Cài bản release, chạy `model_load`, `tts_prompts`, `loopback`,
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

Với mỗi cấu hình, xem lần lượt: Hội thoại (cả ngăn ☰), Dịch nhanh, Cài đặt,
Hướng dẫn.
