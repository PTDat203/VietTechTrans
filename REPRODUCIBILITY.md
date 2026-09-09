# Hướng dẫn tái lập dự án

Tài liệu này mô tả cách chạy source code và Phase 06 trên môi trường Windows
CPU đã được dùng để tạo artifact OPUS-MT. Nó không thay thế license, source
data hoặc checkpoint của bên thứ ba.

## Môi trường đã kiểm tra

| Thành phần | Giá trị |
|---|---|
| OS | Windows 11 x64, build 26200 |
| Python | 3.14.6, Anaconda |
| Conda | 26.5.3 |
| PyTorch | 2.14.0+cpu |
| CUDA | Không khả dụng trong run đã ghi nhận |
| Transformers | 4.57.6 |
| Hugging Face Hub | 0.36.2 |
| SacreBLEU | 2.6.0 |

`requirements.txt` pin các dependency trực tiếp đã dùng. `environment.yml`
phù hợp khi tạo conda environment mới. GPU có thể chạy nếu PyTorch/CUDA tương
thích, nhưng số latency/RAM sẽ không so sánh trực tiếp với baseline CPU này.

## Cài đặt

```powershell
git clone https://github.com/PTDat203/VietTechTrans.git
Set-Location VietTechTrans
conda env create -f environment.yml
conda activate envi-it-mt
```

Hoặc, trong virtual environment Python 3.14.6:

```powershell
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
```

Xác minh code trước khi tải model:

```powershell
python -m unittest discover -s tests -p "test_*.py"
python tools\check_core_shared.py
```

## Data prerequisite

Các thư mục `data/raw`, `data/interim`, `data/processed`, `data/it_corpus` và
`data/splits` không được Git commit. Máy mới phải tự chạy notebook Phase 01–05
hoặc phục hồi đúng Phase 05 release từ artifact đã được lưu trữ riêng.

Đặc biệt, Phase 06 cần hai test set sealed:

```text
data/processed/general_test_flores200_devtest_v1/general_test.jsonl
data/processed/it_en_vi_v1/it_test.jsonl
```

Hãy chạy `python tools\validate_phase05_gate.py`. Lệnh phải trả về
`PHASE_05_GATE=PASS`; nếu thiếu data/checksum không đúng, dừng tại đây.

## Chạy baseline

Checkpoint tải từ Hugging Face trong lần đầu và cần Internet cùng dung lượng
đĩa trống. Không commit `models/` hoặc `runs/`. Mỗi run lưu commit SHA trong
`resolved_config.json`, do đó checkpoint thực tế vẫn xác định được dù config
yêu cầu revision `main`.

```powershell
# OPUS-MT
python tools\run_opus_mt_baseline.py --direction en_to_vi --device cpu --progress-interval 250 --preview 2
python tools\run_opus_mt_baseline.py --direction vi_to_en --device cpu --progress-interval 250 --preview 2

# EnViT5
python tools\run_envit5_baseline.py --direction en_to_vi --device cpu --progress-interval 250 --preview 2
python tools\run_envit5_baseline.py --direction vi_to_en --device cpu --progress-interval 250 --preview 2
```

Trên Windows, EnViT5 download vào `models/envit5/<commit-SHA>/` bằng file
thường để tránh yêu cầu quyền symlink. Một run đang chạy nằm trong
`<direction>.in_progress`; không chạy lại cùng direction, không xóa thư mục đó
trừ khi chủ động hủy toàn bộ run. Run hoàn chỉnh có đủ sáu artifact ở
`runs/core_mt_baseline_v2/<model>/<direction>/`.

## Chạy từ notebook

Mở `jupyter lab`, vào `notebooks/06_core_mt/`. Notebook mặc định đặt
`RUN_FULL_BASELINE = False`. Đổi thành `True` chỉ khi muốn bắt đầu run và chưa
có `completed`/`.in_progress` directory cùng direction. Sau khi run xong, đặt
lại `False`, Run All để hiển thị log, prediction, metric, benchmark và biểu đồ.

## Kết quả đã có

OPUS-MT EN→VI và VI→EN hoàn thành theo `core_mt_baseline_v2`. Bản tóm tắt nhỏ
được commit tại `reports/phase06_opus_mt_baseline_v2_summary.json`; prediction
đầy đủ và artifact runtime ở `runs/` chỉ tồn tại cục bộ.

EnViT5 và M2M-100 chưa có baseline cuối trong Git tại thời điểm tài liệu này
được cập nhật. Không dùng kết quả OPUS-MT để chọn model thắng hoặc thực hiện
domain adaptation trước khi đủ bảng so sánh ba CORE MT.
