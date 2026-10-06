# Thiết kế implementation Phase 03

Runner tạo target, integrity gate và Student training đều từ chối chạy khi
protocol hoặc experiment record chưa được duyệt. Runner train chỉ nhận staged
target có integrity report `pass`. Student EN→VI v2 và Student v1 VI→EN v3
đều đã có decision freeze cho Phase 4.

| Entry point dự kiến | Input | Output | Chặn khi |
|---|---|---|---|
| `validate_phase03_design.py` | Config, manifest, Core decision, checkpoint hash | PASS/FAIL chỉ đọc | Protocol sai hoặc chạm test. |
| Teacher-target runner | IT Train hợp lệ theo input-length policy, Teacher đã freeze | Target JSONL | Chưa duyệt experiment record. |
| Target validator | Eligible manifest, target staging, Student tokenizer | Integrity report đầy đủ | Sai data boundary, thiếu/trùng ID, có ID bị loại, hash sai, target rỗng; hoặc target vượt capacity thì chặn Student training nhưng giữ target staging. |
| Teacher-target reuse v2 | Target/report `v1` có checksum, policy 3 ID đã duyệt | Target `v2`, lineage, integrity report | Parent artifact khác hash, lỗi cha không đúng ba target rỗng, hoặc chưa duyệt amendment. |
| Teacher-target reuse VI→EN v3 | Target/report `v2` có checksum, policy 8 ID đã duyệt | Target `v3`, lineage, integrity report | Parent artifact khác hash, lỗi cấu trúc khác, hoặc danh sách 4 target over-limit khác report v2. |
| Student-training runner | Target PASS, Student revision | Checkpoint, training run | Record chưa đủ. |
| Evaluation runner | Student checkpoint, amendment cohort, một validation set | Student prediction/metric, Teacher comparable metric, provenance | Dataset/Teacher artifact/checkpoint/hash không khớp hoặc source không thuộc cohort đã duyệt. |
| Review-sheet runner | 50 ID sealed, Teacher/Student prediction IT Validation | CSV review và provenance | Thiếu prediction, sai sample, sai source/reference hoặc ghi đè. |
| Freeze validator | Evidence, review, main-core decision | PASS/FAIL chỉ đọc | Identity/hash checkpoint, input, evaluation, review hoặc trạng thái decision thiếu/sai. |

Mỗi entry point chỉ làm một việc và chỉ ghi dưới `<run-id>.running`. Không runner nào nhận đường dẫn tùy ý tới IT Test hoặc General Test.

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
