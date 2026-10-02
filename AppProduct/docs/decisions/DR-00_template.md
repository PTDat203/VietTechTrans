# DR-NN — <Tên quyết định>

**Trạng thái:** nháp | đã chốt | thay thế bởi DR-xx · **Ngày:** yyyy-mm-dd ·
**Người viết:** … · **Liên quan:** mốc Mx, DR-yy, file hoặc commit

> Khuôn báo cáo mini theo sketch2: *ứng viên → tại sao chọn → ứng viên được
> chọn → lý do chọn và thiết kế*; *định nghĩa → quy trình → ưu điểm → nhược
> điểm*. Xoá các dòng hướng dẫn (bắt đầu bằng `>`) khi viết.

## 1. Bối cảnh & ràng buộc

> Vấn đề cần quyết định, thuộc mốc nào. Ràng buộc có nguồn: chạy offline, không
> tải model lần đầu, có EN và VI, license rõ (sơ đồ Phase 05 của Core); máy
> khoảng 4 GB RAM, minSdk 26, ABI arm64-v8a và x86_64 (kế hoạch app); yêu cầu
> từ sketch.

## 2. Định nghĩa

> Khái niệm cần biết để đọc báo cáo, mỗi mục 1–3 câu.

## 3. Quy trình hoạt động

> Sơ đồ (Mermaid) luồng dữ liệu của thành phần trong app: đầu vào, các bước,
> đầu ra, luồng chạy (thread).

```mermaid
flowchart LR
  IN["Đầu vào"] --> STEP["Bước xử lý"] --> OUT["Đầu ra"]
```

## 4. Ứng viên

| Ứng viên | Phiên bản | License | Dung lượng | Offline | Android | Số liệu (nguồn) |
|---|---|---|---|---|---|---|
| … | … | … | … | có / không | có / không | … [n] |

> Chỉ ghi số liệu có nguồn ([n] trỏ tới mục 11). Số tự đo thì ghi run ID.

## 5. Tiêu chí

> Tiêu chí dùng để so ứng viên, lấy từ yêu cầu có nguồn. Không tự đặt ngưỡng số;
> nếu cần so số liệu thì đặt các ứng viên cạnh nhau và ghi nguồn đo.

## 6. Ứng viên được chọn

> Tên, phiên bản, dạng phân phối (AAR, model, thư viện Maven).

## 7. Lý do chọn & thiết kế tích hợp

> Vì sao chọn, theo từng tiêu chí ở mục 5. Thiết kế tích hợp: file và vị trí
> trong app, tham số và giá trị khởi điểm, phiên bản, SHA-256 (tham chiếu
> `artifacts.lock.json`), class/package liên quan.

## 8. Ưu điểm

## 9. Nhược điểm & rủi ro

> Mỗi rủi ro kèm cách giảm hoặc cách phát hiện.

## 10. Kiểm chứng

> Lệnh đã chạy và kết quả (marker `…=PASS`), test liên quan, run ID trong
> `evidence/runs/`. Emulator chỉ kiểm tra chức năng.

## 11. Nguồn

1. …
