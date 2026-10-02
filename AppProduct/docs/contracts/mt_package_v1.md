# Hợp đồng gói MT v1 (Phase 04)

**Bản nháp v1 — chốt với anh ở Phase 04.** Sửa hợp đồng cần cả anh và Đạt đồng
ý; thay đổi không tương thích thì tăng major của `schema_version`. Dựa trên Core
ở commit `124721f`.

## 1. Mục đích

Phase 04 giao một gói MT (Core MT hoặc Student) chạy offline trên Android. App
chỉ cần gói này và code trong package `vn.viettechtrans.app.mt` để dịch; phần
còn lại của app không đổi. Hợp đồng giữ đúng cách Core đã chạy ở Phase 02 để
bản dịch trên máy so được với checkpoint gốc.

## 2. Nguồn trong Core

| Quy định | Nguồn trong `Core/` |
|---|---|
| Mã chiều `en_to_vi`, `vi_to_en` | `src/core_mt/contracts.py` (`Direction`) |
| Tiền tố `en:` / `vi:`, tiền tố đầu ra `vi:` / `en:`, `max_length` 512 | `configs/phase02_models.json` |
| Tiền tố ghép liền với text, không dấu cách | `src/core_mt/baseline.py` dòng 120: `f"{self.spec.input_prefix}{segment}"` |
| Bỏ đúng tiền tố đầu ra và một khoảng trắng | `baseline.py` dòng 141–148; `tests/test_phase02_pretrained_baseline.py` dòng 41–45 |
| Chỉ tách câu khi vượt giới hạn đã khai báo | `baseline.py` `_split_source` (dòng 179) |
| Greedy, `num_beams` 1, `decoder_start_token_id` 0, `eos_token_id` 1, `pad_token_id` 0 | GenerationConfig của checkpoint, ghi trong `evidence/phase02/final/*/*/run.json` |
| Checkpoint đã freeze | `evidence/phase02/core_mt_decision.json` (SHA-256 blob LF `1791078e24144c6e244a0121768cbc424832967486b29d2a929b2eab40f403d2`) |
| `sha256_tree` của checkpoint | `evidence/phase02/envit5/*/adapted/*/training_run.json` |
| Chất lượng trên IT Test | `evidence/phase02/final/*/it_test/metrics.json` |
| Chuẩn hoá đầu vào | `tools/build_phase01.py`: `CONTROL` (dòng 25), `norm()` (dòng 46–47) |
| Tokenizer | `models/envit5/840bc88104d5a4277af740eaedb024df8c3093e7/spiece.model` (SentencePiece BPE, 50 000 piece, `nmt_nfkc`) |

## 3. Cấu trúc thư mục

```text
app/src/main/assets/mt/        cài bằng tools/fetch_artifacts.py từ mục lock, không commit
  manifest.json
  tokenizer/spiece.model
  en_to_vi/encoder_model.onnx
  en_to_vi/decoder_model_merged.onnx
  vi_to_en/encoder_model.onnx
  vi_to_en/decoder_model_merged.onnx
  golden/en_to_vi.jsonl
  golden/vi_to_en.jsonl
  LICENSE_NOTES.md
```

Tên file ONNX ở trên là ví dụ (tên mặc định khi export bằng Optimum). App chỉ
đọc đường dẫn từ `manifest.json`, không hardcode tên file.

## 4. Schema `manifest.json`

Quy ước đường dẫn: `tokenizer.file`, `directions.*.files.*`, `golden_file`,
`license_notes` và `files[].path` là POSIX, tương đối so với `assets/mt/`, không
chứa `..`. `source_checkpoint.path` và `core_refs[].path` là POSIX, tương đối so
với thư mục `Core/` (quy ước của Core).

### 4.1 Trường cấp gói

