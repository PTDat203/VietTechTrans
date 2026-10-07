import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import { createRequire } from "module";

const require = createRequire(import.meta.url);
const sharp = require("sharp");
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const outDir = path.join(root, "assets", "report_workflows_portrait");
fs.mkdirSync(outDir, { recursive: true });

const workflows = [
  ["01", "Giai đoạn 1 — Dataset IT EN↔VI", "Tạo và khóa bộ dữ liệu trước khi đánh giá model.", [
    ["Snapshot RAW", "Lưu nguồn, file gốc và checksum"],
    ["Audit dữ liệu", "Kiểm tra schema, null, blank và trùng lặp"],
    ["Làm sạch", "Chuẩn hóa text, loại lỗi dữ liệu rõ ràng"],
    ["Nhóm nội dung IT", "Gán primary_group và technical_tags"],
    ["Khóa IT release", "Chia IT Train/Validation/Test, kiểm tra leakage và manifest"],
    ["Tập ngoài miền và gate", "Tạo FLORES dev/devtest, kiểm tra overlap, checksum và schema"],
  ], "IT Train, IT Validation và General Validation sẵn sàng cho Giai đoạn 2"],
  ["02", "Giai đoạn 2 — Chọn Core MT", "Chọn một Core MT cho mỗi chiều EN→VI và VI→EN.", [
    ["Kiểm tra input", "Xác nhận dataset gate PASS; test vẫn khóa"],
    ["Đánh giá pretrained", "3 model × 2 chiều × 2 tập validation"],
    ["Chọn model giữ", "Ghi selection record cho từng chiều"],
    ["Chuẩn bị thích nghi", "Ghi experiment record trước khi train"],
    ["Thích nghi IT", "Chỉ dùng IT Train"],
    ["Đánh giá và review", "Chạy validation, review 50 câu đã khóa"],
    ["Chốt Core và báo cáo", "Freeze Core; final test chỉ mở sau đó"],
  ], "Core MT và evidence cho các giai đoạn sau"],
  ["03", "Giai đoạn 3 — Tạo Student MT", "Tạo mô hình gọn từ Core MT đã chốt.", [
    ["Nhận Core MT", "Đọc checkpoint, revision và generation config"],
    ["Lập protocol Student", "Ghi dữ liệu, seed, checkpoint và metric"],
    ["Target và integrity", "Teacher tạo/reuse target từ IT Train; kiểm tra ID và độ dài"],
    ["Train Student", "Student học source → Teacher target và lưu checkpoint cuối"],
    ["Đánh giá và review", "IT/General Validation, so Teacher, review 50 row ID"],
    ["Quyết định Main-Core", "Người phụ trách freeze hoặc reject từng Student"],
  ], "Main-Core, hash checkpoint và decision để máy khác dùng ở Giai đoạn 4"],
];

const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
function lines(text, limit = 52) {
  const words = text.split(" "); const out = []; let line = "";
  for (const word of words) { if ((line + " " + word).trim().length > limit && line) { out.push(line); line = word; } else line = (line + " " + word).trim(); }
  if (line) out.push(line); return out;
}
function textLines(text, x, y, className, limit) {
  return lines(text, limit).map((line, i) => `<text x="${x}" y="${y + i * 25}" text-anchor="middle" class="${className}">${esc(line)}</text>`).join("");
}
function build(id, title, subtitle, steps, result) {
  const h = 1660, w = 1180, start = 175, step = Math.floor((1250 - start) / (steps.length - 1));
  const cards = steps.map(([name, detail], i) => {
    const y = start + i * step;
    const fill = i === steps.length - 1 ? "#fff8e8" : "#f5f9ff";
    const stroke = i === steps.length - 1 ? "#a97416" : "#3974c9";
    const arrow = i < steps.length - 1 ? `<path d="M590 ${y + 126}V${y + step - 14}" class="arrow"/>` : "";
    return `<rect x="90" y="${y}" width="1000" height="126" rx="14" fill="${fill}" stroke="${stroke}" stroke-width="3"/>${textLines(`${i + 1}. ${name}`, 590, y + 42, "step", 45)}${textLines(detail, 590, y + 83, "detail", 60)}${arrow}`;
  }).join("");
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">
  <defs><marker id="m" markerWidth="10" markerHeight="10" refX="8" refY="5" orient="auto"><path d="M0 0L10 5L0 10Z" fill="#475569"/></marker><style>.title{font:700 34px Arial;fill:#172033}.sub{font:19px Arial;fill:#475569}.step{font:700 25px Arial;fill:#172033}.detail{font:20px Arial;fill:#334155}.out{font:700 24px Arial;fill:#172033}.arrow{stroke:#475569;stroke-width:3;marker-end:url(#m)}</style></defs>
  <text x="70" y="62" class="title">${esc(title)}</text>${textLines(subtitle, 590, 100, "sub", 90)}${cards}
  <rect x="90" y="1450" width="1000" height="100" rx="14" fill="#eef8f1" stroke="#2f7d4e" stroke-width="3"/>${textLines(result, 590, 1490, "out", 58)}
  </svg>`;
}

for (const [id, title, subtitle, steps, result] of workflows) {
  const svg = build(id, title, subtitle, steps, result);
  await sharp(Buffer.from(svg), { density: 220 }).png().toFile(path.join(outDir, `giai_doan_${id}.png`));
}
