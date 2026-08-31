# Dataset Card — {{DATASET_VERSION}}

## Motivation

VietTechTrans xây dựng corpus song ngữ Anh–Việt tập trung vào miền Công nghệ thông tin để phục vụ nghiên cứu dịch máy chạy offline. Bộ dữ liệu hướng đến ba nhu cầu: so sánh các mô hình dịch máy lõi trên dữ liệu IT, nghiên cứu domain adaptation/knowledge distillation, và đánh giá mô hình gọn nhẹ trong bối cảnh triển khai cục bộ.

Corpus không nhằm thay thế một benchmark dịch máy tổng quát. IT test split được giữ riêng để đo năng lực dịch trong miền IT, còn benchmark tổng quát cần được bổ sung từ một nguồn độc lập ở thí nghiệm tiếp theo.

## Composition

- Phiên bản: `{{DATASET_VERSION}}`
- Số cặp được Phase 04 phê duyệt: {{INPUT_APPROVED_ROWS}}
- Số global exact duplicate bị loại: {{GLOBAL_DEDUP_REMOVED}}
- Số cặp cuối: {{FINAL_ROWS}}
- Split: train {{TRAIN_ROWS}}, validation {{VALIDATION_ROWS}}, IT test {{IT_TEST_ROWS}}
- Taxonomy: `coding`, `ai_ml`, `hardware`, `system`, `ui_localization`, `command_cli`, `documentation`.

Mỗi dòng split giữ `dataset_row_id`, nguồn, chỉ mục dòng gốc, văn bản Anh/Việt đã làm sạch, taxonomy, hash pair và leakage group ID.

## Collection and processing

Năm nguồn được thu thập, audit, rule-based cleaning và IT filtering ở Phase 01–04. Phase 05 chỉ dùng nguồn có `approved_for_dataset_building = true`, đồng thời xác minh checksum của artifact Phase 04 trước khi gộp.

Global exact deduplication dùng cặp EN–VI đã chuẩn hóa. Khi chia split, mọi bản ghi có cùng câu tiếng Anh chuẩn hóa được gán vào cùng một split để ngăn source-text leakage. Split được sinh xác định với seed `{{SPLIT_SEED}}` và tỷ lệ mục tiêu 80/10/10.

## Quality checks

Phase 05 xuất corpus profiling, phân bố subdomain theo split, global dedup report, 5-gram leakage report, attrition funnel và Sankey source → subdomain → split. Exact-pair overlap và normalized-English leakage-group overlap giữa train với validation/IT test phải bằng 0.

## Known limitations

- Taxonomy hiện là rule-based và chỉ gồm bảy nhãn trên; nó không tách riêng networking, security hay software.
- Các corpus localization có thể làm tăng tỷ lệ câu ngắn, câu UI và cụm từ kỹ thuật lặp lại; 5-gram overlap cần được diễn giải cùng với exact leakage checks, không dùng một mình để kết luận leakage.
- Human-validation worksheet hiện chỉ có 5 dòng minh họa, không đủ để ước lượng precision hoặc inter-annotator agreement có ý nghĩa thống kê.
- Bộ dữ liệu không bao gồm General Test độc lập; không dùng IT test để suy rộng kết luận sang mọi miền ngôn ngữ.

## Reproducibility and maintenance

Mỗi lần build sinh `dataset_manifest.json` và `final_report_manifest.json`, chứa checksum cho artifact đầu ra. Để tái tạo phiên bản này, chạy notebook Phase 5 với cùng input manifest, code và seed. Khi thay đổi taxonomy, rule filtering hoặc input source, phải tăng dataset version và build lại toàn bộ split/report.
