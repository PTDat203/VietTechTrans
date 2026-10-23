# Hợp đồng gói MT v2 (Main-Core Phase 03 trên Android)

Thay cho bản nháp v1 (viết cho EnViT5 + LoRA, Core commit `124721f`). Phase 03 đã freeze
**Student MarianMT** làm Main-Core cho cả hai chiều (`Core/evidence/phase03/main_core_decision.json`,
commit `50fb865`), nên v1 không còn dùng: tokenizer, tiền tố, cách giải mã đều khác.
Code app: `app/src/main/kotlin/vn/viettechtrans/app/mt/`. Công cụ: `tools/mt/`.

## 1. Thay đổi so với v1

| Hạng mục | v1 (EnViT5) | v2 (Main-Core Marian) |
|---|---|---|
| Model | T5 + LoRA, 2×317 MB | MarianMT 71,7 triệu tham số, 4-bit khối 16 + lớp đầu ra 4+4 bit, 82,5 MB mỗi chiều (8-bit 91 MB đến 2026-10-08) |
| Tiền tố đầu vào | `en:` / `vi:` | EN→VI: `>>vie<<` dính liền câu; VI→EN: không có |
| Tiền tố đầu ra | Cắt `vi:` / `en:` | Không có (bỏ `OutputPrefixStripper`) |
| Tokenizer | 1 SentencePiece BPE dùng chung | SentencePiece unigram `source.spm` + `vocab.json` riêng mỗi chiều |
| Giải mã | Greedy | Beam search theo `generation_config`: EN→VI 4 beam, VI→EN 6 beam |
| File ONNX | encoder + decoder (Optimum) | 1 file/chiều: `If(is_encoder)` encoder \| 1 bước decoder |

## 2. Nguồn trong Core

| Quy định | Nguồn |
|---|---|
| Checkpoint, `sha256_tree`, run, metric Phase 03 | `evidence/phase03/main_core_decision.json` |
| Tiền tố `>>vie<<` (EN→VI), rỗng (VI→EN) | `decision.decisions.*.student.input_prefix` |
| Tiền tố ghép liền: `prefix + source` | `tools/run_phase03_student_evaluation.py` |
| Giải mã: `model.generate(**encoded)` không tham số → dùng `generation_config.json` của checkpoint | cùng file trên |
| `num_beams` 4 / 6, `max_length` 512, cấm pad, `renormalize_logits`, `forced_eos` 0 | `models/phase03/*/final-epoch-1/generation_config.json` |
| Giới hạn đầu vào 512 token (Phase 04 tự định cách xử lý câu dài hơn) | `configs/phase03_input_length_policy.json` (`not_a_runtime_policy`) |
| Chuẩn hoá đầu vào `phase01_nfc_whitespace_v1` | `tools/build_phase01.py` `norm()` (giữ từ v1, test parity sẵn có) |

## 3. Cấu trúc thư mục

```text
app/src/main/assets/mt/          sinh bằng tools/mt/package_mt.py, không commit
  manifest.json
  LICENSE_NOTES.md
  en_to_vi/model.onnx            82,5 MB (4-bit khối 16, lớp đầu ra 4+4 bit)
  en_to_vi/source.spm            tokenizer nguồn (copy nguyên từ checkpoint)
  en_to_vi/vocab.txt             dòng i = token id i (từ vocab.json)
  en_to_vi/golden.jsonl          20 câu viết tay + id đầu ra mong đợi
  vi_to_en/...                   như trên
.cache/mt/fixtures/<chiều>_tokenizer.jsonl   fixture tokenizer cho test JVM (không commit)
```

## 4. `manifest.json`

