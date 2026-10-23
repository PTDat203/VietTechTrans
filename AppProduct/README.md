# AppProduct

Ứng dụng Android dịch nói Anh ↔ Việt chạy offline của VietTechTrans:
giọng nói → STT → MT → TTS → giọng nói.

Giai đoạn hiện tại: hội thoại hai người, lịch sử kiểu ChatGPT (☰, ＋, sửa câu đã gửi),
Dịch nhanh, và **dịch offline bằng Main-Core của Core** (Student MarianMT Phase 03,
chuyển sang ONNX 4-bit, chạy bằng ONNX Runtime trong app). Gói MT theo
[docs/contracts/mt_package_v2.md](docs/contracts/mt_package_v2.md), dựng bằng
`tools/mt/package_mt.py`. Chỉ có **một bản build, chạy offline hoàn toàn** (APK không có
quyền Internet); bản `dev` dùng ML Kit đã bỏ ngày 2026-10-07
([DR-09](docs/decisions/DR-09_thu_gon_app.md)).

Không sửa `Core/`. Đề xuất cho Core ghi trong [docs/design_check.md](docs/design_check.md).

## Tiến độ

| Mốc | Nội dung | Trạng thái |
|---|---|---|
| M-pre | `artifacts.lock.json`, `fetch_artifacts.py`, fixture parity từ Core, khung tài liệu | xong (2026-09-30) |
| M0 | Cài công cụ, khung Gradle + Compose, manifest offline | xong (2026-10-01) |
| M1 | Interface dịch (`mt/`) và test parity | xong (2026-10-01): 19 test, 544 ca parity với Core |
| M2 | Nối runtime sherpa-onnx, `check_apk.py` | runtime xong (2026-10-01); còn `check_apk.py` |
| M3 | TTS và đường ống evidence | TTS xong (int8, phát song song); còn bộ đo evidence |
| M4 | STT trên file WAV | STT mic xong (VAD, chữ tạm thời); còn bộ đo WAV/loopback |
| M5 | Mic thật, màn Dịch, bộ dịch | xong (2026-10-02): màn Dịch có nút ⇄ |
| M6 | Hội thoại hai người | xong (2026-10-02); thu gọn 2026-10-07 (chỉ Cắt, chỉ khung chat, [DR-09](docs/decisions/DR-09_thu_gon_app.md)) |
| Phase 04 (app) | Gắn Main-Core MT | xong (2026-10-07); 2026-10-08 chuyển 4-bit khối 16 + lớp đầu ra 4+4 bit, 82,5 MB/chiều: chạy nhanh cả trên máy không có dotprod (8-bit mất 15–30 s/câu), chrF++ chỉ thấp hơn 8-bit 0,04–0,14 (DR-07 §13–14). 2026-10-23: áp dụng chính sách rule dịch text v1 của nhóm (R01–R13, `docs/contracts/mt_app_rule_policy_v1.md`): thuật ngữ, URL, code gõ riêng được giữ nguyên; câu có thuật ngữ bị model làm hỏng được gắn "Cần kiểm tra thuật ngữ" thay vì bị vá; bản dịch làm mất URL/code bị từ chối; câu gõ không tự đọc; evidence ba tầng trong `evidence/phase04/mt_rules_v1` (DR-07 §16; lớp `TermProtector` của §15 đã gỡ). Bản 8-bit cũ: chrF++ lệch ≤ 0,04 so với Student Phase 03 ([evidence](evidence/phase04/mt_export_q8/run.json)); chạy offline trên AVD |
| M7 | Lịch sử, chính sách RAM, cài đặt | xong (2026-10-07): lịch sử JSON ([DR-06](docs/decisions/DR-06_lich_su.md)), sửa câu, Kiểm tra máy trong Cài đặt |
| M8 | Hướng dẫn, báo cáo | Hướng dẫn trong app và bảng báo cáo xong (2026-10-07); màn Thực nghiệm đã bỏ (DR-09); chờ số đo trên điện thoại thật |

Mỗi mốc xong khi: test JVM xanh, build release được, có DR tương ứng.

## Thư mục

```text
AppProduct/
  artifacts.lock.json   URL, kích thước, SHA-256 của model và AAR
  app/                  module Android duy nhất (:app), package vn.viettechtrans.app   (từ M0)
  gradle/, gradlew*     Gradle wrapper và version catalog                           (từ M0)
  third_party/          AAR sherpa-onnx tải về (không commit)
  .cache/               file tải về (không commit)
  tools/                script Python (chỉ thư viện chuẩn) và tests/
  tools/mt/             dựng, kiểm và đóng gói Main-Core MT (cần torch, transformers, onnx, onnxruntime)
  tools/tts/            chuyển giọng Piper sang bản chỉ nén trọng số (fetch_artifacts gọi), so sánh các bản
  docs/                 tài liệu (bảng dưới)
  evidence/             build và run đã kéo về PC
  Documents/            sketch, ghi chú gốc (không commit)
```

