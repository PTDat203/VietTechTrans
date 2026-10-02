# Hợp đồng evidence của app v1 (`app-evidence/1`)

**Bản nháp — hoàn thiện ở M3.** Tên trường, giá trị `status` và cấu trúc
`summary.json` có thể đổi đến khi M3 xong.

## 1. Mục đích

Ghi số đo của app (thời gian, bộ nhớ, dung lượng, phiên bản) thành file kiểm lại
được trên PC. Theo quy ước evidence của Core (`Core/docs/run_snapshot_policy.md`):
ghi vào thư mục `.running` rồi đổi tên khi xong; JSON UTF-8, LF; SHA-256; marker
`…=PASS|WAITING|FAIL`. Đáp ứng yêu cầu Phase 05 của Core: ghi thời gian STT, MT,
TTS, tổng; ghi thiết bị, APK và model version.

## 2. Thư mục run

Trên máy, app ghi vào `getExternalFilesDir("evidence")`, tức
`/sdcard/Android/data/<applicationId>/files/evidence/`:

```text
files/evidence/
  <run-id>.running/    đang ghi; app bị đóng giữa chừng thì giữ nguyên, không bao giờ là evidence
  <run-id>/            đổi tên khi suite chạy xong
    run.json
    items.jsonl
```

Trên PC, run được kéo về `AppProduct/evidence/runs/<run-id>/`; validator thêm
`summary.json` (ghi một lần, không ghi đè).

## 3. Run ID

`<yyyyMMddTHHmmssZ>_<flavor>-<buildType>_<device>_<suite>`

| Phần | Giá trị |
|---|---|
| `yyyyMMddTHHmmssZ` | Thời điểm bắt đầu, UTC |
| `flavor-buildType` | `offline-release`, `offline-debug`, `dev-debug`… |
| `device` | `Build.MODEL` chữ thường, ký tự ngoài `[a-z0-9]` đổi thành `-` (không có `_`) |
| `suite` | `model_load`, `tts_prompts`, `loopback`, `wav_turns`, `session` |

Ví dụ: `20261015T083012Z_offline-release_sdk-gphone64-x86-64_model_load`. Tách
run ID bằng `_` tối đa 3 lần (tên suite có thể chứa `_`).

## 4. `run.json`

UTF-8, LF, thụt lề 2 dấu cách, kết thúc bằng ký tự xuống dòng, thứ tự khoá cố định.

| Trường | Nội dung |
|---|---|
| `schema_version` | `"app-evidence/1"` |
| `run_id`, `suite` | Như mục 3 |
| `created_at_utc`, `finished_at_utc` | ISO-8601 có `+00:00` |
| `experiment_tag` | Mã thực nghiệm (`"TN-02"`) hoặc `null` |
| `command` | `"ui"` hoặc lệnh `am start` kèm extras |
| `app` | `application_id`, `flavor`, `build_type`, `version_name`, `version_code`, `git_sha`, `git_dirty`, `apk_sha256` (SHA-256 của APK đang cài, tính lúc bắt đầu run) |
| `device` | `manufacturer`, `model`, `soc_model` (API 31+, nếu thấp hơn thì `null`), `sdk_int`, `abis`, `total_mem_bytes`, `is_low_ram_device`, `cpu_cores`, `is_emulator`, cùng `build_fingerprint`, `hardware`, `product` làm căn cứ cho `is_emulator` |
| `network` | `airplane_mode_on`, `internet_permission_requested` |
| `translator` | `null` hoặc `{kind: "core_mt" \| "mlkit_dev", id, version, package_id}` (`package_id` chỉ có với `core_mt`) |
| `models` | `lock_sha256` và `files[{path, bytes, sha256}]` chép từ `assets/models/manifest.json` (không băm lại trên máy) |
| `settings` | Mọi tham số, bảng 4.1 |
| `memory` | `start{pss_kb, vmrss_kb}`, `end{pss_kb, vmrss_kb, vmhwm_kb}` |
| `storage` | `app_bytes`, `data_bytes`, `cache_bytes` của gói app (`StorageStatsManager`) |
| `exit_reasons` | Các lần hệ thống đóng app gần nhất (`getHistoricalProcessExitReasons`, API 30+; `null` nếu thấp hơn): `[{time_utc, reason, description, pss_kb, rss_kb}]` |
| `items_file`, `items_count` | `"items.jsonl"` và số dòng |