| Trường | Kiểu / giá trị | Ý nghĩa |
|---|---|---|
| `schema_version` | chuỗi `"1.0"` | `major.minor`; app chấp nhận major 1 |
| `package_id` | chuỗi, dạng `[A-Za-z0-9][A-Za-z0-9._-]*` (như `validate_identifier` của Core) | ID duy nhất của gói; ghi vào evidence |
| `package_version` | chuỗi semver, ví dụ `"1.0.0"` | Phiên bản gói |
| `created_at_utc` | chuỗi ISO-8601 có `+00:00` | Thời điểm tạo gói |
| `model_role` | `"core_mt"` \| `"student_mt"` | Core MT của Phase 02 hay Student của Phase 03 |
| `base_model.model_id` | chuỗi | `"VietAI/envit5-translation"` |
| `base_model.revision` | chuỗi | Commit Hugging Face, `"840bc88104d5a4277af740eaedb024df8c3093e7"` |
| `runtime.engine` | `"onnxruntime"` | Runtime chạy gói |
| `runtime.engine_version` | chuỗi | Phiên bản ORT dùng khi export, sinh golden và trong app (kế hoạch: 1.30.0) |
| `runtime.execution_provider` | `"cpu"` | |
| `runtime.abis` | mảng ⊆ `["arm64-v8a", "x86_64"]` | ABI đã kiểm |
| `quantization.method` | chuỗi, ví dụ `"none"`, `"dynamic_int8"` | Cách lượng tử hoá |
| `quantization.tool` | chuỗi hoặc `null` | Công cụ và phiên bản |
| `quantization.per_channel` | bool hoặc `null` | |
| `tokenizer.type` | `"sentencepiece"` | |
| `tokenizer.file` | chuỗi | `"tokenizer/spiece.model"` |
| `tokenizer.sp_piece_count` | `50000` | Số piece trong `spiece.model` |
| `tokenizer.vocab_size` | `50048` | Vocab của model = 50 000 piece + 48 `<extra_id_*>` (id 50000–50047) |
| `tokenizer.add_bos` | `false` | `spiece.model` không có BOS (`bos_id` −1) |
| `tokenizer.add_eos` | `true` | Thêm `eos_id` cuối input (như `</s>` của Hugging Face) |
| `tokenizer.pad_id`, `eos_id`, `unk_id` | `0`, `1`, `2` | |
| `tokenizer.decode_skip_ids` | `[0, 1, 2]` | Bỏ trước khi decode |
| `tokenizer.decode_skip_id_ranges` | `[[50000, 50047]]` | Khoảng id bỏ trước khi decode (gồm hai đầu); SentencePiece không biết các id này |
| `input_normalization` | `"phase01_nfc_whitespace_v1"` | Chuẩn hoá mà app chạy trước mọi bộ dịch (mục 5.1) |
| `directions` | object, key `en_to_vi` và/hoặc `vi_to_en` | Ít nhất một chiều (mục 4.2) |
| `files` | mảng `{path, bytes, sha256}` | Mọi file trong gói trừ `manifest.json`; `bytes` > 0; `sha256` 64 ký tự hex thường |
| `core_refs` | mảng `{path, sha256}` | File Core mà gói dựa vào; `sha256` của byte LF (blob git) |
| `license_notes` | chuỗi | File ghi license của base model, adapter và runtime |

### 4.2 Trường của mỗi chiều (`directions.<chiều>`)

| Trường | Kiểu / giá trị | Ý nghĩa |
|---|---|---|
| `input_prefix` | `"en:"` / `"vi:"` | Ghép liền với text, **không** dấu cách |
| `output_control_prefix` | `"vi:"` / `"en:"` | Tiền tố model sinh ở đầu bản dịch |
| `output_normalization` | `"remove_exact_leading_control_prefix_and_one_delimiter_whitespace"` | Như `baseline.py` (mục 5.2 bước 6) |
| `files` | object vai trò → path | v1 có `encoder` và `decoder` (decoder merged có KV cache) |
| `generation.strategy` | `"greedy"` | |
| `generation.num_beams` | `1` | |
| `generation.max_length` | `512` | Độ dài tối đa phía decoder tính cả token bắt đầu, tức tối đa 511 token sinh ra. Không dùng giá trị 20 mặc định của checkpoint |
| `generation.decoder_start_token_id` | `0` | |
| `generation.eos_token_id` | `1` | |
| `input_length_policy.max_input_tokens` | số nguyên hoặc `null` | `null` = không giới hạn, như Phase 02 (`segmented_rows` 0) |
| `input_length_policy.on_exceed` | `"segment_whitespace"` | Chỉ dùng khi có giới hạn: tách như `_split_source` (giữ mọi ký tự), dịch từng đoạn, nối kết quả bằng một dấu cách |
| `source_checkpoint.path` | chuỗi | Checkpoint gốc |
| `source_checkpoint.sha256_tree` | chuỗi | `core_mt.adaptation.sha256_tree` của checkpoint. Hàm này sắp đường dẫn bằng `sorted(Path)`, không phân biệt hoa thường trên Windows nhưng phân biệt trên Linux; giá trị Phase 02 tính trên Windows, nên kiểm lại bằng chính hàm đó trên Windows (xem `design_check.md` mục 7.8) |
| `golden_file` | chuỗi | `"golden/<chiều>.jsonl"` (mục 6, quy tắc 4) |
| `quality.checkpoint_it_test` | `{"chrF++", "sacreBLEU"}` | Chất lượng checkpoint gốc trên IT Test, chép từ Core, không đo lại |
| `quality.exported` | object hoặc `null` | Chất lượng bản export; cấu trúc chốt ở Phase 04 (đo trên IT Validation, cùng cấu hình metric của Core) |

