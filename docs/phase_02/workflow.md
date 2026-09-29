# Quy trình thực hiện Phase 02

```text
Phase 1 gate PASS + sample review 50 câu đã khóa
  → Notebook 00: kiểm tra input, checksum và khóa final test
  → 12 pretrained runs: 3 candidate × 2 direction × 2 validation
  → Notebook 01: đối chiếu metrics + output lỗi rõ ràng
  → selection/<direction>.md: người thực hiện ghi candidate được giữ
  → adaptation feasibility trên phần cứng cố định, rồi chỉ IT Train: full FT hoặc LoRA đã ghi trước + training_run.json sau train
  → checkpoint: models/phase02/<experiment_id>/<checkpoint_id>/
  → adapted evaluation: hai evidence directory cho cùng checkpoint
      selection_validation + general_validation
  → Notebook 02: đối chiếu pretrained/adapted, không chọn tự động
  → review 50 câu đã khóa, do người review điền
  → Notebook 03: kiểm tra review hoàn chỉnh và freeze hai Core
  → validate_phase02_final_reporting.py PASS
  → run_phase02_final_evaluation.py: 4 final runs, 2 Core × IT Test/General Test, đúng một lần
  → Notebook 04: báo cáo final, không quay lại selection
```

### 1. Kiểm tra và niêm phong input

Chạy `python tools\validate_phase02_setup.py`. Tạo `releases/phase02_input_it_en_vi.zip` nếu cần chuyển giao input cho người chạy; bundle chỉ gồm train, IT Validation, General Validation, manifest và protocol, không có final test. Lưu output lệnh cùng nhật ký môi trường trong thư mục evidence của run.

### 2. Pretrained screening

Chạy đủ 12 tổ hợp trước khi viết selection record. Runner dùng GenerationConfig checkpoint; ngoại lệ duy nhất là runtime control có nguồn model card chính thức và được ghi trong `configs/phase02_models.json` cùng `run.json`. Batch size chỉ là cách gom inference, không phải tuning. Với NLLB, language control là bắt buộc. EnViT5 dùng `max_length=512` theo model card và chỉ bỏ exact language-control prefix đích trước metric. Một run lỗi để lại `.running` để chẩn đoán và không được notebook tổng hợp như evidence hoàn tất.

Nếu source dài hơn giới hạn input của checkpoint, runner tách xác định theo whitespace, chỉ tách giữa ký tự khi một token liền vượt giới hạn. Mọi ký tự nguồn được giữ lại; từng đoạn dùng GenerationConfig checkpoint cùng runtime control có nguồn model card nếu có và output được ghép lại. `run.json` ghi giới hạn input/policy, còn `metrics.json` ghi số dòng bị tách. Đây là giới hạn kỹ thuật của checkpoint, không phải beam/length tuning.

Người thực hiện đọc Notebook 01, kiểm tra đủ 12 run, số dòng, model identity, chrF++, SacreBLEU và ví dụ output. Sau đó tự viết `selection/en_to_vi.md` và `selection/vi_to_en.md`: candidate giữ/loại, hai validation metrics và lý do cụ thể. Không có weighted score hoặc threshold.

### 3. Feasibility và adaptation có record trước khi train

Kết quả preflight phần cứng được ghi tại
`docs/phase_02/lora_hardware_feasibility.md`. Preflight chỉ xác nhận cấu hình
có thể chạy một training step; nó không dùng chất lượng dịch để chọn tham số và
không thay thế experiment record.

Mỗi candidate giữ có một `experiment_record.md` được hoàn tất trước lệnh train tại `evidence/phase02/<model>/<direction>/adapted/<experiment_id>/`. Record nêu checkpoint đầu vào và revision, train.jsonl + checksum, phương pháp adaptation (`full_ft` hoặc LoRA), mọi LoRA setting nếu dùng, epoch, effective batch size, learning rate, seed, phần cứng, checkpoint dự định lưu/đánh giá và lệnh chạy. Training script chỉ đọc IT Train, lưu checkpoint vào `models/phase02/<experiment_id>/<checkpoint_id>/`, rồi ghi `training_run.json` cùng thư mục experiment. Không xem validation trong quá trình train.

Đánh giá checkpoint đã ghi trước trên IT Validation và General Validation. Cùng một checkpoint phải được dùng ở hai run, tạo hai thư mục evidence riêng theo `<checkpoint_id>/<dataset_role>/`. Nếu một experiment lưu nhiều checkpoint, mỗi checkpoint được đánh giá cần hai evidence directory riêng; experiment record vẫn là một record của cấu hình train đó. Không được giữ checkpoint chỉ vì đã xem General Validation rồi thay đổi lịch train.

### 4. Review và khóa Core

Tạo review sheet bằng `tools\prepare_phase02_review_sheet.py` từ prediction của checkpoint adapted. Reviewer điền đủ 50 dòng bằng `yes`, `no` hoặc `unclear`, ghi notes ngắn khi có lỗi. Notebook 03 chỉ đếm và dẫn các ví dụ đã review; nó không quy đổi review thành điểm.

Người chịu trách nhiệm so sánh từng chiều từ bốn nguồn: adapted IT Validation, General Validation, 50 review, provenance/experiment record. Ghi một Core duy nhất mỗi chiều vào `core_mt_decision.json` và đóng băng trước final. Nếu evidence sát nhau, lý do phải nói rõ mức không chắc chắn; chỉ giữ hai candidate adaptation khi thực sự có năng lực chạy cả hai, không phải để search mở.

### 5. Final reporting

Chạy gate final. Chỉ Core và checkpoint trong decision được phép chạy trên IT Test/General Test, mỗi bộ dữ liệu một lần. Notebook 04 hiển thị result tách theo tập và điều kiện chạy. Final result là kết quả báo cáo, không phải tín hiệu đổi model, checkpoint hay hyperparameter.
