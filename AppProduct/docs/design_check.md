# Kiểm tra thiết kế AppProduct so với Core

**Bản nháp — cập nhật ở M8.** Gửi: anh (phụ trách Core) · Người viết: Đạt ·
Ngày: 2026-09-30 · Core đọc ở commit `124721f`.

Theo sketch2: đọc lại Phase 03–05, README và sơ đồ; kiểm tra lại thiết kế (quy
trình app sẽ code, thông số dùng cho sản phẩm, giống và khác so với thiết kế
của anh); báo anh chi tiết. Em cần anh xem mục 6 (hợp đồng Phase 04), mục 7
(phát hiện trong Core) và trả lời mục 8.

## 1. Tài liệu đã đọc

| Nhóm | File trong `Core/` |
|---|---|
| Tổng quan, sơ đồ | `README.md`; `docs/project_workflow.svg`; `docs/phase_03/workflow.svg`, `docs/phase_04/workflow.svg`, `docs/phase_05/workflow.svg` (Phase 03–05 hiện chỉ có sơ đồ, chưa có README) |
| Phase 01 | `docs/phase_01/README.md`, `implementation.md`; `tools/build_phase01.py` (`norm`, `CONTROL`) |
| Phase 02 | `docs/phase_02/README.md`, `final_report.md`, `implementation.md`, `workflow.md`, `selection_record.md`, `lora_hardware_feasibility.md` |
| Quy ước | `docs/run_snapshot_policy.md`, `docs/provenance_and_reproducibility.md`, `docs/environment.md`, `.gitattributes`, `environment.yml` |
| Code, test | `src/core_mt/{contracts, baseline, adaptation, data, metrics}.py`; `tests/test_phase02_pretrained_baseline.py`, `tests/test_phase02_adaptation.py` |
| Config, evidence | `configs/phase02_models.json`, `configs/phase02_protocol.json`; `evidence/phase02/core_mt_decision.json`; `evidence/phase02/final/*/*/{metrics,run}.json`; `evidence/phase02/envit5/*/adapted/*/training_run.json` |
| Model | `models/envit5/840bc88104d5a4277af740eaedb024df8c3093e7/` (config, `spiece.model`, `tokenizer.json`) |

## 2. Quy trình app sẽ code

### 2.1 Một người — màn Dịch

1. Chọn chiều bằng hai nút "Việt → Anh" / "Anh → Việt" (lưu lại; không tự nhận
   diện ngôn ngữ).
2. Nói (chạm để bắt đầu, chạm lại để dừng; tự dừng khi im lặng 1.5 s hoặc đủ
   60 s) hoặc gõ (tối đa 1000 ký tự).
3. VAD cắt đoạn có giọng, STT theo ngôn ngữ nguồn; chữ tạm thời (partial) hiện
   trong lúc nói.
4. Làm sạch từ đệm và từ lặp, rồi lọc kết quả vô nghĩa (không lưu).
5. Chuẩn hoá đầu vào như `norm()` của Phase 01, gọi bộ dịch.
6. TTS đọc bản dịch bằng giọng của ngôn ngữ đích; lưu text vào lịch sử (không
   lưu audio).
7. Nếu STT nhận sai: sửa transcript, bấm "Dịch lại"; tạo dòng lịch sử mới.

### 2.2 Hai người — màn Hội thoại

- Màn hình chia đôi: A nói tiếng Việt (nửa dưới), B nói tiếng Anh (nửa trên,
  xoay 180°). Mỗi người một nút nói nên ngôn ngữ đầu vào luôn biết trước.
- Mỗi lượt đi đúng bước 2–6 ở trên; bản dịch đọc bằng giọng của người nghe.
- Half-duplex: mic tắt khi TTS đọc; chạm để dừng đọc.
- Chen lời, chọn trong Cài đặt:

  | Tình huống | Cắt (mặc định) | Chờ |
  |---|---|---|
  | B chạm khi A đang nói | Lượt A chạy nền (vẫn STT → MT → lưu, không phát, nhãn "chưa phát"); mic chuyển sang B ngay | B vào hàng chờ; chạm lần nữa thì huỷ |
  | Ai đó chạm khi TTS đang đọc | Dừng đọc, mở mic cho người chạm | Xếp hàng chờ |
  | TTS đọc xong | Về Idle | Mở mic cho người đang chờ, rung nhẹ báo |

