# Quy trình sử dụng và xử lý lỗi của VietTechTrans

Bảng dùng cho báo cáo đồ án. Mục 1 là các trường hợp sử dụng (người dùng làm gì, app phản hồi thế
nào); mục 2 là các trường hợp có thể lỗi, app xử lý ra sao và người dùng cần làm gì. Giới hạn đầu vào
ở mục 3 (cũng có trong Hướng dẫn sử dụng của app: `app/src/main/assets/manual/huong_dan_vi.md`).

## 1. Trường hợp sử dụng

| Mã | Trường hợp | Các bước của người dùng | Kết quả mong đợi |
|---|---|---|---|
| UC-01 | Mở app lần đầu | Mở app | Màn Hội thoại; dòng "Đang chuẩn bị mô hình…" trong lúc nạp (5–20 s), vẫn gõ được |
| UC-02 | Người Việt nói | Chạm **Tiếng Việt**, nói, ngừng 1,5 s (hoặc chạm lại) | Câu tiếng Việt và bản dịch tiếng Anh hiện trong bong bóng trái; app đọc bản dịch bằng giọng Anh |
| UC-03 | Người Anh nói | Chạm **English**, nói | Bong bóng phải; app đọc bản dịch bằng giọng Việt |
| UC-04 | Gõ thay vì nói | Chạm ⌨, gõ, chọn **Gửi tiếng Việt** hoặc **Send English** | Như UC-02/03 |
| UC-05 | Chen lời | Người kia chạm nút của họ khi người này đang nói | Lượt đang nói được dịch nhưng không đọc (nhãn "chưa phát"); micro chuyển sang người vừa chạm |
| UC-06 | Ngắt lúc app đang đọc | Chạm một nút micro khi app đang đọc | App ngừng đọc (nhãn "bị ngắt"), mở micro cho người chạm |
| UC-07 | Nghe lại | Bấm 🔊 trên một câu | App đọc lại bản dịch |
| UC-08 | Sửa câu nghe nhầm | Bấm ✎ trên câu, sửa chữ, bấm **Dịch lại** | Câu được thay, dịch lại, đọc lại; nhãn "đã sửa"; lịch sử lưu bản đã sửa |
| UC-09 | Bắt đầu cuộc mới | Bấm ＋ | Màn trống; cuộc cũ vẫn trong danh sách ☰ |
| UC-10 | Mở cuộc cũ | Bấm ☰, chọn một cuộc | Các câu cũ hiện lại; nói tiếp được |
| UC-11 | Đổi tên / xoá cuộc | ☰ → ⋮ → Đổi tên hoặc Xoá (có xác nhận) | Danh sách cập nhật; cuộc đang mở bị xoá thì app chuyển sang cuộc mới |
| UC-12 | Dịch một chiều | ☰ → Dịch nhanh; chọn chiều (⇄), gõ hoặc nói, bấm **Dịch** | Bản dịch hiện trong thẻ màu, nghe được; không lưu lịch sử |
| UC-13 | Kiểm tra máy | ☰ → Cài đặt → Kiểm tra máy này | Báo thông số máy, thời gian nạp và dịch, RAM, kết luận đạt/không đạt; sao chép được |
| UC-14 | Xoá toàn bộ lịch sử | Cài đặt → Lịch sử trò chuyện → Xoá toàn bộ lịch sử → Xoá | Danh sách trống; cuộc đang mở được đóng |
| UC-15 | Đọc hướng dẫn | ☰ → Hướng dẫn, hoặc nút ? trên Dịch nhanh / Cài đặt | Hướng dẫn mở (đúng mục của màn khi bấm ?), không cần mạng |

## 2. Trường hợp lỗi và cách xử lý

