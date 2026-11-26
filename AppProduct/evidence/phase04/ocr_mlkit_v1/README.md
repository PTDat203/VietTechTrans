# Dịch ảnh: độ chính xác đọc chữ (DR-10)

Đo ngày 2026-11-26 trên AVD `Pixel_10a` (sdk_gphone16k_x86_64, Android 17), bản release x86_64, Chế độ máy bay bật.
Emulator chỉ kiểm tra chức năng: thời gian đọc ở đây không dùng để kết luận cho điện thoại thật.

| File | Nội dung |
|---|---|
| `*.png`, `truth.json` | 8 ảnh thử (4 tiếng Việt, 4 tiếng Anh; 6 ảnh kiểu chụp màn hình, 2 ảnh kiểu chụp bằng điện thoại: nghiêng 3,5°, mờ, nhiễu) và chữ đúng của từng ảnh. Sinh bằng `python tools/ocr/make_test_images.py evidence/phase04/ocr_mlkit_v1` (font Windows; chạy lại ra đúng các ảnh này) |
| `app_results.jsonl` | Chữ app đưa vào ô sau khi đọc ảnh (`ocr`), số dòng và thời gian đọc ghi trên màn hình, bản dịch app hiện. Ảnh được chọn qua lối tắt Dịch ảnh → bộ chọn ảnh của Android, kết quả đọc bằng `uiautomator dump` |
| `summary.json` | CER/WER từng ảnh và từng ngôn ngữ: `python tools/ocr/score_ocr.py evidence/phase04/ocr_mlkit_v1` |
| `bundled_model_logcat.txt` | Log của app khi đọc `vi_photo.png`: ML Kit chọn mô hình đóng gói trong APK (`Selected local version`, thư viện `libmlkit_google_ocr_pipeline.so` nạp từ `base.apk`, model từ `assets/mlkit-google-ocr-models`), không dùng module OCR của Google Play services có sẵn trên emulator |
| `paddle_latin_dict_check.json` | Ứng viên bị loại: bộ ký tự của PaddleOCR `latin_PP-OCRv5_mobile_rec` thiếu 90/178 chữ cái tiếng Việt (toàn bộ khối U+1EA0–U+1EF9) |

## Kết quả

| Ngôn ngữ | Ảnh | Ký tự | Lỗi ký tự | CER |
|---|---|---|---|---|
| Tiếng Việt | 4 | 290 | 2 | 0,7 % |
| Tiếng Anh | 4 | 320 | 0 | 0,0 % |

- Hai lỗi đều ở `vi_dialog.png`: "kết nối" đọc thành "kết nôi" (mất dấu sắc) và mất dấu chấm cuối dòng đầu. Mô hình dịch
  đưa "Không thể kết nôi tới máy chủ" thành "Could not close temporary folder: %s": người dùng phải so chữ trong ô với ảnh
  (dòng nhắc dưới ảnh) và sửa.
- `vi_photo.png` lúc đầu đọc "Hãy" thành "Häy" (dấu ngã thành hai chấm). Đã thêm `OcrText.repairTildes`
  (đổi ä/ö/ü/ë/ï/ÿ về chữ có dấu ngã trong khối chữ có chữ cái riêng của tiếng Việt); kết quả trong bảng là của bản đã sửa.
- Thời gian đọc trên emulator (gồm cả nạp mô hình: app tạo bộ đọc cho mỗi ảnh rồi đóng lại): 0,7–0,9 s với ảnh màn hình,
  1,5–2,1 s với ảnh kiểu chụp điện thoại. Đọc lại `vi_photo.png` lúc ghi `bundled_model_logcat.txt` mất 0,64 s, nên số trên
  emulator dao động nhiều.

`app_build` trong `app_results.jsonl` ghi bản app của từng dòng. Chữ đọc được (phần được chấm) không phụ thuộc hai thay đổi
sau bản 1, trừ sửa dấu ngã ở `vi_photo`. Bản dịch của bản 1 còn ghép tiêu đề với đoạn văn ("Cập nhật phần mềm Một phiên bản
mới…"); từ bản 2 chữ nhiều dòng được dịch từng dòng (`TurnRunner.translateLines`).

## Quan sát thêm (không chấm)

Ảnh chụp màn hình cũ của app có chữ nhỏ (thanh trạng thái, nhãn 11–12 sp) bị đọc sai nhiều hơn: "Hội thoại" → "Hội thogi",
"Tiếng Việt" → "Tiểng Việt", biểu tượng bị đọc thành chữ ("ŽA", "X«"). Chữ càng nhỏ trong ảnh càng dễ sai: Hướng dẫn trong
app khuyên chụp gần, thẳng, đủ sáng.
