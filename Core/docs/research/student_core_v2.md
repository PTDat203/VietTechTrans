# Nghiên cứu Student Core V2

## Phạm vi

Đây là báo cáo tóm tắt kết quả nghiên cứu cục bộ của Student Core V2. V2 là
một ứng viên nghiên cứu; Core V1 vẫn là bản được chấp nhận của dự án. Báo cáo
không đưa model, dữ liệu, checkpoint hay mã thử nghiệm cục bộ lên Git.

## Cơ sở lựa chọn hướng nghiên cứu

Các lượt rà soát Core V1 cho thấy lỗi không chỉ nằm ở điểm số tổng hợp. Những
nhóm lỗi cần theo dõi trực tiếp ở V2 gồm:

- làm sai thực thể và danh tính sản phẩm, ví dụ tên phần mềm, hãng hoặc model;
- làm sai số, ngày, phiên bản, đơn vị, đường dẫn và lệnh;
- dịch sai hoặc xử lý thiếu chính xác thuật ngữ kỹ thuật;
- bỏ sót ý, mệnh đề, thành phần của danh sách, quan hệ hoặc tính phủ định;
- thay đổi định dạng, chữ hoa/thường hoặc chính tả có ý nghĩa kỹ thuật.

Vì vậy, V2 không được chọn chỉ theo một metric hoặc vì model nhỏ hơn. Quy tắc
nghiên cứu yêu cầu so sánh với V1 trên cùng tập Validation, dùng tập chẩn đoán
đã đóng băng, lưu cấu hình/đầu vào/môi trường và tách riêng lỗi vốn đã có trong
Teacher. Một lỗi của Teacher không thể được dùng làm bằng chứng rằng thay đổi
ở Student đã khắc phục nó.

## Kết quả chất lượng và hiệu năng

Trong ma trận thử nghiệm, toàn bộ ứng viên có learning rate `5e-5` không qua
điều kiện bảo toàn General Validation. Các ứng viên `2e-5` còn lại được xếp
hạng theo IT Validation chrF++, với điều kiện không thấp hơn V1 ở General
Validation.

| Hướng | Ứng viên được chọn tạm thời | IT chrF++ | General chrF++ | Kết quả so với V1 |
|---|---|---:|---:|---|
| EN→VI | `en-2e-5-e3` | 67.49 | 52.96 | Đạt điều kiện metric; benchmark batch-8 nhanh hơn V1 10.29% về latency và cao hơn 11.47% về throughput. |
| VI→EN | `vi-2e-5-e3` | 62.30 | 47.50 | Đạt điều kiện metric; benchmark batch-8 chậm hơn V1 13.43% về latency và thấp hơn 11.84% về throughput. |

Các số hiệu năng trên chỉ áp dụng cho RTX 3050 Ti, cấu hình benchmark đã ghi
nhận và số lần đo hữu hạn; không đại diện cho điện thoại, CPU hoặc mọi điều
kiện triển khai.

## Kết quả rà soát thủ công

Đánh giá lại theo thứ tự ưu tiên nghĩa nguồn, thực thể, chi tiết kỹ thuật,
thuật ngữ, thiếu/thêm ý và độ tự nhiên cho kết quả sau:

| Hướng | Kết quả đánh giá lại | Kết luận |
|---|---|---|
| EN→VI | 8 dòng nghiêng V1, 8 dòng nghiêng V2, 34 dòng tương đương | Không có bên thắng rõ ràng về chất lượng thủ công. |
| VI→EN | 13 dòng nghiêng V1, 6 dòng nghiêng V2, 31 dòng tương đương | V2 chưa đủ bằng chứng để thay thế V1. |

Nhiều đầu ra V1 và V2 giống hệt theo byte nhưng từng bị chấm thắng/thua. Các
dòng giống hệt phải được tự động ghi là tương đương; chỉ các dòng khác nhau
mới cần đánh giá so sánh. Bản đánh giá lại là ý kiến thứ hai có ghi lý do,
không thay thế phán quyết của người phụ trách.

## Bài học để sửa vòng tiếp theo

1. Đóng băng quy tắc chọn checkpoint và tập chẩn đoán trước khi train; không
   dùng thêm các ca lỗi mới để chỉnh tham số sau khi đã xem kết quả.
2. Đánh giá tách theo hướng dịch. Cải thiện EN→VI không cho phép suy ra
   VI→EN cũng cải thiện, đặc biệt khi VI→EN chậm hơn V1.
3. Dùng đánh giá thủ công theo nghĩa nguồn cho các lỗi thực thể, số liệu và
   thuật ngữ. Metric tổng hợp không đủ để phát hiện các lỗi này.
4. Gắn cờ rõ lỗi có sẵn trong Teacher để không quy công hoặc quy lỗi sai cho
   Student.
5. Chỉ xem V2 là ứng viên thay thế sau khi hoàn thành review bịt kín trên các
   dòng đầu ra khác nhau, kiểm tra lại hiệu năng đủ số mẫu và có quyết định
   phê duyệt riêng.

## Trạng thái

Student Core V2 **chưa được chấp nhận thay thế Core V1**. Báo cáo này lưu lại
hướng nghiên cứu, kết quả và bài học để dùng cho vòng cải tiến tiếp theo; mọi
artefact thử nghiệm vẫn ở vùng local-only.

## Nguồn nội bộ cục bộ

- `Core/local_experiments/main_core_v2/diagnostics/v1_error_taxonomy.md`
- `Core/local_experiments/main_core_v2/reports/v1_v2_speed_and_selection_comparison.md`
- `Core/local_experiments/main_core_v2/reviews/audits/human_logic_reassessment_20261011.md`
- `Core/local_experiments/main_core_v2/protocol/v2_research_brief.md`
