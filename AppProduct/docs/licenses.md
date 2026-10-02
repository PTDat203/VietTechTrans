# Giấy phép thành phần bên thứ ba

Bảng này gồm mọi thành phần bên thứ ba dự kiến dùng trong app. Phiên bản, URL và
SHA-256 chính xác của từng file model và AAR: `artifacts.lock.json`. Nội dung
này cũng hiển thị trong app (Cài đặt → Mô hình & giấy phép; mục Giấy phép trong
Hướng dẫn).

**Giả định mặc định (cần thầy xác nhận):** app chỉ dùng cho đồ án, phi thương
mại. Chấp nhận espeak-ng GPL-3.0 (link tĩnh trong runtime) và giọng Việt có
nguồn gốc dữ liệu chỉ cho nghiên cứu. **Chưa chốt:** khi phát APK cho người
khác, mã nguồn sẽ được cung cấp bằng cách nào (repository công khai, hoặc gửi
kèm mã nguồn cùng APK). Nếu thầy không đồng ý: đổi sang `vivos-x_low`, MMS hoặc
giọng khác (cũng cần kiểm license).

## Thành phần trong app

| Thành phần | Dùng ở | License | Nghĩa vụ, ghi chú |
|---|---|---|---|
| sherpa-onnx 1.13.8 (`sherpa-onnx-static-link-onnxruntime-1.13.8.aar`) | Runtime VAD, STT, TTS (cả hai flavor) | Apache-2.0 | Giữ file license, ghi nguồn |
| ONNX Runtime (link tĩnh trong AAR trên) | Chạy model ONNX | MIT | Giữ thông báo bản quyền |
| espeak-ng (link tĩnh trong AAR trên, qua piper-phonemize) | Chuyển chữ sang âm vị cho giọng Piper | GPL-3.0-or-later | Phát tán APK thì phải cung cấp mã nguồn tương ứng theo GPL-3.0 và app phải theo điều khoản tương thích; cách cung cấp mã nguồn: chưa chốt (xem giả định ở trên) |
| espeak-ng-data (chỉ giữ vi, en) | Dữ liệu cho espeak-ng | GPL-3.0-or-later | Như dòng trên |
| Silero VAD (`silero_vad.onnx`) | Phát hiện giọng nói | MIT | Giữ thông báo bản quyền |
| `sherpa-onnx-zipformer-vi-30M-int8-2026-02-09` (hynt/Zipformer-30M-RNNT-6000h) | STT tiếng Việt | CC BY-NC-ND 4.0 | Chỉ phi thương mại; phân phối nguyên bản, không sửa; ghi công tác giả. Chọn ngày 2026-10-01 thay bản 77 MB (Apache-2.0) để giảm dung lượng |
| `sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27` | STT tiếng Anh | MIT | Giữ thông báo bản quyền |
| `vits-piper-vi_VN-vais1000-medium-int8` (bản int8 của k2-fsa) | TTS tiếng Việt | Trọng số: MIT (repo piper-voices). Dữ liệu VAIS-1000: CC BY 4.0. Fine-tune từ giọng lessac, dữ liệu Blizzard 2013 chỉ cho nghiên cứu, cấm dùng thương mại | Ghi công VAIS-1000; chỉ dùng phi thương mại (đồ án) |
| `vits-piper-en_US-ljspeech-medium-int8` (bản int8 của k2-fsa) | TTS tiếng Anh | Trọng số: MIT (repo piper-voices). Dữ liệu LJ Speech: public domain | Ghi nguồn |
| GTCRN (`gtcrn_simple.onnx`) | Khử nhiễu, chỉ cho TN-02 | MIT | Giữ thông báo bản quyền |
| ML Kit Translate (`com.google.mlkit:translate`) | Chỉ flavor `dev` | Điều khoản ML Kit của Google | Không có trong APK offline (`check_apk.py` kiểm); không dùng làm evidence |
| AndroidX, Jetpack Compose, Room, Paging, DataStore | Toàn app | Apache-2.0 | Giữ file license |
| Material Symbols (icon vector trong `res/drawable`) | Icon điều hướng, nút | Apache-2.0 | Giữ thông báo bản quyền |
| Be Vietnam Pro (4 độ đậm, `res/font`, ~0.5 MB) | Font chữ toàn app (thiết kế cho dấu tiếng Việt) | SIL OFL 1.1 | Kèm file license `third_party/fonts/BeVietnamPro-OFL.txt`; không bán riêng font |
| Kotlin stdlib, kotlinx.coroutines, kotlinx.serialization | Toàn app | Apache-2.0 | Giữ file license |

Chỉ dùng khi test trên PC, không có trong APK: JUnit 4 (EPL-1.0), Robolectric
(MIT), AndroidX Test và Compose UI Test (Apache-2.0).

## Dự kiến cho Phase 04 (chưa có trong app)

| Thành phần | License | Ghi chú |
|---|---|---|
| ONNX Runtime Android (`com.microsoft.onnxruntime:onnxruntime-android`) | MIT | Chạy gói MT |
| `VietAI/envit5-translation` và adapter LoRA của Core | OpenRAIL (theo model card) | Điều khoản dùng phải đi kèm khi phát tán trọng số; ghi trong `LICENSE_NOTES.md` của gói MT (chốt ở Phase 04) |

## Nguồn

- sherpa-onnx: <https://github.com/k2-fsa/sherpa-onnx>
- espeak-ng: <https://github.com/espeak-ng/espeak-ng>
- Silero VAD: <https://github.com/snakers4/silero-vad>
- zipformer-vi: <https://huggingface.co/zzasdf/viet_iter3_pseudo_label>
- Moonshine: <https://github.com/moonshine-ai/moonshine>
- Giọng vais1000: <https://huggingface.co/rhasspy/piper-voices/raw/main/vi/vi_VN/vais1000/medium/MODEL_CARD>
- Dữ liệu lessac (Blizzard 2013): <https://www.cstr.ed.ac.uk/projects/blizzard/2013/lessac_blizzard2013/license.html>
- Giọng ljspeech: <https://huggingface.co/rhasspy/piper-voices/raw/main/en/en_US/ljspeech/medium/MODEL_CARD>
- GTCRN: <https://github.com/Xiaobin-Rong/gtcrn>
- ML Kit: <https://developers.google.com/ml-kit/terms>
- EnViT5: <https://huggingface.co/VietAI/envit5-translation>
