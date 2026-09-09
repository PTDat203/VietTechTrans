# Phase 06C — EnViT5 pretrained baseline

Model: `VietAI/envit5-translation`, một checkpoint T5 dùng cho cả hai chiều.
Adapter không chỉnh nội dung test set; nó chỉ áp dụng giao diện đã nêu trên
model card:

| Direction | Input gửi tokenizer | Target tag gỡ trước khi chấm |
|---|---|---|
| EN→VI | `en: <English source>` | `vi:` |
| VI→EN | `vi: <Vietnamese source>` | `en:` |

Target tag chỉ bị gỡ khi nó đứng ngay đầu output decoded. Đây là tag giao diện
của model; phần bản dịch còn lại được giữ nguyên để tính metric.

## File dùng chung

- `configs/envit5_baseline_v1.json`: checkpoint và prefix theo direction.
- `src/core_mt/adapters/envit5.py`: load, prepare input, decode và normalize output.
- `tools/run_envit5_baseline.py`: gọi Phase 05 gate rồi gọi shared runner.
- `tests/test_envit5_adapter.py`: test prefix/output tag, không tải model.

## Notebook và lệnh chạy

| Direction | Notebook | Lệnh CLI tương đương |
|---|---|---|
| EN→VI | `06_04_envit5_baseline_en_to_vi.ipynb` | `python tools/run_envit5_baseline.py --direction en_to_vi --device cpu` |
| VI→EN | `06_05_envit5_baseline_vi_to_en.ipynb` | `python tools/run_envit5_baseline.py --direction vi_to_en --device cpu` |

Notebook đặt `RUN_FULL_BASELINE = False` mặc định. Chỉ đổi thành `True` để
bắt đầu run; sau khi run bắt đầu, đổi lại `False` trước khi xem trạng thái/log.

Trên Windows không cần Developer Mode. Adapter tải checkpoint dạng file thường
vào `models/envit5/<commit-SHA>/`, không dùng symlink của Hugging Face cache.
`models/` và `runs/` bị Git ignore vì có thể lớn và được tái tạo từ config,
Phase 05 release và checkpoint SHA lưu trong artifact.
