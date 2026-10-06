# Snapshot cho run mới

Áp dụng từ Phase 03. Mục đích là để một artifact hoàn tất luôn có source và
cấu hình đúng tại thời điểm nó được tạo.

## Trước khi chạy

Tạo thư mục staging của run:

```text
evidence/<phase>/<run-id>.running/
```

Trong đó lưu:

```text
source_snapshot/       runner và module trực tiếp tạo artifact
config_snapshot/       bản sao config dùng cho run
command.txt            lệnh thực thi đầy đủ
dependency_versions.txt phiên bản Python và package quan trọng
runtime.json           OS, device, CUDA và precision nếu có
input_manifest.json    đường dẫn và SHA-256 của input/checkpoint đầu vào
snapshot_manifest.json danh sách file snapshot và SHA-256
```

Snapshot phải hoàn tất trước train, inference hoặc scoring. Không dùng snapshot
để sửa dữ liệu, prediction hoặc evidence cũ.

## Khi hoàn tất hoặc thất bại

- Chỉ đổi `<run-id>.running` thành `<run-id>` sau khi artifact và snapshot đều
  được ghi xong.
- Run thất bại giữ nguyên trong `.running` để chẩn đoán; không dùng làm evidence.
- Không sửa nội dung completed evidence. Run mới với thay đổi source, config,
  input hoặc checkpoint phải có `run-id` mới.

## Ngoại lệ lịch sử Phase 03

Hai Main-Core Phase 03 đã freeze vẫn giữ tên thư mục `.running` vì decision,
checksum và lineage đã tham chiếu đúng các đường dẫn đó. Không đổi tên hoặc di
chuyển chúng. Với hai run này, trạng thái cuối cùng chỉ lấy từ
`evidence/phase03/main_core_decision.json`.

Ngoại lệ này không áp dụng cho run mới. Run mới phải có đủ snapshot trước khi
chạy và dùng trạng thái thư mục theo quy tắc ở trên.

## Phạm vi

Policy này không tạo thêm model, metric hay selection rule. Nó chỉ bảo toàn
provenance cho các phase thực hiện sau Phase 02.
