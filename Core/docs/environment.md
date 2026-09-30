# Môi trường thực thi

`environment.yml` và `requirements.txt` là dependency baseline để build dataset,
chạy notebook và các kiểm tra thông thường. Chúng không phải source snapshot của
các run Phase 02 đã hoàn tất.

Evidence adaptation Phase 02 ghi nhận môi trường thực tế: Python 3.11.15,
PyTorch 2.14.0+cu130, Transformers 4.57.6, PEFT 0.18.1, CUDA 13.0 và NVIDIA
GeForce RTX 3050 Ti Laptop GPU với 4 GiB VRAM. Hai run LoRA dùng FP16.

Trước khi chạy script, kiểm tra interpreter đang dùng:

```bat
python --version
where python
```

Với run cần GPU, kiểm tra riêng CUDA trước khi train hoặc inference. Không suy
ra GPU availability chỉ từ việc cài `torch`; bản build, driver và thiết bị hiện
có phải được kiểm tra ở máy chạy.

Nếu dựng môi trường mới, dùng version trong dependency files làm điểm bắt đầu và
ghi lại phiên bản thực tế vào snapshot của run theo
[run_snapshot_policy.md](run_snapshot_policy.md).
