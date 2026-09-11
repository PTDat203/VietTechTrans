# Phase 06B — OPUS-MT branch

OPUS-MT là nhánh model đầu tiên. Shared layer giữ protocol, data loader, direction, artifact schema và runner; nhánh này chỉ thêm hai checkpoint Marian bilingual cùng rule input bắt buộc.

- EN→VI: `Helsinki-NLP/opus-mt-en-vi`, input bắt đầu bằng `>>vie<<`.
- VI→EN: `Helsinki-NLP/opus-mt-vi-en`, không thêm prefix ngoài câu nguồn.

Trước khi chạy thật, `main` của runner luôn gọi Phase 05 gate. Mỗi run resolve `main` thành commit SHA rồi chỉ lưu evidence theo đúng một directory mới; không ghi đè run cũ.

Protocol `core_mt_baseline_v2` tách hai mục đích đo. chrF++ và SacreBLEU
được tính từ một prediction trên mọi câu của từng test set. Latency không lặp
lại toàn bộ corpus: mỗi test set chọn tối đa 128 row theo thứ tự SHA-256 của
`seed:row_id`, warm-up tối đa 32 câu, rồi đo 5 lượt trên đúng sample đó. Peak
RSS bao trùm cả quality pass lẫn latency probe. Nhờ vậy ba CORE MT sau này
chịu cùng workload nhưng không phát sinh các lượt dịch lặp không cần thiết.

```powershell
& 'C:\Users\ADMIN\anaconda3\python.exe' -m unittest discover -s tests -p test_opus_mt_adapter.py
& 'C:\Users\ADMIN\anaconda3\python.exe' tools\run_opus_mt_baseline.py --direction en_to_vi --device cpu --progress-interval 250 --preview 2
```

Chạy tiếp chiều còn lại bằng `--direction vi_to_en`. Artifact hoàn chỉnh chỉ
xuất hiện ở `runs/core_mt_baseline_v2/opus_mt/<direction>/`; thư mục
`.in_progress` chỉ là trạng thái đang chạy, chưa phải kết quả được dùng để so
sánh. Hai kết quả baseline hoàn chỉnh được tóm tắt trong
`reports/phase06_opus_mt_baseline_v2_summary.json`.
