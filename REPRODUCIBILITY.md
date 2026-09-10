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

## Phase 05 release bundle

Git chỉ chứa source code, report nhỏ và quyết định manual review. Các split
Phase 05 không nằm trong Git vì khoảng 189 MB. Máy mới cần tải asset
`phase05_release_v1.zip` từ GitHub Release `phase05-release-v1`.

Sau khi tải, chạy tại root project:

```powershell
python tools\verify_phase05_release.py --archive .\phase05_release_v1.zip
Expand-Archive .\phase05_release_v1.zip -DestinationPath .
python tools\validate_phase05_gate.py
python -m unittest discover -s tests -p "test_*.py"
python tools\check_core_shared.py
```

Checksum toàn bộ ZIP được ghi tại
`reports/phase05_release_v1_asset.sha256`; checksum từng file nằm trong ZIP.
Không chạy model khi `PHASE_05_GATE=FAIL`.

Bundle sẽ tạo hai thư mục sealed:

```text
data/processed/general_test_flores200_devtest_v1/general_test.jsonl
data/processed/it_en_vi_v1/it_test.jsonl
```

Hãy chạy `python tools\validate_phase05_gate.py`. Lệnh phải trả về
`PHASE_05_GATE=PASS`; nếu thiếu data/checksum không đúng, dừng tại đây.

## Khi cần sửa lại Phase 01–05

Raw, interim và phần lớn Phase 04 artifact vẫn không được commit do dung lượng.
Tải lại năm nguồn theo notebook Phase 01, rồi chạy lần lượt notebook Phase
02, 03, 04 và 05. Các file
`data/it_corpus/<source>/manual_review_decisions.csv` được commit để giữ đúng
quyết định review thủ công đã dùng cho release này. Không thay đổi chúng nếu
muốn tái tạo `phase05_release_v1`.

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

Thành viên dùng IDE Antigravity và Claude xem hướng dẫn thao tác tại
[ANTIGRAVITY_CLAUDE_HANDOFF.md](ANTIGRAVITY_CLAUDE_HANDOFF.md).
