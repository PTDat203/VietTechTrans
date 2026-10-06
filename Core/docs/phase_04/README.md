# Phase 04 — lấy Main-Core từ Phase 3

Phase 4 được thực hiện trên máy local khác. Repository này không chạy convert,
quantization, benchmark hoặc tạo bất kỳ artifact Phase 4 nào.

Người thực hiện lấy thông tin gốc từ
`evidence/phase03/main_core_decision.json`:

| Chiều | Run Main-Core | Checkpoint | SHA-256 tree |
|---|---|---|---|
| EN→VI | `phase03-seqkd-en-to-vi-v2` | `models/phase03/phase03-seqkd-en-to-vi-v2/final-epoch-1` | `c2f037f8548437bbd6edcaabc33b91f65c6fc7dcbd1124aa78239afe4b8e43bb` |
| VI→EN | `phase03-seqkd-vi-to-en-v3` | `models/phase03/phase03-seqkd-vi-to-en-v3/final-epoch-1` | `fbcba2c1dbaa377073ab69893fabb51127c9de39609d1815f8fb97202442cb53` |

Trước khi mang checkpoint sang máy khác, chạy:

```bat
python tools\validate_phase03_main_core_decision.py
python tools\validate_phase03_provenance.py
```

Sau đó chép mỗi thư mục checkpoint cùng `main_core_decision.json`. Ở máy Phase
4, đối chiếu lại hash cây thư mục với bảng trên trước khi dùng. Metric validation,
review và giới hạn tái lập nằm trong decision để làm mốc tham chiếu, không phải
kết quả của Phase 4.

Hai artifact lịch sử Phase 3 vẫn có hậu tố `.running`; không đổi tên hoặc di
chuyển chúng trong repository này. Trạng thái freeze nằm trong decision.