| Mã | Tình huống | Nguyên nhân | App xử lý | Người dùng làm gì |
|---|---|---|---|---|
| E-01 | Chưa có quyền micro | Từ chối quyền khi app hỏi | Báo "Cần quyền micro để nói. Bạn vẫn gõ được."; không mở micro | Cấp quyền trong Cài đặt điện thoại, hoặc gõ chữ |
| E-02 | Không nghe thấy giọng | Chưa nói trong 6 s, nói quá nhỏ, micro bị che | Dừng nghe, báo "Không nghe thấy giọng nói"; không tạo câu | Chạm lại và nói rõ hơn |
| E-03 | STT nghe nhầm | Ồn, nói nhanh, giọng vùng miền, trộn Anh–Việt, bấm nhầm nút ngôn ngữ | Hiện câu nghe được để người dùng thấy; nút ✎ cho sửa | Bấm ✎, sửa chữ, **Dịch lại** (màn Dịch nhanh: sửa trong ô rồi bấm Dịch) |
| E-04 | Chỉ có tiếng ậm ừ, từ đệm | Nói "ờ", "ừm"… | Lọc từ đệm; nếu không còn nội dung: màn Hội thoại báo "Không nghe thấy giọng nói", màn Dịch nhanh báo "Không nhận ra nội dung"; không tạo câu | Nói lại |
| E-05 | Nói quá dài | Một lượt quá 60 s | Tự dừng ở giây 60 và xử lý phần đã nghe | Tách thành nhiều lượt |
| E-06 | Câu quá dài cho mô hình dịch | Trên 512 token | Tự tách theo câu, dịch từng phần rồi ghép; không cắt chữ | Không cần làm gì |
| E-07 | Dịch chậm | Máy yếu, máy nóng, câu dài | Sau 10 s báo "Mô hình dịch chưa phản hồi…", vẫn chờ kết quả; màn Dịch nhanh có thêm nút Huỷ | Đợi; nếu treo thì đóng và mở lại app |
| E-08 | Dịch lỗi | Lỗi trong thư viện dịch | Câu mang nhãn "dịch lỗi", báo lỗi, app không văng | Bấm ✎ rồi **Dịch lại** |
| E-09 | Bản cài thiếu gói dịch | APK dựng khi chưa chạy `package_mt.py` | Banner "Chưa có mô hình dịch…"; chỉ hiện câu gốc, không tạo bản dịch giả | Cài lại APK đầy đủ |
| E-10 | Không đọc được bản dịch | Lỗi giọng đọc, âm lượng 0, tai nghe Bluetooth | Báo "Không đọc được…"; bản dịch vẫn hiện | Tăng âm lượng phương tiện, kiểm tra tai nghe, bấm 🔊 |
| E-11 | Hai người nói cùng lúc | Một micro không tách được hai giọng | Chỉ một người nói một lúc; chạm nút người kia thì cắt lượt (UC-05) | Nói lần lượt |
| E-12 | Micro đang bị app khác dùng | Cuộc gọi, ghi âm của app khác | Báo "Không mở được micro (đang bị ứng dụng khác dùng?)"; không văng app | Tắt app kia rồi chạm lại |
| E-13 | Máy thiếu RAM | Máy dưới 3 GB, nhiều app mở | Khi hệ thống báo thiếu RAM, app giải phóng mô hình và nạp lại khi cần; ghi lý do thoát vào Chẩn đoán | Đóng app khác; xem Cài đặt → Chẩn đoán → Lần thoát trước |
| E-14 | App bị đóng khi đang dịch | Hệ thống đóng app, hết pin | Mở lại: câu chưa dịch xong hiện nhãn "dịch lỗi" (lịch sử đã lưu chữ) | Bấm ✎ rồi **Dịch lại** |
| E-15 | Sửa câu khi app đang bận | App đang nghe hoặc đang dịch câu khác | Nút ✎ mờ; nếu vẫn gửi thì báo "Đợi lượt đang nói hoặc đang dịch xong rồi sửa" | Đợi xong rồi sửa |
| E-16 | Cuộc trò chuyện đầy | Đã có 500 câu | Báo "Cuộc trò chuyện đã đủ 500 câu…"; không mở micro, không nhận câu gõ | Bấm ＋ tạo cuộc mới |
| E-17 | File lịch sử hỏng | Tắt máy đột ngột khi đang ghi (hiếm) | Ghi file tạm rồi đổi tên nên file cũ không hỏng; file hỏng (nếu có) bị bỏ qua, các cuộc khác vẫn mở được | Không cần làm gì |
| E-18 | Bộ nhớ máy đầy | Không còn chỗ ghi lịch sử | Báo "Lỗi: …" khi lưu; cuộc đang mở vẫn dùng được trong phiên | Giải phóng bộ nhớ |
| E-19 | Bấm nhầm nút ngôn ngữ | Nói tiếng Anh vào nút Tiếng Việt | Nhận dạng sai ngôn ngữ (app không tự đoán ngôn ngữ) | Bấm ✎ sửa câu, hoặc nói lại bằng đúng nút |
| E-20 | Xoay màn hình, chuyển app | — | Cuộc trò chuyện giữ nguyên; app vào nền thì dừng đọc và giải phóng mô hình | Mở lại app, đợi nạp mô hình |
| E-21 | Gõ tiếng Việt không dấu | Bàn phím chưa bật tiếng Việt ("chao ban", "xin chao") | Vẫn dịch nhưng báo "Câu tiếng Việt không có dấu nên dễ bị dịch sai…" (mô hình học từ tiếng Việt có dấu: "chao ban" → "Order", "chào bạn" → "Hello") | Bật bàn phím tiếng Việt, bấm ✎ gõ lại có dấu |
| E-22 | Mô hình lặp từ hoặc tìm kiếm quá lâu | Câu rất ngắn hoặc lạ ("không" → "No, no, no…"; "chào bạn" từng mất 12 s trên điện thoại) | Giới hạn độ dài bản dịch 2 × số token câu gốc + 20 (tới ngưỡng thì lấy câu hoàn chỉnh tốt nhất đã tìm được); gộp cụm 1–3 từ lặp ≥ 4 lần liên tiếp thành 1 | Nói hoặc gõ cả câu thay vì một từ |

