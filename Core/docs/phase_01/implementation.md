# Logic code Phase 01

## Thành phần

| File | Trách nhiệm |
|---|---|
| `configs/data/phase01_it_en_vi.json` | Khai báo nguồn, cột EN/VI, split seed, group và tag hợp lệ |
| `tools/build_phase01.py` | Tạo IT release, báo cáo và manifest |
| `tools/build_general_validation.py` | Tạo General Validation từ FLORES dev |
| `tools/build_general_test.py` | Tạo General Test từ FLORES devtest và chặn overlap với IT release |
| `tools/validate_phase01_gate.py` | Kiểm tra artifact Phase 01 đã khóa |
| `tools/build_phase02_input_bundle.py` | Đóng gói input được phép chuyển sang Phase 02 |

## Điểm bắt đầu và đường đi của dữ liệu

Phase 01 không bắt đầu từ notebook. Người thực hiện chạy lệnh trong `manual/phase_01.md`; entry point của IT release là `main()` trong `tools/build_phase01.py`.

```text
manual/phase_01.md
→ python tools/build_phase01.py [--release-name <tên mới>]
→ main() → build(release_name)
→ source_frame() theo từng nguồn trong configs/data/phase01_it_en_vi.json
→ split() → save()
→ data/processed/<release-name>/ và data/final_report/<release-name>/
```

General Validation và General Test bắt đầu từ hai lệnh riêng trong manual. Gate chỉ đọc artifact đã tạo; gate không tạo lại dữ liệu. Phase 02 chỉ đọc release đã khóa hoặc bundle Phase 02, không gọi lại builder Phase 01. Lý do chọn các bước được ghi trong [README.md](README.md).

## Điểm bắt đầu và đầu ra

Phase 01 không bắt đầu từ notebook. Người thực hiện bắt đầu theo
`manual/phase_01.md` bằng các script dưới đây:

```text
configs/data/phase01_it_en_vi.json + data/raw/
→ python tools/build_phase01.py [--release-name <tên_mới>]
→ data/processed/<release-name>/ và data/final_report/<release-name>/

FLORES dev
→ python tools/build_general_validation.py
→ data/processed/general_validation_en_vi/

FLORES devtest
→ python tools/build_general_test.py --en ... --vi ...
→ data/processed/general_test_flores200_devtest/

ba artifact đã có
→ python tools/validate_phase01_gate.py
→ PHASE_01_GATE=PASS hoặc FAIL
```

`configs/data/phase01_it_en_vi.json` là điểm bắt đầu của logic dữ liệu: nó xác
định nguồn, tên cột EN/VI, seed, tỷ lệ split, group và tag hợp lệ. Notebook chỉ
đọc output sau khi script đã chạy.

## `build_phase01.py`

1. Đọc raw parquet của từng nguồn theo mapping cột trong config.
2. Audit blank/non-string, control character, duplicate, cặp EN–VI giống nhau,
   lệch độ dài, cặp câu dài và thiếu ký tự Latin.
3. Giữ cặp câu có hai vế là string không rỗng và không có control/replacement
   character; chuẩn hóa Unicode NFC và whitespace.
4. Bỏ duplicate chính xác trong từng nguồn. Với EnviTech Reasoning, chỉ giữ các
   category IT đã khai báo trong code.
5. Tạo hash cặp câu, technical tags, `primary_group` và `group_method` bằng quy tắc
   cố định.
6. Bỏ duplicate cặp câu chuẩn hóa giữa các nguồn. Cặp câu có cùng câu nguồn chuẩn hóa
   nhận cùng `leakage_group_id` để không bị chia sang các split khác nhau.
7. Chia theo `source_short_name`, `primary_group` và hash ổn định từ seed 42.
8. Kiểm tra overlap với IT Train, rồi ghi JSONL, Parquet, báo cáo và manifest.

Script chỉ tạo release ở tên chưa tồn tại. `--release-name` là tên release mới;
release hoặc report cùng tên đã tồn tại sẽ bị từ chối, không ghi đè.

Sau gate PASS, Phase 02 không gọi lại `build_phase01.py`. Phase 02 đọc release
local theo protocol hoặc nhận input bundle do `build_phase02_input_bundle.py`
tạo từ IT Train, IT Validation, General Validation, manifest và protocol.

## General sets và gate

General Validation dùng FLORES dev và chỉ là tập kiểm tra ngoài miền. General Test
dùng FLORES devtest; builder kiểm tra overlap cặp câu và overlap câu nguồn với cả
IT Train, IT Validation và IT Test trước khi ghi artifact.

Gate không build dữ liệu. Gate đọc release đã có, đối chiếu SHA-256 trong
manifest, kiểm tra schema/group/tag và xác nhận các overlap đã báo cáo bằng 0.

## Giới hạn của implementation

Tag và group theo quy tắc nhằm mô tả dữ liệu để đọc evidence, không thay cho
annotation ngữ nghĩa thủ công. Audit cũng không tự động kết luận cặp câu có chất
lượng dịch kém. Các quyết định model và chất lượng dịch không nằm trong code
Phase 01.
