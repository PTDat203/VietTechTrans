# Chạy lại Phase 01

## Chuẩn bị

Tạo môi trường từ `environment.yml` hoặc dùng môi trường Python đã có đủ thư
viện phụ thuộc. Chạy lệnh từ thư mục gốc của project.

Xem `docs/environment.md` để phân biệt môi trường cơ sở với môi trường GPU
đã tạo evidence Phase 02, và kiểm tra interpreter trước khi chạy:

```bat
python --version
where python
```

```bat
conda env create -f environment.yml
conda activate envi-it-mt-phase2
```

## Tạo bản dữ liệu đã khóa

Dữ liệu gốc phải có sẵn trong `data/raw/`.

`it_en_vi` là release đã được Phase 02 sử dụng và không được ghi đè. Trên một
thư mục project mới chưa có release này, chạy:

```bat
python tools\build_phase01.py
python tools\build_general_validation.py
python tools\build_general_test.py --en data\raw\flores200\flores200_dataset\devtest\eng_Latn.devtest --vi data\raw\flores200\flores200_dataset\devtest\vie_Latn.devtest
python tools\validate_phase01_gate.py
```

Chỉ tiếp tục khi lệnh cuối trả `PHASE_01_GATE=PASS`.

`build_general_validation.py` tạo
`data/processed/general_validation_en_vi/` từ FLORES dev. Tập này dùng để kiểm
tra ngoài miền ở Phase 02; nó không phải IT Test hoặc General Test.

Nếu thay đổi nguồn, làm sạch, phân nhóm hoặc chia tập, phải tạo release mới thay
vì ghi đè release đã khóa:

```bat
python tools\build_phase01.py --release-name it_en_vi_v2
```

Release mới cần có gate, protocol và experiment mới trước khi được dùng cho một
Phase 02 khác. Không có tùy chọn `--force` cho IT release hoặc General Test
release đã tồn tại.

## Tạo bundle cho Phase 02

```bat
python tools\build_phase02_input_bundle.py
```

Bundle chỉ chứa IT Train, IT Validation, General Validation, manifest và
protocol cần cho Phase 02. Nó không chứa IT Test hoặc General Test.

## Bàn giao sang Phase 02

Sau khi Phase 01 đã build xong và gate trả PASS, Phase 02 có thể đọc trực tiếp
release local theo protocol hoặc dùng file bundle này trên máy khác. Không cần
chạy lại Phase 01, miễn không thay đổi dữ liệu, manifest hoặc checksum của
release đã khóa.
