# AppProduct

Ứng dụng Android dịch nói Anh ↔ Việt chạy offline của VietTechTrans:
giọng nói → STT → MT → TTS → giọng nói.

Giai đoạn hiện tại: khung app, STT, TTS, hội thoại hai người và lịch sử. Chưa
có dịch máy trong bản offline: Core MT (hoặc Student) được gắn vào ở Phase 04
qua interface `Translator` và gói MT theo
[docs/contracts/mt_package_v1.md](docs/contracts/mt_package_v1.md). Bản `dev`
dùng tạm ML Kit Translate để thử luồng; bản này không bao giờ dùng làm evidence.

Không sửa `Core/`. Đề xuất cho Core ghi trong [docs/design_check.md](docs/design_check.md).

## Tiến độ

| Mốc | Nội dung | Trạng thái |
|---|---|---|
| M-pre | `artifacts.lock.json`, `fetch_artifacts.py`, fixture parity từ Core, khung tài liệu | xong (2026-09-30) |
| M0 | Cài công cụ, khung Gradle + Compose, flavor, manifest offline | xong (2026-10-01), chờ thử trên máy thật |
| M1 | Interface dịch (`mt/`) và test parity | xong (2026-10-01): 19 test, 544 ca parity với Core |
| M2 | Nối runtime sherpa-onnx, `check_apk.py` | chưa làm |
| M3 | TTS và đường ống evidence | chưa làm |
| M4 | STT trên file WAV | chưa làm |
| M5 | Mic thật, màn Dịch, bộ dịch | chưa làm |
| M6 | Hội thoại hai người (Cắt/Chờ) | chưa làm |
| M7 | Lịch sử, chính sách RAM, cài đặt | chưa làm |
| M8 | Thực nghiệm, Hướng dẫn, báo cáo | chưa làm |

Mỗi mốc xong khi: test JVM xanh, build được cả hai flavor, có DR tương ứng.

## Thư mục

```text
AppProduct/
  artifacts.lock.json   URL, kích thước, SHA-256 của model và AAR
  app/                  module Android duy nhất (:app), package vn.viettechtrans.app   (từ M0)
  gradle/, gradlew*     Gradle wrapper và version catalog                           (từ M0)
  third_party/          AAR sherpa-onnx tải về (không commit)
  .cache/               file tải về (không commit)
  tools/                script Python (chỉ thư viện chuẩn) và tests/
  docs/                 tài liệu (bảng dưới)
  evidence/             build và run đã kéo về PC
  Documents/            sketch, ghi chú gốc (không commit)
```

Model được tải vào `app/src/main/assets/models/` bằng `tools/fetch_artifacts.py`,
không commit.

## Bắt đầu nhanh (Windows, chạy trong `AppProduct/`)

1. Cài công cụ theo [docs/setup_windows.md](docs/setup_windows.md).
2. Tải và kiểm tra model (lần đầu tải khoảng 264 MB):

   ```bat
   ..\.venv\Scripts\python.exe tools\fetch_artifacts.py
   ..\.venv\Scripts\python.exe tools\fetch_artifacts.py --verify
   ```

   Kết quả đúng: `APP_ARTIFACTS=PASS`.
3. Build hai flavor và chạy test (PowerShell; công cụ dòng lệnh đã cài theo
   [docs/setup_windows.md](docs/setup_windows.md)):

   ```powershell
   $env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\Temurin\jdk-21.0.12.1+1"
   $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
   .\gradlew.bat test :app:assembleOfflineDebug :app:assembleDevDebug "-Pvtt.abis=arm64-v8a"
   ```

   `-Pvtt.abis` phải đặt trong dấu nháy khi dùng PowerShell. APK ra ở
   `app/build/outputs/apk/{offline,dev}/debug/app-<flavor>-arm64-v8a-debug.apk`.
4. Cài lên điện thoại: chép APK sang máy rồi mở để cài (cho phép "Cài ứng dụng
   không rõ nguồn gốc"), hoặc `adb install -r <file.apk>` khi đã bật Gỡ lỗi USB.

## Tài liệu

| Tài liệu | Nội dung |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Kiến trúc, luồng xử lý, luồng chạy, xử lý lỗi, máy trạng thái lượt nói |
| [docs/setup_windows.md](docs/setup_windows.md) | Cài Android Studio, SDK, emulator trên máy này |
| [docs/testing.md](docs/testing.md) | Test JVM, test trên AVD, test tool Python, checklist theo mốc |
| [docs/licenses.md](docs/licenses.md) | Giấy phép của mọi thành phần bên thứ ba |
| [docs/design_check.md](docs/design_check.md) | Kiểm tra thiết kế so với Core, gửi anh (bản nháp) |
| [docs/contracts/mt_package_v1.md](docs/contracts/mt_package_v1.md) | Hợp đồng gói MT cho Phase 04 (bản nháp) |
| [docs/contracts/evidence_app_v1.md](docs/contracts/evidence_app_v1.md) | Định dạng evidence của app (bản nháp) |
| [docs/decisions/](docs/decisions/README.md) | Báo cáo quyết định DR-01…DR-08 |
| [docs/experiments/](docs/experiments/README.md) | Thực nghiệm TN-01…TN-07 |
| [docs/data/it_term_candidates.tsv](docs/data/it_term_candidates.tsv) | Ứng viên lexicon TTS, sinh bởi `tools/build_text_fixtures.py` |
| [evidence/README.md](evidence/README.md) | Cách lưu và kiểm evidence |
