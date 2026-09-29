# Notebook Phase 01

Pipeline chính của Phase 01 là `tools/build_phase01.py`. Notebook chỉ đọc
artifact đã khóa để kiểm tra và trình bày; không phải nơi tạo dataset chính thức.

| Notebook | Nội dung đọc và trình bày |
|---|---|
| `01_01_data_collection.ipynb` | nguồn, bản sao dữ liệu gốc và checksum |
| `01_02_data_audit.ipynb` | tín hiệu audit theo nguồn |
| `01_03_data_cleaning.ipynb` | quy tắc làm sạch, attrition và loại trùng |
| `01_04_it_data_groups.ipynb` | primary group, technical tag và group method |
| `01_05_dataset_release.ipynb` | split, manifest, rò rỉ dữ liệu và Phase 01 gate |

Notebook không đánh giá model, không tạo prediction/metric và không sửa release.
Logic thực thi nằm trong [implementation.md](../../docs/phase_01/implementation.md).
Câu hỏi, câu trả lời và dẫn chứng nằm trong
[README Phase 01](../../docs/phase_01/README.md).
