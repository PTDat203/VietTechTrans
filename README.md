# VietTechTrans

Nghiên cứu xây dựng hệ thống dịch máy **Anh ↔ Việt chạy offline**, ưu tiên miền IT. Dự án đánh giá ba mô hình lõi: **OPUS-MT, EnViT5 và M2M-100**, sau đó nghiên cứu domain adaptation, Knowledge Distillation (KD), quantization và benchmark triển khai offline.

## Mục tiêu

- Xây dựng corpus Anh–Việt miền IT có thể tái lập.
- So sánh chất lượng, kích thước model, RAM và latency của ba CORE MT.
- Đo riêng hiệu năng trên General Test và IT Test.
- Tạo mô hình nhẹ hơn bằng KD và quantization mà vẫn phù hợp triển khai offline.

## Pipeline

![Tiền xử lý data pipeline](<tiền xử lý data - pipeline.svg>)

```text
Data collection
→ Data audit
→ Data cleaning
→ IT filtering
→ Dataset building (train/val/test)
→ Baseline: OPUS-MT / EnViT5 / M2M-100
→ Domain adaptation
→ Core MT comparison
→ Teacher + student selection
→ Knowledge Distillation
→ Quantization
→ Offline benchmark
→ Error analysis + thesis evidence
```

> RAW data, technology candidate và IT-usable data là ba trạng thái khác nhau. Không chọn teacher trước khi hoàn thành comparison của ba CORE MT.

## Cài môi trường

```bash
conda env create -f environment.yml
conda activate envi-it-mt
jupyter lab
```

Hoặc dùng pip:

```bash
pip install -r requirements.txt
jupyter lab
```

## Cấu trúc hiện tại

```text
configs/                         # Config dùng chung
data/                            # RAW, interim, processed và splits (không commit dữ liệu lớn)
notebooks/
└── 01_data_collection/
    ├── 01_01_envitech_reasoning_download.ipynb
    ├── 01_02_tech_viet_translation_download.ipynb
    ├── 01_03_gnome_download.ipynb
    ├── 01_04_ubuntu_download.ipynb
    └── 01_05_kde_download.ipynb
└── 05_dataset_building/
    └── 05_01_build_dataset.ipynb
environment.yml
requirements.txt
```

## Phase 01 — Data collection

| Source | Method | Raw pairs | Candidate pairs |
|---|---|---:|---:|
| EnVi-Tech-Reasoning-SFT | Hugging Face Datasets | 15,115 | 7,464 |
| tech-viet-translation | Hugging Face Datasets | 100,767 | 100,767 |
| GNOME | OPUS direct ZIP | 149 | 149 |
| Ubuntu | mtdata / OPUS | 5,056 | 5,056 |
| KDE4 | OPUS direct ZIP | 42,782 | 42,782 |

Mỗi notebook phase 01 thực hiện: kiểm tra environment → cấu hình source → download/read → inspect → statistics → technology candidate → lưu RAW JSONL/Parquet → lưu audit và metadata → verification.

Các artifact dự kiến cho mỗi source:

```text
data/raw/<source>/
├── <source>_raw.jsonl
├── <source>_raw.parquet
├── audit_summary.json
└── metadata.json
```

## Quy tắc tái lập

1. Chạy notebook bằng `Restart Kernel → Run All`.
2. Không sửa hoặc ghi đè dữ liệu RAW sau khi đã thu thập.
3. Lưu version nguồn, collection date và rule xử lý trong metadata.
4. Mỗi thí nghiệm sau này phải ghi model, dataset version, seed, hardware, metric, RAM và latency.

## Phase 02 — Data audit

Notebook `notebooks/02_data_audit/02_01_source_data_audit.ipynb` audit từng nguồn RAW mà không sửa dữ liệu. Report bao gồm schema, checksum RAW, null/blank, encoding, ngôn ngữ, alignment, duplicate, length, noise, provenance nguồn và mẫu review tái lập được.

Mỗi nguồn tạo:

```text
data/audit/<source>/
├── audit_report.json
├── review_candidates.csv
└── random_review_sample.csv
```

Audit tạo `ready_for_cleaning` để cho phép chạy Phase 03. Các vấn đề về chất lượng được lưu thành cờ review và được xử lý ở Phase 03–04.

