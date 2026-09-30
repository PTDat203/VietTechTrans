# VietTechTrans

VietTechTrans là dự án dịch máy Anh–Việt cho nội dung IT. Dự án đi từ dataset,
chọn Core MT, tạo student model và triển khai offline.

## Tiến độ hiện tại

Phase 01 và Phase 02 đã hoàn tất theo evidence local: dataset `it_en_vi` đã
khóa, Core MT đã được freeze cho hai chiều EN→VI và VI→EN, và final reporting
đã hoàn tất. Phase 03 đến Phase 05 là các bước tiếp theo, chưa có evidence thực
nghiệm trong repository.

## Workflow

```text
Phase 01  Dataset IT EN↔VI
    ↓
Phase 02  Screening, thích nghi IT và chọn Core MT
    ↓
Phase 03  Knowledge distillation
    ↓
Phase 04  Tối ưu chạy offline
    ↓
Phase 05  STT → MT → TTS
```

Sơ đồ đầy đủ: [project_workflow.svg](docs/project_workflow.svg).

## Tài liệu

| Nội dung | Tài liệu |
|---|---|
| Phase 01: dữ liệu, phân nhóm và gate | [docs/phase_01/README.md](docs/phase_01/README.md) |
| Workflow Phase 01 | [docs/phase_01/workflow.svg](docs/phase_01/workflow.svg) |
| Phase 02 | [docs/phase_02/README.md](docs/phase_02/README.md) |
| Kết quả và giới hạn Phase 02 | [docs/phase_02/final_report.md](docs/phase_02/final_report.md), [docs/provenance_and_reproducibility.md](docs/provenance_and_reproducibility.md) |
| Workflow Phase 02 | [docs/phase_02/workflow.svg](docs/phase_02/workflow.svg) |
| Workflow Phase 03 | [docs/phase_03/workflow.svg](docs/phase_03/workflow.svg) |
| Workflow Phase 04 | [docs/phase_04/workflow.svg](docs/phase_04/workflow.svg) |
| Workflow Phase 05 | [docs/phase_05/workflow.svg](docs/phase_05/workflow.svg) |
| Hướng dẫn chạy lại | [manual/README.md](manual/README.md) |
| Môi trường và provenance | [docs/environment.md](docs/environment.md), [docs/provenance_and_reproducibility.md](docs/provenance_and_reproducibility.md) |
| Quy trình snapshot cho run mới | [docs/run_snapshot_policy.md](docs/run_snapshot_policy.md) |

Dataset release hiện hành là `it_en_vi`. Mọi thay đổi về nguồn, rule xử lý,
phân nhóm hoặc split phải tạo release mới và chạy lại gate trước khi sang
Phase 02. IT Test và General Test chỉ mở một lần ở cuối Phase 02, sau khi Core
MT đã được khóa; screening và adaptation chỉ dùng IT Train, IT Validation và
General Validation độc lập.

## Môi trường

`requirements.txt` và `environment.yml` chứa dependency Python cho build dữ
liệu, notebook và Phase 02. Hai script render workflow
cần Node.js và package `sharp`: chạy `npm install` trước khi dùng
`npm run render:workflows` hoặc `npm run render:workflows:portrait`.
