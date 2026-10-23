# Hướng dẫn sử dụng VietTechTrans

VietTechTrans dịch nói giữa **tiếng Việt và tiếng Anh**, chạy hoàn toàn **trên điện thoại, không cần Internet**: nghe giọng nói → chuyển thành chữ → dịch → đọc bản dịch cho người kia nghe. App thiên về câu nói thường ngày và câu kỹ thuật (công nghệ thông tin).

## Bắt đầu {#start}

1. Cài file APK (cho phép "Cài ứng dụng không rõ nguồn gốc" nếu máy hỏi).
2. Mở app: màn **Hội thoại** hiện ra. Lần đầu app nạp mô hình khoảng 5–20 giây; trong lúc đó dòng "Đang chuẩn bị mô hình…" hiện ở trên, bạn vẫn gõ chữ được.
3. Lần đầu bấm nút micro, app xin quyền **Micro**: chọn **Cho phép**. Nếu từ chối, app vẫn dùng được bằng cách gõ chữ.
4. Không cần đăng nhập, không cần mạng. Có thể bật Chế độ máy bay.

## Hội thoại hai người {#conversation}

Đặt điện thoại giữa hai người. Người nói tiếng Việt dùng nút **Tiếng Việt** (bên trái), người nói tiếng Anh dùng nút **English** (bên phải).

1. Chạm nút ngôn ngữ của mình rồi nói. Vòng sáng quanh nút cho biết app đang nghe.
2. Nói xong, ngừng khoảng 1,5 giây: app tự dừng nghe. Muốn dừng ngay thì chạm lại nút của mình.
3. App hiện câu vừa nghe (chữ nhỏ) và bản dịch (chữ đậm), rồi đọc bản dịch bằng giọng ngôn ngữ kia.
4. Đến lượt người kia: chạm nút ngôn ngữ của họ.

Các nút khác:

- **⌨ Gõ chữ**: gõ câu rồi chọn **Gửi tiếng Việt** hoặc **Send English**. Câu gõ chỉ được dịch, không tự đọc: bấm 🔊 trên câu để nghe. Dòng thời gian của câu gõ chỉ có "MT … ms" (không có bước nghe và đọc).
- **🔊 Nghe lại** trên mỗi câu: đọc lại bản dịch.
- **✎ Sửa** trên mỗi câu: sửa câu gốc rồi dịch lại (xem mục dưới).
- Chạm nút micro khi app đang đọc: app ngừng đọc và nghe người vừa chạm. Chạm nút người kia khi người này đang nói: lượt đang nói được dịch nhưng không đọc (nhãn "chưa phát").

## Sửa câu khi app nghe nhầm {#edit}

App có thể nghe nhầm khi nói nhanh, nhỏ, có tiếng ồn, giọng địa phương hoặc trộn tiếng Anh trong câu tiếng Việt. Khi đó:

1. Bấm **✎** trên câu bị nghe nhầm.
2. Sửa lại chữ cho đúng (tối đa 500 ký tự).
3. Bấm **Dịch lại**: app thay câu cũ và dịch lại; bấm 🔊 để nghe bản dịch mới. Câu được đánh dấu "đã sửa".

Nút ✎ tạm mờ khi app đang nghe hoặc đang dịch; đợi xong rồi sửa. Câu bị "dịch lỗi" cũng sửa (hoặc bấm Dịch lại giữ nguyên chữ) để dịch lại.

Khi gõ tiếng Việt, hãy gõ **có dấu** (bật bàn phím tiếng Việt). Mô hình dịch học từ tiếng Việt có dấu, nên câu không dấu thường bị dịch sai: "chao ban" thành "Order", trong khi "chào bạn" thành "Hello". Khi gặp câu như vậy, app vẫn dịch và ghi dưới câu: "Câu tiếng Việt không dấu có thể dịch sai. Hãy thêm dấu nếu có thể."

## Chữ gõ: app xử lý thế nào {#rules}

Câu gõ (và câu sửa) đi qua các quy tắc của nhóm (chính sách rule dịch text v1). App không bao giờ tự đổi chữ của bạn hay sửa bản dịch của mô hình; các quy tắc chỉ quyết định khi nào không cần gọi mô hình và khi nào cần báo cho bạn biết.