| Trường | Giá trị | Ghi chú |
|---|---|---|
| `schema_version` | `"2.0"` | App nhận major 2 |
| `package_id`, `package_version` | `vtt-main-core-phase03-q4r`, `1.0.0` | Ghi vào evidence (`core-mt:<package_id>`) |
| `model_role`, `architecture` | `main_core`, `MarianMT` | |
| `runtime` | `onnxruntime` 1.30.0, `cpu`, ABI `arm64-v8a`, `x86_64` | Cùng phiên bản với Python lúc dựng và đo |
| `quantization` | `matmul_nbits`, 4 bit, block 16, `clip_search`, `accuracy_level` 4, `lm_head` 4+4 bit | Xem §6 |
| `input_normalization` | `phase01_nfc_whitespace_v1` | |
| `directions.<chiều>.model` | `<chiều>/model.onnx` | |
| `directions.<chiều>.tokenizer` | `type`, `source_spm`, `vocab`, `vocab_size`, `eos_id` 0, `unk_id` 1, `pad_id` | |
| `directions.<chiều>.input_prefix` | `>>vie<<` / `""` | App kiểm đúng giá trị |
| `directions.<chiều>.generation` | `beam_search`, `num_beams`, `max_length`, `length_penalty`, `early_stopping` false, `decoder_start_token_id` (= pad), `eos_token_id`, `forced_eos_token_id`, `bad_words_ids` [[pad]], `renormalize_logits` true | App từ chối gói nếu khác những gì decoder hỗ trợ |
| `directions.<chiều>.input_length_policy` | `max_input_tokens` 512, `on_exceed` `split_sentences` | §8 |
| `directions.<chiều>.source_checkpoint` | `run_id`, `path`, `sha256_tree`, `student` | Kiểm lại bằng thuật toán của Core trước khi đóng gói |
| `directions.<chiều>.quality` | `phase03_checkpoint` (từ decision), `exported` (từ `marian_ref.py eval`) | |
| `files[]` | `path`, `bytes`, `sha256` | App đọc đúng `bytes` khi nạp |
| `core_refs[]` | decision + SHA-256 + nguồn (working tree hoặc `git <commit>:path`) | |

## 5. Graph ONNX (`tools/mt/build_marian_onnx.py`)

Viết lại kiến trúc Marian bằng toán tử ONNX (opset 17 + `com.microsoft`), lấy trọng số từ checkpoint.
Một node `If(is_encoder)`; hai nhánh dùng chung initializer ngoài, nên embedding (dùng cho đầu vào
encoder, đầu vào decoder và LM head) chỉ lưu một lần.

| Vào | Kiểu, shape | Encoder | Decoder |
|---|---|---|---|
| `input_ids` | int64 `[B, T]` | `[1, S]` câu nguồn | `[N, 1]` token cuối của N beam |
| `is_encoder` | bool vô hướng | true | false |
| `past_k`, `past_v` | f32 `[6, P, 8, T, 64]` | rỗng (T=0) | KV-cache bước trước (rỗng ở bước đầu) |
| `beam_idx` | int64 `[N]` | `[0]` | beam cha của từng beam (sắp lại cache trong graph) |
| `cross_k_t`, `cross_v` | f32 `[6,1,8,64,S]`, `[6,1,8,S,64]` | rỗng | đầu ra của encoder |

| Ra | Encoder | Decoder |
|---|---|---|
| `out_k`, `out_v` | `cross_k_t`, `cross_v` | KV-cache mới `[6, N, 8, T+1, 64]` |
| `logprobs` | không dùng | `[N, V]`: log-softmax sau khi đặt logit pad = −∞ |

## 6. Lượng tử hoá (đã đo; từ 2026-10-08 chọn 4-bit khối 16 + lớp đầu ra 4+4 bit, DR-07 §13–14)

| Biến thể | Kích thước/chiều | Khớp HF (30 câu General Val.) | 1 bước decoder 4 beam (i5-12500H, 2 luồng) |
|---|---|---|---|
| fp32 | 288 MB | 30/30, sai lệch log-prob 2·10⁻⁵ | 22 ms |
| int8 động, activation per-tensor | 74 MB | 7/30, có lặp từ → loại | — |
| 4-bit block 32 | 54 MB | 5/30 → loại | 25 ms |
| 4-bit layer + 8-bit embedding | 69 MB | 6/30 → loại | — |
| 8-bit block 32, activation fp32 (`accuracy_level` 0) | 91 MB | 28/30 | 250 ms (ORT 1.30 chưa có kernel nhanh) → loại |
| 8-bit block 32, `accuracy_level` 4 (dùng đến 2026-10-07) | 91 MB | EN→VI 20/30, VI→EN 23/30 | 10 ms; **trên ARM không có dotprod/i8mm: như dòng trên** |
| 4-bit block 16, clip, `accuracy_level` 4 | 71 MB | chrF++ −0,06…−0,34 so với 8-bit (DR-07 §13) | 21 ms (6 beam); 23 ms khi không có kernel int8 |
| **4-bit block 16, clip; lớp đầu ra 4-bit + 4-bit phần dư (`q4r`)** | **82,5 MB** | chrF++ −0,04…−0,14 so với 8-bit (DR-07 §14) | AVD 10,5 / 16,7 ms (EN→VI / VI→EN); khoảng +25 % so với dòng trên khi không có kernel int8 |

