# Báo cáo cấu hình LoRA trước IT adaptation

## Phạm vi báo cáo

Tài liệu này chốt cấu hình dùng cho một adaptation EnViT5 ở mỗi chiều EN→VI
và VI→EN trong Phase 02. Adaptation chỉ học từ IT Train. IT Validation,
General Validation và hai tập test không được dùng trong train hoặc để đổi
cấu hình này.

Preflight kiểm tra khả năng chạy của một optimizer update trên GPU. Nó không
đo chất lượng dịch, không chọn Core MT và không thay thế adapted evaluation.

## LoRA là gì?

EnViT5 là model gốc đã biết dịch EN↔VI. Bên trong model có nhiều trọng số:
các con số ảnh hưởng đến cách model xử lý câu và sinh bản dịch.

Full fine-tuning sửa gần như toàn bộ trọng số. LoRA giữ phần lớn trọng số
EnViT5 cố định, rồi gắn thêm adapter nhỏ vào một số lớp. Khi train, chỉ adapter
này được cập nhật. Cách này giảm số tham số cần học và bộ nhớ training. Bài báo
LoRA gốc mô tả việc đóng băng trọng số pretrained và thêm các ma trận low-rank
có thể học. [Hu et al., Abstract](https://arxiv.org/abs/2106.09685)

Sau adaptation, model đánh giá vẫn là **EnViT5 gốc kết hợp adapter LoRA**.
LoRA không tạo dữ liệu, không sửa dữ liệu, không phải student model và không
phải knowledge distillation. Student cho Android thuộc Phase 3, sau khi Core MT
được khóa.

## Vì sao không dùng full fine-tuning?

Máy thực hiện có NVIDIA GeForce RTX 3050 Ti Laptop GPU, 4.00 GiB VRAM.
Preflight trước đây chạy forward, backward và AdamW update ở batch size 1 trên
pair IT Train dài nhất.

| Phương pháp | EN→VI peak reserved | VI→EN peak reserved | So với 4.00 GiB VRAM |
|---|---:|---:|---|
| Full fine-tuning FP32 | 5.51 GiB | 5.54 GiB | Vượt giới hạn |
| Full fine-tuning FP16 | 5.75 GiB | 5.76 GiB | Vượt giới hạn |
| LoRA FP16, batch 1 | 3.50 GiB | 3.39 GiB | Chạy được một micro-batch |

Full fine-tuning đã vượt VRAM ngay tại batch 1. Gradient accumulation không
khắc phục được điều đó vì mỗi micro-batch full fine-tuning đã không vừa.
LoRA được chọn vì cấu hình adapter nhỏ chạy được trên GPU của project.

## Kết quả kiểm tra cấu hình adaptation

Ngày kiểm tra: 22-09-2026. Preflight dùng tám pair dài nhất được chọn xác định
từ IT Train: `92709`, `109601`, `55264`, `53722`, `67021`, `21750`,
`37224`, `35339`. Mỗi micro-batch có một pair. Loss được chia cho 8,
gradient được tích lũy qua tám micro-batch, rồi AdamW thực hiện đúng một update
trong bộ nhớ. Mọi trạng thái bị hủy sau khi chạy; không có checkpoint,
prediction hay metric nào được ghi ra.

| Cấu hình đã test | EN→VI | VI→EN |
|---|---:|---:|
| Pair dài nhất trong tám pair | 616 token | 617 token |
| Per-device batch size | 1 | 1 |
| Gradient accumulation | 8 | 8 |
| Effective batch size | 8 | 8 |
| Learning rate | 2e-4 | 2e-4 |
| Precision | FP16 | FP16 |
| LoRA | `q,v`, rank 8, alpha 16, dropout 0 | `q,v`, rank 8, alpha 16, dropout 0 |
| Peak allocated | 3.38 GiB | 3.34 GiB |
| Peak reserved | 3.62 GiB | 3.59 GiB |
| Dedicated VRAM | 4.00 GiB | 4.00 GiB |
| Kết quả | PASS | PASS |

Kết quả này xác nhận cấu hình có thể hoàn thành một optimizer update với
effective batch size 8. Nó không chứng minh một epoch sẽ cải thiện chrF++ hay
không gặp lỗi trong toàn bộ training run.

## Cấu hình adaptation được chốt

| Thông số | Giá trị | Căn cứ | Mức kết luận |
|---|---|---|---|
| Candidate | `VietAI/envit5-translation` | Candidate dẫn đầu screening ở cả hai chiều; selection record Phase 02. | Được adaptation, chưa là Core MT. |
| Dữ liệu học | IT Train, 121,547 pair | Protocol Phase 02 chỉ cho phép IT Train dùng để học. | Bắt buộc. |
| Method | LoRA | Full fine-tuning không vừa VRAM; LoRA chỉ học adapter nhỏ. [Hu et al.](https://arxiv.org/abs/2106.09685) | Đã chốt theo hardware. |
| Task type | `SEQ_2_SEQ_LM` | EnViT5 sinh chuỗi cho dịch máy; PEFT có cấu hình riêng cho seq2seq. [PEFT LoRA reference](https://huggingface.co/docs/peft/en/package_reference/lora) | Đã chốt theo loại model. |
| Target modules | `q`, `v` | Ví dụ PEFT seq2seq dùng `target_modules=["q", "v"]`. [PEFT example](https://huggingface.co/docs/peft/en/package_reference/lora#peft.LoraModel) | Điểm khởi đầu có nguồn; preflight PASS. |
| LoRA rank | `8` | Ví dụ PEFT seq2seq dùng `r=8`; preflight PASS với EnViT5. [PEFT example](https://huggingface.co/docs/peft/en/package_reference/lora#peft.LoraModel) | Không gọi là rank tối ưu. |
| LoRA alpha | `16` | Giá trị project khai báo trước và PASS cùng rank 8. PEFT định nghĩa `lora_alpha` là cấu hình adapter. [LoraConfig](https://huggingface.co/docs/peft/en/package_reference/lora#peft.LoraConfig) | Không có nguồn chứng minh 16 tốt nhất cho IT Train. |
| LoRA dropout | `0` | Giá trị project khai báo trước và PASS. PEFT ghi `lora_dropout` là tham số adapter. [LoraConfig](https://huggingface.co/docs/peft/en/package_reference/lora#peft.LoraConfig) | Không gọi là dropout tối ưu. |
| Precision | FP16 | Mixed precision có thể giảm memory footprint. [PyTorch AMP](https://docs.pytorch.org/tutorials/recipes/recipes/amp_recipe.html) Preflight cũng PASS. | Đã chốt theo hardware. |
| Per-device batch size | `1` | Batch 1 chạy được trong preflight 4 GB VRAM. | Không suy ra batch 2 chạy được. |
| Gradient accumulation | `8` | Accumulation là nhiều micro-batch trước một optimizer update. Giá trị 8 tạo effective batch 8 từ batch vật lý 1 và PASS preflight. [Transformers guide](https://huggingface.co/docs/transformers/grad_accumulation) | Giá trị project chọn, không gọi là tối ưu. |
| Learning rate | `2e-4` | TRL nói LoRA thường dùng learning rate cao hơn full fine-tuning và đưa `2e-4` làm ví dụ LoRA. [TRL guide](https://huggingface.co/docs/trl/en/peft_integration#learning-rate-considerations) Giá trị này PASS preflight. | Điểm khởi đầu có nguồn, không phải tối ưu được chứng minh. |
| Epoch | `1` | Ngân sách adaptation cố định để rút ngắn thời gian trên GPU 4 GB. Không tối ưu hóa theo validation. | Giá trị project chọn trước run. |
| Optimizer | AdamW | Runner Phase 02 dùng `adamw_torch`; preflight bao gồm trạng thái AdamW khi đo VRAM. | Đã chốt theo runner. |
| Scheduler | Linear | Runner Phase 02 dùng linear scheduler. | Đã chốt theo runner; không đổi sau validation. |
| Warmup ratio | `0.0` | Runner Phase 02 dùng warmup ratio 0.0. | Đã chốt theo runner; không gọi là tối ưu. |
| Seed và data seed | `42` | `TrainingArguments` có `seed` để tái lập training; 42 là default hiện hành của API. [TrainingArguments](https://huggingface.co/docs/transformers/main/main_classes/trainer) | Phục vụ tái lập, không nhằm tăng chất lượng. |
| Checkpoint lưu | Một checkpoint cuối | Runner không evaluate trong train và chỉ lưu sau đủ một epoch. | Tránh chọn checkpoint bằng Validation trong train. |
| Input/target truncation | Không truncation | Protocol và runner đã khóa; không cắt câu để giảm bộ nhớ. | Bắt buộc. |

## Cách dùng nguồn đúng mức

Một nghiên cứu dùng trực tiếp `VietAI/envit5-translation` đã dùng full
fine-tuning với learning rate `4e-5`, batch 64, 200 epoch, FP16,
inverse-square-root scheduler và warmup 200 trên bốn RTX A6000.
[Lee et al., IWSLT 2023, §3.2 và Appendix B.2](https://aclanthology.org/2023.iwslt-1.40.pdf)

Nguồn đó cho thấy EnViT5 có thể được fine-tune bằng Transformers, nhưng không
phải lý do để sao chép các số đó: task, method và hardware của project khác.
Không nguồn nào biết trước learning rate, epoch, rank hoặc effective batch tốt
nhất cho đúng IT Train 121,547 pair.

| Loại căn cứ | Giá trị |
|---|---|
| Hardware và preflight | LoRA, FP16, batch 1, gradient accumulation 8 trên đúng cấu hình adapter đã nêu. |
| Nguồn làm điểm khởi đầu và preflight | `q,v`, rank 8, learning rate 2e-4. |
| Quyết định project ghi trước, không gán là tối ưu | Alpha 16, dropout 0, epoch 1, linear scheduler, warmup 0, seed 42. |

## Việc được làm tiếp

Trước khi train, tạo hai experiment record: một cho EN→VI và một cho VI→EN.
Mỗi record phải chép đúng cấu hình trên, checkpoint ID dự kiến và phần cứng
thực tế. Runner chỉ train bằng IT Train. Khi train xong, cùng checkpoint được
đánh giá trên IT Validation và General Validation. Không mở IT Test hoặc
General Test ở giai đoạn này.
