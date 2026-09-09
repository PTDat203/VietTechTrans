# Phase 06A — Shared CORE MT layer

Phase này chỉ tạo hạ tầng chung cho baseline OPUS-MT, EnViT5 và M2M-100. Nó
không chứa checkpoint ID, tokenizer, prefix hay rule inference model-specific.

## Thành phần

- `protocol.py`: chỉ chấp nhận `core_mt_baseline_v2` đã frozen.
- `data.py`: chỉ public loader cho `general_test` và `it_test`; không có loader `train` hay `validation`.
- `contracts.py`: direction và interface mà adapter sau này bắt buộc tuân theo.
- `artifacts.py`: policy không ghi đè evidence của baseline run.
- `check_core_shared.py`: kiểm tra release gate, protocol và schema hai test set mà không tải model.

## Kiểm tra

```powershell
& 'C:\Users\ADMIN\anaconda3\python.exe' tools\check_core_shared.py
& 'C:\Users\ADMIN\anaconda3\python.exe' -m unittest discover -s tests -p test_core_shared.py
```

Xem [PHASE_06_CORE_MT.md](PHASE_06_CORE_MT.md) để biết thứ tự chạy và điều
kiện tái lập cho các nhánh adapter.
