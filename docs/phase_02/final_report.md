# Báo cáo cuối Phase 02 — Core MT EN↔VI miền IT

## Mục tiêu

Phase 02 chọn một Core MT cho từng chiều EN→VI và VI→EN để dùng làm nguồn
tri thức cho Phase 03. Phase này không đánh giá tốc độ, RAM, quantization hay
khả năng chạy Android.

## Core MT đã khóa trước final evaluation

| Chiều | Model | Checkpoint |
|---|---|---|
| EN→VI | EnViT5 (`VietAI/envit5-translation`) | `models/phase02/envit5-en-to-vi-lora-v1/final-epoch-1` |
| VI→EN | EnViT5 (`VietAI/envit5-translation`) | `models/phase02/envit5-vi-to-en-lora-v1/final-epoch-1` |

Quyết định đã được ghi và khóa trong
`evidence/phase02/core_mt_decision.json` trước khi mở IT Test và General Test.
Kết quả final bên dưới không được dùng để đổi model, checkpoint hay cấu hình
adaptation.

## Điều kiện chạy được ghi nhận

Hai checkpoint được adaptation trên IT Train bằng LoRA: rank 8, alpha 16,
dropout 0, target modules `q` và `v`. Final evaluation dùng batch inference 1.

Môi trường được ghi trong bốn `run.json`:

| Thành phần | Giá trị |
|---|---|
| Python | 3.11.15 |
| PyTorch | 2.14.0+cu130 |
| Transformers | 4.57.6 |
| Tokenizers | 0.22.1 |
| SacreBLEU | 2.6.0 |
| Hệ điều hành | Windows 10.0.26200 |

EnViT5 dùng prefix ngôn ngữ đầu vào `en:` hoặc `vi:` theo chiều dịch.
`max_length=512` là runtime setting được lấy từ model card của checkpoint; prefix
đích `vi:` hoặc `en:` chỉ được bỏ khi nó đứng ở đầu output trước khi tính metric.

`run.json` không ghi thiết bị inference. Vì vậy báo cáo này không khẳng định
final inference đã chạy trên GPU, dù môi trường PyTorch có CUDA.

## Kết quả final

| Chiều | Tập đánh giá | Số câu | chrF++ | SacreBLEU |
|---|---|---:|---:|---:|
| EN→VI | IT Test | 15,244 | 70.33 | 56.82 |
| EN→VI | General Test | 1,012 | 60.09 | 39.28 |
| VI→EN | IT Test | 15,244 | 65.21 | 41.20 |
| VI→EN | General Test | 1,012 | 57.73 | 28.35 |

chrF++ là metric chính; SacreBLEU được báo cáo song song. Cấu hình metric là
chrF++ với `beta=2`, `word_order=2`; SacreBLEU với `tokenize=none`,
`lowercase=false` và `use_effective_order=false`.

## Evidence

Mỗi lượt final có `predictions.jsonl`, `metrics.json` và `run.json`:

- `evidence/phase02/final/en_to_vi/it_test/`
- `evidence/phase02/final/en_to_vi/general_test/`
- `evidence/phase02/final/vi_to_en/it_test/`
- `evidence/phase02/final/vi_to_en/general_test/`

Các file `run.json` liên kết kết quả với Core decision, checkpoint tree hash,
training record, runtime và checksum của tập đánh giá. Bốn lượt đều hoàn thành;
không còn thư mục `.running`.

## Giới hạn

Final evaluation chỉ đánh giá chất lượng dịch trên hai tập test. Nó không chứng
minh Core MT chạy được trên Android hoặc thiết bị yếu. Các nội dung đó thuộc
Phase 3 đến Phase 5.

Trong validation trước khi khóa Core, VI→EN tăng trên IT Validation nhưng giảm
trên General Validation. Giới hạn này đã được ghi trong Core decision trước
khi final evaluation và cần được giữ lại khi thảo luận kết quả.
