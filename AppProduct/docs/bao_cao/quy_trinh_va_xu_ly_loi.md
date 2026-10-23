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
| UC-04 | Gõ thay vì nói | Chạm ⌨, gõ, chọn **Gửi tiếng Việt** hoặc **Send English** | Câu đi qua các rule R01–R07 rồi được dịch như UC-02/03, nhưng **không tự đọc** (bấm 🔊 để nghe); dòng thời gian chỉ có "MT … ms" (R13) |
| UC-05 | Chen lời | Người kia chạm nút của họ khi người này đang nói | Lượt đang nói được dịch nhưng không đọc (nhãn "chưa phát"); micro chuyển sang người vừa chạm |
| UC-06 | Ngắt lúc app đang đọc | Chạm một nút micro khi app đang đọc | App ngừng đọc (nhãn "bị ngắt"), mở micro cho người chạm |
| UC-07 | Nghe lại | Bấm 🔊 trên một câu | App đọc lại bản dịch |
| UC-08 | Sửa câu nghe nhầm | Bấm ✎ trên câu, sửa chữ, bấm **Dịch lại** | Câu được thay và dịch lại theo cùng rule như câu gõ (không tự đọc); nhãn "đã sửa"; lịch sử lưu bản đã sửa |
| UC-09 | Bắt đầu cuộc mới | Bấm ＋ | Màn trống; cuộc cũ vẫn trong danh sách ☰ |
| UC-10 | Mở cuộc cũ | Bấm ☰, chọn một cuộc | Các câu cũ hiện lại; nói tiếp được |
| UC-11 | Đổi tên / xoá cuộc | ☰ → ⋮ → Đổi tên hoặc Xoá (có xác nhận) | Danh sách cập nhật; cuộc đang mở bị xoá thì app chuyển sang cuộc mới |
| UC-12 | Dịch một chiều | ☰ → Dịch nhanh; chọn chiều (⇄), gõ hoặc nói, bấm **Dịch** | Bản dịch hiện trong thẻ màu kèm ghi chú rule; câu nói được đọc ngay, câu gõ chỉ đọc khi bấm 🔊; không lưu lịch sử |
| UC-16 | Kiểm tra rule dịch | ☰ → Cài đặt → Bộ case rule dịch (v1) → Chạy bộ case | Chạy 41 câu cố định của policy qua đúng luồng app, báo bao nhiêu case đúng quyết định, lưu JSONL để đối chiếu (tầng 3 của §8) |
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
| E-06 | Câu quá dài cho mô hình dịch (R09) | Trên 500 `source_ids` (gặp được dù dưới 500 ký tự, ví dụ chuỗi "a.a.a…") | Tự tách theo câu, rồi theo dấu cách, không tách trong URL/email/code; chuỗi không có chỗ tách thì cắt theo ký tự; dịch từng phần rồi ghép; không bỏ chữ | Không cần làm gì |
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
| E-21 | Gõ tiếng Việt không dấu (R07) | Bàn phím chưa bật tiếng Việt ("chao ban", "toi can kiem tra loi API") | Vẫn dịch, ghi dưới câu "Câu tiếng Việt không dấu có thể dịch sai. Hãy thêm dấu nếu có thể." Không báo chỉ vì thiếu dấu: "database connection pool", "CPU", URL, email, code, "API timeout 504" không bị báo. Không tự thêm dấu | Bật bàn phím tiếng Việt, bấm ✎ gõ lại có dấu |
| E-22 | Mô hình lặp từ hoặc tìm kiếm quá lâu | Câu rất ngắn hoặc lạ ("không" → "No, no, no…"; "chào bạn" từng mất 12 s trên điện thoại) | Giới hạn độ dài bản dịch 2 × số token câu gốc + 20 (tới ngưỡng thì lấy câu hoàn chỉnh tốt nhất đã tìm được); gộp cụm 1–3 từ lặp ≥ 4 lần liên tiếp thành 1 | Nói hoặc gõ cả câu thay vì một từ |
| E-23 | Tên công nghệ IT bị dịch thành từ khác (R03, R08) | Tên hiếm với mô hình ("Javascript" → "Vocabulary", "Django" → "Toyota"), hoặc trùng từ tiếng Anh thường ("React" → "phản ứng") | Gõ riêng một tên: giữ nguyên, không gọi mô hình, nhãn "Thuật ngữ/ký hiệu được giữ nguyên" (R03). Trong câu: dịch cả câu, hiện đúng bản dịch của mô hình, ghi "Cần kiểm tra thuật ngữ: …" và lưu vào Nhật ký rule dịch (R08). App không thay hay vá bản dịch (policy §9) | Tự kiểm tra bản dịch; lỗi được ghi làm evidence cho Student v2 |
| E-24 | Ô nhập rỗng hoặc quá 500 ký tự (R02) | Bấm gửi khi chưa gõ; dán đoạn dài | Rỗng: không gọi mô hình, báo "Hãy nhập nội dung cần dịch."; quá 500 ký tự hiển thị: khoá nút gửi, báo "Nội dung tối đa 500 ký tự.", giữ nguyên chữ | Rút gọn hoặc tách câu |
| E-25 | Chỉ gõ một từ kỹ thuật nhiều nghĩa (R04) | "timeout", "production", "database connection pool" | Giữ nguyên, không gọi mô hình, nhắc "Từ kỹ thuật ngắn có thể có nhiều nghĩa. Hãy nhập cả câu nếu cần dịch theo ngữ cảnh." (một mình, mô hình cho "production" → "Performion") | Nhập cả câu |
| E-26 | Gõ nhầm ngôn ngữ (R06) | Bấm **Gửi tiếng Việt** với "Hello, how are you?" | Chưa gửi; hỏi **Đổi sang English** / **Vẫn dịch** (Dịch nhanh: đổi chiều hoặc vẫn dịch). Không tự đổi chiều. "Vẫn dịch" được ghi cờ language_mismatch_overridden vào Nhật ký rule dịch | Chọn đúng ngôn ngữ |
| E-27 | Bản dịch không hợp lệ (R12) | Mô hình trả về rỗng, hoặc làm mất URL, email, code, mã HTTP, đường dẫn tuyệt đối có trong câu ("…https://example.com/docs…" → "…iTunes Premium.com/docs") | Không tạo bản dịch thành công; câu mang nhãn "không dịch được", báo "Không thể tạo bản dịch hợp lệ. Hãy thử lại hoặc nhập câu rõ hơn."; giữ câu gốc; ghi lỗi và thông tin gói vào Nhật ký rule dịch | Bấm ✎, viết rõ hơn (tách phần code/URL ra) rồi Dịch lại |

