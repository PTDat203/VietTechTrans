# Chọn phương pháp KD và kiến trúc Student cho EN→VI

## Trạng thái và ranh giới

Phase 03 EN→VI đã dùng Sequence-level KD với Student
`Helsinki-NLP/opus-mt-en-vi`, revision
`989c9fb9ec63987901022baf0182dcec3e149be6`. Run `phase03-seqkd-en-to-vi-v2`
đã train, đánh giá và được freeze làm Main-Core EN→VI cho Phase 4 trong
`evidence/phase03/main_core_decision.json`. Hậu tố `.running` chỉ được giữ để
bảo toàn path và checksum lịch sử, không phải trạng thái run. Tài liệu này ghi
lý do chọn trước khi chạy; kết quả hiện có không chứng minh kiến trúc Student là
tốt nhất.

Student không phải Teacher được nén trực tiếp. Teacher, kiến trúc Student và
phương pháp KD là ba thành phần khác nhau:

```text
Teacher EnViT5 đã khóa
→ sinh pseudo-target tiếng Việt từ IT Train
→ Sequence-level KD quy định Student học source → pseudo-target
→ Student Marian EN→VI cập nhật trọng số riêng
```

Pruning hoặc quantization mới là các cách giảm trực tiếp trọng số của một model;
chúng thuộc Phase 04. Sequence-level KD chỉ xác định cách Student học từ output
Teacher, không tự tạo một kiến trúc Student.

## Chọn phương pháp KD

| Phương pháp | Quyết định | Lý do trong dự án |
|---|---|---|
| Sequence-level KD | Chọn | Teacher và Student chỉ cần chung source/target text; không cần vocabulary hay layer giống nhau. |
| Word-level/logit KD | Không chọn | Tokenizer và vocabulary khác nhau; cần loss hoặc mapping riêng, không cần thiết cho câu hỏi hiện tại. |
| Feature/intermediate KD | Không chọn | Cần ghép layer và projection giữa hai kiến trúc khác nhau; tăng biến thí nghiệm mà chưa phục vụ quyết định Main-Core. |

Sequence-level KD cho dịch máy được mô tả trong [Kim & Rush (2016)](https://arxiv.org/abs/1606.07947).
Ý tưởng distillation bằng soft target được nêu trong [Hinton, Vinyals & Dean (2015)](https://arxiv.org/abs/1503.02531), nhưng không tự giải quyết khác biệt tokenizer của dự án.

## Chọn kiến trúc Student EN→VI

### Tiêu chí

Student cần là encoder-decoder có thể nhận source tiếng Anh và sinh tiếng Việt,
nhỏ hơn Teacher, chạy được trong điều kiện train đã kiểm tra của dự án, và không
đòi thêm cơ chế KD phức tạp. Đây là shortlist phục vụ một thí nghiệm đồ án,
không phải khảo sát toàn bộ model dịch máy.

| Candidate | Dữ kiện kiểm tra | Đánh giá cho dự án |
|---|---|---|
| `Helsinki-NLP/opus-mt-en-vi` | Checkpoint dịch English→Vietnamese, Apache-2.0; Marian encoder/decoder 6+6 lớp, `d_model=512`, giới hạn 512 token. | Được chọn. Là checkpoint dịch EN→VI song ngữ sẵn có, nhỏ hơn Teacher và đã train được trong run hiện tại. |
| `google/mt5-small` | mT5 pretrain trên 101 ngôn ngữ, có Vietnamese; model card nêu chỉ pretrain, cần fine-tune cho task sau đó. | Không chọn cho run này. Có Vietnamese nhưng không phải checkpoint dịch EN→VI chuyên biệt; thay model sẽ tạo thêm biến về tokenizer, training và evaluation. |
| `google-t5/t5-small` | 60M tham số; model card chỉ liệt kê English, French, Romanian, German. | Không chọn. Nhỏ hơn, nhưng không có Vietnamese trong nhóm ngôn ngữ được model card liệt kê; rủi ro khởi tạo cho EN→VI cao hơn. |

Nguồn cho các thông tin trên: [config OPUS-MT](https://huggingface.co/Helsinki-NLP/opus-mt-en-vi/blob/main/config.json),
[model card OPUS-MT](https://huggingface.co/Helsinki-NLP/opus-mt-en-vi),
[model card mT5-small](https://huggingface.co/google/mt5-small), và
[model card T5-small](https://huggingface.co/google-t5/t5-small).

Teacher EnViT5 dùng T5 với `d_model=768`, 12 encoder layer và 12 decoder layer;
Student Marian dùng `d_model=512`, 6 encoder layer và 6 decoder layer. Đây là
khác biệt capacity có thật, không phải phép nén trực tiếp Teacher. Nguồn:
[config EnViT5](https://huggingface.co/VietAI/envit5-translation/blob/main/config.json).

`Helsinki-NLP/opus-mt-en-vi` từng xuất hiện ở Phase 02 với vai trò candidate
pretrained và không được chọn làm Core. Sang Phase 03, checkpoint này được chọn
lại với vai trò **khởi tạo Student**, không phải continuation của candidate
Phase 02 và không phải Teacher.

Kết luận chính xác là: Student hiện tại phù hợp trong shortlist đã xét và đã
chứng minh khả năng train trên máy dự án. Quyết định freeze được người phụ trách
ghi sau khi đối chiếu validation, review và provenance; nó không có nghĩa
Student bằng Teacher hoặc là kiến trúc tốt nhất.

## Kết quả run hiện tại và giới hạn

Trên cohort có thể so sánh với Teacher, Student EN→VI của run
`phase03-seqkd-en-to-vi-v2` thấp hơn Teacher: IT
Validation 15.252 câu giảm 4,63 chrF++ và 6,55 SacreBLEU; General Validation
997 câu giảm 7,92 chrF++ và 8,81 SacreBLEU. Review sealed 50 câu ghi 28
`translation_error=yes` và 19 `technical_error=yes`. Dù vậy, quyết định đã
freeze checkpoint này làm Main-Core Phase 4. Phase 4 phải dùng đúng checkpoint
và hash đã bàn giao, rồi đo thay đổi sau convert/quantization; không được dùng
quyết định freeze để khẳng định Student ngang Teacher hoặc sẵn sàng chạy Android.

Một câu IT Validation dài 686 token vượt capacity 512 của Student. Câu này
không bị sửa; cả Student và Teacher đều được tính lại trên 15.252 câu còn lại,
nên so sánh cùng cohort nhưng không đại diện đủ 15.253 câu IT Validation của
Phase 02. Chi tiết evidence nằm trong [README Phase 03](README.md).
