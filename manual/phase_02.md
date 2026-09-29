# Chạy Phase 02

Chạy lệnh từ `C:\Users\ADMIN\ENVI-IT-MT`. Notebook chỉ đọc evidence;
script trong `tools/` mới tạo prediction, metric hoặc checkpoint.
Điểm bắt đầu và đường đi của từng script: [implementation.md](../docs/phase_02/implementation.md).

Trước khi chạy, kiểm tra interpreter bằng `python --version` và `where python`.
`docs/environment.md` ghi môi trường thực tế đã tạo evidence Phase 02 cùng giới
hạn tái lập của environment hiện có.

## 1. Kiểm tra input

```bat
python tools\validate_phase02_setup.py
```

Lệnh phải trả `PHASE_02_SETUP=PASS`. Không mở IT Test hoặc General Test.

## 2. Pretrained screening

Chạy đủ 12 tổ hợp model, chiều dịch và validation set. Ví dụ:

```bat
python tools\run_phase02_pretrained_baseline.py --model envit5 --direction en_to_vi --dataset selection_validation
```

Sau khi đủ 12 lượt, mở `02_01_pretrained_screening.ipynb` và viết hai
selection record. Không dùng notebook để chọn tự động.

## 3. Adaptation

Trước train, chạy preflight GPU, viết experiment record và kiểm tra record.
Cấu hình EnViT5 LoRA hiện tại nằm trong
`docs/phase_02/lora_hardware_feasibility.md`.

Mỗi lệnh adaptation chỉ đọc IT Train và tạo một checkpoint cuối. Ví dụ EN→VI:

```bat
python tools\run_phase02_adaptation.py --model envit5 --method lora --direction en_to_vi --experiment-id envit5-en-to-vi-lora-v1 --checkpoint-id final-epoch-1 --epochs 1 --per-device-train-batch-size 1 --gradient-accumulation-steps 8 --learning-rate 2e-4 --seed 42 --fp16 --lora-rank 8 --lora-alpha 16 --lora-dropout 0
```

Sau khi EN→VI hoàn tất, chạy VI→EN với record riêng:

```bat
python tools\run_phase02_adaptation.py --model envit5 --method lora --direction vi_to_en --experiment-id envit5-vi-to-en-lora-v1 --checkpoint-id final-epoch-1 --epochs 1 --per-device-train-batch-size 1 --gradient-accumulation-steps 8 --learning-rate 2e-4 --seed 42 --fp16 --lora-rank 8 --lora-alpha 16 --lora-dropout 0
```

Sau train, chạy cùng checkpoint trên IT Validation và General Validation, rồi
kiểm tra evidence:

```bat
python tools\run_phase02_adapted_evaluation.py --model envit5 --direction en_to_vi --experiment-id envit5-en-to-vi-lora-v1 --checkpoint-id final-epoch-1 --dataset selection_validation
python tools\run_phase02_adapted_evaluation.py --model envit5 --direction en_to_vi --experiment-id envit5-en-to-vi-lora-v1 --checkpoint-id final-epoch-1 --dataset general_validation
python tools\validate_phase02_adapted_evidence.py --model envit5 --direction en_to_vi --experiment-id envit5-en-to-vi-lora-v1 --checkpoint-id final-epoch-1
```

Lặp lại đúng cấu trúc cho VI→EN.

## 4. Review, freeze và final reporting

Tạo review sheet từ prediction adapted, reviewer điền 50 dòng đã khóa, rồi ghi
`core_mt_decision.json`. Chỉ khi
`python tools\validate_phase02_final_reporting.py` trả PASS mới được chạy
IT Test và General Test. Final result chỉ để báo cáo, không dùng để đổi Core.