- Bất biến, kiểm bằng fuzz test 10 000 chuỗi sự kiện: mic mở thì không có TTS
  phát; tối đa một người đang được thu âm (`Listening`); mỗi lượt kết thúc đúng một lần và
  lưu tối đa một lần; lượt đã bỏ phát thì không bao giờ phát.

Luồng chạy, nạp model theo màn và bảng xử lý lỗi: [architecture.md](architecture.md).

## 3. Thông số sản phẩm (đầu ra)

### 3.1 Nền tảng

| Mục | Giá trị |
|---|---|
| Ngôn ngữ, UI | Kotlin, Jetpack Compose; một module Gradle `:app` |
| Android | minSdk 26; compile/target SDK theo template Android Studio, ghim trong `gradle/libs.versions.toml` (chốt ở M0) |
| ABI | arm64-v8a, x86_64 (emulator); ABI split; không hỗ trợ 32-bit |
| Flavor | `offline` (không có quyền INTERNET), `dev` (thêm ML Kit, không dùng làm evidence) |
| Máy test | AVD API 35, RAM 4 GB (`VTT_API35_4GB`); nhắm máy khoảng 4 GB RAM; chưa có máy thật |

### 3.2 Model và runtime

Hash chính xác của từng file: `artifacts.lock.json`.

| Vai trò | Gói | Dung lượng | License |
|---|---|---|---|
| Runtime | `sherpa-onnx-static-link-onnxruntime-1.13.8.aar` | 38.7 MB | Apache-2.0; ONNX Runtime MIT; espeak-ng GPL-3.0 (link tĩnh) |
| VAD | `silero_vad.onnx` | 0.6 MB | MIT |
| STT VI | `sherpa-onnx-zipformer-vi-30M-int8-2026-02-09` | 32.3 MB | CC BY-NC-ND 4.0 (phi thương mại, không sửa) |
| STT EN | `sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27` (dự phòng: zipformer-en) | 42.2 MB | MIT |
| TTS VI | `vits-piper-vi_VN-vais1000-medium-int8` | 17.7 MB | Dữ liệu CC BY 4.0; fine-tune từ giọng lessac (dữ liệu chỉ cho nghiên cứu) → chỉ dùng cho đồ án phi thương mại |
| TTS EN | `vits-piper-en_US-ljspeech-medium-int8` | 18.5 MB | Dữ liệu public domain |
| espeak-ng-data | chỉ giữ từ điển vi và en | 0.95 MB (bản đủ 18 MB) | GPL-3.0 |
| Khử nhiễu (chỉ TN-02) | `gtcrn_simple.onnx` | 0.54 MB | MIT |
| MT | gói theo `mt_package_v1` (Phase 04) | chưa có | theo gói |

Ngày 2026-10-01 (người dùng yêu cầu app nhẹ hơn): đổi STT VI sang bản 30M, TTS sang int8, bỏ `noCompress` → asset 118.2 MB, APK release arm64: offline 104.2 MB, dev 120.4 MB (trước: 272.9 / 290.4 MB bản debug). Đo ở M-pre: asset 250.2 MB (260 file); thư viện native trong AAR (chưa nén)
24.2 MB cho arm64-v8a, 27.4 MB cho x86_64. Kích thước APK thật đo bằng
`tools/check_apk.py` ở M2.

### 3.3 Giá trị khởi điểm (ghi vào mọi evidence, tinh chỉnh bằng TN)

| Tham số | Giá trị |
|---|---|
| VAD threshold | 0.5 |
| minSilence | 0.6 s |
| maxSpeech | 20 s (mặc định Kotlin là 5 s, làm cắt câu) |
| Im lặng kết thúc lượt | 1.5 s |
| Pre-roll / post-roll | 0.4 s / 0.2 s |
| Chu kỳ partial | 0.5 s |
| numThreads VAD / STT / TTS | 1 / 2 / 2 |
| TTS chunk | ≤ 25 từ |
| Watchdog MT | 10 s |
| Không nghe thấy giọng / lượt tối đa / text gõ tối đa | 6 s / 60 s / 1000 ký tự |
| Giải phóng model ngoài bộ của màn | sau 30 s |

### 3.4 Chỉ số ghi cho mỗi lượt