Chất lượng bản chọn trên toàn bộ tập validation của Phase 03: xem `quality.exported` trong
`manifest.json` và `evidence/phase04/mt_export_q4r/` (4-bit thường: `mt_export_q4b16c/`; 8-bit cũ: `mt_export_q8/`).

Vì sao không giữ 8-bit: ONNX Runtime 1.30 chỉ có kernel 8-bit nhanh trên ARM khi CPU có dotprod/i8mm; thiếu thì
giải nén cả ma trận trọng số mỗi bước (15–30 s/câu trên máy yếu). 4-bit có kernel cho mọi ARM64.

## 7. Tokenizer và giải mã

- **Mã hoá** (port `MarianTokenizer` của transformers 4.57): tách các token đặc biệt (`</s>`, `<unk>`,
  `<pad>`) khỏi text; bỏ mã `>>…<<` ở đầu đoạn thành một token; SentencePiece unigram với chuẩn hoá
  `nmt_nfkc` (charsmap biên dịch sẵn, Darts double-array), bỏ khoảng trắng thừa, thêm `▁` đầu; gộp các
  mảnh `<unk>` liền nhau; ánh xạ mảnh → `vocab.txt` (không có → `<unk>`); thêm `</s>`.
- **Giải mã chuỗi**: bỏ id đặc biệt, nối mảnh, `▁` → dấu cách, cắt khoảng trắng hai đầu.
- **Beam search**: port từng bước `GenerationMixin._beam_search` (top 2×beam ứng viên, giả thuyết xong
  chỉ lấy trong top beam, chia điểm cho độ dài mũ `length_penalty`, heuristic dừng sớm khi
  `early_stopping=false`, ép EOS ở `max_length − 1`), giữ số học float32 và hằng −10⁹ như HF.
  Bản Python `tools/mt/marian_ref.py` khớp HF 30/30 câu trên graph fp32.