## 5. Quy trình dịch trong app

### 5.1 Pipeline (ngoài gói, luôn chạy, không tắt được)

`MtInputNormalizer` (`phase01_nfc_whitespace_v1`):

1. Xoá ký tự điều khiển trong `CONTROL` của Core và U+FFFD. Phase 01 loại cả
   dòng chứa các ký tự này; app không loại được input của người dùng nên xoá.
   Các ký tự điều khiển mà Python coi là khoảng trắng (U+000B, U+000C,
   U+001C–U+001F, U+0085) không xoá mà gộp ở bước 3, để giống `norm()`.
2. Chuẩn hoá Unicode NFC.
3. Gộp mỗi chuỗi khoảng trắng (29 code point mà `\s` của Python khớp) thành
   một U+0020.
4. Bỏ khoảng trắng hai đầu.

Parity với `norm()` được kiểm bằng fixture `core_norm_parity.json` do
`tools/build_text_fixtures.py` sinh từ chính hàm của Core. Pipeline cũng đo
`mt_ms` và chạy watchdog 10 s.

### 5.2 `CoreMtTranslator` (viết ở Phase 04)

Cài interface `Translator` của app (chi tiết chốt ở M1):

```kotlin
interface Translator : AutoCloseable {
    val info: TranslatorInfo
    val status: StateFlow<TranslatorStatus>
    suspend fun prepare(directions: Set<Direction>)
    suspend fun translate(direction: Direction, text: String): TranslationResult
    fun release(directions: Set<Direction>)
}
```

Các bước trong `translate`:

1. Ghép `input_prefix + text` (không dấu cách).
2. Encode bằng `spiece.model`, thêm `eos_id`, không thêm BOS.
3. Nếu `max_input_tokens` khác `null` và bị vượt: xử lý theo `on_exceed`.
4. Chạy encoder một lần; decoder greedy bắt đầu từ `decoder_start_token_id`,
   dừng ở `eos_token_id` hoặc khi đạt `max_length`.
5. Bỏ `decode_skip_ids` và `decode_skip_id_ranges`, decode bằng SentencePiece.
6. Nếu kết quả bắt đầu đúng bằng `output_control_prefix`: bỏ tiền tố đó và tối
   đa một ký tự khoảng trắng ngay sau nó (một trong 29 code point ở mục 5.1, như
   `str.isspace()` mà `baseline.py` dùng). Không strip hay sửa gì thêm.

## 6. Quy tắc

1. **Hash.** SHA-256 tính trên byte chính xác của file. File text trong gói
   (JSON, JSONL, MD) dùng LF. `core_refs` dùng hash của byte LF (blob git),
   không dùng bản CRLF trên Windows.
2. **Định danh.** Đổi bất kỳ byte nào của gói thì phải có `package_id` mới và
   tăng `package_version`.
3. **Tương thích.** App chấp nhận `schema_version` major 1 và bỏ qua trường tuỳ
   chọn lạ. Thiếu trường bắt buộc hoặc sai kiểu thì gói không hợp lệ: app báo
   lỗi, không dịch.
4. **Golden.** `golden/<chiều>.jsonl`, mỗi dòng `{"id", "source", "expected"}`:
   `id` là `dataset_row_id` của IT Validation; `source` là text đã chuẩn hoá theo
   mục 5.1, chưa có tiền tố; `expected` là output của bản ONNX export chạy bằng
   Python (ORT CPU) sau bước 5–6 của mục 5.2. Chỉ lấy từ IT Validation, không
   bao giờ từ IT Test hay General Test. Test trên máy báo tỉ lệ khớp chính xác
   và liệt kê câu lệch; không tự đặt ngưỡng (chốt ở Phase 04).
5. **NFC.** `tokenizer.json` (thư viện `tokenizers`, cũng là
   `T5TokenizerFast` mà `AutoTokenizer` nạp) cho id sai với chữ Việt dạng NFD:
   dấu thứ hai của chữ có hai dấu bị mất ("đặt" → "đạt", "Kiểm" → "Kiêm").
   `spiece.model` cho đúng id với cả NFC và NFD. Mọi script sinh golden hoặc
   test phải đưa text NFC vào tokenizer; app đã NFC ở mục 5.1.
