# Phase 03 — distillation tạo Student MT

## Trạng thái

Student v1 VI→EN là run `phase03-seqkd-vi-to-en-v3`. Người phụ trách đã freeze
checkpoint của run này làm Main-Core VI→EN cho Phase 4. Decision artifact là
`evidence/phase03/main_core_decision.json`; nó khóa identity, hash checkpoint,
input Teacher-target, evaluation và review đã dùng để ra quyết định.

Student EN→VI là run `phase03-seqkd-en-to-vi-v2`. Người phụ trách đã freeze
checkpoint của run này làm Main-Core EN→VI cho Phase 4. Phase 3 đã hoàn tất
việc chọn Main-Core cho cả hai chiều. Các artifact lịch sử vẫn ở các đường dẫn
`.running` đã được ghi trong decision; chúng không bị di chuyển hoặc sửa lại.

Người thực hiện Phase 4 lấy hai checkpoint, hash và baseline trực tiếp từ
decision này theo [hướng dẫn Phase 4](../phase_04/README.md). Repository hiện
tại không tạo artifact hay chạy thí nghiệm Phase 4.

## Vấn đề và câu hỏi nghiên cứu

Core EnViT5 được chọn ở Phase 02 theo chất lượng validation, không theo tiêu chí chạy thiết bị yếu. Phase 03 cần trả lời: với từng chiều EN→VI và VI→EN, Student học từ Teacher bằng phương pháp KD được chọn có giữ đủ chất lượng dịch và thuật ngữ kỹ thuật để được freeze thành Main-Core cho Phase 4 không? Khả năng chạy thiết bị chỉ được đánh giá ở Phase 4; Phase 3 chỉ ghi metadata model làm đầu vào cho bước đó.

Các câu hỏi phụ và cách trả lời:

| Câu hỏi | Evidence dùng để trả lời |
|---|---|
| Student giữ được bao nhiêu chất lượng so với Teacher? | chrF++ và SacreBLEU, tách riêng theo chiều và theo hai validation set. |
| Distillation có làm xấu ngoài miền không? | General Validation là guardrail; nêu riêng VI→EN vì Core Phase 02 đã giảm ở tập này sau adaptation. |
| Student có lỗi rõ ràng với command, path, code hay thuật ngữ không? | Review student trên đúng 50 row ID IT Validation đã niêm phong tại Phase 02; chỉ ghi `translation_error`, `technical_error`, ghi chú. |
| Student có thể trở thành Main-Core để sang Phase 04 không? | Người thực hiện freeze hoặc reject từng chiều sau khi xem provenance, validation và review. Parameter count/checkpoint size chỉ mô tả artifact, không chứng minh chạy được trên thiết bị. |

Không giả định trước distillation sẽ tốt hơn, Student là nhỏ nhất, hoặc Student chạy được Android. Phase 3 không trả lời latency, RAM, quantization hay Android.

## Đã khảo sát gì và vì sao thiết kế như vậy?

| Dữ kiện đã có | Hệ quả thiết kế |
|---|---|
| Phase 01 khóa IT Train 121.547 dòng, IT Validation 15.253 dòng, General Validation 997 dòng; các split không chồng lấp. | Chỉ các dòng IT Train hợp lệ theo policy độ dài sinh pseudo-target và train; validation chỉ đánh giá. |
| Phase 02 freeze Core EnViT5 + LoRA hai chiều, có checkpoint hash và runtime control. | Teacher không được đổi model, checkpoint hay decoding. |
| Student có checkpoint khởi tạo và revision cố định theo từng chiều. | Thí nghiệm tái lập được; không đưa kiến trúc mới vào Phase 3. |
| Chiều EN→VI có language control bắt buộc. | Student giữ language control đã được lưu trong cấu hình kỹ thuật. |
| Teacher và Student khác kiến trúc/tokenizer. | Sequence-level KD đã được chọn vì không cần vocabulary mapping hay layer matching. |
| Phase 02 thiếu source snapshot khớp hoàn toàn code/config local. | Phase 03 phải snapshot trước run và giữ evidence bất biến. |

Đồ án dùng Teacher, Student và sequence-level distillation có sẵn; không tuyên bố phương pháp mới. Lý do chọn kiến trúc Student EN→VI và nguồn kỹ thuật được ghi tại [kd_method_selection.md](kd_method_selection.md). Phần thiết kế là protocol dữ liệu, gate, provenance và đánh giá trong phạm vi IT EN↔VI.

Phương pháp đã chọn và giới hạn: [kd_method_selection.md](kd_method_selection.md). Định danh checkpoint được lưu trong cấu hình tái lập kỹ thuật.
Các thông số cần duyệt và đề xuất trước experiment: [experiment_parameter_research.md](experiment_parameter_research.md).

## Quy trình hoạt động

