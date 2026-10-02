# Evidence của app

Định dạng chi tiết: [docs/contracts/evidence_app_v1.md](../docs/contracts/evidence_app_v1.md)
(bản nháp, hoàn thiện ở M3).

## Cấu trúc

```text
evidence/
  builds/<tên APK>.json     do tools/check_apk.py ghi: kích thước, SHA-256, kết quả kiểm APK
  runs/<run-id>/
    run.json                app ghi trên máy
    items.jsonl             app ghi trên máy
    summary.json            tools/validate_app_evidence.py ghi trên PC
```

Run ID: `<yyyyMMddTHHmmssZ>_<flavor>-<buildType>_<device>_<suite>`, ví dụ
`20261015T083012Z_offline-release_sdk-gphone64-x86-64_model_load`.

## Quy tắc

- App ghi vào `<run-id>.running/` rồi đổi tên thành `<run-id>/` khi xong. Thư
  mục còn `.running` không bao giờ là evidence (đã có trong `.gitignore`).
- Run đã hoàn tất không sửa. Đổi build, model hay tham số thì chạy run mới.
- Chỉ commit run có `APP_EVIDENCE=PASS`. Run của bản `dev` không phải evidence.
- Run trên emulator (`is_emulator = true`) chỉ kiểm tra chức năng; không dùng số
  liệu tốc độ hay RAM của emulator để kết luận cho máy thật.
- `APP_OFFLINE_E2E` chỉ PASS khi có run của bản offline release + Core MT +
  Airplane mode + máy thật + cả hai chiều; hiện tại là
  `WAITING reason=no_core_mt,no_real_device`.

## Kéo run về PC

Chạy trong `AppProduct/`:

```bat
adb pull /sdcard/Android/data/vn.viettechtrans.app/files/evidence/<run-id> evidence/runs/
..\.venv\Scripts\python.exe tools\validate_app_evidence.py evidence\runs\<run-id>
```

## Danh sách run

Chưa có run (cập nhật ở M8).
