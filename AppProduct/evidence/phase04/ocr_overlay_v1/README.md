# Dịch trên ảnh: bản dịch vẽ lên ảnh (DR-10 §12)

Chạy ngày 2026-11-26 trên AVD `Pixel_10a` (sdk_gphone16k_x86_64, Android 17), bản release x86_64, Chế độ máy bay bật,
với 8 ảnh thử của [`../ocr_mlkit_v1`](../ocr_mlkit_v1/README.md).

Mỗi ảnh được mở qua lối tắt Dịch ảnh → bộ chọn ảnh, khởi động app lại từ đầu. Với ảnh tiếng Anh, chọn "Đổi sang" ở câu
hỏi R06. Sau khi có bản dịch, mở "Xem ảnh dịch toàn màn hình" rồi chụp màn hình. Kết quả đọc bằng `uiautomator dump`.

| File | Nội dung |
|---|---|
| `compare_vi.png`, `compare_en.png` | Mỗi hàng một ảnh thử: trái là ảnh gốc, phải là ảnh dịch app vẽ (phần ảnh của ảnh chụp màn hình toàn màn hình, thu về cùng bề rộng) |
| `app_results.jsonl` | Chữ app đọc (`ocr`), có giống lần đo `ocr_mlkit_v1` không, số dòng, thời gian đọc trên emulator, bản dịch, app có vẽ ảnh dịch không (`overlay_drawn`), có bị lệch số dòng không (`overlay_unmatched`) |

## Kết quả

- 8/8 ảnh có ảnh dịch; không ảnh nào bị lệch số dòng.
- Chữ đọc được giống hệt `ocr_mlkit_v1` ở cả 8 ảnh. Từ bản này app tự giải mã ảnh rồi đưa bitmap cho ML Kit, thay cho
  `InputImage.fromFilePath`, để toạ độ khối chữ khớp với ảnh được vẽ.
- Nhìn bằng mắt trên hai ảnh so sánh:
  - Bản dịch đặt đúng chỗ từng khối. Ở hai ảnh chụp nghiêng 3,5°, chữ nghiêng theo tờ giấy.
  - Không còn sót nét chữ gốc. Mảng phủ không lộ viền trên nền giấy có nhiễu.
  - Chiều Anh→Việt hiện đủ dấu. Cỡ chữ gần bằng chữ gốc; tiêu đề vẫn to hơn đoạn văn.
- **Còn yếu:**
  - Câu dịch dài hơn khung mà bên phải không còn nền trống thì chữ nhỏ lại. Ví dụ dòng 1 của `vi_photo`, nơi mép tờ giấy
    chặn phần tràn sang phải.
  - Bản dịch sai vẫn được vẽ như bản đúng. Ví dụ "Could not close temporary folder: %s" ở `vi_dialog`, do app đọc sai
    dấu thanh "kết nôi".

## Các lần chỉnh trước bản này (chỉ xem trên emulator, không lưu)

1. Bản đầu:
   - Còn sót mép chữ gốc: vạch của chữ "V", và dấu chấm cuối mà ML Kit bỏ sót.
   - Mảng phủ hơi xám, lộ viền.
   - Tiêu đề bị vẽ nhỏ.
2. Sửa lần 1:
   - Nới vùng phủ theo mực quanh khung.
   - Lấy màu nền bằng trung vị.
   - Làm mờ mép vùng phủ.
3. Sửa lần 2:
   - Cho chữ tràn sang nền trống bên phải trước khi thu nhỏ.
   - Bắt đầu chữ từ nét mực đầu tiên.
   - Tính cỡ chữ bằng cách đo chính dòng chữ gốc với font vẽ. Dòng gốc không có chữ thò xuống (g, p, y) có khung thấp hơn,
     và cách đo cũ làm chữ "Không thể kết nối tới máy phục vụ." quá nhỏ.
   - Giữ số dòng của bản dịch bằng số dòng gốc.