```text
Phase 02 Core đã freeze
  → Gate thiết kế chỉ đọc
  → Duyệt protocol, evidence contract và workflow
  → Duyệt experiment record riêng từng chiều
  → Snapshot .running trước khi chạy
  → Teacher sinh một pseudo-target cho mỗi source IT Train hợp lệ theo chiều
  → Integrity gate quét toàn bộ: ID, source hash, target rỗng, data boundary, target length
  → Có target vượt capacity: giữ target staged, chặn train và chờ protocol mới
  → Train Student từ revision đã khóa
  → Đánh giá IT Validation và General Validation
  → Review đúng 50 row ID niêm phong
  → Người thực hiện freeze hoặc reject Student từng chiều
  → Chỉ Student được freeze mới trở thành Main-Core
  → Phase 04 chỉ nhận Main-Core đã freeze
```

### Gate và hai lần duyệt

Gate đầu tiên chỉ đọc manifest, Core decision, checkpoint hash, Student revision và protocol; không tải model, không tạo prediction/target/metric. Sequence-level KD đã được duyệt. Experiment record là gate còn lại và phải khóa run ID, seed, optimizer, learning rate, batch, gradient accumulation, epoch, precision, thiết bị và checkpoint cuối.

### Sequence-level KD, dữ liệu và integrity

Tạo `evidence/phase03/<run-id>.running/` trước execution, gồm source/config snapshot, lệnh, dependency, runtime và checksum input. Teacher chỉ nhận các dòng IT Train hợp lệ theo chiều và sinh đúng một text target cho mỗi source đó; Student học từ source–Teacher target. VI→EN v2 kế thừa hai source vượt capacity 512 token và loại thêm đúng hai pair không có nội dung dịch `°↔°`, `æ↔æ`; IT Train gốc không thay đổi. Sau khi toàn bộ target đã được lưu, integrity gate token hóa 100% target bằng tokenizer Student và ghi toàn bộ row vượt capacity. Nếu có row vượt, Student training bị chặn nhưng target staged vẫn giữ nguyên; không chạy Teacher lại, không truncate, không tự loại target. Chỉ một protocol mới được duyệt mới quyết định bước tiếp theo. Validation/test không được chảy vào phần học. Target không được sửa, hậu biên tập, lọc chất lượng hoặc sinh lại chọn lọc. Sai integrity rule hoặc data boundary thì run ở lại `.running`.

### Amendment VI→EN v3 sau integrity gate

`phase03-seqkd-vi-to-en-v2.running` giữ nguyên: đủ 121.543 target, không lỗi cấu trúc hay target rỗng, nhưng có bốn target vượt 512 token theo tokenizer Student. Inspection ghi nhận các target này bị lặp/degenerate, nên không tăng limit hoặc truncate. `phase03-seqkd-vi-to-en-v3` chỉ tái sử dụng 121.539 target còn lại. Policy v3 khóa toàn bộ tám ID bị loại: bốn ID source đã bị loại trong v2 và đúng bốn ID trong `over_limit_rows` của integrity report v2, cùng checksum của Train, parent target và parent report. Runner reuse kiểm tra các checksum, tạo lineage và scan lại toàn bộ target; không load Teacher/GPU và không sửa v2.

### Student v1 VI→EN đã freeze cho Phase 4

| Hạng mục | Giá trị đã khóa |
|---|---|
| Run / checkpoint | `phase03-seqkd-vi-to-en-v3` / `models/phase03/phase03-seqkd-vi-to-en-v3/final-epoch-1` |
| Checkpoint hash | `fbcba2c1dbaa377073ab69893fabb51127c9de39609d1815f8fb97202442cb53` |
| Student | MarianMT, `Helsinki-NLP/opus-mt-vi-en` revision `c8d2853e77f5fae31124d993e0b35176b1c8914e` |
| Dữ liệu học | 121.539 pseudo-target VI→EN đã integrity pass; target hash `0d1251e8f0dc2ea46522b65130bc1b3aeca5e303dd5a5f3cc755714b1c4b5069` |
| Training | seed/data seed 42; AdamW fused; learning rate `5e-5`; linear scheduler; warmup/weight decay `0`; max grad norm `1`; 1 epoch; batch vật lý 4, accumulation 4, effective batch 16; fp16 |
| Thiết bị / thời gian | `cuda:0` NVIDIA GeForce RTX 3050 Ti Laptop GPU; 1.895,45 giây (31 phút 35 giây) |
| Train loss | `1,0225820768596594` |

| Cohort | Teacher chrF++ / BLEU | Student v1 chrF++ / BLEU | Số câu |
|---|---:|---:|---:|
| IT Validation comparable | 65,34 / 41,23 | 61,30 / 36,30 | 15.252 |
| General Validation | 58,45 / 29,84 | 46,20 / 17,52 | 997 |

IT Validation dùng cohort 15.252/15.253 câu theo amendment v3: một source dài
935 token vượt giới hạn input 512 của Student, không bị cắt hoặc sửa. Teacher
metric được tính lại trên đúng cohort đã so sánh, không chạy Teacher inference
lại. Review 50 row ID niêm phong đã hoàn tất: 24 dòng `translation_error=yes`
và 19 dòng `technical_error=yes`. Các con số được ghi để truy xuất evidence,
không phải ngưỡng tự động cho quyết định freeze.

