# Selection record — trước adaptation

Sao chép thành `evidence/phase02/selection/<direction>.md` sau khi 12 pretrained run đã hoàn tất. Đây là quyết định của người thực hiện, không phải kết quả tự động từ notebook.

| Trường | Nội dung |
|---|---|
| Direction | `en_to_vi` hoặc `vi_to_en` |
| Ngày/giờ (UTC) | |
| Candidate giữ để adaptation | Một candidate, hoặc tối đa hai khi evidence chưa phân biệt được và nguồn lực đủ để adaptation cả hai |
| Candidate loại | Tên và lý do cụ thể |
| IT Validation | chrF++, SacreBLEU và link metrics từng candidate |
| General Validation | chrF++, SacreBLEU và link metrics từng candidate |
| Lỗi output đã kiểm tra | Link prediction/row ID, nếu có |
| Khả năng thực hiện | Xác nhận candidate có thể đi tiếp adaptation trong điều kiện đồ án; không dùng benchmark Android để xếp hạng |
| Mức không chắc chắn | Nêu rõ nếu hai candidate sát nhau |
| Người quyết định | |

Không ghi weighted score, ngưỡng tự đặt hoặc kết quả final test vào record này.
