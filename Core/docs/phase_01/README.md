# Phase 01 — xây dựng và khóa dataset IT EN↔VI

## Mục tiêu

Phase 01 tạo dataset song ngữ EN↔VI miền IT làm đầu vào cố định cho Phase 02.
Kết quả cần trả lời ba câu hỏi: dữ liệu đến từ đâu, những cặp câu nào bị loại theo
quy tắc nào, và các split có được tách không chồng lấp không.

Sơ đồ quy trình: [workflow.svg](workflow.svg). Logic của script:
[implementation.md](implementation.md).

## Câu hỏi nghiên cứu của Phase 01

**Câu hỏi:** Trước khi thiết kế Phase 01, đã khảo sát những gì và khảo sát như
thế nào?

**Trả lời:** Đã kiểm tra metadata của năm nguồn sẽ dùng: cặp ngôn ngữ, loại dữ
liệu, revision/snapshot, cách thu thập và số dòng raw. Sau đó chạy audit local
để xem blank, non-string, control character, duplicate, độ dài và các tín hiệu
cần xem xét. Các nguồn gồm
- [EnVi-Tech Reasoning](https://huggingface.co/datasets/kotorii1/EnVi-Tech-Reasoning-SFT)
- [Tech Viet Translation](https://huggingface.co/datasets/lightontech/tech-viet-translation)
- [GNOME](https://opus.nlpl.eu/GNOME/corpus/version/GNOME)
- [Ubuntu](https://opus.nlpl.eu/Ubuntu)
- [KDE4](https://opus.nlpl.eu/KDE4).
Metadata local nằm tại `data/raw/*/metadata.json`; kết quả audit và làm sạch
nằm trong `data/processed/it_en_vi/dataset_report.json`.

**Câu hỏi:** Các giải pháp sẵn có được dùng ra sao, và phần nào là thiết kế của
đồ án?

**Trả lời:** Dự án dùng corpus song ngữ công khai có sẵn. Phần được thiết kế cho đồ án là cách
đóng gói các nguồn đã chọn thành một release có manifest, checksum, split cố
định và kiểm tra overlap để Phase 02 dùng cùng một đầu vào. FLORES-200 có sẵn
split `dev` và `devtest`, nên được dùng làm General Validation/Test ngoài miền;
- xem [tài liệu FLORES-200](https://github.com/facebookresearch/flores/blob/main/flores200/README.md?plain=1).

**Câu hỏi:** Tại sao chọn các dataset trên Hugging Face?

**Trả lời:** Hugging Face cho phép tải dataset bằng
một cách thống nhất và chỉ rõ `revision` khi tải. Nhờ vậy, dự án ghi được URL,
revision, ngày thu thập và schema trong metadata local. Lợi ích là việc thu thập
và truy vết dễ kiểm tra hơn, không phải vì dataset trên Hugging Face mặc nhiên
có chất lượng tốt hơn nguồn khác. Sau khi tải, dự án vẫn lưu bản sao raw và
checksum riêng. 
- Xem [hướng dẫn tải dataset của Hugging Face](https://huggingface.co/docs/datasets/loading)
và metadata tại `data/raw/envitech_reasoning/metadata.json`,
`data/raw/tech_viet_translation/metadata.json`.

**Câu hỏi:** Tại sao dùng FLORES-200?

**Trả lời:** FLORES-200 được dùng riêng làm tập kiểm tra ngoài miền, không trộn
vào IT release. Bộ dữ liệu có sẵn hai split `dev` và `devtest`, phù hợp để tách
General Validation khỏi General Test theo protocol. Nhờ đó Phase 02 có thêm một
điểm kiểm tra trên dữ liệu không thuộc các nguồn IT đã dùng để adaptation. . Nguồn, split và kiểm tra overlap được ghi trong
`configs/data/general_test_flores200_devtest.json`; 
- xem [tài liệu FLORES-200](https://github.com/facebookresearch/flores/blob/main/flores200/README.md?plain=1).

**Câu hỏi:** Vì sao chọn làm sạch tối thiểu, bốn nhóm lớn và kiểm tra leakage?

**Trả lời:** Nội dung IT thường có command, code, path và chuỗi UI; lọc mạnh có
thể bỏ đúng dữ liệu cần giữ. Vì vậy code chỉ tự động loại lỗi rõ ràng và duplicate
chính xác; các tín hiệu khác được báo cáo để người đọc kiểm tra. Bốn nhóm lớn và
technical tag chỉ để mô tả coverage, không thay cho gán nhãn ngữ nghĩa thủ công.
Cùng câu nguồn chuẩn hóa được giữ trong một split để hạn chế việc train và
validation/test chứa các biến thể của cùng nội dung. Quy tắc nằm trong
`tools/build_phase01.py`; gate kiểm tra lại trong `tools/validate_phase01_gate.py`.

## Input và output đã khóa

Input IT gồm năm nguồn: EnviTech Reasoning, Tech Viet Translation, GNOME,
Ubuntu và KDE4. Bản sao dữ liệu gốc, revision và checksum được ghi trong
`dataset_report.json`.

Bản dữ liệu đã khóa (release) hiện hành là `it_en_vi`:

| Tập dữ liệu | Số cặp câu | Vai trò ở Phase 02 |
|---|---:|---|
| IT Train | 121.547 | Adaptation |
| IT Validation | 15.253 | Screening, adapted evaluation và review |
| IT Test | 15.244 | Báo cáo cuối sau khi Core MT được khóa |
| General Validation (FLORES dev) | 997 | Kiểm tra ngoài miền |
| General Test (FLORES devtest) | 1.012 | Báo cáo cuối ngoài miền |

IT release nằm trong `data/processed/it_en_vi/`. General Validation và General
Test là các artifact riêng, lần lượt ở `data/processed/general_validation_en_vi/`
và `data/processed/general_test_flores200_devtest/`.

## Luồng xử lý

```text
 bản sao dữ liệu gốc
  → audit tín hiệu lỗi
  → làm sạch theo quy tắc cố định
  → loại trùng trong nguồn và giữa nguồn
  → gán technical tags và một primary group
  → chia tập theo seed 42, giữ cùng câu nguồn trong cùng split
  → kiểm tra rò rỉ dữ liệu
  → manifest, báo cáo và gói input Phase 02
```

Làm sạch chỉ loại cặp câu có dữ liệu không hợp lệ rõ ràng: null/blank/non-string,
control/replacement character và duplicate chính xác. Các tín hiệu như cặp câu
giống nhau giữa EN–VI, lệch độ dài, câu dài hoặc nghi ngờ ngôn ngữ được báo cáo
để kiểm tra; chúng không tự động là lý do loại cặp câu.

## Nhóm và technical tags

Mỗi cặp câu có đúng một `primary_group`. Các giá trị này là giá trị schema đã khóa:

| `primary_group` | Ý nghĩa |
|---|---|
| `Hỗ trợ kỹ thuật / xử lý sự cố` | lỗi, yêu cầu hỗ trợ, hướng dẫn xử lý |
| `Software / UI` | chuỗi giao diện và thao tác phần mềm |
| `CLI / Command` | lệnh và nội dung terminal |
| `Code & Technical Reference` | code, API, path, cấu hình, version và tham chiếu kỹ thuật |

`technical_tags` là các dấu hiệu được gán bằng quy tắc: error, command, UI, code
hoặc API, path, config và version. Tag không quyết định giữ hay loại dữ liệu.
`group_method` cho biết group được gán bởi quy tắc (`rule_based`) hay theo group
mặc định (`default_group`); nó không phải nhãn gán thủ công.

## Phase 01 có thể trình bày

- Nguồn, phiên bản/snapshot và checksum của dữ liệu đầu vào.
- Số cặp câu trước làm sạch, sau làm sạch và sau loại trùng toàn cục.
- Phân bố theo nguồn, primary group và technical tag.
- Schema của release, seed và số cặp câu mỗi split.
- Kết quả rò rỉ dữ liệu: overlap cặp câu chuẩn hóa và overlap câu nguồn chuẩn hóa giữa
  IT Train với IT Validation/IT Test đều bằng 0.
- General Validation và General Test được tách khỏi IT release, có manifest và
  kiểm tra overlap trước khi dùng.

## Cổng kiểm tra và release bất biến

`tools/validate_phase01_gate.py` kiểm tra checksum manifest, schema, group,
tag, `group_method` và rò rỉ dữ liệu. Chỉ khi gate PASS, release mới được dùng cho
Phase 02.

`it_en_vi` đã được dùng để tạo evidence Phase 02 nên không được ghi đè. Khi
thay đổi nguồn, quy tắc làm sạch, quy tắc phân nhóm hoặc chia tập, tạo release mới và một chuỗi
experiment mới. 
- Hướng dẫn chạy: [manual/phase_01.md](../../manual/phase_01.md).

## Notebook

Notebook chỉ đọc release và báo cáo để kiểm tra hoặc trình bày. Pipeline chính
vẫn là script trong `tools/`; notebook không tạo release chính thức. 
- Danh sách notebook: [notebooks/phase_01/README.md](../../notebooks/phase_01/README.md).