Nếu mở rộng đồ án sau này, có thể đánh giá một cấu hình Student khác theo một
protocol và decision mới. Việc đó không làm thay đổi checkpoint Student v1 đã
freeze cho Phase 4.

### Train, đánh giá và quyết định

Student khởi tạo từ checkpoint và revision đã khóa trong cấu hình kỹ thuật. Training chỉ dùng IT Train theo phương pháp KD đã duyệt, không evaluate trong train và chỉ lưu checkpoint cuối khai báo trước. Đánh giá riêng trên IT Validation và General Validation bằng chrF++ (`beta=2`, `word_order=2`) và SacreBLEU (`tokenize=none`, không lowercase). Record cũng ghi parameter count và checkpoint size của Teacher/Student để xác nhận Student nhỏ hơn, nên là input hợp lý cho Phase 4; đây không phải benchmark thiết bị. Người thực hiện đối chiếu Student với Teacher để mô tả mức chất lượng giữ lại, rồi ghi freeze/reject; không weighted score hoặc ngưỡng tự đặt.

Không gửi validation/test vào teacher, không trộn reference gốc vào nhãn train, không rewrite/filter target, không benchmark/export/quantize hay tích hợp STT/TTS.

### Run EN→VI đã thực hiện: v1, amendment và v2

`phase03-seqkd-en-to-vi-v1.running` giữ nguyên vì integrity scan của nó thất bại do đúng ba target rỗng. Amendment `v2` không chạy Teacher lại: sau khi protocol, policy và record `v2` được duyệt, `tools/reuse_phase03_teacher_targets.py` kiểm tra checksum Train, target/report `v1`, và ba ID đã khóa; nó chỉ sao chép 121.544 row còn lại sang run `v2` mới rồi scan integrity lại. `reuse_lineage.json` ghi checksum và nguồn của artifact cha. Không được sửa trực tiếp file trong `v1.running`.

Run `v1` đã tạo đủ 121.547 target nhưng integrity fail vì ba target rỗng. V2 dùng đúng policy này, tái sử dụng 121.544 target còn lại và integrity pass. Student đã train một epoch; training record ở `evidence/phase03/phase03-seqkd-en-to-vi-v2.running/training/en_to_vi/`.

### Amendment độ dài validation EN→VI

Student Marian có capacity 512 token. `configs/phase03_evaluation_input_length_amendment_en_to_vi_v1.json` khóa một IT Validation source dài 686 token là unsupported-length case; source/reference không bị sửa. Evaluator đã chấm Student trên 15.252/15.253 row IT Validation, tái tính metric Teacher từ prediction Phase 02 trên đúng cohort đó và ghi rõ scope. General Validation giữ đủ 997 row. Không chạy Teacher lại.

### Student EN→VI đã freeze cho Phase 4

| Cohort | Teacher chrF++ / BLEU | Student chrF++ / BLEU | Nhận định |
|---|---:|---:|---|
| IT Validation, 15.252 câu | 71,18 / 57,76 | 66,55 / 51,21 | Student thấp hơn Teacher 4,63 chrF++ và 6,55 BLEU. |
| General Validation, 997 câu | 60,36 / 39,24 | 52,44 / 30,43 | Student thấp hơn Teacher 7,92 chrF++ và 8,81 BLEU. |

Review sealed 50 câu của Student có 28 dòng `translation_error=yes` và 19 dòng `technical_error=yes`. Người phụ trách đã freeze checkpoint này cho Phase 4; các con số không phải ngưỡng tự động. Chưa mở IT Test hoặc General Test Phase 3.

## Giới hạn tái lập của run EN→VI v2

V2 có snapshot config, dependency, command và source của bước reuse target. Tuy nhiên snapshot chưa chứa source của runner train/evaluation đã tạo checkpoint và metric hiện có. Không thể bổ sung snapshot hồi tố để biến chúng thành source snapshot đúng thời điểm chạy. Quyết định freeze ghi rõ giới hạn này; xem [giới hạn provenance](../provenance_and_reproducibility.md).

## Artifact sau khi được phép chạy

| Artifact | Mục đích |
|---|---|
| `teacher_targets/<direction>.jsonl` | Một pseudo-target có provenance cho mỗi source IT Train hợp lệ theo chiều. |
| `teacher_targets/<direction>.integrity.json` | Kết quả integrity gate. |
| `training/<direction>/training_run.json` | Cấu hình train, checkpoint hash, runtime. |
| `evaluation/.../` | Prediction, metric, provenance từng validation set. |
| `reviews/phase03_<run_id>_<direction>_review.csv` | Review thủ công 50 row ID cố định. |
| `main_core_decision.json` | Freeze/reject; Student được chấp nhận trở thành Main-Core. |

Xem [manual](../../manual/phase_03.md) và [thiết kế implementation](implementation.md).
Notebook chỉ đọc evidence để trình bày Main-Core: [03_01_main_core_evidence.ipynb](../../notebooks/phase_03/03_01_main_core_evidence.ipynb).