`stt_ms`, `mt_ms`, `tts_first_audio_ms`, `total_ms`, `response_ms`, `cpu_ms`,
`vmrss_kb`, `vmhwm_kb`, PSS, cùng thiết bị, APK và phiên bản model. Định nghĩa
mốc đầu–cuối: [contracts/evidence_app_v1.md](contracts/evidence_app_v1.md).

## 4. Giống thiết kế Phase 04/05 của anh

| Yêu cầu trong sơ đồ của anh | App làm |
|---|---|
| Mỗi thành phần chạy cục bộ; không dùng system service/API nếu không chứng minh được offline (Phase 05) | STT, TTS, VAD chạy trong tiến trình app bằng sherpa-onnx; không dùng `SpeechRecognizer`/`TextToSpeech` của hệ thống. Bản offline không có quyền INTERNET, `check_apk.py` kiểm |
| Có EN và VI, Android, license rõ; model không cần network (Phase 05) | Hai STT, hai TTS cho VI và EN; license ghi ở `licenses.md` |
| Đưa STT, MT, TTS và asset vào app; không tải model lần đầu (Phase 05) | Mọi model nằm trong APK; espeak-ng-data copy từ assets, không tải mạng |
| Chọn chiều và ngôn ngữ đầu vào rõ; Audio → STT → MT → TTS → audio (Phase 05) | Hai nút chọn chiều; mỗi người một nút nói; luồng ở mục 2 |
| Tắt Wi-Fi và mobile data, test hai chiều trên máy thật (Phase 05) | Mỗi run ghi `airplane_mode_on`; `APP_OFFLINE_E2E` chỉ PASS khi có bản offline release + Core MT + Airplane + máy thật + hai chiều |
| Ghi thời gian STT, MT, TTS, tổng; ghi thiết bị, APK, model version (Phase 05) | `run.json` + `items.jsonl` |
| Đặt model, tokenizer, config trong app; xác định runtime và ABI; không dùng API hoặc tải model lúc chạy (Phase 04) | Gói `assets/mt/` có `manifest.json` khai runtime, ABI, tokenizer, cách sinh |
| So output export với checkpoint gốc (Phase 04) | `quality.exported` trong manifest gói: chất lượng bản export trên IT Validation, đặt cạnh checkpoint (chốt ở Phase 04); golden file sinh từ bản ONNX export, app chạy lại golden trên máy |
| Đo tải model, latency, RAM; ghi dung lượng app/model (Phase 04) | Suite `model_load`, `mt_ms`, PSS/VmHWM; `check_apk.py` ghi kích thước APK |
| Quy ước evidence (`run_snapshot_policy.md`) | `<run-id>.running` rồi đổi tên; JSON UTF-8 LF; SHA-256; marker PASS/WAITING/FAIL |
| Mã chiều và hợp đồng MT của Phase 02 | Dùng `en_to_vi` / `vi_to_en`; tiền tố, cách sinh và bỏ tiền tố như `baseline.py` |

## 5. Khác thiết kế của anh

| Khác biệt | Lý do | Ảnh hưởng |
|---|---|---|
| STT/TTS (Phase 05) làm trước Phase 03/04; MT gắn sau qua `Translator` và gói MT | Adapter và Student chưa có trên máy em | Evidence giai đoạn này chưa có MT; `APP_OFFLINE_E2E=WAITING` |
| ML Kit Translate trong bản `dev` | Thử luồng hội thoại khi chưa có Core MT | Cần mạng một lần để tải gói (~30 MB); không bao giờ là evidence; APK offline không có ML Kit (`check_apk.py` kiểm) |
| Test trên emulator | Chưa có máy thật | Số liệu emulator chỉ kiểm tra chức năng; Phase 05 vẫn cần máy thật |
| Thêm hội thoại hai người, chen lời, lịch sử (text), Hướng dẫn trong app, màn Thực nghiệm và TN-01…07 | Yêu cầu trong sketch; sơ đồ Core không có | Thêm DR và TN; không đổi hợp đồng MT |
| Transcript sửa được, có "Dịch lại" | Xử lý khi STT nhận sai | Mỗi lần sửa tạo dòng lịch sử mới; evidence ghi cờ `edited` |
| Ký tự điều khiển và U+FFFD bị xoá, không loại cả câu | Phase 01 loại dòng; app không loại được input của người dùng | Với text không có các ký tự này, kết quả giống `norm()` (kiểm bằng fixture) |

## 6. Hợp đồng Phase 04 (tóm tắt)

