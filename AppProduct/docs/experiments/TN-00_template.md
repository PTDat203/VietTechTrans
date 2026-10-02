# TN-NN — <Chủ đề>

**Trạng thái:** protocol | đã chạy | WAITING (lý do) · **Ngày:** yyyy-mm-dd ·
**Người viết:** … · **Liên quan:** DR-xx, run ID

> Mục 1–5 (protocol) viết và commit **trước** khi chạy; không sửa sau khi đã
> chạy. Mục 6–7 điền sau khi chạy. Xoá các dòng hướng dẫn (bắt đầu bằng `>`).

## 1. Mục tiêu

> Một câu: thực nghiệm này giúp quyết định gì trong app.

## 2. Câu hỏi

> Câu hỏi trong sketch mà thực nghiệm trả lời, viết lại thành câu đo được.

## 3. Thiết lập

| Mục | Giá trị |
|---|---|
| Build | flavor, build type, version, git SHA |
| Thiết bị | emulator / máy thật (model, RAM, Android) |
| Suite | `model_load` / `tts_prompts` / `loopback` / `wav_turns` / `session` |
| Biến thay đổi | tham số và các mức thử (chỉ ghi phần khác giá trị khởi điểm) |
| Số lần lặp | … |

## 4. Dữ liệu

| Mục | Giá trị |
|---|---|
| WAV | 16 kHz, mono; thư mục hoặc danh sách file |
| n | số câu / số lượt |
| Nguồn | tự ghi âm, TTS, câu viết tay (không lấy câu từ dataset) |
| Đồng ý ghi âm | ai đồng ý, cách ghi nhận; giọng người không đồng ý thì không lưu |

## 5. Cách đo

> Chỉ số theo [evidence_app_v1.md](../contracts/evidence_app_v1.md) (ví dụ
> `stt_ms`, `response_ms`, CER/WER do `validate_app_evidence.py` tính). Đặt các
> mức thử cạnh nhau; không tự đặt ngưỡng đạt/không đạt.

## 6. Kết quả

| Mức thử | Run ID | n | Số liệu (từ `summary.json`) |
|---|---|---|---|
| … | `…` | … | … |

## 7. Nhận xét & giới hạn

> Kết quả trả lời câu hỏi ở mục 2 đến đâu. Giới hạn: emulator chỉ kiểm tra chức
> năng, n nhỏ, giọng tổng hợp thay giọng thật…
