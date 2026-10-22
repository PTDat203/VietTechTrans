# Thông số máy tối thiểu cho VietTechTrans

Bảng dùng cho báo cáo đồ án. App chạy toàn bộ trên điện thoại (không có quyền Internet), nên cấu hình
máy quyết định app có chạy được và nhanh hay chậm. Số đo ghi nguồn; số đo trên điện thoại thật lấy từ
**Cài đặt → Kiểm tra máy này** (mục 4).

## 1. Bảng thông số

| Hạng mục | Tối thiểu | Khuyến nghị | Căn cứ |
|---|---|---|---|
| Hệ điều hành | Android 8.0 (API 26) | Android 10 trở lên | `minSdk = 26` trong `app/build.gradle.kts` |
| Kiến trúc CPU | ARM 64-bit (arm64-v8a) | ARMv8.2 trở lên (có lệnh dotprod: Cortex-A55/A75 trở lên) | APK chỉ có thư viện arm64-v8a (máy thật) và x86_64 (máy ảo); không hỗ trợ máy 32-bit. Không có dotprod (Cortex-A53/A73) vẫn chạy được nhờ mô hình dịch 4-bit, chỉ chậm hơn |
| Số nhân CPU | 4 nhân | 8 nhân (có nhân hiệu năng cao) | Mô hình dịch 2 luồng, nhận dạng 2 luồng, giọng đọc 2–4 luồng (bằng nửa số nhân) |
| RAM | 3 GB | 4 GB trở lên | App dùng khoảng 800 MB (PSS) khi nạp đủ 6 mô hình ở màn Hội thoại (đo trên máy ảo, mục 3); Android và các app khác cần phần còn lại |
| Bộ nhớ trống khi cài | 500 MB | 1 GB | APK arm64 khoảng 282 MB (bảng mục 2) + mã biên dịch, dữ liệu espeak, lịch sử |
| Bộ nhớ cho lịch sử | Không đáng kể | — | Chỉ lưu chữ, khoảng 1 KB mỗi câu; tối đa 500 câu mỗi cuộc trò chuyện (khoảng 0,5 MB) |
| Micro | Có | Micro thường của điện thoại | Ghi 16 kHz, 1 kênh |
| Loa hoặc tai nghe | Có | — | Đọc bản dịch |
| Màn hình | Rộng từ 360 dp (khoảng 5 inch) | Từ 6 inch | Giao diện kiểm tra ở 360 dp và màn rộng |
| Mạng | Không cần | — | Manifest xoá quyền INTERNET; kiểm bằng `aapt2 dump permissions` |
| Quyền | Micro | — | Xin khi bấm nút nói lần đầu; từ chối thì vẫn gõ chữ được |

Dưới 3 GB RAM: app vẫn cài được nhưng hệ thống có thể đóng app ở màn Hội thoại (cần cả 6 mô hình).
Khi đó dùng màn **Dịch nhanh** (chỉ nạp 3 mô hình của một chiều) và đóng các app khác.

## 2. Thành phần chiếm dung lượng và RAM

| Thành phần | Dung lượng trong APK | Ghi chú |
|---|---|---|
| Mô hình dịch Anh→Việt (MarianMT 4-bit, lớp đầu ra 4+4 bit) | 82,5 MB | Main-Core Phase 03 (từ 2026-10-08; 8-bit 91 MB chạy rất chậm trên chip không có dotprod, DR-07 §13–14) |
| Mô hình dịch Việt→Anh (MarianMT 4-bit, lớp đầu ra 4+4 bit) | 82,5 MB | |
| Nhận dạng tiếng Việt (Zipformer 30M int8) | 32 MB | |
| Nhận dạng tiếng Anh (Moonshine tiny) | 42 MB | |
| Giọng đọc tiếng Việt (Piper, trọng số int8) | 18 MB | Từ 2026-10-07 chỉ nén trọng số, nhanh hơn bản int8 cũ (DR-04) |
| Giọng đọc tiếng Anh (Piper, trọng số int8) | 19 MB | |
| ONNX Runtime + sherpa-onnx (thư viện native arm64) | khoảng 57 MB | ONNX Runtime 33 MB, sherpa-onnx 24 MB |
| Phát hiện giọng nói, font, giao diện | vài MB | |

