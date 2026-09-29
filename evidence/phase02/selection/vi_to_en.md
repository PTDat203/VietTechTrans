# Selection record — VI→EN

## Trạng thái

Hoàn tất trước adaptation theo quyết định của người phụ trách dựa trên pretrained screening đã khóa.

| Trường | Nội dung |
|---|---|
| Direction | `vi_to_en` |
| Ngày quyết định | 2026-09-21 |
| Candidate giữ để adaptation | `envit5` — VietAI/envit5-translation |
| Candidate xếp sau theo pretrained screening | `nllb200_distilled_600m` — không phải fallback tự động và không được adaptation nếu không có quyết định mới có evidence. |
| Candidate không giữ | `opus_mt`, `nllb200_distilled_600m` |

## Bốn chỉ tiêu validation

| Model | IT Validation chrF++ | IT Validation SacreBLEU | General Validation chrF++ | General Validation SacreBLEU |
|---|---:|---:|---:|---:|
| EnViT5 | **60.431968** | **31.983891** | **60.143713** | **32.063374** |
| NLLB-200 distilled 600M | 56.562728 | 27.438879 | 58.281380 | 31.259308 |
| OPUS-MT | 49.393100 | 21.317596 | 51.653257 | 22.543067 |

## Evidence

- EnViT5 IT Validation: `evidence/phase02/envit5/vi_to_en/pretrained/selection_validation/`
- EnViT5 General Validation: `evidence/phase02/envit5/vi_to_en/pretrained/general_validation/`
- NLLB IT Validation: `evidence/phase02/nllb200_distilled_600m/vi_to_en/pretrained/selection_validation/`
- NLLB General Validation: `evidence/phase02/nllb200_distilled_600m/vi_to_en/pretrained/general_validation/`
- OPUS-MT IT Validation: `evidence/phase02/opus_mt/vi_to_en/pretrained/selection_validation/`
- OPUS-MT General Validation: `evidence/phase02/opus_mt/vi_to_en/pretrained/general_validation/`

Mỗi đường dẫn có `predictions.jsonl`, `metrics.json` và `run.json`. Toàn bộ 12 lượt dùng cùng runner/adapter provenance; EnViT5 dùng `max_length=512` theo model card và đã bỏ exact language-control prefix trước metric.

## Kết luận selection

EnViT5 được giữ là candidate duy nhất cho IT adaptation ở chiều VI→EN. Model này dẫn cả bốn chỉ tiêu: chrF++ và SacreBLEU trên IT Validation, chrF++ và SacreBLEU trên General Validation. IT Validation là evidence chính cho miền IT; General Validation cũng dẫn hai candidate còn lại.

NLLB-200 distilled 600M đứng thứ hai trên cả bốn chỉ tiêu, nên được ghi nhận là candidate xếp sau theo pretrained screening. Ghi nhận này không tạo quy tắc tự động thay EnViT5 bằng NLLB nếu adaptation có vấn đề.

OPUS-MT không được giữ vì thấp hơn EnViT5 và NLLB trên cả bốn chỉ tiêu.

## Kiểm tra output và khả năng thực hiện

Notebook `02_01_pretrained_screening.ipynb` dùng prediction evidence để kiểm tra lỗi rõ ràng. Đây chưa phải manual review 50 câu và record này không tự tạo error score hoặc tuyên bố không có lỗi ngữ nghĩa.

EnViT5 đã hoàn tất bốn pretrained run với checkpoint, revision, runtime control và metric configuration truy vết được. Điều này đủ cho quyết định đi tiếp adaptation; cấu hình train, phần cứng và checkpoint adapted sẽ được ghi trong experiment record trước lệnh train. Kích thước model, RAM, latency và Android deployment không dùng để xếp hạng ở đây.

## Ranh giới quyết định

EnViT5 hiện là candidate cho adaptation, chưa phải Core MT/Teacher cuối cùng. Core chỉ được khóa sau adaptation, đánh giá lại trên IT Validation và General Validation, cùng manual review 50 câu đã khóa. IT Test và General Test không được dùng ở selection này.
