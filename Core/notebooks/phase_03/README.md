# Notebook bằng chứng Phase 03

Phase 03 chạy bằng runner và validator trong `tools/`. Notebook chỉ đọc
evidence đã khóa để trình bày; không chạy lại target, training, evaluation,
review hoặc freeze decision.

| Notebook | Nội dung | Mục đích |
|---|---|---|
| `03_01_main_core_evidence.ipynb` | Main-Core hai chiều, checkpoint/hash, metric validation và review | Trình bày Student đã freeze và giới hạn kết quả trước khi người khác lấy checkpoint sang máy Phase 4. |

Nguồn quyết định là
[`evidence/phase03/main_core_decision.json`](../../evidence/phase03/main_core_decision.json).
Hướng dẫn lấy checkpoint sang máy khác nằm trong
[`docs/phase_04/README.md`](../../docs/phase_04/README.md). Repository này không
tạo artifact Phase 4.