### 4.1 `settings` — giá trị khởi điểm, cần đo

| Khoá | Khởi điểm | Ghi chú |
|---|---|---|
| `vad_threshold` | 0.5 | |
| `vad_min_silence_s` | 0.6 | Im lặng tách đoạn |
| `vad_max_speech_s` | 20 | Đoạn nói dài hơn thì tự tách; mặc định Kotlin 5 s làm cắt câu |
| `end_of_turn_silence_s` | 1.5 | TN-03 thử 1.0 / 1.5 / 2.0 |
| `pre_roll_s`, `post_roll_s` | 0.4, 0.2 | |
| `partial_interval_s` | 0.5 | |
| `num_threads.vad`, `.stt`, `.tts` | 1, 2, 2 | TN-06 |
| `tts_chunk_max_words` | 25 | |
| `mt_watchdog_s` | 10 | |
| `no_speech_timeout_s` | 6 | |
| `max_turn_s` | 60 | |
| `typed_text_max_chars` | 1000 | |
| `model_release_grace_s` | 30 | |
| `interrupt_policy` | `"cut"` | Tuỳ chọn `"queue"` |
| `auto_speak`, `partial_text` | (chốt ở M5) | |
| `disfluency_cleanup` | (chốt ở M4) | TN-03 |
| `tts_lexicon` | (chốt ở M3) | TN-07 |
| `denoiser_gtcrn` | `false` | Chỉ bật cho TN-02 |

## 5. `items.jsonl`

Mỗi dòng một object JSON (UTF-8, LF). Trường không áp dụng thì ghi `null`.

| Trường | Nội dung |
|---|---|
| `item` | Số thứ tự, từ 1 |
| `status` | `ok`, `no_speech`, `meaningless`, `no_translator`, `mt_failed`, `load_failed`, `cancelled` (chốt ở M5) |
| `lang` hoặc `direction` | `vi`/`en`, hoặc `en_to_vi`/`vi_to_en` |
| `input` | `{kind: "mic" \| "wav" \| "text" \| "prompt", ref}` (tên file WAV, id câu) |
| `ref_text` | Câu chuẩn nếu có (để tính CER/WER trên PC) |
| `stt_raw`, `stt_text` | Text STT thô và sau làm sạch |
| `target_text` | Bản dịch hoặc text được đọc |
| `flags` | Ví dụ `edited`, `interrupted`, `tts_skipped` |
| Chỉ số | Các trường ở mục 6 |

Suite `model_load` ghi mỗi lần nạp một dòng: `role`, `repeat`, `load_wall_ms`,
`load_cpu_ms`, `pss_before_kb`, `pss_after_kb`, `vmhwm_kb`; thêm dòng cho cả bộ
model của màn Dịch, của màn Hội thoại và lần giải phóng (RSS giảm bao nhiêu).

## 6. Định nghĩa thời gian và tài nguyên

Đồng hồ: `SystemClock.elapsedRealtimeNanos()`, đổi ra ms (số thực). CPU:
`Process.getElapsedCpuTime()`. Bộ nhớ: `VmRSS`, `VmHWM` từ `/proc/self/status`;
PSS từ `Debug.MemoryInfo`, đọc ngoài đoạn đo thời gian.

| Mốc | Định nghĩa |
|---|---|
| `t_capture_start` | Mic mở (lần đọc `AudioRecord` đầu tiên) hoặc bắt đầu đưa WAV |
| `t_speech_start` | Mẫu có giọng đầu tiên (đầu đoạn VAD đầu tiên) |
| `t_speech_end` | Mẫu có giọng cuối cùng (cuối đoạn VAD cuối cùng) |
| `t_capture_end` | Dừng thu: chạm dừng, im lặng kết thúc lượt, hết 60 s hoặc hết WAV |
| `t_stt_final` | Có text cuối, sau `SttPostProcessor` và `MeaningfulFilter` |
| `t_mt_start`, `t_mt_end` | Gọi và nhận kết quả `translate()` |
| `t_tts_start` | Text được giao cho TTS (bằng `t_mt_end` khi có bộ dịch) |
| `t_first_audio` | Mẫu PCM đầu tiên ghi vào `AudioTrack`; khi đo không phát (`wav_turns`, `tts_prompts`): lúc chunk đầu tiên được sinh |
| `t_turn_end` | Điểm phát xong (marker của `AudioTrack`), hoặc lúc lưu nếu không phát |

