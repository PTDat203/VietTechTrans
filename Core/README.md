# VietTechTrans

VietTechTrans là phần Core của dự án dịch máy Anh–Việt cho nội dung IT: xây
dựng dataset, chọn Core MT và tạo Student Main-Core.

## Tiến độ hiện tại

Phase 01 và Phase 02 đã hoàn tất theo evidence local: dataset `it_en_vi` đã
khóa, Core MT đã được freeze cho hai chiều EN→VI và VI→EN, và final reporting
đã hoàn tất. Phase 03 đã freeze hai Student Main-Core cho Phase 4: EN→VI
`phase03-seqkd-en-to-vi-v2` và VI→EN `phase03-seqkd-vi-to-en-v3`. Core dừng
ở đây. Phase 4 được thực hiện trên máy local khác; repository này chỉ giữ
hướng dẫn bàn giao checkpoint và hash.

## Workflow

```text
Phase 01  Dataset IT EN↔VI
    ↓
Phase 02  Screening, thích nghi IT và chọn Core MT
    ↓
Phase 03  Knowledge distillation và freeze Student Main-Core
    ↓
Bàn giao checkpoint + hash cho máy Phase 4 khác
```

Sơ đồ Core: [project_workflow.svg](docs/project_workflow.svg).

## Tài liệu

| Nội dung | Tài liệu |
|---|---|
| Phase 01: dữ liệu, phân nhóm và gate | [docs/phase_01/README.md](docs/phase_01/README.md) |
| Workflow Phase 01 | [docs/phase_01/workflow.svg](docs/phase_01/workflow.svg) |
| Phase 02 | [docs/phase_02/README.md](docs/phase_02/README.md) |
| Kết quả và giới hạn Phase 02 | [docs/phase_02/final_report.md](docs/phase_02/final_report.md), [docs/provenance_and_reproducibility.md](docs/provenance_and_reproducibility.md) |
| Workflow Phase 02 | [docs/phase_02/workflow.svg](docs/phase_02/workflow.svg) |
| Workflow Phase 03 | [docs/phase_03/workflow.svg](docs/phase_03/workflow.svg) |
| Thiết kế và câu hỏi nghiên cứu Phase 03 | [docs/phase_03/README.md](docs/phase_03/README.md) |
| Cách lấy Main-Core cho Phase 4 | [docs/phase_04/README.md](docs/phase_04/README.md), [Phase 3 decision](evidence/phase03/main_core_decision.json) |
| Bàn giao Main-Core sang máy Phase 4 khác | [docs/phase_04/README.md](docs/phase_04/README.md) |
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
