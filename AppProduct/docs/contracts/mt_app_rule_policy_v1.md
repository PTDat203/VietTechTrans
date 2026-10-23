# Chính sách rule dịch text trên app v1

**Trạng thái:** đã triển khai trong AppProduct (`mt/rules/`) và kiểm tra trên fixed cases: 41 case của
`mt_rule_cases_v1.json` chạy ở ba tầng (checkpoint PyTorch, graph q4r trên PC, APK trên emulator) cùng
test JVM; evidence ở `evidence/phase04/mt_rules_v1/`, ghi ở DR-07 §16  
**Giới hạn:** chưa phải bằng chứng Student v1 tăng chất lượng dịch; chưa có đánh giá metric đầy đủ trên
cohort sau toàn bộ rule  
**Phạm vi:** AppProduct, luồng nhập bằng text EN↔VI, chạy offline với gói `vtt-main-core-phase03-q4r`  
**Không thuộc phạm vi:** train, checkpoint, tokenizer, prefix, metric lịch sử của Student v1 Core

## 1. Mục đích và nguyên tắc

Các rule này giảm hành vi gây hại trong ứng dụng: gửi input rỗng/quá dài, dịch nhầm chiều,
để một thuật ngữ kỹ thuật đơn lẻ bị sinh thành từ vô nghĩa, tìm kiếm beam quá lâu, hoặc hiển thị
trạng thái voice trong demo text. Rule không được dùng để tuyên bố Student v1 hiểu thêm ngữ nghĩa IT.

Đã triển khai rule trong app ≠ đã cải thiện năng lực dịch của Student v1. Rule lo an toàn khi chạy,
literal, input lỗi và UX; rule không biến lỗi ngữ nghĩa của Student thành đúng. Ví dụ case
`r08-sentence` trên APK: `Deploy CI/CD lên staging trước khi merge vào main.` được dịch thành
`Delloy CI/CD to staging ahead of merge in the main.` R08 chỉ gắn `Cần kiểm tra thuật ngữ` cho
`deploy`, không sửa bản dịch. Làm vậy là đúng chính sách, và ca này vẫn là dữ kiện cho Student v2.

Student v1 trong `Core/evidence/phase03/main_core_decision.json` là checkpoint đã freeze. Mọi rule
trong tài liệu này được thực hiện ở AppProduct, trước hoặc sau khi gọi `MarianTranslator`; không sửa
checkpoint, `generation_config`, prefix, SentencePiece, vocab hay kết quả Phase 03.

Ngôn ngữ người dùng chọn quyết định hướng dịch. Phát hiện ngôn ngữ chỉ để cảnh báo nhập nhầm, không
tự đổi model hay chiều dịch.

## 2. Hai giới hạn cần phân biệt

| Giới hạn | Đơn vị | Hành động | Lý do |
|---|---:|---|---|
| Nhập liệu | Tối đa 500 ký tự hiển thị sau chuẩn hoá | Chặn gửi nếu vượt giới hạn | Quy tắc UX của app |
| Model | Tối đa 500 `source_ids` sau tokenizer | Tách đoạn, không cắt nội dung | Tránh vượt giới hạn vị trí Marian 512 token |
| Sinh câu | `min(512, 2 × source_ids + 20)` token đích | Dừng tìm kiếm theo Rule R10 | Chặn beam chạy vô ích ở câu ngắn |

Một ký tự hiển thị gồm cả khoảng trắng, xuống dòng và emoji. Bộ đếm UI và kiểm tra lúc gửi phải
dùng cùng cách đếm. `source_ids` là kết quả tokenizer Marian, không phải số ký tự hoặc số từ.

## 3. Thứ tự xử lý bắt buộc