Chi tiết: [contracts/mt_package_v1.md](contracts/mt_package_v1.md).

- Gói ở `app/src/main/assets/mt/`: `manifest.json`, `tokenizer/spiece.model`,
  ONNX encoder và decoder mỗi chiều, `golden/<chiều>.jsonl`, `LICENSE_NOTES.md`.
  Không commit; ghim trong `artifacts.lock.json`, cài bằng `fetch_artifacts.py`.
- Runtime ONNX Runtime Android (CPU), ABI arm64-v8a và x86_64; `model_role` là
  `core_mt` hoặc `student_mt`.
- Đầu vào: chuẩn hoá `phase01_nfc_whitespace_v1`, ghép `en:` / `vi:` liền với
  text (không dấu cách), SentencePiece, thêm eos 1, không BOS.
- Sinh: greedy, `num_beams` 1, `max_length` 512, `decoder_start_token_id` 0,
  `eos_token_id` 1.
- Đầu ra: bỏ id 0–2 và 50000–50047, decode, bỏ đúng tiền tố `vi:` / `en:` và
  một khoảng trắng.
- Golden `{id, source, expected}` sinh bằng bản ONNX export chạy Python trên IT
  Validation; không dùng IT Test hay General Test.
- Hash trên byte LF; đổi byte nào thì đổi `package_id`; app nhận
  `schema_version` major 1.
- Phase 04 chỉ sửa: `mt/**`, dependency ONNX Runtime, `assets/mt/`, mục lock.

## 7. Phát hiện trong Core

1. **CRLF làm lệch hash đã ghi.**
   - Máy em có `core.autocrlf=true`; `git ls-files --eol` báo `i/lf w/crlf` cho
     mọi file trong `evidence/`, `configs/`, `src/`, `tools/`.
   - `evidence/phase02/core_mt_decision.json`: bản trên đĩa băm ra
     `7e73e2d6df4a…`, blob LF là `1791078e2414…` (giá trị ghi trong `run.json`
     của final). `tools/build_phase01.py`: `66663a8a…` so với `a04d1454…`.
   - Hệ quả: validator băm file trên đĩa báo lệch trên Windows dù evidence đúng.
   - Phase 01 thì ngược lại: `data/final_report/it_en_vi/final_report_manifest.json`
     ghi hash của byte CRLF, còn `DATASET_CARD.md` đã pin LF nên lệch (341 B trên
     đĩa, manifest ghi 350 B).
   - Đề xuất: thêm vào `Core/.gitattributes` (mỗi pattern một dòng), rồi
     checkout lại các file này (xoá file, `git checkout -- <path>`); chọn một
     quy ước cho `data/final_report/`.

     ```text
     evidence/** text eol=lf
     configs/** text eol=lf
     src/** text eol=lf
     tools/** text eol=lf
     ```

2. **`it_en_vi` và `it_en_vi_v1`.** `src/core_mt/data.py` và
   `configs/phase02_protocol.json` đọc `data/processed/it_en_vi/`, nhưng máy em
   chỉ có `data/processed/it_en_vi_v1/` (bản cũ: train 110 452 dòng, khác train
   121 547 dòng của Phase 02, hash `d6dbad4f…`). Gate Phase 01 và
   `validate_phase02_setup.py` không chạy được ở máy em; `build_text_fixtures.py`
   chỉ lấy được ứng viên lexicon từ `it_en_vi_v1/train.jsonl` (có ghi hash).
3. **Thiếu trên máy em:** hai adapter LoRA
   (`models/phase02/envit5-en-to-vi-lora-v1/final-epoch-1`, `sha256_tree`
   `d96171cb…`; `models/phase02/envit5-vi-to-en-lora-v1/final-epoch-1`,
   `3d2475f0…`), `data/processed/it_en_vi/`,
   `data/processed/general_validation_en_vi/` và các `predictions.jsonl` của
   Phase 02. `manual/phase_02.md` chạy lệnh từ `C:\Users\ADMIN\ENVI-IT-MT`. Em
   cần chúng để thử export, sinh golden trên IT Validation và thử câu thật.
4. **`environment.yml`:** dòng 11–14 (`ipykernel`, `nbformat`, `nbconvert`,
   `matplotlib`) thụt vào dưới `- jupyterlab=4.5.9`; PyYAML đọc thành một chuỗi
   `"jupyterlab=4.5.9 - ipykernel=6.31.0 - …"`, nên `conda env create` có thể
   lỗi. Comment "Python 3.14's _socket DLL is blocked…" không đúng ở máy em
   (`.venv` Python 3.14.7 import `socket`, `ssl` bình thường).