Mô hình được nạp theo màn: Hội thoại nạp 6 mô hình (2 nhận dạng, 2 giọng đọc, 2 dịch); Dịch nhanh nạp 3.
Khi app vào nền hoặc máy báo thiếu RAM, app giải phóng toàn bộ mô hình.

## 3. Số đo hiện có

Đo bằng **Cài đặt → Kiểm tra máy này** trên bản release (APK x86_64), chế độ máy bay,
ngày 2026-10-07. Môi trường: máy ảo Pixel_10a, Android 17 (API 37), x86_64, 4 nhân,
RAM 4,1 GB, chạy trên laptop i5-12500H.

| Đo | Kết quả |
|---|---|
| Nạp mô hình | STT_VI 1,2 s · STT_EN 0,5 s · TTS_VI 1,3 s · TTS_EN 1,6 s · MT Anh→Việt 0,6 s · MT Việt→Anh 0,6 s |
| Dịch Anh→Việt (20 câu mẫu, 2 luồng) | Trung bình 157 ms, chậm nhất 243 ms, 10,5 ms/bước; giống bản mẫu PC 20/20 |
| Dịch Việt→Anh (20 câu mẫu, 2 luồng) | Trung bình 215 ms, chậm nhất 339 ms, 16,7 ms/bước; giống bản mẫu PC 20/20 |
| Đọc tiếng Việt | 194–238 ms cho 2,0 s âm thanh (RTF 0,10–0,12); bản int8 cũ: RTF 0,20 |
| Đọc tiếng Anh | 177–193 ms cho 2,5–2,8 s âm thanh (RTF 0,06–0,08); bản int8 cũ: RTF 0,22 |
| Nhận dạng (đọc → nghe lại) | VI 50 ms (RTF 0,02), EN 62 ms (RTF 0,02); nhận đúng cả hai câu |
| RAM app khi đủ 6 mô hình | 797 MB (PSS) với dịch 4-bit + đầu ra 4+4 bit; dịch 4-bit thường 666 MB; 8-bit 657–722 MB |
| Mở app (`am start -W`, khởi động lạnh) | 0,6–2,1 s |
| Kết luận của app | ĐẠT cấu hình khuyến nghị (từ 4 GB RAM) |

Số khác (PC, ngoài app):

| Đo | Kết quả | Nguồn |
|---|---|---|
| Một bước giải mã 4 beam của mô hình dịch | 10 ms | PC i5-12500H, 2 luồng (`evidence/phase04`) |
| Chất lượng sau lượng tử hoá (chrF++) | Thấp hơn bản 8-bit 0,04–0,14 (tối đa 0,2 %) | `evidence/phase04/mt_export_q4r/run.json` |
| Một bước giải mã 6 beam, 8-bit khi CPU không có kernel int8 (như điện thoại không có dotprod) | 232 ms; bản 4-bit: 23 ms | PC i5-12500H, 2 luồng (DR-07 §13) |

Máy ảo chạy trên CPU laptop nên chỉ dùng để kiểm tra chức năng. Điện thoại thật thường
chậm hơn 2–5 lần tuỳ CPU; cần ghi số đo thật vào mục 4 trước khi chốt báo cáo.

## 4. Đo trên điện thoại thật

1. Cài `app-arm64-v8a-release.apk` lên điện thoại.
2. Mở **☰ → Cài đặt → Kiểm tra máy này → Bắt đầu kiểm tra** (1–2 phút, không cần mạng).
3. Bấm **Sao chép kết quả** và dán vào bảng dưới.

App tự kết luận theo bảng mục 1: dưới 3 GB RAM là "DƯỚI cấu hình tối thiểu"; 3–4 GB là "ĐẠT tối
thiểu"; từ 4 GB là "ĐẠT cấu hình khuyến nghị" (so theo GB thập phân, có trừ hao phần RAM hệ thống giữ).

| Điện thoại | Android | RAM | Nạp 6 mô hình | Dịch trung bình Anh→Việt / Việt→Anh | Khớp bản mẫu | RAM app | Kết luận |
|---|---|---|---|---|---|---|---|
| (điền sau khi đo) | | | | | | | |
