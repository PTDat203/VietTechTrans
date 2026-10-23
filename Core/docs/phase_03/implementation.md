# Thiết kế implementation Phase 03

Runner tạo target, integrity gate và Student training đều từ chối chạy khi
protocol hoặc experiment record chưa được duyệt. Runner train chỉ nhận staged
target có integrity report `pass`. Student EN→VI v2 và Student v1 VI→EN v3
đều đã có decision freeze cho Phase 4.

| Script / điểm bắt đầu | Hàm để tìm trong code | Input | Output | Chặn khi |
|---|---|---|---|---|
| `validate_phase03_design.py` | `main()` | Config, manifest, Core decision, checkpoint hash | PASS/FAIL chỉ đọc | Protocol sai hoặc chạm test. |
| `run_phase03_teacher_targets.py` | `main()` → `resolve_execution_inputs()` → `validate_targets()` | IT Train hợp lệ theo input-length policy, Teacher đã freeze | Target JSONL | Chưa duyệt experiment record. |
| `reuse_phase03_teacher_targets.py` | `main()` → `validate_policy()` → `validate_parent()` | Target/report `v1` có checksum, policy 3 ID đã duyệt | Target `v2`, lineage, integrity report | Parent artifact khác hash, lỗi cha không đúng ba target rỗng, hoặc chưa duyệt amendment. |
| `reuse_phase03_teacher_targets_vi_to_en_v3.py` | `main()` → `validate_policy()` → `validate_parent()` | Target/report `v2` có checksum, policy 8 ID đã duyệt | Target `v3`, lineage, integrity report | Parent artifact khác hash, lỗi cấu trúc khác, hoặc danh sách 4 target over-limit khác report v2. |
| `run_phase03_student_training.py` | `main()` → `require_cuda_device()` | Target PASS, Student revision | Checkpoint, training run | Record chưa đủ. |
| `run_phase03_student_evaluation.py` | `main()` → `require_approved_inputs()` → `load_comparison_rows()` | Student checkpoint, amendment cohort, một validation set | Student prediction/metric, Teacher comparable metric, provenance | Dataset/Teacher artifact/checkpoint/hash không khớp hoặc source không thuộc cohort đã duyệt. |
| `prepare_phase03_review_sheet.py` | `main()` → `build_review_rows()` | 50 ID sealed, Teacher/Student prediction IT Validation | CSV review và provenance | Thiếu prediction, sai sample, sai source/reference hoặc ghi đè. |
| `validate_phase03_main_core_decision.py` | `main()` → `validate_direction()` → `validate_review()` | Evidence, review, main-core decision | PASS/FAIL chỉ đọc | Identity/hash checkpoint, input, evaluation, review hoặc trạng thái decision thiếu/sai. |
| `build_phase03_student_baseline_comparison.py` | `main()` → `build()` → `select_historical_predictions()` | Prediction OPUS-MT Phase 02, cohort Student v1 | Baseline đã lọc, metric và manifest comparison | Hash/protocol/run lịch sử hoặc cohort không khớp. |
| `validate_phase03_student_baseline_comparison.py` | `main()` → `validate()` | Protocol, baseline, Student v1, Main-Core decision | PASS/FAIL và kết quả comparison | Metric, hash, cohort hoặc Main-Core decision không khớp. |

Mỗi script bắt đầu từ `main()`. Cột hàm cho biết nơi cần mở đầu khi được hỏi
“phần này làm ở đâu?”. Mỗi entry point chỉ làm một việc và chỉ ghi dưới
`<run-id>.running`. Không runner nào nhận đường dẫn tùy ý tới IT Test hoặc
General Test.

## Trạng thái implementation EN→VI v2

`reuse_phase03_teacher_targets.py` được snapshot trong run v2. Nhưng source
snapshot của run chưa có `run_phase03_student_training.py` và
`run_phase03_student_evaluation.py`, dù hai script này đã tạo checkpoint và
evaluation artifact. Đây là giới hạn provenance của run hiện tại, không được
che bằng cách thêm snapshot sau khi chạy. Run mới phải snapshot tất cả runner
trước execution theo `docs/run_snapshot_policy.md`.

Experiment record mỗi chiều phải có run ID, direction, teacher checkpoint/hash, student model/revision, seed, optimizer, learning rate, batch, accumulation, epoch, precision, device và checkpoint cuối. Training evidence ghi thêm parameter count và checkpoint size; đây là metadata đầu vào cho Phase 4, không phải benchmark. Không tạo nhánh student train thường nếu không có protocol mới.

## Trạng thái VI→EN v3

`evidence/phase03/main_core_decision.json` freeze checkpoint EN→VI v2 và
VI→EN v3 làm Main-Core cho Phase 4. Decision giữ hash checkpoint, target input,
metric hai validation và review 50 dòng của mỗi chiều.
`tools/validate_phase03_main_core_decision.py` chỉ kiểm tra tính khớp của các
artifact này; nó không đánh giá lại chất lượng và không tạo hoặc sửa evidence.