| Chỉ số | Cách tính |
|---|---|
| `stt_ms` | `t_stt_final − t_capture_end` |
| `stt_compute_ms` | Tổng thời gian các lần decode cuối của lượt (không tính partial) |
| `stt_rtf` | `stt_compute_ms` / độ dài audio đã decode |
| `first_partial_ms` | Lúc có partial đầu tiên − `t_speech_start` (`null` khi tắt partial) |
| `partial_count` | Số lần decode partial trong lượt |
| `mt_ms` | `t_mt_end − t_mt_start` (`null` khi không có bộ dịch) |
| `tts_first_audio_ms` | `t_first_audio − t_tts_start` |
| `tts_synth_ms` | Tổng thời gian sinh của mọi chunk |
| `tts_audio_ms` | Số mẫu sinh ra / sample rate × 1000 |
| `tts_rtf` | `tts_synth_ms / tts_audio_ms` |
| `endpoint_wait_ms` | `t_capture_end − t_speech_end` |
| `total_ms` | `t_first_audio − t_capture_end` |
| `response_ms` | `t_first_audio − t_speech_end` (= `endpoint_wait_ms + total_ms`) |
| `cpu_ms` | CPU của tiến trình từ `t_capture_start` đến `t_turn_end` |
| `vmrss_kb`, `vmhwm_kb` | Đọc ở `t_turn_end` |
| `pss_kb` | PSS toàn tiến trình, đọc sau khi item kết thúc |

Lượt gõ phím không có các chỉ số STT (ghi `null`).

## 7. Bộ đo (suite)

Chạy từ màn Thực nghiệm hoặc bằng
`adb shell am start -n vn.viettechtrans.app/.MainActivity -a vn.viettechtrans.action.BENCH --es suite <tên>`
(tên activity chốt ở M3). WAV đẩy vào `files/bench/wav/<vi|en>/` bằng
`adb push` (chốt ở M4).

| Suite | Làm gì |
|---|---|
| `model_load` | Nạp mỗi engine 5 lần; so bộ model màn Dịch với màn Hội thoại; giải phóng và đo RSS giảm |
| `tts_prompts` | 20 câu VI + 20 câu EN viết tay (không lấy từ dataset để tránh vấn đề license); đo `tts_*` |
| `loopback` | TTS → STT trong app, không cần mic; chỉ kiểm tra chức năng (giọng tổng hợp) |
| `wav_turns` | WAV đi qua đúng luồng thật: VAD, partial, làm sạch, bộ dịch nếu có, TTS không phát |
| `session` | Ghi các lượt thật khi bật "Ghi đo đạc", gồm cả lượt bị lọc và bị ngắt |

## 8. Marker

| Marker | Tool | Ý nghĩa |
|---|---|---|
| `APP_EVIDENCE=PASS` / `FAIL <lý do>` | `tools/validate_app_evidence.py` | Schema, UTC, LF đúng; `apk_sha256` khớp `evidence/builds/*.json`; bản offline không xin INTERNET. Run của bản `dev` không bao giờ là evidence |
| `APP_OFFLINE_APK=PASS` / `FAIL <lý do>` | `tools/check_apk.py` | Không có INTERNET/ACCESS_NETWORK_STATE; không có class ML Kit; ABI ⊆ {arm64-v8a, x86_64}; căn trang 16 KB; hash asset khớp lock. Chạy trên APK dev phải ra FAIL |
| `APP_OFFLINE_E2E=WAITING reason=no_core_mt,no_real_device` | `tools/validate_app_evidence.py` | Chỉ PASS khi có run của bản offline release + Core MT + Airplane mode + máy thật + cả hai chiều |

Run có `is_emulator = true` chỉ kiểm tra chức năng: `summary.json` ghi rõ, và số
liệu tốc độ hay RAM của emulator không dùng để kết luận cho máy thật.

## 9. Luồng trên PC

Chạy trong `AppProduct/`:

```bat
adb pull /sdcard/Android/data/vn.viettechtrans.app/files/evidence/<run-id> evidence/runs/
..\.venv\Scripts\python.exe tools\validate_app_evidence.py evidence\runs\<run-id>
```

Validator kiểm mục 8, tính CER/WER khi có `ref_text`, ghi `summary.json` và in
marker. Chỉ commit run có `APP_EVIDENCE=PASS`.
