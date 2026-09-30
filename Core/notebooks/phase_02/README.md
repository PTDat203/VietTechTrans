# Notebook evidence Phase 02

Chạy notebook theo thứ tự số. Chúng là sổ đọc evidence: không có cell train, gọi model, sửa dataset, tạo prediction, ghi metric hay điền review. Lệnh tạo evidence nằm trong `tools/`; quyết định selection/Core do người chịu trách nhiệm ghi bằng artifact bất biến.

| Notebook | Gate trước khi mở | Mục đích |
|---|---|---|
| `02_00_protocol_and_inputs.ipynb` | Phase 1 gate | Kiểm tra protocol, số dòng, checksum/manifest và review sample đã khóa |
| `02_01_pretrained_screening.ipynb` | 12 pretrained runs hoàn tất | Hiện bảng metrics/provenance, không xếp hạng tự động |
| `02_02_adaptation_evidence.ipynb` | Experiment record và cùng checkpoint đã có đủ hai validation evidence | Đối chiếu pretrained/adapted theo từng direction; không chọn tự động |
| `02_03_review_and_core_freeze.ipynb` | Review 50 dòng đã điền | Kiểm tra tính đầy đủ của review và hiển thị decision đã freeze |
| `02_04_final_reporting.ipynb` | Final-reporting gate PASS | Báo cáo bốn final run, tách IT Test/General Test |

Không commit prediction, checkpoint hay review nếu chính sách dữ liệu không cho phép. Notebook và evidence nhỏ chỉ được lưu khi phù hợp với chính sách dữ liệu.

Câu hỏi nghiên cứu, lý do chọn candidate và nội dung Phase 02 có thể trình bày:
[README Phase 02](../../docs/phase_02/README.md). Điểm bắt đầu của các script:
[logic code](../../docs/phase_02/implementation.md).
