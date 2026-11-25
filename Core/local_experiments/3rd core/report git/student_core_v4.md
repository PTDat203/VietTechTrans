# Báo cáo nghiên cứu Student Core V4

## Mục tiêu và bối cảnh

Student Core V4/V4-B là nghiên cứu lịch sử nhằm cải thiện dịch thuật chuyên ngành IT trong khi giữ chất lượng dịch tổng quát của Student V1. V3 cho thấy chất lượng trên tập chuyên biệt có thể tăng nhưng giảm trên General Validation. V4 vì vậy xem lại chất lượng dữ liệu, nguồn tham chiếu và cách đánh giá trước khi chọn mô hình.

V4/V4-B được nghiên cứu trong workspace `3rd core`; số thứ tự workspace không phải phiên bản model. Student V1 vẫn là baseline của dự án. Báo cáo này tóm tắt kết quả nghiên cứu, không bao gồm dữ liệu, model hoặc đầu ra thử nghiệm.

## Kiểm tra dữ liệu và hướng nghiên cứu

Trong nhánh VI→EN ban đầu, kiểm tra dữ liệu trusted ghi nhận 65 nhóm câu nguồn chuẩn hóa có các bản tham chiếu mâu thuẫn và 265 Teacher target được dùng lại cho nhiều nguồn khác nhau. Những tín hiệu này không chứng minh mọi bản dịch liên quan đều sai, nhưng không đủ cơ sở để dùng toàn bộ tập trusted như dữ liệu chuẩn.

Quyết định data-gate ngày 14/10/2026 chặn huấn luyện nhánh đó: tập IT cần được review từng dòng và nguồn dữ liệu tổng quát cần có nguồn gốc, chất lượng và kiểm tra trùng lặp phù hợp. Các nguồn general được khảo sát khi đó chưa được phê duyệt. Đây là quyết định cho nhánh cụ thể ở thời điểm đó, không phải kết luận rằng mọi thí nghiệm V4 về sau đều không chạy.

Một hướng V4 khác thiết kế kiểm tra lại dữ liệu Phase 01 rồi huấn luyện Student bằng sequence-level knowledge distillation: Teacher tạo bản dịch cho dữ liệu train, Student học từ bản dịch đó. General Validation chỉ dùng để đánh giá; dữ liệu test được giữ riêng. Những dòng nghi ngờ được cách ly để review thay vì tự động coi là sai.

## Rà soát V4-B

Bản ghi rà soát AI cuối cùng ngày 16/10/2026 phân loại 163.869 cặp dữ liệu: 81.076 giữ tạm thời, 51.187 loại và 31.606 chưa chắc chắn. Đây là quyết định tự động, không phải xác nhận của con người về tính đúng nghĩa của từng cặp. Bản ghi đó cũng nêu tại thời điểm hoàn tất phân loại chưa tạo dataset; các giai đoạn sau cần được xem qua bằng chứng riêng.

## Kết quả chọn ứng viên V4-B

Bản tổng hợp ngày 19/10/2026 đánh giá 6 checkpoint cho mỗi hướng dịch. Một ứng viên phải đạt ít nhất cả chrF++ và BLEU của V1 trên General Validation mới đủ điều kiện chọn theo Validation V4-B. Kết quả: **không có ứng viên đủ điều kiện ở cả hai hướng**; IT test không được dùng trong bước chọn này.

| Hướng | Ứng viên có chrF++ cao nhất trên Validation V4-B | chrF++ V4-B | chrF++ General của ứng viên | chrF++ General V1 |
|---|---|---:|---:|---:|
| EN→VI | `en-2e-5-e3` | 72,20 | 51,95 | 52,44 |
| VI→EN | `vi-2e-5-e3` | 66,35 | 45,30 | 46,20 |

Hai checkpoint trong bảng được chọn để minh họa điểm Validation V4-B cao nhất, không phải người thắng hợp lệ: cả hai đều thấp hơn baseline General ở chrF++. Các metric trên các tập khác nhau không thể so trực tiếp để kết luận mức cải thiện của V4 so với V1 trên cùng tập IT.

## Bài học và trạng thái

- Cần kiểm tra ý nghĩa và nguồn gốc cặp dữ liệu; kiểm tra cấu trúc, số hay độ dài không tự chứng minh cặp dịch đúng.
- Rà soát AI hỗ trợ sàng lọc nhưng nhãn giữ tạm thời không tương đương dữ liệu chuẩn được con người xác nhận.
- Điểm cao trên Validation chuyên ngành không đủ nếu chất lượng tổng quát thấp hơn ngưỡng đã định; cần xem từng hướng dịch và cả hai metric guardrail.
- Không chọn checkpoint trên IT test. Review thủ công, đo tốc độ và quyết định phê duyệt vẫn cần thiết nếu có ứng viên vượt guardrail trong nghiên cứu tiếp theo.

Theo bản chọn V4-B được kiểm tra, không có ứng viên thay thế Student V1. Không suy ra rằng mọi nguyên nhân giảm General đã được xác định hoặc rằng kết quả này áp dụng ngoài các tập và cấu hình thử nghiệm đã ghi nhận.

## Nguồn nội bộ

Tóm tắt dựa trên quyết định data-gate VI→EN ngày 14/10/2026, tài liệu thiết kế V4 re-audited Phase 01 KD, báo cáo `final-v2-20261016` và bản `v4b_matrix_selection` ngày 19/10/2026. Các nguồn và artefact gốc được lưu cục bộ; việc công bố báo cáo không đồng nghĩa công bố các nguồn đó.
