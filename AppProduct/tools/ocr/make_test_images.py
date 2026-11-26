"""Renders the Dịch ảnh test set (DR-10): IT sentences in Vietnamese and English as screenshots and photo-like
pictures. Writes <id>.png and truth.json (the text of each picture; one line per paragraph, as OcrText joins blocks).

  python tools/ocr/make_test_images.py evidence/phase04/ocr_mlkit_v1

Needs Pillow and the Windows fonts Arial, Segoe UI, Times New Roman and Tahoma (C:\\Windows\\Fonts).
"""
import json
import random
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

FONTS = {"arial": r"C:\Windows\Fonts\arial.ttf", "segoe": r"C:\Windows\Fonts\segoeui.ttf",
         "times": r"C:\Windows\Fonts\times.ttf", "tahoma": r"C:\Windows\Fonts\tahoma.ttf"}

# id, lang, paragraphs, font, size, style
CASES = [
    ("vi_dialog", "vi", ["Không thể kết nối tới máy chủ.", "Vui lòng kiểm tra kết nối mạng và thử lại."], "segoe", 40, "screen"),
    ("vi_settings", "vi", ["Cập nhật phần mềm", "Phiên bản mới đã sẵn sàng. Hãy sao lưu dữ liệu trước khi cài đặt."], "arial", 36, "screen"),
    ("vi_notice", "vi", ["Mật khẩu phải có ít nhất 8 ký tự, gồm chữ hoa, chữ thường và chữ số."], "times", 42, "screen"),
    ("vi_photo", "vi", ["Ứng dụng bị lỗi khi mở tệp đính kèm.", "Hãy khởi động lại điện thoại."], "tahoma", 44, "photo"),
    ("en_dialog", "en", ["Unable to connect to the server.", "Please check your network connection and try again."], "segoe", 40, "screen"),
    ("en_settings", "en", ["Software update", "A new version is available. Back up your data before installing."], "arial", 36, "screen"),
    ("en_notice", "en", ["Your password must contain at least 8 characters, including a number."], "times", 42, "screen"),
    ("en_photo", "en", ["The application stopped while opening the attachment.", "Restart your phone and try again."], "tahoma", 44, "photo"),
]


def wrap(draw, text, font, width):
    words, lines, line = text.split(), [], ""
    for w in words:
        trial = f"{line} {w}".strip()
        if draw.textlength(trial, font=font) <= width:
            line = trial
        else:
            lines.append(line)
            line = w
    lines.append(line)
    return lines


def render(case, out: Path):
    cid, lang, paragraphs, font_name, size, style = case
    font = ImageFont.truetype(FONTS[font_name], size)
    width = 1080
    img = Image.new("RGB", (width, 900), "white")
    draw = ImageDraw.Draw(img)
    y = 80
    for i, para in enumerate(paragraphs):
        # A short first paragraph is a heading: larger letters.
        f = ImageFont.truetype(FONTS[font_name], int(size * 1.25)) if (len(paragraphs) > 1 and i == 0 and len(para) < 25) else font
        for line in wrap(draw, para, f, width - 160):
            draw.text((80, y), line, fill=(20, 20, 30), font=f)
            y += int(f.size * 1.45)
        y += int(size * 1.2)
    img = img.crop((0, 0, width, y + 60))
    if style == "photo":
        # Off-white page on a grey table, rotated 3.5°, blurred and noisy, like a phone photo.
        rnd = random.Random(cid)
        bg = Image.new("RGB", (img.width + 200, img.height + 200), (205, 200, 190))
        bg.paste(img.point(lambda v: int(v * 0.93) + 8), (100, 100))
        img = bg.rotate(3.5, resample=Image.BICUBIC, fillcolor=(190, 185, 175)).filter(ImageFilter.GaussianBlur(1.2))
        px = img.load()
        for _ in range(img.width * img.height // 25):
            x, yy = rnd.randrange(img.width), rnd.randrange(img.height)
            d = rnd.randint(-25, 25)
            px[x, yy] = tuple(max(0, min(255, c + d)) for c in px[x, yy])
    img.save(out / f"{cid}.png")
    return {"id": cid, "lang": lang, "style": style, "font": font_name, "text": "\n".join(paragraphs)}


def main() -> int:
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    out.mkdir(parents=True, exist_ok=True)
    truth = [render(c, out) for c in CASES]
    (out / "truth.json").write_text(json.dumps(truth, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"OCR_TEST_IMAGES={len(truth)} -> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
