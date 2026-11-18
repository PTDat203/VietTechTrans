# Báo cáo nghiên cứu Student Core V3

## Phạm vi và trạng thái

Student Core V3 là nghiên cứu lịch sử về dữ liệu bảo thủ và thích nghi trực
tiếp từ Student V1. Báo cáo này chỉ công bố tóm tắt phương pháp, kết quả đã
kiểm chứng và giới hạn. Dữ liệu, checkpoint, dự đoán, bản ghi chạy và mã
nghiên cứu vẫn thuộc vùng local-only.

V3 chưa được chấp nhận thay thế Student V1.

## Vấn đề được khảo sát

Rà soát 152.044 cặp song ngữ của dữ liệu Phase 3 tìm thấy 34.004 dòng có ít
nhất một tín hiệu cần xem xét. Các tín hiệu gồm token mã/đường dẫn không được
giữ nguyên, lệch số, tỷ lệ độ dài bất thường, cặp EN/VI giống nhau, mục tiêu
tiếng Việt thiếu dấu và nguồn tiếng Anh không có ký tự Latin.

Tín hiệu không đồng nghĩa với dữ liệu hỏng. Vì vậy V3 không xóa theo tín hiệu
đơn lẻ. Chính sách bảo thủ chỉ loại khỏi tập ứng viên các dòng có bằng chứng
cấu trúc trực tiếp: mục tiêu rỗng sau chuẩn hóa, hoặc đồng thời có tỷ lệ độ dài
cực đoan và lệch số. Dữ liệu loại ra được quarantine cục bộ; Validation, review
và test của Phase 3 không được đưa vào train hay chọn ứng viên.

## Cách thử nghiệm

V3 khởi đầu từ checkpoint Student V1 của từng hướng dịch, dùng tham chiếu song
ngữ trực tiếp của tập trusted-source thay vì tái sử dụng Teacher target nhiễu.
Hai hướng EN→VI và VI→EN được huấn luyện riêng. Chọn tự động chỉ được xem là
hợp lệ khi ứng viên không thấp hơn V1 trên General Validation; sau đó vẫn phải
qua benchmark tốc độ, review thủ công và phê duyệt riêng.

## Kết quả

Ứng viên cao nhất theo tập trusted-source là `en-2e-5-e3` cho EN→VI và
`vi-2e-5-e3` cho VI→EN.

| Hướng | V1 trên trusted chrF++ | V3 tốt nhất trên trusted chrF++ | V1 General chrF++ | V3 tốt nhất General chrF++ | Kết luận |
|---|---:|---:|---:|---:|---|
| EN→VI | 68,50 | 71,70 | 52,44 | 52,02 | Không đạt guardrail General Validation |
| VI→EN | 62,57 | 66,73 | 46,20 | 44,03 | Không đạt guardrail General Validation |

Mọi ứng viên trong ma trận đều không đạt guardrail General Validation. Vì thế,
dù điểm trên trusted-source cao hơn V1, không ứng viên nào đủ điều kiện để gọi
là bản nâng cấp hoặc thay thế V1. Trạng thái của kết quả tự động là xếp hạng
sơ bộ; không thay cho review thủ công, đo tốc độ và phê duyệt của người phụ
trách.

## Bài học

1. Điểm số trên một tập dữ liệu đã làm sạch không đủ để khẳng định mô hình tốt
   hơn trong phạm vi sử dụng chung. Guardrail độc lập phải là điều kiện bắt buộc.
2. Lọc dữ liệu quá rộng dựa trên token kỹ thuật hoặc số có thể loại cả bản dịch
   hợp lệ. Cần ưu tiên bằng chứng cấu trúc rõ ràng và giữ hàng bị loại để kiểm
   tra lại.
3. Cải thiện dữ liệu huấn luyện không bảo đảm chất lượng ở cả hai hướng dịch;
   EN→VI và VI→EN cần được đánh giá và ra quyết định riêng.
4. Metric tự động chỉ là một tầng kiểm tra. Review theo nghĩa nguồn, benchmark
   tốc độ và quyết định phê duyệt vẫn cần thiết trước khi thay Core đang dùng.

## Kết luận

V3 cung cấp cách phát hiện và cô lập các cặp song ngữ có rủi ro, đồng thời cho
thấy thích nghi trên trusted-source có thể tăng điểm trên tập cùng phân phối.
Tuy nhiên, do không bảo toàn được General Validation, kết quả không hỗ trợ việc
thay thế Student V1. Giá trị của V3 là bài học về kiểm soát chất lượng dữ liệu
và quy tắc chọn mô hình cho các nghiên cứu sau.