## Phase 03 — Data cleaning

Notebook `notebooks/03_data_cleaning/03_01_rule_based_cleaning.ipynb` chạy riêng cho từng nguồn sau Phase 02 schema 1.1. RAW chỉ được đọc. Notebook xác minh SHA-256 RAW, checksum audit report và `ready_for_cleaning = true` trước khi tạo interim artifact.

Các rule 1.1.0:

- Chuẩn hoá Unicode NFC và whitespace.
- Loại blank/null, non-string, replacement/control character và exact duplicate sau chuẩn hoá.
- Giữ `audit_flags` và `requires_phase_04_review` trên interim pairs; Phase 04 quyết định các trường hợp noise, alignment, length ratio và language theo ngữ cảnh IT.

Mỗi nguồn tạo:

```text
data/interim/<source>/
├── cleaned_pairs.parquet
├── cleaned_pairs.jsonl
├── rejected_pairs.parquet
├── cleaning_report.json
└── cleaning_manifest.json
```

`cleaning_manifest.json` giữ checksum cho tất cả output và audit report đã dùng. `data/interim/` không được commit vì có thể lớn; notebook, audit report và rule được version control.

## Phase 04 — IT filtering

Notebook `notebooks/04_it_filtering/04_01_it_filtering.ipynb` đọc `cleaned_pairs.parquet` cùng manifest Phase 03 và xác minh checksum trước khi lọc. Notebook gán taxonomy IT (`coding`, `ai_ml`, `hardware`, `system`, `ui_localization`, `command_cli`, `documentation`) bằng rule có version; không dùng audit flag làm điều kiện loại tự động.

- Audit flag có policy rõ ràng: `blank_or_null`, `encoding_issue`, `identical_pair`, `exact_duplicate` và các `noise_*` bị auto-reject; `language_suspect`, `extreme_length_ratio`, `very_long_pair` vào `manual_review`. Cờ không nhận diện cũng vào `manual_review`; auto-reject có ưu tiên khi một pair mang nhiều cờ.
- Người review điền `manual_review_decisions.csv` rồi chạy lại notebook để áp dụng quyết định `approve` hoặc `reject`.
- `envitech_reasoning` chỉ được approve tự động khi có bằng chứng keyword thuộc taxonomy, vì RAW nguồn này có cả dữ liệu ngoài IT. Các corpus technology/localization khác có source prior được ghi rõ trong artifact.
- Phase 05 chỉ được dùng source có `approved_for_dataset_building = true`, nghĩa là không còn pair `manual_review`.

Mỗi nguồn tạo:

```text
data/it_corpus/<source>/
├── approved_it_pairs.parquet
├── approved_it_pairs.jsonl
├── rejected_or_non_it_pairs.parquet
├── manual_review_pairs.csv
├── manual_review_decisions.csv
├── it_filtering_report.json
└── it_filtering_manifest.json
```

## Phase 05 — Dataset building and evidence report

Notebook `notebooks/05_dataset_building/05_01_build_dataset.ipynb` chuẩn bị và **hiển thị trước** corpus profile, phân bố subdomain theo split, global exact dedup, 5-gram leakage check, attrition funnel, Sankey và worksheet human validation `head(5)`. Chỉ cell cuối cùng mới ghi output.

```text
data/processed/it_en_vi_v1/       # train/validation/it_test, dedup artifact, manifest
data/final_report/it_en_vi_v1/    # CSV/JSON/HTML charts, Dataset Card, report manifest
```

`DATASET_CARD.md` ở project root là tài liệu nguồn được version-control; Phase 05 điền số liệu build rồi copy bản kết quả vào `data/final_report/it_en_vi_v1/DATASET_CARD.md`.

Split dùng seed `42`, target ratio 80/10/10 và group theo câu tiếng Anh chuẩn hoá để không rò rỉ source text giữa split.

## Trạng thái

- [x] Phase 01: Data collection
- [x] Phase 02: Data audit
- [x] Phase 03: Data cleaning
- [x] Phase 04: IT filtering (đã xử lý audit flags, hoàn tất manual review và mở gate Phase 05)
- [x] Phase 05: Dataset building and evidence report
- [ ] Baseline / domain adaptation / KD / quantization / offline deployment

English-Vietnamese Machine Translation for IT Domain