- **Tối đa 500 ký tự** (tính cả dấu cách và emoji). Quá 500 thì nút gửi bị khoá và hiện "Nội dung tối đa 500 ký tự."; chữ vẫn giữ nguyên để bạn sửa. Gửi khi ô trống thì hiện "Hãy nhập nội dung cần dịch."
- **Chỉ gõ một thuật ngữ hay ký hiệu kỹ thuật** (JavaScript, API, CI/CD, Docker, 404, địa chỉ web, email, đường dẫn, đoạn code, lệnh như `git status`): app giữ nguyên, không gọi mô hình, ghi "Thuật ngữ/ký hiệu được giữ nguyên".
- **Chỉ gõ một từ kỹ thuật nhiều nghĩa** (timeout, production, staging, merge, database connection pool…): app giữ nguyên và nhắc "Từ kỹ thuật ngắn có thể có nhiều nghĩa. Hãy nhập cả câu nếu cần dịch theo ngữ cảnh."
- **Từ giao tiếp ngắn** (yes, no, hello, thanks / ừ, vâng, chào, cảm ơn, xin lỗi) được dịch bình thường. Một từ khác đứng một mình vẫn được dịch nhưng kèm "Nội dung rất ngắn; kết quả có thể thiếu ngữ cảnh".
- **Gõ nhầm ngôn ngữ**: ví dụ bấm **Gửi tiếng Việt** với câu "Hello, how are you?". App chưa gửi mà hỏi **Đổi sang English** hay **Vẫn dịch**. App không tự đổi chiều; câu trộn như "Tôi cần deploy API lên production." vẫn dịch bình thường.
- **Câu có thuật ngữ** được dịch cả câu. Nếu bản dịch làm mất một tên công nghệ hay thuật ngữ quen thuộc (ví dụ "Tôi đang học Javascript" ra "I'm learning Vocabulary"), app vẫn hiện đúng bản dịch của mô hình và ghi "Cần kiểm tra thuật ngữ: JavaScript". Khi đó hãy tự kiểm tra lại, hoặc gõ thuật ngữ riêng.
- **Bản dịch không hợp lệ**: nếu mô hình trả về rỗng hoặc làm mất địa chỉ web, email, code, mã lỗi HTTP hay đường dẫn (như /api/users) có trong câu, app không hiện bản dịch mà báo "Không thể tạo bản dịch hợp lệ. Hãy thử lại hoặc nhập câu rõ hơn." Câu gốc vẫn còn để bạn bấm ✎ sửa.
- Nếu mô hình lặp một cụm từ nhiều lần ("ứng ứng ứng ứng dụng"), app gộp lại và ghi "Đã gộp cụm từ lặp trong bản dịch".

Các trường hợp "Cần kiểm tra thuật ngữ", "bản dịch không hợp lệ" và "Vẫn dịch" được ghi vào **Cài đặt → Nhật ký rule dịch** (chỉ lưu trên máy) để nhóm kiểm tra mô hình.

## Lịch sử và cuộc trò chuyện mới {#history}

App lưu từng cuộc trò chuyện (chỉ lưu chữ, không lưu âm thanh) giống ứng dụng chat.

- **＋** ở góc trên: bắt đầu cuộc trò chuyện mới. Cuộc cũ được giữ lại.
- **☰** ở góc trên: mở danh sách cuộc trò chuyện, xếp theo Hôm nay, Hôm qua, 7 ngày qua, 30 ngày qua, Cũ hơn. Chạm một cuộc để mở và nói tiếp.
- **⋮** cạnh mỗi cuộc: **Đổi tên** hoặc **Xoá**.
- Tên cuộc trò chuyện tự lấy từ câu đầu tiên, có thể đổi tên.
- Xoá toàn bộ lịch sử: **Cài đặt → Lịch sử trò chuyện → Xoá toàn bộ lịch sử**.

## Dịch nhanh {#translate}

Mở **☰ → Dịch** để dịch một chiều (ví dụ đọc biển báo, tài liệu ngắn):