Model giọng nói được tải vào `app/src/main/assets/models/` bằng `tools/fetch_artifacts.py`;
gói dịch được dựng vào `app/src/main/assets/mt/` bằng `tools/mt/package_mt.py` từ
`Core/models/phase03/` (cả hai đều không commit).

## Bắt đầu nhanh (Windows, chạy trong `AppProduct/`)

1. Cài công cụ theo [docs/setup_windows.md](docs/setup_windows.md).
2. Tải và kiểm tra model (lần đầu tải khoảng 264 MB):

   ```bat
   ..\.venv\Scripts\python.exe tools\fetch_artifacts.py
   ..\.venv\Scripts\python.exe tools\fetch_artifacts.py --verify
   ```

   Kết quả đúng: `APP_ARTIFACTS=PASS`.
   Dựng gói dịch (cần checkpoint trong `Core/models/phase03/`, khoảng 3 phút):

   ```bat
   ..\.venv\Scripts\python.exe -I -m pip install onnx==1.23.2 onnxruntime==1.30.0
   ..\.venv\Scripts\python.exe -I tools\mt\package_mt.py --variant q4r --quality evidence\phase04\mt_export_q4r
   ```

   Kết quả đúng: `APP_MT_PACKAGE=PASS`. Thiếu gói này thì app vẫn chạy, chỉ hiện câu gốc.
3. Chạy test và build (PowerShell; công cụ dòng lệnh đã cài theo
   [docs/setup_windows.md](docs/setup_windows.md)):

   ```powershell
   $env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\Temurin\jdk-21.0.12.1+1"
   $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
   .\gradlew.bat :app:testDebugUnitTest
   .\gradlew.bat :app:assembleRelease "-Pvtt.abis=arm64-v8a,x86_64"
   ```

   `-Pvtt.abis` phải đặt trong dấu nháy khi dùng PowerShell. APK ra ở
   `app/build/outputs/apk/release/app-<abi>-release.apk` (arm64-v8a cho điện thoại,
   khoảng 300 MB; x86_64 cho máy ảo). Bản release ký bằng khoá debug để cài tay.
4. Cài lên điện thoại: chép APK sang máy rồi mở để cài (cho phép "Cài ứng dụng
   không rõ nguồn gốc"), hoặc `adb install -r <file.apk>` khi đã bật Gỡ lỗi USB.

## Tài liệu

| Tài liệu | Nội dung |
|---|---|
| [app/src/main/assets/manual/huong_dan_vi.md](app/src/main/assets/manual/huong_dan_vi.md) | Hướng dẫn sử dụng (hiện trong app: ☰ → Hướng dẫn), gồm giới hạn đầu vào và lỗi thường gặp |
| [docs/bao_cao/thong_so_may_toi_thieu.md](docs/bao_cao/thong_so_may_toi_thieu.md) | Bảng thông số máy tối thiểu (cho báo cáo) |
| [docs/bao_cao/quy_trinh_va_xu_ly_loi.md](docs/bao_cao/quy_trinh_va_xu_ly_loi.md) | Bảng quy trình sử dụng, lỗi và cách xử lý, giới hạn đầu vào (cho báo cáo) |
| [docs/architecture.md](docs/architecture.md) | Kiến trúc, luồng xử lý, luồng chạy, xử lý lỗi, máy trạng thái lượt nói |
| [docs/setup_windows.md](docs/setup_windows.md) | Cài Android Studio, SDK, emulator trên máy này |
| [docs/testing.md](docs/testing.md) | Test JVM, test trên AVD, test tool Python, checklist theo mốc |
| [docs/licenses.md](docs/licenses.md) | Giấy phép của mọi thành phần bên thứ ba |
| [docs/design_check.md](docs/design_check.md) | Kiểm tra thiết kế so với Core, gửi anh (bản nháp) |
| [docs/contracts/mt_package_v2.md](docs/contracts/mt_package_v2.md) | Gói Main-Core MT: graph ONNX, tokenizer, beam search, lượng tử hoá, kiểm chứng |
| [docs/contracts/evidence_app_v1.md](docs/contracts/evidence_app_v1.md) | Định dạng evidence của app (bản nháp) |
| [docs/decisions/](docs/decisions/README.md) | Báo cáo quyết định DR-01…DR-09 |
| [docs/experiments/](docs/experiments/README.md) | Protocol thực nghiệm TN-01…TN-07 (làm ngoài app; màn Thực nghiệm đã bỏ) |
| [docs/data/it_term_candidates.tsv](docs/data/it_term_candidates.tsv) | Ứng viên lexicon TTS, sinh bởi `tools/build_text_fixtures.py` |
| [evidence/README.md](evidence/README.md) | Cách lưu và kiểm evidence |
