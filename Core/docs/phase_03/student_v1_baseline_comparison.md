# So sánh Student v1 với baseline khởi tạo

## Câu hỏi và cách trả lời

Student v1 có tốt hơn checkpoint mà nó được khởi tạo trước khi KD hay không?

Baseline là OPUS-MT gốc đúng revision đã dùng để khởi tạo Student. Student v1
là checkpoint đó sau sequence-level KD. Vì vậy chênh lệch `Student v1 - baseline`
đo phần thay đổi sau KD trong phạm vi cohort này. Đây không phải so sánh Student
với Teacher và cũng không thay đổi quyết định Main-Core đã freeze.

## Cohort và cách tính

Cả hai chiều dùng IT Validation 15.252 câu. Dataset gốc có 15.253 câu; một row
ID `948273d4ab1d4f0978ca1ad3b664b36ecc0e78b704514d7598e4deafd606e5ad`
không được Student v1 hỗ trợ vì source vượt giới hạn input 512 token. Source và
reference không bị sửa; row này chỉ bị loại khỏi cả hai vế so sánh.

Baseline không được inference lại. Tool lấy prediction OPUS-MT đã freeze ở
Phase 02, kiểm tra hash và metadata model/revision/beam, sau đó lọc đúng cohort
15.252 câu. Student dùng prediction và metric đã freeze của Phase 03. Cả hai
vế đều chấm chrF++ (`beta=2`, `word_order=2`) và SacreBLEU (`tokenize=none`,
không lowercase).

## Kết quả

| Chiều | Baseline chrF++ / BLEU | Student v1 chrF++ / BLEU | Student v1 - baseline |
|---|---:|---:|---:|
| EN→VI | 50,85 / 31,99 | 66,55 / 51,21 | +15,70 chrF++; +19,22 BLEU |
| VI→EN | 49,51 / 21,32 | 61,30 / 36,30 | +11,79 chrF++; +14,97 BLEU |

Trên cohort IT Validation được khóa, Student v1 cao hơn baseline khởi tạo ở cả
hai metric và hai chiều. Kết quả chỉ mô tả khác biệt của KD trên cohort này;
không chứng minh chất lượng trên dữ liệu ngoài miền, latency hay khả năng chạy
trên thiết bị.

## Evidence và kiểm tra lại

- Protocol: `configs/phase03_supplementary/phase03_student_v1_baseline_comparison_v1.json`.
- Kết quả: `evidence/phase03/supplementary/phase03_student_v1_baseline_comparison_v1/comparison_result.json`.
- Baseline đã lọc, manifest và metric: `evidence/phase03/supplementary/phase03_student_v1_baseline_comparison_v1/baseline/`.
- Lệnh dựng evidence: `python tools/build_phase03_student_baseline_comparison.py --direction en_to_vi` và thay `en_to_vi` bằng `vi_to_en` cho chiều còn lại.
- Lệnh kiểm tra: `python tools/validate_phase03_student_baseline_comparison.py`.

Validator kiểm tra protocol, hash data, cohort, identity của run OPUS-MT Phase
02, prediction/metric baseline, prediction/metric Student và hash của
`main_core_decision.json`. Nó không tải model, không train và không thay đổi
Main-Core.