```text
text thô
  → R01 chuẩn hoá
  → R02 rỗng / 500 ký tự
  → R03-R05 phân loại input đơn lẻ
  → R06 chiều ngôn ngữ, R07 tiếng Việt không dấu
  → quyết định giữ nguyên / chờ người dùng xác nhận / gọi Marian
  → R09 tách theo source_ids nếu cần
  → R10 sinh câu, R11 gộp lặp
  → R12 kiểm tra output cấu trúc
  → bubble kết quả và cảnh báo
```

Mỗi message xử lý độc lập, giữ nguyên người nói và thứ tự hội thoại. Với text demo, không đi qua STT;
TTS chỉ chạy nếu người dùng bấm nghe sau khi đã có bản dịch.

## 4. Rule đầu vào

### R01 — Chuẩn hoá contract

- Chuẩn hoá Unicode NFC và whitespace đúng contract `phase01_nfc_whitespace_v1`.
- Bỏ whitespace đầu/cuối; nếu chỉ còn whitespace, xử lý như input rỗng.
- Không tự thêm dấu tiếng Việt, sửa chính tả, đổi chữ hoa/thường của code hay thay thuật ngữ.

Ví dụ: `  tôi   cần kiểm tra API  ` trở thành `tôi cần kiểm tra API`.

### R02 — Input rỗng và tối đa 500 ký tự

| Điều kiện | Hành động |
|---|---|
| Rỗng sau R01 | Không gọi model, hiện “Hãy nhập nội dung cần dịch.” |
| Từ 1 đến 500 ký tự | Cho đi tiếp |
| Trên 500 ký tự | Khóa gửi, hiện “Nội dung tối đa 500 ký tự.” và giữ nguyên text để người dùng sửa |

Không tự cắt phần cuối input.

### R03 — Bỏ qua dịch cho literal kỹ thuật độc lập

Nếu toàn bộ input là literal kỹ thuật độc lập (có thể kèm dấu câu đơn giản), không gọi Marian.
Bubble đích giữ nguyên literal và có nhãn `Thuật ngữ/ký hiệu được giữ nguyên`.

Các loại literal:

- URL, email, đường dẫn, tên tệp, phiên bản, HTTP status, số.
- Code inline, lệnh, identifier, class/function/exception.
- Acronym và tên công nghệ trong `preserve_exact` của lexicon đã review.

Ví dụ: `API`, `CI/CD`, `JavaScript`, `Docker`, `404`, `dev@example.com`,
`await fetch('/api/users')`, `/api/users`, `NullPointerException`.

Không dùng R03 cho một câu có literal kỹ thuật, ví dụ `API returns timeout`. Câu này vẫn đi qua R08.

### R04 — Cụm kỹ thuật ngắn phụ thuộc ngữ cảnh

Nếu toàn bộ input là một từ hoặc cụm kỹ thuật ngắn trong `keep_with_context_warning`, không gọi Marian.
Giữ nguyên input và hiện `Từ kỹ thuật ngắn có thể có nhiều nghĩa. Hãy nhập cả câu nếu cần dịch theo ngữ cảnh.`

Ví dụ: `timeout`, `production`, `staging`, `deployment`, `migration`, `database connection pool`,
`foreign key constraint`, `merge`, `branch`.

Lý do: `production` có thể là “sản xuất” hoặc “môi trường production”; `timeout` có thể là “hết thời
gian chờ”. Giữ nguyên an toàn hơn sinh `Performion` hay `heartout`.

### R05 — Từ giao tiếp ngắn

Nếu input là từ/cụm giao tiếp trong `short_dialogue_words`, vẫn gọi Marian vì nghĩa thông dụng đủ rõ.

| Chiều | Ví dụ input | Kết quả mong đợi |
|---|---|---|
| EN→VI | `yes`, `no`, `hello`, `thanks`, `please` | `có`, `không`, `xin chào`, `cảm ơn`, … |
| VI→EN | `ừ`, `vâng`, `không`, `chào`, `cảm ơn`, `xin lỗi` | `yes`, `no`, `hello`, `thank you`, … |