6. **Mở rộng.** `directions.*.files` là map vai trò → path. Nếu Phase 04 chọn
   base dùng chung cộng adapter LoRA, thêm vai trò mới (ví dụ `adapter`), sửa
   code trong `mt/` và tăng minor của `schema_version`.

## 7. App kiểm gói (`MtPackageValidator`, M1)

- `schema_version` major 1; đủ trường bắt buộc, đúng kiểu, đúng giá trị cho phép.
- Mọi đường dẫn được tham chiếu có trong `files[]`.
- File có trong assets và đúng `bytes`.
- SHA-256 được kiểm bởi `tools/check_apk.py` (trên PC, mọi asset so với lock) và
  nút "Kiểm tra toàn vẹn" ở Cài đặt → Mô hình & giấy phép (trên máy).
- Không có gói: bản offline hiện banner "Chưa có mô hình dịch offline (Phase 04)", chỉ hiện
  câu gốc. Gói lỗi: báo lỗi kèm nút [Thử lại].

## 8. Dữ liệu cho unit test

Token ID kiểm ngày 2026-09-30 bằng `spiece.model` và `tokenizer.json` của
snapshot `840bc88…` (hai cách cho cùng kết quả với input NFC; đã gồm eos 1):

| Input | ID | Piece |
|---|---|---|
| `en:How do I reset my router?` | `[1055, 49804, 49799, 124, 331, 35, 19784, 691, 22693, 49835, 1]` | `▁en` `:` `H` `ow` `▁do` `▁I` `▁reset` `▁my` `▁router` `?` |
| `en:Install the package.` | `[1055, 49804, 49805, 32942, 410, 50, 9977, 49774, 1]` | `▁en` `:` `I` `nst` `all` `▁the` `▁package` `.` |
| `vi:Cài đặt gói.` | `[1875, 49804, 49786, 191, 1991, 4820, 49774, 1]` | `▁vi` `:` `C` `ài` `▁đặt` `▁gói` `.` |

Ca sai (để test phát hiện lệch hợp đồng):

- `en: How do I reset my router?` (có dấu cách sau tiền tố) cho
  `[1055, 49804, 922, 331, 35, 19784, 691, 22693, 49835, 1]` (`▁How` thay cho
  `H` `ow`).
- `vi:Cài đặt gói.` dạng NFD qua `tokenizer.json` cho
  `[1875, 49804, 49786, 191, 3388, 4820, 49774, 1]` (3388 = `▁đạt`).

Bỏ tiền tố đầu ra (ca của Core, chiều `en_to_vi`):

| Output của model | Sau bước 6 |
|---|---|
| `vi: Bản dịch` | `Bản dịch` |
| `Nội dung có vi: ở giữa` | không đổi |
| `en: Translation` | không đổi |

## 9. Ví dụ `manifest.json`