1. Chọn chiều bằng thanh **Tiếng Việt ⇄ Tiếng Anh**; nút ⇄ đảo chiều.
2. Gõ vào ô, hoặc chạm nút micro và nói: chữ nghe được hiện trong ô để bạn sửa nếu nghe nhầm.
3. Bấm **Dịch**. Bản dịch hiện trong thẻ màu; bấm 🔊 để nghe. Câu gõ đi qua các quy tắc ở mục "Chữ gõ: app xử lý thế nào"; câu nói thì app dịch rồi đọc luôn.

Các câu ở màn Dịch không lưu vào lịch sử.

## Cài đặt và kiểm tra máy {#settings}

- **Kiểm tra máy này**: đo máy của bạn (nạp mô hình, tốc độ dịch, đọc và nghe, RAM) và cho biết máy có đạt cấu hình tối thiểu không. Mất khoảng 1–2 phút. Bấm **Sao chép kết quả** để gửi cho nhóm phát triển.
- **Bộ case rule dịch (v1)**: chạy các câu kiểm tra cố định của chính sách rule dịch qua đúng luồng của app và lưu kết quả để nhóm đối chiếu.
- **Nhật ký rule dịch**: các câu cần kiểm tra thuật ngữ, bản dịch không hợp lệ và các lần chọn "Vẫn dịch"; sao chép hoặc xoá được.
- **Lịch sử trò chuyện**: số cuộc đã lưu, dung lượng, xoá toàn bộ (xoá cả nhật ký rule dịch).
- **Chẩn đoán**: RAM đang dùng, lần thoát trước, lỗi gần nhất (gửi kèm khi báo lỗi).
- **Giới thiệu**: phiên bản app và gói mô hình dịch.

## Giới hạn của app {#limits}

| Nội dung | Giới hạn | Khi vượt |
|---|---|---|
| Ngôn ngữ | Tiếng Việt ↔ tiếng Anh | Không dịch ngôn ngữ khác |
| Chữ gõ mỗi câu (Hội thoại, Dịch, Sửa) | 500 ký tự (tính cả dấu cách, emoji) | Khoá nút gửi, giữ nguyên chữ để sửa |
| Thời gian nói mỗi lượt | 60 giây | App tự dừng nghe ở giây thứ 60 |
| Im lặng để kết thúc lượt nói | 1,5 giây | App tự dừng nghe |
| Chờ người bắt đầu nói | 6 giây | Dừng, báo "Không nghe thấy giọng nói" |
| Độ dài một lần dịch | 500 token của mô hình | Tự tách theo câu, rồi theo dấu cách (không tách giữa địa chỉ web, email, code), dịch từng phần, không cắt chữ |
| Độ dài bản dịch | Khoảng gấp đôi câu gốc (2 × số token câu gốc + 20) | Dừng tại đó; chỉ xảy ra khi mô hình lặp từ |
| Số câu trong một cuộc trò chuyện | 500 câu | Báo đầy, bấm ＋ để tạo cuộc mới |
| Số cuộc trò chuyện lưu | Không giới hạn (mỗi câu khoảng 1 KB) | Phụ thuộc bộ nhớ máy |
| Tên cuộc trò chuyện | 60 ký tự | Ô nhập không nhận thêm |
| Số người nói cùng lúc | 1 (một micro) | Chạm nút người khác thì cắt lượt đang nói |

## Mẹo để app nghe đúng {#tips}

- Nói rõ, tốc độ vừa phải, cách điện thoại khoảng 20–40 cm.
- Tránh nơi ồn; không nói chồng lên người kia hoặc lên tiếng app đang đọc.
- Câu dài nên tách thành vài câu ngắn.
- Thuật ngữ tiếng Anh trong câu tiếng Việt dễ bị nghe nhầm: nếu sai, bấm ✎ để sửa.
- Gõ tiếng Việt có dấu. Câu chỉ một hai từ ("vâng", "không") đôi khi dịch kém hơn cả câu ("vâng, tôi đồng ý").
- Mỗi người chỉ dùng nút đúng ngôn ngữ mình nói. App không tự đổi ngôn ngữ; với câu gõ, app chỉ hỏi lại khi câu có vẻ là ngôn ngữ kia.

