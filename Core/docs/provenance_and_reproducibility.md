# Provenance và giới hạn tái lập

## Phase 02 đã hoàn tất

Các artifact Phase 02 hiện có được giữ nguyên: dataset đã dùng, checkpoint,
prediction, metric, review, Core MT decision và final evaluation. Không chạy lại
hay chỉnh sửa chúng chỉ để làm khớp với source local hiện tại.

Kiểm tra local xác nhận manifest dataset khớp artifact tương ứng; mỗi evaluation
có số dòng prediction khớp `metrics.json`; `run.json` tham chiếu đúng checksum
dữ liệu nguồn hiện có. Hai review sheet EnViT5 hoàn tất đều có 50 dòng hợp lệ.

## Giới hạn của run cũ

Một số `run.json` Phase 02 lưu SHA-256 của runner, adapter hoặc protocol không
còn trùng với file source/config local hiện tại. Project không giữ bản source
snapshot đúng thời điểm của các run đó. Vì vậy source local hiện nay không đủ để
xác nhận tái chạy sẽ sinh lại chính xác evidence cũ.

Đây là giới hạn tái lập của run cũ, không phải bằng chứng metrics, predictions
hoặc final result sai. Không thay hash cũ và không ghi đè evidence để làm chúng
trùng source mới.

## Vai trò của experiment record

Hai `experiment_record.md` của EnViT5 là record lập trước train. Các dòng
`Chưa chạy` hoặc `Chưa có` phản ánh thời điểm lập record và được giữ nguyên.
Trạng thái thực tế sau run nằm trong chuỗi artifact:

```text
experiment record trước run
→ training_run.json
→ checkpoint final-epoch-1
→ adapted evaluation trên hai validation set
→ review thủ công
→ core_mt_decision.json
→ final evaluation
```

Không sửa record cũ sau sự kiện để tránh lẫn dữ kiện quyết định trước run với
dữ kiện tạo ra sau run.

## Run sau Phase 02

Mọi run mới phải theo [run_snapshot_policy.md](run_snapshot_policy.md). Quy
trình này chỉ áp dụng từ Phase 03 trở đi, không áp dụng ngược cho evidence Phase
02 đã khóa.

## Giới hạn snapshot lịch sử của Phase 03

Hai checkpoint Student đã freeze cho Phase 4 được xác định trong
`evidence/phase03/main_core_decision.json`. Artifact lịch sử giữ nguyên đường
dẫn `.running`; trạng thái cuối cùng nằm ở decision, không nằm ở tên thư mục.

| Run | Có | Thiếu | Cách hiểu đúng |
|---|---|---|---|
| EN→VI `phase03-seqkd-en-to-vi-v2` | Config, command, dependency, lineage, target, training record, evaluation, review | Source snapshot chính xác của runner training/evaluation; `runtime.json`; `input_manifest.json` | Checkpoint, metric và review vẫn kiểm tra được theo decision. Không thể tái lập tuyệt đối source code đã tạo run. |
| VI→EN `phase03-seqkd-vi-to-en-v3` | Config, command, dependency, lineage, target, source snapshot reuse/training/evaluation/review, training record, evaluation, review | `runtime.json`; `input_manifest.json` riêng của v3 | Chuỗi source runner tốt hơn EN→VI v2, nhưng snapshot v3 chưa đủ mọi artifact theo policy. |

Không tạo snapshot hồi tố và không copy source hiện tại vào artifact cũ. Việc đó
không làm evidence đáng tin hơn. Validator provenance sẽ báo
`PASS_WITH_DISCLOSED_HISTORICAL_LIMIT` cho hai run này khi các artifact còn lại
khớp. Khi Phase 4 được thực hiện trên repository/máy khác, run mới cần có
snapshot đầy đủ và không dùng ngoại lệ này.
