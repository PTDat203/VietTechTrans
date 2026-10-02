# Thực nghiệm (TN)

"TN" là **Thực nghiệm** (ký hiệu trong sketch2). Mỗi TN trả lời một câu hỏi thực
tế trong sketch bằng số đo trên app. Viết theo [TN-00_template.md](TN-00_template.md):
protocol viết trước, kết quả điền sau, kèm run ID trong `evidence/runs/`. Kết
quả trên emulator chỉ kiểm tra chức năng. Protocol và run: mốc M8; TN nào chưa
chạy được thì ghi WAITING kèm lý do (ví dụ chưa có máy thật, chưa có người thu âm).

| TN | Chủ đề | Mục tiêu | Câu hỏi (sketch) | Thiết lập |
|---|---|---|---|---|
| TN-01 | Nói sai ngôn ngữ / trộn Anh–Việt | Biết STT xử lý câu sai chiều và thuật ngữ tiếng Anh trong câu tiếng Việt ra sao | "A nói nửa Anh nửa Việt thì sao?"; "chọn Việt mà có người nói tiếng Anh?" | Câu EN đưa vào chế độ VI (và ngược lại); câu VI có thuật ngữ IT; đo tỉ lệ nhận đúng thuật ngữ, CER/WER |
| TN-02 | Ồn | Biết độ giảm chất lượng STT theo mức ồn và tác dụng của khử nhiễu | "Xung quanh ồn quá thì sao?"; "STT–TTS giảm nhiễu tốt không?" | WAV sạch trộn ồn bằng `tools/mix_noise.py` ở SNR 20/10/5/0 dB; GTCRN bật/tắt; đo CER/WER, tỉ lệ VAD bỏ sót |
| TN-03 | Ấp úng | Chọn mặc định cho làm sạch từ đệm và thời gian im lặng kết thúc lượt | "A nói mà ấp úng thì sao?" | Làm sạch bật/tắt; im lặng kết thúc lượt 1.0/1.5/2.0 s; đo số lượt kết thúc sớm, CER/WER |
| TN-04 | Chen lời | So quy tắc Cắt và Chờ khi hai người nói | "A đang nói, B chen vào thì sao?" (chen 1/2/3) | Kịch bản hội thoại với Cắt và Chờ, chen khi TTS đang đọc, nói chồng; đo phần lời giữ lại của lượt bị cắt, thời gian chờ |
| TN-05 | Giọng vùng miền | Biết STT tiếng Việt kém đi bao nhiêu với giọng các vùng | "Giọng nhà quê?" | Người nói nhiều vùng đọc cùng bộ câu viết tay (số người chốt ở M8, có đồng ý ghi âm); đo CER theo vùng |
| TN-06 | Tài nguyên | Biết app tốn bao nhiêu thời gian và RAM trên máy yếu | "Nhét các thứ vào app thì có chạy được trên máy yếu không?"; "runtime và tài nguyên app tiêu tốn?" | numThreads, partial bật/tắt, cỡ đoạn TTS; bộ model màn Dịch vs màn Hội thoại; đo thời gian nạp, PSS/VmHWM, RTF |
| TN-07 | Phát âm thuật ngữ IT | Biết lexicon có giúp giọng Việt đọc thuật ngữ IT dễ hiểu hơn không | "Chuyên hơn về công nghệ thông tin" | Lexicon bật/tắt; loopback TTS → STT tính CER trên thuật ngữ; người nghe đánh giá |
