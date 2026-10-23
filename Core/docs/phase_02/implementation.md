# Logic code Phase 02

## Điểm bắt đầu

Phase 02 bắt đầu sau khi Phase 01 gate PASS. Notebook chỉ đọc evidence; các
script trong `tools/` là nơi tạo checkpoint, prediction và metric.

```text
manual/phase_02.md
→ python tools/validate_phase02_setup.py
→ Phase 01 gate + protocol + IT Train/Validation + General Validation + mẫu review
→ PHASE_02_SETUP=PASS
```

Gate trên chỉ đọc input đã khóa và mẫu review; không đọc IT Test hoặc General
Test. Nó là điều kiện để bắt đầu, không tạo một kết quả dịch.

## Đường đi của từng việc

```text
configs/phase02_models.json + validation đã khóa
→ run_phase02_pretrained_baseline.py
→ evidence/phase02/<model>/<direction>/pretrained/<dataset>/

12 pretrained evidence + người thực hiện
→ evidence/phase02/selection/<direction>.md

experiment_record.md đã điền trước + IT Train
→ run_phase02_adaptation.py
→ models/phase02/<experiment_id>/<checkpoint_id>/ + training_run.json

checkpoint + training record + validation đã khóa
→ run_phase02_adapted_evaluation.py
→ hai thư mục adapted evaluation: IT Validation và General Validation

mẫu review đã khóa + adapted prediction
→ prepare_phase02_review_sheet.py
→ reviews/phase02_<model>_<direction>_<checkpoint>_review.csv

validation, review, provenance + người thực hiện
→ evidence/phase02/core_mt_decision.json
→ validate_phase02_final_reporting.py
→ run_phase02_final_evaluation.py
→ bốn final evidence: 2 chiều × IT Test/General Test
```

## Script và lý do có mặt

| Việc cần giải thích | Script | Hàm để mở đầu | Hàm/lớp hỗ trợ cần xem | Output và lý do |
|---|---|---|---|---|
| Kiểm tra input trước Phase 02 | `validate_phase02_setup.py` | `main()` | `load_phase02_protocol()` | PASS/FAIL; chặn input chưa khóa hoặc mở final test sớm |
| Chạy 3 candidate gốc | `run_phase02_pretrained_baseline.py` | `main()` | `load_candidate_spec()` → `PretrainedAdapter.load()` → `run_pretrained_evaluation()` → `score_predictions()` | Prediction, metric và runtime để so sánh cùng data boundary |
| Adapt EnViT5 bằng LoRA | `run_phase02_adaptation.py` | `main()` | `ensure_completed_experiment_record()` → `attach_envit5_lora()` → `Envit5AdaptationDataset` | Checkpoint và training record; chỉ dùng IT Train |
| Đánh giá checkpoint adapted | `run_phase02_adapted_evaluation.py` | `main()` | `sha256_tree()` → `PretrainedAdapter.load()` → `run_pretrained_evaluation()` | Evidence IT Validation và General Validation cùng checkpoint |
| Chuẩn bị review 50 câu | `prepare_phase02_review_sheet.py` | `main()` | `select_review_rows()` → `write_review_template()` | CSV review cố định, không chọn mẫu sau prediction |
| Kiểm tra điều kiện mở final reporting | `validate_phase02_final_reporting.py` | `main()` | Đọc `core_mt_decision.json` | PASS/FAIL trước khi mở final test |
| Chạy final evaluation | `run_phase02_final_evaluation.py` | `main()` | `frozen_selection()` → `run_final_evaluation()` → `score_predictions()` | Bốn final evidence; không quay lại đổi Core |

Khi được hỏi “phần này code ở đâu?”, mở script trong bảng, tìm `main()` rồi
theo chuỗi hàm/lớp ở cột kế bên. Tên function giữ tiếng Anh để khớp code và
thư viện, còn vai trò của nó được mô tả bằng tiếng Việt.

`configs/phase02_protocol.json` là điểm bắt đầu của ràng buộc: candidate,
vai trò dữ liệu, thứ tự gate, metric và các việc bị cấm.
`configs/phase02_models.json` là điểm bắt đầu của model ID và runtime control.
Hai file không tự chọn model. Selection record, review và Core decision đều cần
người thực hiện ghi lý do.

## Giới hạn

Code không tìm candidate mới, không tự tune hyperparameter, không tạo điểm tổng
hợp và không dùng final test để chọn Core. Nếu thay candidate, data boundary,
protocol hoặc cấu hình adaptation, phải tạo experiment/evidence mới thay vì sửa
evidence hoàn tất.