## 3. Giới hạn đầu vào

| Nội dung | Giới hạn | Khi vượt |
|---|---|---|
| Ngôn ngữ | Tiếng Việt ↔ tiếng Anh | Không dịch ngôn ngữ khác |
| Chữ gõ mỗi câu (Hội thoại, Dịch nhanh, Sửa) | 1000 ký tự | Ô nhập không nhận thêm |
| Thời gian nói mỗi lượt | 60 giây | Tự dừng nghe |
| Im lặng kết thúc lượt nói | 1,5 giây | Tự dừng nghe |
| Chờ người bắt đầu nói | 6 giây | Dừng, báo không nghe thấy giọng |
| Độ dài một lần dịch | 512 token (khoảng 300 từ) | Tự tách theo câu |
| Độ dài bản dịch | 2 × số token câu gốc + 20 | Dừng tại đó (chỉ gặp khi mô hình lặp) |
| Câu trong một cuộc trò chuyện | 500 | Báo đầy, tạo cuộc mới |
| Số cuộc trò chuyện lưu | Không giới hạn (khoảng 1 KB mỗi câu) | Phụ thuộc bộ nhớ |
| Tên cuộc trò chuyện | 60 ký tự | Ô nhập không nhận thêm |
| Người nói cùng lúc | 1 | Chạm nút người khác thì cắt lượt |

Nguồn trong code: `ListenConfig` (60 s, 1,5 s, 6 s), `ConversationViewModel.MAX_TYPED_CHARS` và
`TranslateViewModel.MAX_TYPED_CHARS` (1000), `ConversationStore.MAX_MESSAGES` (500) và
`TITLE_MAX_CHARS` (60), `assets/mt/manifest.json` `max_input_tokens` (512),
`GenerationParams.maxLengthFor` (2 × token + 20), `OutputCleanup.collapseRepeats` (lặp ≥ 4 lần).
