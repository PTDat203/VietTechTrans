# Handoff cho thành viên dùng Antigravity và Claude

Tài liệu này dành cho người pull repository trên máy khác và cần tiếp tục
Phase 06 mà không làm thay đổi Phase 01–05 release.

## Mục tiêu trước khi mở notebook

1. Clone/pull nhánh `main`.
2. Tạo môi trường theo `environment.yml` hoặc `requirements.txt`.
3. Tải asset `phase05_release_v1.zip` từ GitHub Release `phase05-release-v1`.
4. Kiểm tra bundle và giải nén tại **root repository**, không phải trong
   `data/processed`.

```powershell
python tools\verify_phase05_release.py --archive .\phase05_release_v1.zip
Expand-Archive .\phase05_release_v1.zip -DestinationPath .
python tools\validate_phase05_gate.py
```

Kết quả bắt buộc là `PHASE_05_GATE=PASS`. Nếu fail, dừng lại và gửi toàn bộ
output cho người phụ trách release; không tự sửa JSON manifest, test set hoặc
checksum để ép gate pass.

## Prompt nên gửi Claude trong Antigravity

```text
Đọc README.md, REPRODUCIBILITY.md, PHASE_05_RELEASE.md,
PHASE_06_CORE_MT.md và notebook trong notebooks/06_core_mt/.

Trước khi chỉnh code, hãy chạy:
1. python tools/validate_phase05_gate.py
2. python -m unittest discover -s tests -p "test_*.py"
3. python tools/check_core_shared.py

Hãy báo rõ trạng thái từng lệnh. Không sửa data/processed, manifest,
evaluation_protocol_v2.json, test set hoặc manual_review_decisions.csv nếu
không có yêu cầu rõ ràng. Chỉ tiếp tục Phase 06 khi gate PASS.
```

Claude chỉ có thể đọc những file đã tồn tại cục bộ. Nếu chưa tải release ZIP,
AI không thể suy ra hay tái tạo chính xác IT Test/General Test từ notebook.

## Tiếp tục Phase 06

| Việc | Notebook/script |
|---|---|
| Xem shared layer và gate | `06_01_shared_core_setup.ipynb` |
| Xem OPUS-MT EN→VI đã hoàn thành | `06_02_opus_mt_baseline.ipynb` |
| Xem OPUS-MT VI→EN đã hoàn thành | `06_03_opus_mt_baseline_vi_to_en.ipynb` |
| Chạy EnViT5 EN→VI | `06_04_envit5_baseline_en_to_vi.ipynb` |
| Chạy EnViT5 VI→EN | `06_05_envit5_baseline_vi_to_en.ipynb` |

Trong notebook cần chạy model, đổi `RUN_FULL_BASELINE = False` thành `True`
một lần, Run All, rồi đổi lại `False` khi xem log/kết quả. Không chạy lại khi
cùng direction đã có thư mục `.in_progress` hoặc completed run.

Checkpoint sẽ được tải từ Hugging Face; `models/` và `runs/` không nằm trong
Git. Artifact run hoàn chỉnh luôn nằm ở
`runs/core_mt_baseline_v2/<model>/<direction>/` và phải có đủ sáu file evidence.

## Khi phải sửa Phase 01–05

Release ZIP chỉ dùng để tiếp tục Phase 06. Muốn thay đổi pipeline dữ liệu,
phải tải lại nguồn gốc và chạy Phase 01–05 theo thứ tự. Quyết định review thủ
công đã được commit tại:

```text
data/it_corpus/<source>/manual_review_decisions.csv
```

Các file này là một phần của phương pháp. Không xoá, tạo quyết định mới hoặc
commit thay đổi vào chúng nếu chưa thống nhất rằng sẽ tạo dataset release/version
mới.
