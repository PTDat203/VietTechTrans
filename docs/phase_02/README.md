# Phase 02 — chọn Core MT

## Câu hỏi nghiên cứu

**Câu hỏi:** Phase 02 cần trả lời điều gì?

**Trả lời:** Trong ba candidate OPUS-MT, EnViT5 và NLLB-200 distilled 600M,
chọn một Core MT cho EN→VI và một Core MT cho VI→EN. Quyết định dựa trên
validation, review thủ công và provenance checkpoint; final test chỉ dùng để
báo cáo sau khi Core đã khóa. Core này là đầu vào cho Phase 3.

**Câu hỏi:** Trước khi chọn candidate, đã khảo sát gì?

**Trả lời:** Đã kiểm tra model card chính thức, cặp ngôn ngữ, cách gọi model và
language control của từng candidate.
- [OPUS-MT EN→VI](https://huggingface.co/Helsinki-NLP/opus-mt-en-vi): model dịch máy song ngữ từ tiếng Anh sang tiếng Việt.
- [EnViT5 Translation](https://huggingface.co/VietAI/envit5-translation): model dịch máy hỗ trợ hai chiều Anh–Việt (EN↔VI).
- [NLLB-200 distilled 600M](https://huggingface.co/facebook/nllb-200-distilled-600M): model dịch máy đa ngôn ngữ, bản distilled khoảng 600 triệu tham số.
Cấu hình được đưa vào `configs/phase02_models.json`; 12 pretrained run kiểm tra cả hai chiều trên IT Validation và General Validation.

**Câu hỏi:** Vì sao chỉ dùng ba candidate này?

**Trả lời:** Ba model tạo ba điểm tham chiếu khác nhau:
- song ngữ OPUS-MT,
- song ngữ EN↔VI EnViT5
- đa ngôn ngữ NLLB-200.
Mục tiêu là có một tập candidate
nhỏ, chạy được theo cùng protocol để chọn Core MT cho đồ án; không phải khảo sát
hay xếp hạng tất cả model dịch hiện có. Project không tuyên bố candidate, LoRA
hay quy trình chọn Core là ý tưởng mới.

**Câu hỏi:** Vì sao thiết kế theo các gate thay vì chọn model từ một bảng metric?

**Trả lời:** 
- IT Validation cho kết quả trong miền;
- General Validation cho biết kết quả có thay đổi ngoài miền;
- review 50 câu kiểm tra lỗi dịch và lỗi kỹ thuật trên mẫu đã khóa trước prediction.
Người thực hiện ghi lý do lựa chọn theo từng
chiều. Vì vậy không có weighted score, ngưỡng tự đặt hoặc quyết định tự động.
Protocol và evidence contract nằm trong `configs/phase02_protocol.json`.

Logic code và điểm bắt đầu của từng việc: [implementation.md](implementation.md).

## Dữ liệu

| Dữ liệu | Số câu | Dùng cho |
|---|---:|---|
| IT Train | 121,547 | Adaptation |
| IT Validation | 15,253 | Screening, adapted evaluation, review và chọn Core |
| General Validation | 997 | Kiểm tra ngoài miền |
| IT Test | 15,244 | Báo cáo cuối, một lần |
| General Test | 1,012 | Báo cáo cuối, một lần |

Chỉ IT Train được dùng để học. IT Test và General Test chỉ được mở sau khi
hai Core đã được freeze.

## Quy trình

1. Chạy 12 pretrained evaluation: 3 candidate × 2 chiều × 2 validation set.
2. Người thực hiện viết selection record cho từng chiều.
3. Adapt candidate được giữ bằng IT Train, với experiment record viết trước.
4. Đánh giá cùng checkpoint trên IT Validation và General Validation.
5. Review thủ công 50 câu IT Validation đã khóa trước prediction.
6. Ghi và freeze một Core cho mỗi chiều.
7. Qua gate, chạy bốn final evaluation và chỉ báo cáo kết quả.

Final runner chỉ nhận `--direction` và `--dataset` (`it_test` hoặc
`general_test`). Model và checkpoint được đọc từ Core decision đã freeze; runner
không nhận tham số để thay đổi chúng. Mỗi thư mục evidence final chỉ được tạo một
lần và không thể ghi đè.

Chi tiết workflow: [workflow.md](workflow.md) và [workflow.svg](workflow.svg).

## Evidence cần có

Mỗi evaluation hoàn tất có `predictions.jsonl`, `metrics.json` và `run.json`.
`metrics.json` ghi số câu, chrF++ và SacreBLEU. `run.json` ghi model,
checkpoint revision, dataset role, runtime và cấu hình chạy.

Checkpoint adapted nằm trong `models/phase02/`. Evidence của adaptation tách
khỏi checkpoint. Experiment record, training run và hai adapted evaluation phải
liên kết cùng experiment ID và checkpoint ID.

## Quy tắc quyết định

- chrF++ là metric chính, đo mức độ trùng khớp theo các n-gram ký tự và có xét khoảng trắng; phù hợp để đánh giá các khác biệt về hình thức từ trong tiếng Việt.
- SacreBLEU là metric bổ sung, tính BLEU theo cách chuẩn hóa và tái lập được, dùng để đối chiếu kết quả với các báo cáo dịch máy khác.
- General Validation dùng để phát hiện suy giảm ngoài miền, không dùng để tiếp tục tune checkpoint.

Không dùng weighted score, ngưỡng tự đặt, LLM judge, GenAI rewrite, synthetic
data, automatic filtering hoặc final test để chọn model. Notebook chỉ đọc
evidence; không train, chạy model, ghi metric hay tạo quyết định.

Xem [báo cáo LoRA](lora_hardware_feasibility.md) cho cấu hình adaptation đã
kiểm tra trên GPU hiện có.

Kết quả sau khi Core được khóa: [báo cáo cuối](final_report.md).

## Phase 02 có thể trình bày

- Candidate đã khảo sát, model ID và runtime control được dùng.
- Kết quả pretrained và adapted trên IT Validation, General Validation.
- Experiment record, checkpoint, checksum dữ liệu và runtime của run đã hoàn tất.
- Review 50 câu đã khóa và lý do người thực hiện chọn một Core cho mỗi chiều.
- Kết quả final trên IT Test và General Test sau khi quyết định đã freeze.

Các evidence này không chứng minh model là tốt nhất trong mọi điều kiện, không
đại diện cho mọi model hiện có và không đánh giá khả năng chạy trên thiết bị yếu.