## Lỗi thường gặp và cách xử lý {#errors}

| Hiện tượng | Nguyên nhân thường gặp | Cách xử lý |
|---|---|---|
| "Cần quyền micro để nói" | Chưa cho phép Micro | Cài đặt điện thoại → Ứng dụng → VietTechTrans → Quyền → Micro → Cho phép; hoặc gõ chữ |
| "Không nghe thấy giọng nói" | Nói quá nhỏ, chưa nói trong 6 giây, micro bị che | Chạm lại nút và nói to, rõ hơn |
| Câu nghe được bị sai | Ồn, nói nhanh, giọng địa phương, trộn Anh–Việt | Bấm ✎ sửa câu rồi Dịch lại |
| Bản dịch sai hẳn nghĩa, kèm "Câu tiếng Việt không dấu có thể dịch sai…" | Gõ tiếng Việt không dấu | Bật bàn phím tiếng Việt, bấm ✎ gõ lại có dấu rồi Dịch lại |
| "Cần kiểm tra thuật ngữ: …" dưới bản dịch | Mô hình làm mất một tên công nghệ ("Javascript" thành "Vocabulary", "React" thành "phản ứng") | Tự kiểm tra bản dịch; gõ riêng tên đó thì app giữ nguyên |
| "Không thể tạo bản dịch hợp lệ…" | Mô hình làm mất địa chỉ web, email, code hoặc mã lỗi HTTP, hoặc trả về rỗng | Bấm ✎, viết câu rõ hơn (có thể tách phần code ra riêng) rồi Dịch lại |
| App hỏi "Đổi sang …" hay "Vẫn dịch" | Câu có vẻ là ngôn ngữ kia | Chọn **Đổi sang …** nếu bấm nhầm nút, **Vẫn dịch** nếu đúng là câu trộn |
| Nút gửi bị khoá, "Nội dung tối đa 500 ký tự." | Câu dài hơn 500 ký tự | Rút gọn hoặc tách thành nhiều câu |
| "Đang chuẩn bị mô hình…" lâu | Lần đầu mở app, máy yếu | Đợi; vẫn gõ chữ được |
| "Mô hình dịch chưa phản hồi…" | Câu dài, máy yếu, máy đang nóng | Đợi thêm; nếu treo, đóng và mở lại app |
| Nhãn "dịch lỗi" trên câu | Lỗi khi dịch, hoặc app bị đóng giữa chừng | Bấm ✎ rồi Dịch lại |
| Nhãn "chưa phát" | Người khác chạm nút khi câu đang được xử lý | Bấm 🔊 để nghe lại |
| Không nghe thấy tiếng đọc | Âm lượng nhỏ, đang bật tai nghe Bluetooth | Tăng âm lượng phương tiện, kiểm tra tai nghe |
| App bị đóng khi đang dùng | Máy thiếu RAM | Đóng các app khác; xem Cài đặt → Chẩn đoán → Lần thoát trước |
| "Cuộc trò chuyện đã đủ 500 câu" | Đạt giới hạn | Bấm ＋ tạo cuộc mới |
| Banner "Chưa có mô hình dịch" | Bản cài thiếu gói dịch | Cài lại bản APK đầy đủ |

## Offline và quyền riêng tư {#offline}

- App **không có quyền truy cập Internet**: giọng nói, chữ và bản dịch không rời khỏi điện thoại.
- Âm thanh chỉ dùng để nhận dạng rồi bỏ đi, không lưu. Lịch sử chỉ lưu chữ, nằm trong bộ nhớ riêng của app; gỡ app là mất.
- Quyền duy nhất app xin: **Micro**.

## Giấy phép {#licenses}

Mô hình dịch: Student MarianMT của nhóm (khởi tạo từ Helsinki-NLP OPUS-MT, Apache-2.0; chưng cất từ VietAI EnViT5, giấy phép OpenRAIL). Nhận dạng giọng nói và đọc: sherpa-onnx (Apache-2.0), Zipformer tiếng Việt (CC BY-NC-ND 4.0), Moonshine (MIT), giọng Piper. App dùng cho mục đích học tập, phi thương mại.
