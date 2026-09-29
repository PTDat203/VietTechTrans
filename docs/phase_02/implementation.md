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

| Điểm bắt đầu | Script | Việc script làm | Lý do |
|---|---|---|---|
| Protocol và validation | `validate_phase02_setup.py` | Kiểm tra Phase 01 gate, input và mẫu review | Chặn việc chạy khi input chưa khóa hoặc mở final test sớm |
| Model config + một validation set | `run_phase02_pretrained_baseline.py` | Chạy một candidate, ghi prediction, metric, runtime | So sánh candidate trong cùng data boundary |
| Selection record đã viết trước | `run_phase02_adaptation.py` | Chỉ đọc IT Train, lưu một checkpoint và training record | Không dùng validation để train hoặc chọn checkpoint |
| Checkpoint đã ghi provenance | `run_phase02_adapted_evaluation.py` | Kiểm tra hash checkpoint, chạy trên hai validation set | Liên kết cùng checkpoint với evidence trong miền và ngoài miền |
| Mẫu 50 dòng đã khóa | `prepare_phase02_review_sheet.py` | Ghép prediction vào CSV review, không ghi đè file đã có | Tránh chọn mẫu sau khi xem output |
| Core decision do người thực hiện freeze | `validate_phase02_final_reporting.py` | Kiểm tra đúng hai chiều, model, checkpoint và lý do | Chỉ mở final reporting sau quyết định |
| Final gate PASS | `run_phase02_final_evaluation.py` | Đọc model/checkpoint từ decision, chạy đúng một lần mỗi final set | Final result không thể quay lại đổi Core |

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
