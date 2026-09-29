# Experiment record — Phase 02 adaptation

Sao chép file này thành `evidence/phase02/<model>/<direction>/adapted/<experiment_id>/experiment_record.md` và điền trước khi train. Một record tương ứng với một cấu hình train. Checkpoint của experiment được lưu ở `models/phase02/<experiment_id>/<checkpoint_id>/`; evidence đánh giá của từng checkpoint nằm ở `.../adapted/<experiment_id>/<checkpoint_id>/<dataset_role>/`. Không thay giá trị đã ghi sau khi bắt đầu run; nếu đổi thiết lập, tạo experiment ID và record mới.

## Nhận diện

| Trường | Giá trị thực tế |
|---|---|
| Experiment ID | |
| Ngày/giờ bắt đầu (UTC) | |
| Candidate | |
| Model ID | |
| Checkpoint/revision đầu vào | |
| Direction | |
| Lý do candidate được giữ | Link tới hai pretrained evidence và `selection/<direction>.md` |

## Dữ liệu và train

| Trường | Giá trị thực tế |
|---|---|
| Dữ liệu train | Chỉ `data/processed/it_en_vi/train.jsonl` |
| Manifest/checksum release | |
| Phương pháp adaptation | `full_ft` hoặc `lora` |
| LoRA rank / alpha / dropout / target modules | Ghi `không áp dụng` nếu `full_ft` |
| Epoch kế hoạch | |
| Per-device batch size | |
| Gradient accumulation | |
| Effective batch size | |
| Mixed precision | `none` hoặc `fp16`; phải khớp với GPU preflight |
| Learning rate | |
| Optimizer/scheduler nếu dùng | |
| Seed | |
| Checkpoint ID dự kiến | |
| Hardware, OS, CUDA và runtime | |
| Lệnh thực thi | |

EnViT5 dùng language-control prefix ở input (`en:` hoặc `vi:`) và prefix ngôn ngữ đích trong label (`vi:` hoặc `en:`). Đây là định dạng checkpoint; không sửa nội dung câu nguồn hoặc câu tham chiếu.

## Checkpoint và đánh giá

| Trường | Giá trị thực tế |
|---|---|
| Checkpoint được lưu | Đường dẫn, global step/epoch |
| Checkpoint được đánh giá | Đường dẫn duy nhất, global step/epoch |
| IT Validation evidence | Đường dẫn metrics/run |
| General Validation evidence | Đường dẫn metrics/run |
| Review sheet (nếu có) | Đường dẫn |
| Quyết định checkpoint | Giữ/loại và lý do dựa trên evidence |

Xác nhận: không dùng validation/test để train; không dùng GenAI, synthetic data hay automatic filtering.
