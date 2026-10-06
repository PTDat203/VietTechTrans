# Phase 03 — hai lần duyệt trước execution

## Trạng thái Phase 03

Các lệnh bên dưới mô tả quy trình trước execution hoặc cho một run mới được
duyệt; không dùng chúng để chạy lại hay ghi đè `phase03-seqkd-en-to-vi-v1/v2`.
EN→VI v1 đã dừng ở integrity gate vì ba target rỗng. EN→VI v2 đã tái sử dụng
121.544 target hợp lệ, train Student một epoch, đánh giá hai validation set và
hoàn tất review 50 câu. Checkpoint v2 EN→VI và v3 VI→EN đều đã freeze làm
Main-Core cho Phase 4. Artifact lịch sử giữ nguyên ở `.running` theo đường dẫn
đã khóa trong decision, không chạy lại hoặc ghi đè chúng.

Nếu kiểm tra evidence hiện có, chỉ chạy validator đọc file và dùng đúng Python
environment đã ghi ở bước 1; không chạy lại teacher target, training hoặc
evaluation dưới cùng run ID.

## Kiểm tra code và evidence hiện có

`pytest` là dependency của project. Sau khi kích hoạt environment, chạy:

```bat
python -m pytest tests -q
python tools\validate_phase03_review_sheet.py --direction en_to_vi --review reviews\phase03_phase03-seqkd-en-to-vi-v2_en_to_vi_review.csv
python tools\validate_phase03_evaluation_input_length_amendment.py
```

Các lệnh này chỉ kiểm tra logic test, cấu trúc review và amendment cohort; chúng
không tạo target, checkpoint, prediction hay metric.

## Lần duyệt 1: thiết kế

1. Trong PowerShell, kích hoạt cho phiên terminal hiện tại môi trường đã có đủ dependency và CUDA:

   ```powershell
   $env:Path = "C:\Users\ADMIN\anaconda3\envs\envi-it-mt-phase6-gpu;C:\Users\ADMIN\anaconda3\envs\envi-it-mt-phase6-gpu\Scripts;$env:Path"
   python --version
   ```

   Không dùng `envi-it-mt-phase2` cho execution: môi trường đó có Python nhưng thiếu `torch`, `transformers` và `sacrebleu`.

2. Đọc `configs/phase03_protocol.json`, `docs/phase_03/README.md` và `docs/phase_03/kd_method_selection.md`.
3. Chạy:

   ```bat
   python tools\validate_phase03_design.py
   ```

4. Gate chỉ đọc input/protocol; không tải model, sinh target hay metric.
5. Người phụ trách duyệt hoặc yêu cầu sửa thiết kế.

## Hardware preflight đã được duyệt

Hardware preflight chỉ trả lời cấu hình GPU 4 GiB có thể chạy được ở mức nào.
Nó dùng đúng tám dòng đầu của IT Train theo file order, chỉ giữ pseudo-target
trong RAM để tạo một micro-batch Student, và không ghi source/reference/target
vào artifact. Mỗi batch Student có đúng một forward, backward và AdamW step với
learning rate `0.0`, nên không làm thay đổi checkpoint hay quyết định learning
rate cho experiment.

Chạy một lần với ID mới:

```bat
python tools\run_phase03_hardware_preflight.py --preflight-id phase03-hardware-preflight-v1
```

Kết quả được ghi bất biến tại
`evidence/phase03/preflight/phase03-hardware-preflight-v1.json`. Khi chạy xong,
duyệt riêng batch vật lý/precision khả dụng. Gradient accumulation, optimizer,
learning rate, epoch, seed và checkpoint rule vẫn phải được ghi trong hai
experiment record và duyệt trước execution.

Khi cần so batch Teacher lớn hơn, benchmark phải dùng các câu dài đã biết và
chứng minh target tạo ở batch khác nhau vẫn giống nhau. Ví dụ batch 8/16:

```bat
python tools\benchmark_phase03_teacher_batch.py --benchmark-id phase03-teacher-batch-8-vs-16-v1
```

Record chỉ lưu throughput, VRAM, row ID và hash so sánh output; không lưu target.

## Audit giới hạn input của Student

Trước khi viết experiment record, đo toàn bộ IT Train bằng đúng tokenizer và
revision của từng Student. Audit chỉ ghi thống kê token và `dataset_row_id`,
không ghi text hay sinh bản dịch:

```bat
python tools\audit_phase03_input_lengths.py --audit-id phase03-input-length-audit-v1
```

Kết quả hiện có tại `evidence/phase03/design_audits/phase03-input-length-audit-v1.json`.
Nếu có dòng vượt `max_position_embeddings`, người phụ trách phải duyệt một
input-length policy cố định trước execution; runner không được tự truncate hay
loại dòng.

Trước khi cân nhắc tăng giới hạn trong policy, kiểm tra checkpoint có thực sự
xử lý được các dòng vượt giới hạn hay không:

```bat
python tools\check_phase03_student_length_capacity.py --check-id phase03-vi-to-en-length-capacity-v1
```

Policy gốc tại `configs/phase03_input_length_policy.json` loại hai source
VI→EN có 552 và 515 token. VI→EN v2 kế thừa hai exclusion đó và loại thêm đúng
hai pair không có nội dung dịch `°↔°`, `æ↔æ`, nên dùng 121.543 dòng. File IT
Train gốc không bị sửa. Teacher target runner và integrity gate đọc policy v2
qua run ID được duyệt.

Preflight Student đầy đủ hơn, dùng 16 câu dài hợp lệ, target Teacher trong bộ
nhớ và một update AMP thực tế, chạy bằng:

```bat
python tools\preflight_phase03_student_training_config.py --preflight-id phase03-student-training-runtime-v1
```

Record `phase03-student-training-runtime-v2.json` xác nhận cấu hình default
hiện tại phù hợp hardware: `fp16`, `adamw_torch_fused`, learning rate `5e-5`,
linear scheduler/warmup `0`, weight decay `0`, max grad norm `1`, physical
batch `4`, gradient accumulation `4` và evaluation batch `8`. Đây không phải
evidence chất lượng.

## Lần duyệt 2: experiment

Sequence-level KD đã được duyệt trong `docs/phase_03/kd_method_selection.md`. Với
EN→VI, record đã chạy là `configs/phase03_experiments/phase03-seqkd-en-to-vi-v2.json`
và reuse protocol tương ứng. VI→EN v2 đã dừng ở integrity gate; VI→EN tiếp tục
với run reuse `phase03-seqkd-vi-to-en-v3`. Trước execution phải chạy
`python tools\validate_phase03_vi_to_en_v3.py`. Record phải khóa run ID, model revision, checkpoint hash, seed,
optimizer, learning rate, batch, gradient accumulation, epoch, precision, thiết
bị, checkpoint cuối và integrity behavior.

Thiết bị đã preflight và được khóa trong hai record là `cuda:0` — NVIDIA
GeForce RTX 3050 Ti Laptop GPU (4 GiB). Sau khi duyệt, mỗi chiều chạy target
runner một lần; runner tự tạo staging và không nhận đường dẫn dataset tùy ý:

```bat
python tools\run_phase03_teacher_targets.py --direction en_to_vi
python tools\reuse_phase03_teacher_targets_vi_to_en_v3.py
```

Runner sinh và lưu toàn bộ pseudo-target trước khi scan. Integrity report ghi
tất cả row vượt 512 token; khi có bất kỳ row nào, runner giữ staging và trả
trạng thái blocked, không train Student, truncate, loại row hoặc sinh Teacher
lại.

Chỉ khi integrity report của một chiều trả `pass`, train đúng chiều đó bằng:

```bat
python tools\run_phase03_student_training.py --direction en_to_vi
python tools\run_phase03_student_training.py --direction vi_to_en --run-id phase03-seqkd-vi-to-en-v3
```

## Student v1 VI→EN đã freeze

`phase03-seqkd-en-to-vi-v2` và `phase03-seqkd-vi-to-en-v3` đã được freeze làm
Main-Core cho Phase 4.
Không chạy lại target, training hoặc evaluation dưới run ID này. Kiểm tra
decision đã khóa bằng một lệnh:

```bat
python tools\validate_phase03_main_core_decision.py
python tools\validate_phase03_provenance.py
```

Hai lệnh chỉ đọc artifact. Lệnh thứ hai trả
`PASS_WITH_DISCLOSED_HISTORICAL_LIMIT`: EN→VI thiếu snapshot mã train/evaluate,
runtime và input manifest; VI→EN thiếu runtime và input manifest. Đây là giới
hạn tái lập đã ghi nhận, không được tạo snapshot hồi tố. Khi Phase 4 bắt đầu
trên máy khác, lấy checkpoint và hash từ `main_core_decision.json` theo
`docs/phase_04/README.md`; repository này không tạo artifact Phase 4.

Hai thư mục Main-Core vẫn có hậu tố `.running` để bảo toàn path và checksum
lịch sử. Không đổi tên hoặc di chuyển chúng; trạng thái chính thức do
`main_core_decision.json` xác định.

Runner kiểm tra GPU đã khóa, hash snapshot Student và hash target đã được
integrity scan. Nó không dùng checkpoint định kỳ của Trainer; sau đúng một
epoch, checkpoint duy nhất được lưu tại `final-epoch-1` như record.

Thứ tự sau khi được duyệt:

```text
design gate → snapshot → teacher targets → integrity gate → training
→ IT Validation → General Validation → review 50 row ID → freeze/reject
→ Student accepted becomes Main-Core
```

IT Test và General Test không xuất hiện trong lệnh/input Phase 03. Run lỗi giữ trong `.running`; không ghi đè cùng run ID.
