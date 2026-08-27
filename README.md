# VietTechTrans

Nghiên cứu xây dựng hệ thống dịch máy **Anh ↔ Việt chạy offline**, ưu tiên miền IT. Dự án đánh giá ba mô hình lõi: **OPUS-MT, EnViT5 và M2M-100**, sau đó nghiên cứu domain adaptation, Knowledge Distillation (KD), quantization và benchmark triển khai offline.

## Mục tiêu

- Xây dựng corpus Anh–Việt miền IT có provenance và có thể tái lập.
- So sánh chất lượng, kích thước model, RAM và latency của ba CORE MT.
- Đo riêng hiệu năng trên General Test và IT Test.
- Tạo mô hình nhẹ hơn bằng KD và quantization mà vẫn phù hợp triển khai offline.

## Pipeline

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
3. Lưu version nguồn, license, collection date và rule xử lý trong metadata.
4. Mỗi thí nghiệm sau này phải ghi model, dataset version, seed, hardware, metric, RAM và latency.

## Phase 02 — Data audit

Notebook `notebooks/02_data_audit/02_01_source_data_audit.ipynb` audit từng nguồn RAW mà không sửa dữ liệu. Report bao gồm schema, checksum RAW, null/blank, encoding, ngôn ngữ, alignment, duplicate, length, noise, provenance/license và mẫu review tái lập được.

Mỗi nguồn tạo:

```text
data/audit/<source>/
├── audit_report.json
├── review_candidates.csv
└── random_review_sample.csv
```

Audit tách hai gate độc lập:

- `ready_for_cleaning`: cho phép chạy Phase 03.
- `approved_for_dataset_building`: bắt buộc trước Phase 05, training hoặc phát hành dataset/artifact cuối.

License hoặc provenance chưa xác minh không chặn cleaning, nhưng giữ `approved_for_dataset_building = false`.

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

## Trạng thái

- [x] Phase 01: Data collection
- [x] Phase 02: Data audit
- [x] Phase 03: Data cleaning
- [ ] Phase 04: IT filtering
- [ ] Phase 05: Dataset building
- [ ] Baseline / domain adaptation / KD / quantization / offline deployment

## License và dữ liệu

Mã nguồn dự án sử dụng license được lựa chọn riêng. License của từng dataset/model phải được xác minh theo nguồn gốc trước khi dùng cho artifact cuối hoặc phát hành lại.


English-Vietnamese Machine Translation for IT Domain