Input một từ không nằm trong R03–R05 không được gắn nhãn “dịch đúng” chỉ vì model có output. Hiện
cảnh báo `Nội dung rất ngắn; kết quả có thể thiếu ngữ cảnh`, nhưng vẫn cho người dùng gửi nếu không
phải literal kỹ thuật hoặc sai chiều rõ ràng.

### R06 — Cảnh báo sai chiều ngôn ngữ

Trước khi nhận diện ngôn ngữ, bỏ qua span của URL, code, literal R03 và acronym. Detector offline chỉ
trả `vi_high`, `en_high`, `mixed`, hoặc `uncertain`.

| Chiều được chọn | Detector | Hành động |
|---|---|---|
| VI→EN | `vi_high` hoặc `mixed` | Cho dịch |
| EN→VI | `en_high` hoặc `mixed` | Cho dịch |
| VI→EN | `en_high` | Không gửi ngay; cho `Đổi sang English` hoặc `Vẫn dịch` |
| EN→VI | `vi_high` | Không gửi ngay; cho `Đổi sang Tiếng Việt` hoặc `Vẫn dịch` |
| Bất kỳ | `uncertain` | Không tự đổi chiều; xử lý theo R03–R05 hoặc cho dịch với cảnh báo nhẹ |

Ví dụ: chọn VI→EN rồi nhập `Hello, how are you?` phải cảnh báo; chọn VI→EN rồi nhập
`Tôi cần deploy API lên production.` vẫn phải cho dịch. Khi người dùng chọn `Vẫn dịch`, ghi cờ
`language_mismatch_overridden` vào log test.

### R07 — Cảnh báo tiếng Việt không dấu

Chỉ xét khi người dùng chọn VI→EN. Sau khi bỏ literal kỹ thuật, nếu text có tín hiệu tiếng Việt không
dấu đủ mạnh, hiện `Câu tiếng Việt không dấu có thể dịch sai. Hãy thêm dấu nếu có thể.` rồi vẫn cho dịch.

Không cảnh báo chỉ vì text không có dấu. Không cảnh báo các input như `database connection pool`,
`CPU`, URL, email, code hay `API timeout 504`. Không tự khôi phục dấu trước khi gọi model.

### R08 — Câu có thuật ngữ IT

Với câu tự nhiên chứa thuật ngữ, gọi Marian cho cả câu. Không tự dùng từ điển để thay output hay ép
`staging` thành một bản dịch cụ thể.

- Literal bất biến như URL, email, code, HTTP status phải còn trong output; nếu mất, xử lý R12.
- Các từ phụ thuộc ngữ cảnh như `timeout`, `staging`, `production`, `pool` không bị bypass chỉ vì có
  mặt trong câu.
- Nếu output làm biến dạng thuật ngữ, hiển thị output thật và gắn `Cần kiểm tra thuật ngữ`; lưu ca này
  vào evidence. Không âm thầm “vá” bản dịch.

Ví dụ: `Deploy CI/CD lên staging trước khi merge vào main.` được dịch như một câu; nếu `staging` thành
`stash`, đây là lỗi cần ghi nhận cho Student v2/glossary đã kiểm chứng, không phải lý do thay từ sau dịch.

## 5. Rule runtime q4r

### R09 — Tách đoạn theo token

Sau khi tokenizer, nếu input vượt 500 `source_ids`:

1. Tách tại xuống dòng hoặc sau `.`, `!`, `?`, `…`, `;`, `:`.
2. Gom các câu mà mỗi đoạn không vượt 500 `source_ids`.
3. Một câu vẫn quá dài: tách theo whitespace.
4. Không tách bên trong URL, email, code hoặc literal kỹ thuật.
5. Dịch từng đoạn rồi nối bằng một khoảng trắng; ghi `segment_count`.

Không cắt bỏ nội dung. Rule này là hàng rào kỹ thuật sau R02; với giới hạn 500 ký tự, nó hiếm khi kích
hoạt nhưng vẫn cần cho các token hoá bất thường.

