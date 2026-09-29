# Experiment record — Phase 02 adaptation

## Nhận diện

| Trường | Giá trị thực tế |
|---|---|
| Experiment ID | `envit5-en-to-vi-lora-v1` |
| Ngày/giờ bắt đầu (UTC) | Chưa chạy. Record được tạo trước train ngày 2026-09-22. |
| Candidate | `envit5` |
| Model ID | `VietAI/envit5-translation` |
| Checkpoint/revision đầu vào | `840bc88104d5a4277af740eaedb024df8c3093e7` |
| Direction | `en_to_vi` |
| Lý do candidate được giữ | EnViT5 dẫn bốn chỉ tiêu pretrained screening ở EN→VI. Xem `evidence/phase02/selection/en_to_vi.md`, cùng pretrained evidence của EnViT5. |

## Dữ liệu và train

| Trường | Giá trị thực tế |
|---|---|
| Dữ liệu train | Chỉ `data/processed/it_en_vi/train.jsonl` (121,547 pair). |
| Manifest/checksum release | `data/processed/it_en_vi/dataset_manifest.json`; SHA-256 `d6dbad4f61a57289935465a05194dd1d88003c2d63c55b90698dd19f10f1b98e`. |
| Phương pháp adaptation | `lora` |
| LoRA rank / alpha / dropout / target modules | `rank=8`, `alpha=16`, `dropout=0`, target modules `q,v`, task type `SEQ_2_SEQ_LM`, bias `none`. |
| Epoch kế hoạch | `1` |
| Per-device batch size | `1` |
| Gradient accumulation | `8` |
| Effective batch size | `8` |
| Mixed precision | `fp16`; preflight PASS với peak reserved 3.62 GiB trên 4.00 GiB VRAM. |
| Learning rate | `2e-4` |
| Optimizer/scheduler nếu dùng | `adamw_torch` (AdamW), scheduler `linear`, warmup ratio `0.0`. |
| Seed | `42`; data seed `42`. |
| Checkpoint ID dự kiến | `final-epoch-1` |
| Hardware, OS, CUDA và runtime | Windows 10 10.0.26200; CPU Intel Core i5-11400H; RAM 31.78 GB; NVIDIA GeForce RTX 3050 Ti Laptop GPU, 4.00 GiB VRAM; Python 3.11.15; PyTorch 2.14.0+cu130; CUDA 13.0; Transformers 4.57.6; PEFT 0.18.1. |
| Lệnh thực thi | `python tools/run_phase02_adaptation.py --model envit5 --method lora --direction en_to_vi --experiment-id envit5-en-to-vi-lora-v1 --checkpoint-id final-epoch-1 --epochs 1 --per-device-train-batch-size 1 --gradient-accumulation-steps 8 --learning-rate 2e-4 --seed 42 --fp16 --lora-rank 8 --lora-alpha 16 --lora-dropout 0` |

EnViT5 dùng prefix `en:` ở input và prefix `vi:` trong label. Đây là định dạng checkpoint, không sửa câu nguồn hoặc câu tham chiếu.

## Checkpoint và đánh giá

| Trường | Giá trị thực tế |
|---|---|
| Checkpoint được lưu | Chưa có. Dự kiến: `models/phase02/envit5-en-to-vi-lora-v1/final-epoch-1/` sau khi train đủ 1 epoch. |
| Checkpoint được đánh giá | Chưa có. Sau train chỉ đánh giá checkpoint `final-epoch-1`. |
| IT Validation evidence | Chưa có. Sẽ tạo sau train tại evidence adapted của checkpoint `final-epoch-1`. |
| General Validation evidence | Chưa có. Sẽ tạo sau train tại evidence adapted của checkpoint `final-epoch-1`. |
| Review sheet (nếu có) | Chưa có. Chỉ tạo sau adapted evaluation. |
| Quyết định checkpoint | Chưa có. Không có quyết định Core trước adapted evaluation và manual review. |

Xác nhận: không dùng validation/test để train; không dùng GenAI, synthetic data hay automatic filtering.