- **Chính sách của app trên `generation`** (thêm 2026-10-07 sau khi thử trên điện thoại; manifest giữ
  nguyên `generation_config` của Core):
  - `max_length` thực tế = min(512, 2 × số id nguồn + 20) (`GenerationParams.maxLengthFor`,
    `marian_ref.app_max_length`). Lý do: với `early_stopping=false`, khi beam tốt nhất xong sớm, các beam
    còn lại vẫn chạy tới 512 (VI→EN "chào bạn" → "Hello": 511 bước, 12,7 s trên điện thoại; PyTorch
    Phase 03 cũng 511 bước). Trên Validation chỉ đầu ra lặp vô nghĩa dài hơn ngưỡng này.
  - Khi chạm ngưỡng: nếu đã có giả thuyết hoàn chỉnh thì trả giả thuyết hoàn chỉnh tốt nhất; chỉ khi chưa
    có mới ép EOS như HF (`BeamSearch.preferFinished`, `beam_search(prefer_finished=True)`). Ép EOS kiểu HF
    ở ngưỡng làm 4 câu Validation bình thường tệ đi (beam lặp dài thắng nhờ điểm trung bình). Với quy tắc
    này, mọi đầu ra ngắn hơn ngưỡng − 1 token giống hệt khi không giới hạn (tìm kiếm giống nhau tới
    ngưỡng, và giả thuyết tốt nhất đã nằm trong tập hoàn chỉnh).
  - Gộp lặp sau khi giải mã: cụm 1–3 từ lặp ≥ 4 lần liên tiếp giữ 1 lần (`OutputCleanup.collapseRepeats`,
    `marian_ref.collapse_repeats`, parity bằng `mt_collapse_repeats.json`).
  - Chính sách rule dịch text v1 (thêm 2026-10-23, `docs/contracts/mt_app_rule_policy_v1.md`, DR-07 §16), nằm ngoài
    gói nên manifest không đổi:
    - Model chỉ nhận chữ sau R01 (`phase01_nfc_whitespace_v1`); không rule nào đổi chữ hoa/thường, thay thuật ngữ hay
      sửa bản dịch. Thử nghiệm placeholder `TermProtector` (DR-07 §15) đã gỡ khỏi app vì trái R01/R08/§9.
    - Trước model (`mt/rules/TextRules`): R02 tối đa 500 ký tự hiển thị; R03 literal kỹ thuật và R04 từ kỹ thuật nhiều
      nghĩa đứng một mình thì không gọi model; R05–R07 chỉ cảnh báo; R06 hỏi trước khi gửi.
    - Trong engine: R09 tối đa 500 `source_ids` mỗi lần gọi (`TextRules.MAX_SOURCE_IDS`, thấp hơn 512 của manifest),
      không tách trong URL/email/code; R10 như trên; R11 gộp lặp và báo cờ `repeat_cleanup_applied`
      (`TranslationResult.repeatCleanupApplied`, kèm `rawText` và `sourceIds` cho evidence).
    - Sau model: R12 từ chối output rỗng hoặc làm mất URL/email/code/mã HTTP/đường dẫn tuyệt đối; R08 gắn "Cần kiểm tra
      thuật ngữ" khi mất thuật ngữ của lexicon (`assets/text/mt_rule_lexicon_v1.json`). Đổi gói MT thì chạy lại
      `TextRulesCalibrationTest` và bộ case `mt_rule_cases_v1` (ba tầng, §8 của policy).
  - Kết quả đo: `evidence/phase04/mt_length_cap/`.

## 8. Câu dài hơn 500 `source_ids` (R09)

Không cắt câu. Manifest khai báo 512 vị trí; app chỉ đưa tối đa 500 `source_ids` mỗi lần gọi (chính sách rule dịch
text v1, R09; `TextRules.MAX_SOURCE_IDS`). Nếu cả đoạn vượt mức đó: tách theo câu (sau `. ! ? … ; :` hoặc xuống
dòng), gom nhiều câu vào một đoạn khi còn vừa; một câu vẫn quá dài thì tách theo dấu cách; không tách bên trong
URL, email, code hay literal kỹ thuật; một chuỗi không có chỗ tách mà vẫn quá dài thì cắt theo ký tự (chỉ là phương
án cuối). Dịch từng đoạn rồi nối bằng một dấu cách, ghi `segment_count` (`MarianEngine.segment`).

## 9. Kiểm chứng

| Kiểm tra | Công cụ |
|---|---|
| Hash checkpoint khớp decision | `package_mt.py` (thuật toán `sha256_tree` của Core) |
| Graph fp32 = Hugging Face; bản lượng tử hoá so với HF | `marian_ref.py verify` |
| chrF++/BLEU của bản đóng gói trên IT Validation + General Validation, so với Student Phase 03 (`eval --length-cap` = chính sách của app) | `marian_ref.py eval` + `summarize` (dùng `core_mt.metrics`) |
| Tokenizer Kotlin = HF trên 4 873 câu/chiều | `MarianTokenizerParityTest` |
| Kotlin + ONNX Runtime desktop = golden từng token | `MarianGoldenTest` |
| Logic beam search | `BeamSearchTest` |
| Gói hợp lệ, decoder hỗ trợ đúng cấu hình | `MtPackageManifestTest`, `MtPackageValidator` lúc mở app |

## 10. Dựng lại gói

```bat
.venv\Scripts\python.exe -I -m pip install onnx==1.23.2 onnxruntime==1.30.0
cd AppProduct
..\.venv\Scripts\python.exe -I tools\mt\package_mt.py --variant q4r --quality evidence\phase04\mt_export_q4r
```

Cần `Core/models/phase03/*/final-epoch-1` (không có trong git). Decision đọc từ working tree, nếu
không có thì lấy bằng `git show main:Core/evidence/phase03/main_core_decision.json`.