5. **NFD và `tokenizer.json`.** `AutoTokenizer` nạp `T5TokenizerFast`
   (`tokenizer.json`). Với chữ Việt dạng NFD, id sai: `vi:Cài đặt gói.` cho 3388
   (`▁đạt`) thay cho 1991 (`▁đặt`); "Kiểm tra lỗi hệ thống" decode thành "Kiêm
   tra lôi hẹ thông". `spiece.model` cho đúng id với cả NFC và NFD. Dữ liệu
   Phase 01 đã NFC nên Phase 02 không bị ảnh hưởng; script export và golden ở
   Phase 04 cần đưa text NFC vào tokenizer (app đã làm).
6. **Revision base model.** `PretrainedAdapter.load()` nạp base bằng model id,
   không ghim revision (`baseline.py` dòng 95). Khi export hoặc sinh golden nên
   trỏ tới snapshot `models/envit5/840bc88…` hoặc ghim revision.
7. **Comment trong `Core/.gitattributes`:** "Phase 05 stores byte-level
   checksums…" dùng cách đánh số phase dữ liệu cũ (như tên release cũ
   `phase05_release_v1`), dễ nhầm với Phase 05 hiện tại (STT → MT → TTS).
8. **`sha256_tree` phụ thuộc hệ điều hành.** `adaptation.py` dòng 44 sắp file
   bằng `sorted()` trên đối tượng `Path`. Trên Windows phép so sánh này không
   phân biệt hoa thường, trên Linux/macOS thì có. Thư mục checkpoint có
   `README.md` (PEFT tạo) nằm cạnh các file chữ thường, nên thứ tự băm khác nhau
   giữa hai hệ điều hành. Vì vậy `d96171cb…` / `3d2475f0…` (tính trên Windows)
   có thể không tái lập được trên Linux hoặc Colab.
   - Đo trên `espeak-ng-data` của app: thứ tự POSIX cho `10cc7219…`, thứ tự không
     phân biệt hoa thường cho `d899bebd…`.
   - Đề xuất: với run mới, sắp theo chuỗi `relative_to(...).as_posix()` (phân
     biệt hoa thường). Với checkpoint Phase 02, ghi rõ "tính trên Windows" và kiểm
     bằng chính hàm của Core trên Windows.

## 8. Câu hỏi cho anh

1. Gói MT: hai model merged, mỗi chiều khoảng 317 MB (int8)? Màn Hội thoại cần
   cả hai chiều cùng STT/TTS, ước tính từ 1 GB RAM trở lên. Hay dùng Student,
   hay một base dùng chung cộng adapter LoRA chạy bằng ONNX Runtime?
2. Student có giữ tiền tố `en:` / `vi:` và tokenizer của EnViT5 không?
3. `max_input_tokens`: giữ không giới hạn như Phase 02 hay đặt giới hạn cho máy
   yếu? Nếu đặt, tách câu như `_split_source` được không?
4. Golden file lấy từ IT Validation: anh đồng ý không? Bao nhiêu dòng, ai sinh?
5. MT có chịu được text kiểu STT (không dấu câu, chữ thường trừ chữ đầu, số đọc
   thành chữ) không? Phase 03 có tăng cường dữ liệu kiểu này không?
6. Gói MT sẽ để ở đâu: URL để ghi vào `artifacts.lock.json`, hay chép tay rồi
   dùng `fetch_artifacts.py --from-dir`?
7. Ai viết `CoreMtTranslator` (Kotlin, trong package `mt/`)?

## 9. Việc tiếp theo

| Việc | Người phụ trách | Hạn |
|---|---|---|
| Gửi bản nháp này cho anh | Đạt | … |
| Trả lời mục 8 | anh | … |
| Chuyển hai adapter LoRA, `it_en_vi`, `general_validation_en_vi` cho Đạt (kiểm `sha256_tree`) | anh | … |
| Quyết định các đề xuất ở mục 7 | anh | … |
| Chốt `mt_package_v1` | anh, Đạt | Phase 04 |
| Viết `CoreMtTranslator` | theo câu 8.7 | Phase 04 |
| Cập nhật tài liệu này | Đạt | M8 |