## 3. Giới hạn đầu vào

| Nội dung | Giới hạn | Khi vượt |
|---|---|---|
| Ngôn ngữ | Tiếng Việt ↔ tiếng Anh | Không dịch ngôn ngữ khác |
| Chữ gõ mỗi câu (Hội thoại, Dịch nhanh, Sửa) | 500 ký tự hiển thị sau chuẩn hoá (tính cả dấu cách, emoji; R02) | Khoá nút gửi, giữ nguyên chữ |
| Thời gian nói mỗi lượt | 60 giây | Tự dừng nghe |
| Im lặng kết thúc lượt nói | 1,5 giây | Tự dừng nghe |
| Chờ người bắt đầu nói | 6 giây | Dừng, báo không nghe thấy giọng |
| Độ dài một lần dịch | 500 `source_ids` (R09; mô hình có 512 vị trí) | Tự tách theo câu, rồi theo dấu cách, không tách trong URL/email/code; không cắt chữ |
| Độ dài bản dịch | 2 × số token câu gốc + 20 | Dừng tại đó (chỉ gặp khi mô hình lặp) |
| Câu trong một cuộc trò chuyện | 500 | Báo đầy, tạo cuộc mới |
| Số cuộc trò chuyện lưu | Không giới hạn (khoảng 1 KB mỗi câu) | Phụ thuộc bộ nhớ |
| Tên cuộc trò chuyện | 60 ký tự | Ô nhập không nhận thêm |
| Người nói cùng lúc | 1 | Chạm nút người khác thì cắt lượt |

Nguồn trong code: `ListenConfig` (60 s, 1,5 s, 6 s), `TextRules.MAX_CHARS` (500, R02) và `TextRules.MAX_SOURCE_IDS`
(500, R09), `ConversationStore.MAX_MESSAGES` (500) và
`TITLE_MAX_CHARS` (60), `assets/mt/manifest.json` `max_input_tokens` (512),
`GenerationParams.maxLengthFor` (2 × token + 20), `OutputCleanup.collapseRepeats` (lặp ≥ 4 lần).
