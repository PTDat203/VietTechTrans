# Dataset Card — it_en_vi_v1

## Motivation

VietTechTrans xây dựng corpus song ngữ Anh–Việt tập trung vào miền Công nghệ thông tin để phục vụ nghiên cứu dịch máy chạy offline. Bộ dữ liệu hướng đến ba nhu cầu: so sánh các mô hình dịch máy lõi trên dữ liệu IT, nghiên cứu domain adaptation/knowledge distillation, và đánh giá mô hình gọn nhẹ trong bối cảnh triển khai cục bộ.

Corpus không nhằm thay thế một benchmark dịch máy tổng quát. IT test split được giữ riêng để đo năng lực dịch trong miền IT. Benchmark tổng quát là release độc lập `general_test_flores200_devtest_v1` (FLORES-200 `devtest`, EN↔VI, CC-BY-SA 4.0); không được trộn nó vào corpus IT hoặc dùng để chọn checkpoint/hyperparameter.

## Composition

- Phiên bản: `it_en_vi_v1`
- Số cặp được Phase 04 phê duyệt: 138,479
- Số global exact duplicate bị loại: 340
- Số cặp cuối: 138,139
- Split: train 110,452, validation 13,836, IT test 13,851
- Taxonomy: `coding`, `ai_ml`, `hardware`, `system`, `ui_localization`, `command_cli`, `documentation`.

Mỗi dòng split giữ `dataset_row_id`, nguồn, chỉ mục dòng gốc, văn bản Anh/Việt đã làm sạch, taxonomy, hash pair và leakage group ID.

## Collection and processing

Năm nguồn được thu thập, audit, rule-based cleaning và IT filtering ở Phase 01–04. Phase 05 chỉ dùng nguồn có `approved_for_dataset_building = true`, đồng thời xác minh checksum của artifact Phase 04 trước khi gộp.

Global exact deduplication dùng cặp EN–VI đã chuẩn hóa. Khi chia split, mọi bản ghi có cùng câu tiếng Anh chuẩn hóa được gán vào cùng một split để ngăn source-text leakage. Split được sinh xác định với seed `42` và tỷ lệ mục tiêu 80/10/10.

## Quality checks

Phase 05 xuất corpus profiling, phân bố subdomain theo split, global dedup report, 5-gram leakage report, attrition funnel và Sankey source → subdomain → split. Exact-pair overlap và normalized-English leakage-group overlap giữa train với validation/IT test phải bằng 0.

## Known limitations

- Taxonomy hiện là rule-based và chỉ gồm bảy nhãn trên; nó không tách riêng networking, security hay software.
- Các corpus localization có thể làm tăng tỷ lệ câu ngắn, câu UI và cụm từ kỹ thuật lặp lại; 5-gram overlap cần được diễn giải cùng với exact leakage checks, không dùng một mình để kết luận leakage.
- General Test FLORES-200 là benchmark tổng quát, không phản ánh riêng văn phong phần mềm hay tài liệu kỹ thuật; báo cáo phải giữ kết quả General Test và IT Test tách biệt.

## Reproducibility and maintenance

Mỗi lần build sinh `dataset_manifest.json` và `final_report_manifest.json`, chứa checksum cho artifact đầu ra. Để tái tạo phiên bản này, chạy notebook Phase 5 với cùng input manifest, code và seed. Khi thay đổi taxonomy, rule filtering hoặc input source, phải tăng dataset version và build lại toàn bộ split/report.