### R10 — Giới hạn sinh và hypothesis hoàn thành

Giữ beam theo manifest (`EN→VI = 4`, `VI→EN = 6`). Không thay `generation_config` của Core.

```text
max_output_ids = min(512, 2 × source_ids + 20)
```

Khi chạm ngưỡng, trả hypothesis hoàn thành tốt nhất nếu có. Chỉ ép EOS nếu chưa có hypothesis hoàn
thành. Đây là policy hiện hữu trong `GenerationParams.maxLengthFor` và `BeamSearch(preferFinished = true)`.

### R11 — Gộp lặp output

Sau giải mã, nếu một cụm từ dài 1–3 từ lặp liên tiếp từ bốn lần trở lên, chỉ giữ một lần và đặt cờ
`repeat_cleanup_applied`.

| Output thô | Output hiển thị |
|---|---|
| `Please please please please deploy.` | `Please deploy.` |
| `No no no.` | Giữ nguyên, vì chỉ lặp ba lần |

## 6. Kiểm tra output và hiển thị

### R12 — Output không hợp lệ về cấu trúc

Nếu output rỗng, chỉ có whitespace, hoặc làm mất URL/email/code/HTTP status có trong source:

- Không tạo bubble dịch thành công.
- Hiện `Không thể tạo bản dịch hợp lệ. Hãy thử lại hoặc nhập câu rõ hơn.`
- Giữ nguyên source để người dùng sửa/gửi lại.
- Ghi lỗi và thông tin package vào log.

Không có rule tự động đáng tin để kết luận mọi output “vô nghĩa” như `Name` hay `Commodorescript`.
R03–R04 ngăn các ca thuật ngữ đơn lẻ; các lỗi còn lại phải được ghi thành test/evidence.

### R13 — Giao diện text-only

Khi message tạo từ bàn phím:

- Chỉ hiển thị `MT <thời gian> ms`.
- Không hiện `STT --`, `TTS --`, tổng thời gian gồm STT/TTS, hay toast `Không nghe thấy giọng nói`.
- Toast microphone chỉ xuất hiện sau thao tác voice thất bại.
- Bubble hiển thị ngôn ngữ nguồn, source, output và cảnh báo rule đã kích hoạt.

## 7. Lexicon đặc biệt từ Phase 1

Phase 1 có bốn nhóm chính: `Hỗ trợ kỹ thuật / xử lý sự cố`, `Software / UI`, `CLI / Command`,
`Code & Technical Reference`; và tag `has_error`, `has_command`, `has_ui`, `has_code_or_api`,
`has_path`, `has_config`, `has_version`. Chúng là nguồn lấy ứng viên, không phải glossary runtime.

Không dùng trực tiếp `AppProduct/docs/data/it_term_candidates.tsv`: file này được sinh cho TTS từ release
legacy và có token nhiễu hoặc từ thông thường như `Name`, `of`, `App`, `Core`, `game`.

Tạo lexicon app version riêng, được review thủ công, với ba nhóm không chồng lấp:

```json
{
  "schema_version": "1.0",
  "preserve_exact": {
    "acronyms": ["API", "CPU", "RAM", "JWT", "HTTP", "HTTPS", "SDK", "SQL", "CI/CD"],
    "technologies": ["JavaScript", "TypeScript", "React", "Docker", "Kubernetes", "PostgreSQL", "MySQL", "Git", "Linux"],
    "patterns": ["url", "email", "file_path", "code", "http_status", "version", "identifier"]
  },
  "keep_with_context_warning": [
    "timeout", "production", "staging", "deployment", "migration", "database",
    "pool", "commit", "merge", "branch", "request", "response", "cache", "token"
  ],
  "short_dialogue_words": {
    "en_to_vi": ["yes", "no", "hello", "thanks", "please", "sorry"],
    "vi_to_en": ["ừ", "vâng", "không", "chào", "cảm ơn", "xin lỗi"]
  }
}
```

