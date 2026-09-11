# Phase 06 — CORE MT pretrained baseline

Phase này chỉ đánh giá checkpoint pretrained chính thức. Không fine-tune, không
đọc IT `train`/`validation`, không điều chỉnh prompt theo test set và không
chọn checkpoint từ General Test hoặc IT Test.

## Điều kiện vào phase

Phase 05 phải tồn tại cục bộ vì data release không được commit vào Git. Chạy:

```powershell
& 'C:\Users\ADMIN\anaconda3\python.exe' tools\validate_phase05_gate.py
& 'C:\Users\ADMIN\anaconda3\python.exe' tools\check_core_shared.py
```

Chỉ tiếp tục khi có `PHASE_05_GATE=PASS` và `CORE_SHARED=READY`.

## Protocol cố định

`configs/evaluation_protocol_v2.json` áp dụng cho mọi model × direction:

- Quality: sinh đúng một prediction cho mỗi câu General Test và IT Test.
- Metric: chrF++ là chính, SacreBLEU là phụ; không lấy trung bình hai test set.
- Latency: tối đa 128 row/test set, chọn cố định từ `SHA-256(seed:row_id)`,
  warm-up tối đa 32 câu, đo 5 lượt.
- RAM: peak process RSS qua quality pass và latency probe.
- Artifact: `resolved_config.json`, `predictions.jsonl`, `metrics.json`,
  `benchmark.json`, `environment.txt`, `run.log`.

Runner chỉ đổi `.in_progress` thành run hoàn chỉnh khi đủ sáu artifact. Không
xóa hoặc ghi đè run hoàn chỉnh khi muốn chạy lại; hãy tạo version protocol/run
mới để giữ evidence cũ.

## Thứ tự nhánh

1. OPUS-MT EN→VI và VI→EN: hoàn thành.
2. EnViT5 EN→VI, sau đó VI→EN: code/notebook sẵn sàng.
3. M2M-100 EN→VI và VI→EN: chưa bắt đầu.
4. Chỉ lập bảng CORE comparison khi đủ sáu run hoàn chỉnh.

Notebook là bản ghi thực nghiệm; module Python là implementation tái sử dụng:

```text
notebooks/06_core_mt/             # trình bày code, log, bảng và biểu đồ
src/core_mt/                      # protocol, runner, metrics, adapter
configs/                          # checkpoint và protocol frozen
runs/                             # artifact cục bộ, không commit
```

Hướng dẫn cài đặt/chạy cụ thể xem [REPRODUCIBILITY.md](REPRODUCIBILITY.md).
