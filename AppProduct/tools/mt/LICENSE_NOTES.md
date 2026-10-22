# Giấy phép gói dịch (Main-Core Phase 03)

Gói này chứa hai mô hình dịch đã chuyển sang ONNX và lượng tử hoá 8-bit cho điện thoại.
Trọng số gốc nằm ở `Core/models/phase03/` (không phát tán trong repo).

| Thành phần | Nguồn | Giấy phép |
|---|---|---|
| Kiến trúc và trọng số khởi tạo Student EN→VI | `Helsinki-NLP/opus-mt-en-vi` rev `989c9fb9ec63987901022baf0182dcec3e149be6` | Apache-2.0 |
| Kiến trúc và trọng số khởi tạo Student VI→EN | `Helsinki-NLP/opus-mt-vi-en` rev `c8d2853e77f5fae31124d993e0b35176b1c8914e` | Apache-2.0 |
| Teacher dùng để chưng cất (sequence-level KD) | `VietAI/envit5-translation` + LoRA Phase 02 | OpenRAIL |
| Tokenizer (`source.spm`, `vocab.txt`) | Đi kèm hai checkpoint OPUS-MT | Apache-2.0 |
| Dữ liệu IT dùng để train | Core Phase 01 (xem manifest dữ liệu của Core) | Theo từng nguồn dữ liệu |

Ghi chú:

- Student học từ bản dịch do Teacher sinh ra. Giấy phép OpenRAIL coi mô hình tạo ra bằng
  chưng cất từ đầu ra của mô hình gốc là "Derivative of the Model", nên các giới hạn sử dụng
  (use-based restrictions) của OpenRAIL đi kèm gói này. App là đồ án phi thương mại.
- Giữ thông báo Apache-2.0 và ghi công Helsinki-NLP (OPUS-MT), VietAI (EnViT5).
- Chuyển đổi và lượng tử hoá: `AppProduct/tools/mt/build_marian_onnx.py`; đóng gói:
  `AppProduct/tools/mt/package_mt.py`.
