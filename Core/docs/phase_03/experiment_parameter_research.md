# Nghiên cứu lựa chọn thông số experiment Phase 03

## Mục đích và cách đọc

Tài liệu này chuẩn bị các quyết định phải được khóa trong hai experiment record
EN→VI và VI→EN. Nó không phải experiment record, không cho phép execution và
không gọi bất kỳ giá trị nào là tối ưu. "Đề xuất" chỉ là lựa chọn một run có
thể tái lập, phải được người phụ trách duyệt trước khi protocol được freeze.

Với EN→VI, cấu hình này đã được dùng cho run v2. Kết quả cho thấy cấu hình chạy
được, không chứng minh nó là cấu hình tốt nhất: Student thấp hơn Teacher trên
cả IT Validation cohort và General Validation. Vì vậy không dùng tài liệu này
để suy ra Student EN→VI của run `phase03-seqkd-en-to-vi-v2` nên được freeze;
xem [README Phase 03](README.md).

Sequence-to-sequence training dùng `Seq2SeqTrainer`/`Seq2SeqTrainingArguments`;
Transformers cũng ghi rõ batch tổng phụ thuộc micro-batch và gradient
accumulation. [Trainer documentation](https://huggingface.co/docs/transformers/main_classes/trainer)

## Thông số Teacher và dữ liệu

| Mục cần khóa | Các lựa chọn đã xem | Đề xuất/trạng thái | Lý do và giới hạn |
|---|---|---|---|
| Teacher checkpoint, hash, language controls | Đổi checkpoint; dùng Core đã freeze | **Đã khóa từ Phase 02** | Đổi Teacher sẽ làm thay đổi nguồn tri thức; không thuộc Phase 03. |
| KD method | Sequence-level; logit/feature KD | **Đã duyệt: sequence-level** | Chỉ truyền source–target text; không cần mapping vocabulary/layer. Xem [research record](kd_method_selection.md). |
| Teacher precision | FP32; FP16 | **FP16** | Preflight chạy được trên RTX 3050 Ti 4 GiB. Đây là quyết định khả năng chạy, không phải chọn chất lượng. |
| Teacher generation batch | 8; 16 | **16, người phụ trách đã chọn** | Benchmark trên 16 dòng hợp lệ gồm câu thường và dài cho thấy throughput tăng 25,7% EN→VI và 17,2% VI→EN so với batch 8; target hash giống nhau. Xem `phase03-teacher-batch-8-vs-16-v2.json`. |
| Teacher decoding | Đổi beam/temperature; giữ Phase 02 runtime | **Giữ greedy runtime đã dùng:** `max_length=512`, `do_sample=false`, `num_beams=1`, `length_penalty=1.0`, `repetition_penalty=1.0` | Checkpoint local không có `generation_config.json`; các giá trị effective từ Transformers 4.57.6 được ghi tường minh vào record, không phải một lựa chọn decoding mới. Batch không đổi target; không dùng Validation để tune. |
| Train input EN→VI | 121.547 dòng; loại dòng | **v1: 121.547 dòng; v2: 121.544 dòng** | Audit source không thấy dòng nào vượt 512 token. V1 có ba Teacher target rỗng; v2 chỉ tái sử dụng các target không rỗng theo policy đã duyệt, không sửa IT Train. |
| Train input VI→EN | Truncate; segment; loại source lỗi đã khóa | **v3: 121.539 dòng** | V2 đã loại bốn source theo input policy; v3 loại thêm đúng bốn pseudo-target Teacher bị lặp và vượt 512 token theo integrity report. Không truncate, sửa target hoặc chạy Teacher lại. File nguồn Phase 01 không bị sửa. |

## Thông số Student cần duyệt

| Mục cần khóa | Các lựa chọn đã xem | Đề xuất của tôi | Căn cứ, giới hạn và điều kiện duyệt |
|---|---|---|---|
| Checkpoint/revision, tokenizer, prefix | Đổi Student; dùng revision đã ghi | **Giữ Student đã khóa** | Phục vụ tái lập. Không đánh giá lại candidate Phase 02. |
| Precision training | FP32; FP16 | **Giữ default `fp16=True`: PASS** | Runtime preflight với trọng số FP32 + AMP FP16 đã PASS ở câu dài hợp lệ: peak reserved 2,463 GiB EN→VI và 2,279 GiB VI→EN. |
| Physical batch | 1, 2, 4 | **4: PASS** | Preflight dùng bốn micro-batch trên 16 câu dài hợp lệ và không OOM. |
| Gradient accumulation | 1, 2, 4, lớn hơn | **4** → effective batch `4 × 4 = 16`: PASS | Giữ micro-batch trong VRAM nhưng update sau 16 pair. Preflight đã chạy đủ bốn micro-batch trước một update. Giá trị này không được gọi là tốt nhất. |
| Optimizer | `adamw_torch`; fused AdamW; optimizer mới | **Giữ default `adamw_torch_fused`: PASS** | Runtime hiện tại mặc định fused AdamW; preflight đã chạy đủ optimizer state và update với biến thể này. Không cần optimizer mới. [Trainer documentation](https://huggingface.co/docs/transformers/main_classes/trainer), [PyTorch AdamW reference](https://docs.pytorch.org/docs/stable/generated/torch.optim.AdamW.html). |
| Learning rate | Copy LR LoRA Phase 02; mặc định Trainer; LR tùy ý sau validation | **Giữ default `5e-5`: numerically PASS** | Đây là default hiện hành của `TrainingArguments`; một update dài ở cả hai chiều có loss hữu hạn. Điều đó chỉ chứng minh không lỗi số học trong preflight, không chứng minh `5e-5` tối ưu. |
| Scheduler / warmup | Linear; các scheduler khác | **Giữ default Linear, warmup ratio `0.0`: PASS** | Preflight đã tạo và step scheduler này. Không thêm schedule mới không có mục tiêu thiết kế. |
| Weight decay | Không khai báo; nhiều giá trị thử | **Giữ default `0.0`: PASS** | Không đưa regularization mới mà không có căn cứ riêng hoặc validation-tuning. |
| Epoch | 1; nhiều epoch và chọn theo validation | **1 epoch — đã duyệt** | Giới hạn run trước khi nhìn validation; tạo đúng một checkpoint cuối và tránh lựa chọn checkpoint theo metric. Preflight runtime hiện cho ước lượng compute-only khoảng 1,34 giờ EN→VI và 1,05 giờ VI→EN/epoch ở effective batch 16; chưa gồm I/O. Một epoch không được khẳng định là tốt nhất. |
| Seed / data seed | Không ghi seed; một seed; nhiều seed | **42 cho cả hai — đã duyệt** | Cố định seed để tái lập shuffle và runtime. Một seed không chứng minh độ ổn định thống kê; chạy nhiều seed là nghiên cứu mở rộng, không tự thêm. [PyTorch reproducibility notes](https://docs.pytorch.org/docs/stable/notes/randomness.html). |
| Gradient clipping | Không clipping; nhiều ngưỡng | **Giữ default `max_grad_norm=1.0`: PASS** | Preflight đã thực hiện clip trước optimizer step. Ghi tường minh để không phụ thuộc default library ngầm. |
| Evaluation batch | Giảm xuống 1–4; giữ default 8 | **Giữ default `per_device_eval_batch_size=8`: PASS** | Student generation batch 8 đã PASS trên tám câu dài hợp lệ: peak reserved 2,479 GiB EN→VI và 2,289 GiB VI→EN. Đây là check capacity, không dùng validation data. |
| Checkpoint / evaluation trong train | Nhiều checkpoint + chọn best; checkpoint cuối | **Không checkpoint định kỳ; runner lưu đúng một checkpoint cuối `final-epoch-1`; không evaluation trong train; `load_best_model_at_end=false`** | Tránh path `checkpoint-<step>` do Trainer tự đặt. Validation chỉ dùng sau training để đánh giá run đã khóa, không điều khiển checkpoint/hyperparameter. |

## Rủi ro chưa được che giấu

| Vấn đề | Hệ quả nếu không khóa trước | Cách xử lý trước execution |
|---|---|---|
| Pseudo-target Teacher có thể token hóa dài hơn 512 token theo tokenizer Student, dù source hợp lệ. | Student decoder cũng có positional embedding 512; train có thể lỗi hoặc truncate ngầm. | **Đã duyệt:** sau generation, gate quét 100% target, ghi toàn bộ `dataset_row_id` + token length và chặn Student training nếu có lỗi. Target staged được giữ; không truncate, tự loại hay chạy Teacher lại. |
| Student batch 4 với AMP/optimizer mặc định có thể OOM ở câu dài. | Nếu không đo đúng runtime, memory preflight FP16 thuần không đủ đại diện. | Đã PASS trên 16 câu dài hợp lệ, với bốn micro-batch và một update: `phase03-student-training-runtime-v1.json`. |
| Các giá trị LR, epoch và accumulation chưa có validation evidence. | Không thể nói chúng tối ưu. | Duyệt chúng là protocol trước run; báo cáo kết quả như một experiment đã định trước, không như kết quả search. |

## Bản tóm tắt để duyệt experiment record

Hai record chỉ nên dùng các giá trị dưới đây nếu preflight Student batch 4 PASS:

```text
Teacher: FP16, batch 16, frozen Phase 02 generation runtime, max_length 512
Student: FP16, physical batch 4, gradient accumulation 4, effective batch 16
Training: adamw_torch_fused, learning rate 5e-5, linear scheduler, warmup 0,
          weight decay 0, max grad norm 1, epoch 1, seed/data seed 42
Evaluation: per-device batch 8
Checkpoint: final epoch 1 only; no evaluation/checkpoint selection in training
```

Điều kiện bắt buộc còn lại: Student preflight trên câu dài hợp lệ PASS và
target-integrity gate có kiểm tra capacity 512 của label theo tokenizer Student.