```json
{
  "schema_version": "1.0",
  "package_id": "envit5-lora-v1-ort1.30.0-int8-r1",
  "package_version": "1.0.0",
  "created_at_utc": "<yyyy-mm-ddThh:mm:ss+00:00>",
  "model_role": "core_mt",
  "base_model": {
    "model_id": "VietAI/envit5-translation",
    "revision": "840bc88104d5a4277af740eaedb024df8c3093e7"
  },
  "runtime": {
    "engine": "onnxruntime",
    "engine_version": "1.30.0",
    "execution_provider": "cpu",
    "abis": ["arm64-v8a", "x86_64"]
  },
  "quantization": {
    "method": "dynamic_int8",
    "tool": "onnxruntime.quantization 1.30.0",
    "per_channel": false
  },
  "tokenizer": {
    "type": "sentencepiece",
    "file": "tokenizer/spiece.model",
    "sp_piece_count": 50000,
    "vocab_size": 50048,
    "add_bos": false,
    "add_eos": true,
    "pad_id": 0,
    "eos_id": 1,
    "unk_id": 2,
    "decode_skip_ids": [0, 1, 2],
    "decode_skip_id_ranges": [[50000, 50047]]
  },
  "input_normalization": "phase01_nfc_whitespace_v1",
  "directions": {
    "en_to_vi": {
      "input_prefix": "en:",
      "output_control_prefix": "vi:",
      "output_normalization": "remove_exact_leading_control_prefix_and_one_delimiter_whitespace",
      "files": {
        "encoder": "en_to_vi/encoder_model.onnx",
        "decoder": "en_to_vi/decoder_model_merged.onnx"
      },
      "generation": {
        "strategy": "greedy",
        "num_beams": 1,
        "max_length": 512,
        "decoder_start_token_id": 0,
        "eos_token_id": 1
      },
      "input_length_policy": {
        "max_input_tokens": null,
        "on_exceed": "segment_whitespace"
      },
      "source_checkpoint": {
        "path": "models/phase02/envit5-en-to-vi-lora-v1/final-epoch-1",
        "sha256_tree": "d96171cb2ccecbdb56e60744695084bc5f82b3ac11e8d512e53d99514bd12d11"
      },
      "golden_file": "golden/en_to_vi.jsonl",
      "quality": {
        "checkpoint_it_test": {"chrF++": 70.33, "sacreBLEU": 56.82},
        "exported": null
      }
    },
    "vi_to_en": {
      "input_prefix": "vi:",
      "output_control_prefix": "en:",
      "output_normalization": "remove_exact_leading_control_prefix_and_one_delimiter_whitespace",
      "files": {
        "encoder": "vi_to_en/encoder_model.onnx",
        "decoder": "vi_to_en/decoder_model_merged.onnx"
      },
      "generation": {
        "strategy": "greedy",
        "num_beams": 1,
        "max_length": 512,
        "decoder_start_token_id": 0,
        "eos_token_id": 1
      },
      "input_length_policy": {
        "max_input_tokens": null,
        "on_exceed": "segment_whitespace"
      },
      "source_checkpoint": {
        "path": "models/phase02/envit5-vi-to-en-lora-v1/final-epoch-1",
        "sha256_tree": "3d2475f0f6075d3332dbd2a258da9ef6d85675eb4f5846b0bf7fcbf456cb5c12"
      },
      "golden_file": "golden/vi_to_en.jsonl",
      "quality": {
        "checkpoint_it_test": {"chrF++": 65.21, "sacreBLEU": 41.20},
        "exported": null
      }
    }
  },
  "files": [
    {"path": "tokenizer/spiece.model", "bytes": 1102207, "sha256": "3b4eda923bbac1726e8fda66254a8783ecc705be5577149ee8c98074efdb5de5"},
    {"path": "en_to_vi/encoder_model.onnx", "bytes": 0, "sha256": "<sha256>"},
    {"path": "en_to_vi/decoder_model_merged.onnx", "bytes": 0, "sha256": "<sha256>"},
    {"path": "vi_to_en/encoder_model.onnx", "bytes": 0, "sha256": "<sha256>"},
    {"path": "vi_to_en/decoder_model_merged.onnx", "bytes": 0, "sha256": "<sha256>"},
    {"path": "golden/en_to_vi.jsonl", "bytes": 0, "sha256": "<sha256>"},
    {"path": "golden/vi_to_en.jsonl", "bytes": 0, "sha256": "<sha256>"},
    {"path": "LICENSE_NOTES.md", "bytes": 0, "sha256": "<sha256>"}
  ],
  "core_refs": [
    {"path": "evidence/phase02/core_mt_decision.json", "sha256": "1791078e24144c6e244a0121768cbc424832967486b29d2a929b2eab40f403d2"},
    {"path": "configs/phase02_models.json", "sha256": "c36712ae571cdd6748354ed6302b2dd0e32c064aff38d7a878735a8f7ca447b8"}
  ],
  "license_notes": "LICENSE_NOTES.md"
}
```

- **Giá trị thật:** `base_model`, `tokenizer`, prefix, `generation`,
  `source_checkpoint`, `quality.checkpoint_it_test` (IT Test làm tròn 2 chữ số),
  `core_refs`, và mục `tokenizer/spiece.model` trong `files` (file của snapshot
  base; Phase 04 kiểm lại với file trong checkpoint).
- **Giá trị ví dụ:** `package_id`, `quantization`, tên file ONNX.
- **Chỗ trống:** `created_at_utc`, `bytes` = 0 và `sha256` = `"<sha256>"`.
  Validator từ chối các giá trị này; Phase 04 điền.

## 10. Phạm vi thay đổi của Phase 04

Một thay đổi Phase 04 chỉ được sửa:

- `app/src/{main,test,androidTest}/kotlin/vn/viettechtrans/app/mt/**`;
- khai báo dependency ONNX Runtime Android: một dòng trong
  `app/build.gradle.kts`, phiên bản ghim trong `gradle/libs.versions.toml`;
- `app/src/main/assets/mt/` (gói, cài bằng `tools/fetch_artifacts.py`);
- mục gói MT trong `artifacts.lock.json`.

Sửa ngoài danh sách này (UI, speech, turn, evidence…) phải bàn trước và cập nhật
hợp đồng. sherpa-onnx dùng AAR link tĩnh ONNX Runtime, nên thêm
`onnxruntime-android` không bị trùng `libonnxruntime.so`.