Ánh xạ từ Phase 1 vào review:

| Nguồn Phase 1 | Nhóm lexicon cần xem xét | Không được suy ra tự động |
|---|---|---|
| `has_code_or_api` | `preserve_exact` cho code, API, identifier, công nghệ | Mọi từ tiếng Anh trong câu |
| `has_command`, `has_path`, `has_version` | pattern bất biến | Dịch hay không dịch cả câu lệnh |
| `has_error`, `has_config`, `has_ui` | `keep_with_context_warning` | Gán một nghĩa cố định cho `error`, `settings`, `timeout` |
| Nhóm primary | Chọn mẫu review/test đa miền | Tự coi nhóm là nhãn glossary |

Quy trình thêm term: lấy ứng viên theo tag → loại token phổ thông/nhiễu → phân vào đúng một nhóm →
thêm test dạng một từ và trong câu → tăng version lexicon. Không thêm term vào `preserve_exact` nếu nó
có thể cần dịch theo ngữ cảnh.

## 8. Evidence và nghiệm thu

Mỗi lần thay lexicon hoặc rule phải lưu: input chuẩn hoá, chiều đã chọn, rule/cảnh báo kích hoạt,
`source_ids`, số đoạn, output thô, output sau cleanup, package ID, hash manifest/model và thời gian MT.

Đánh giá ba tầng, cùng bộ case cố định:

| Tầng | Mục tiêu |
|---|---|
| Student v1 trước nén | Xác định lỗi vốn có của checkpoint |
| q4r reference runner | Đo export/quantization, chưa áp dụng policy UI nếu đo tổn thất thuần |
| APK Android sau toàn bộ rule | Đo chất lượng triển khai thực tế và UX |

Không gọi kết quả tầng APK là “tổn thất quantization”. Policy app, segment, length cap và cleanup đều có
thể thay output.

### Bộ test bắt buộc

| Case | Kết quả bắt buộc |
|---|---|
| `JavaScript`, `API`, `404`, URL, email, code | R03, giữ nguyên, không gọi Marian |
| `timeout`, `production`, `database connection pool` | R04, giữ nguyên và cảnh báo ngữ cảnh |
| `yes`, `no`, `hello`, `ừ`, `cảm ơn` | R05, dịch |
| Chọn VI→EN + `Hello, how are you?` | R06, cảnh báo sai chiều |
| Chọn VI→EN + `Tôi cần deploy API lên production.` | Cho dịch, không chặn câu trộn |
| `toi can kiem tra loi API` | R07, cảnh báo thiếu dấu, vẫn dịch |
| `Deploy CI/CD lên staging trước khi merge vào main.` | R08, dịch cả câu và ghi review nếu term biến dạng |
| 500 / 501 ký tự | Cho gửi / chặn gửi |
| Input >500 `source_ids` | R09, tách đoạn không mất nội dung |
| Output lặp bốn lần | R11, gộp lặp |
| Text-only | Không xuất hiện STT/TTS hay toast microphone |

## 9. Việc không làm

- Không sửa Student v1, checkpoint hash, metric Phase 03, tokenizer, prefix hoặc `generation_config`.
- Không tự đổi chiều dịch chỉ vì detector nghi ngờ.
- Không tự khôi phục dấu, sửa chính tả hay ép nghĩa từ IT.
- Không dùng dictionary để âm thầm thay output model trong câu.
- Không dùng output q4r hoặc output đã cleanup làm nhãn train Student v2.
- Không đưa toàn bộ term candidates Phase 1 vào lexicon runtime khi chưa review và test.

Lỗi nghĩa còn lại như `foreign key constraint` dịch sai hoặc `staging` thành `stash` là evidence cho
Student v2 hoặc một glossary có đánh giá riêng; không được báo là đã giải quyết chỉ bằng rule app.
