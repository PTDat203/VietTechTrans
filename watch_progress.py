"""Theo doi tien do baseline Phase 06 theo thoi gian thuc.

Doc runs/core_mt_baseline_v2/<model>/<direction>.in_progress/run.log va quy doi
ra phan tram tren TONG so lan generate cua ca run, kem toc do va gio du kien xong.

Chay:  python watch_progress.py
Thoat: Ctrl+C  (khong anh huong gi den run dang chay)
"""
import re
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path

ROOT = Path(r"D:\DATN_Dat")
RUNS = ROOT / "runs" / "core_mt_baseline_v2"

# Khop dung protocol v2: quality 1 luot moi cau + probe (32 warmup + 5x128) moi test set.
QUALITY = {"general_test": 1012, "it_test": 13851}
PROBE_PER_SET = 32 + 5 * 128
TOTAL = sum(QUALITY.values()) + PROBE_PER_SET * len(QUALITY)

RE_QUALITY = re.compile(r"\[(\w+)\] quality: (\d+)/(\d+) sentences")
RE_PROBE = re.compile(r"\[(\w+)\] repeat (\d+): (\d+)/(\d+) probe sentences")
RE_DONE = re.compile(r"Baseline completed successfully")
RE_SCORE = re.compile(r"\[(\w+)\] chrF\+\+=([\d.]+); SacreBLEU=([\d.]+)")

ORDER = ["general_test", "it_test"]


def find_runs():
    if not RUNS.is_dir():
        return []
    out = []
    for model_dir in sorted(RUNS.iterdir()):
        if not model_dir.is_dir():
            continue
        for d in sorted(model_dir.iterdir()):
            log = d / "run.log"
            if log.is_file():
                out.append((model_dir.name, d.name, log))
    return out


def done_count(text):
    """Quy doi dong log cuoi cung thanh so cau da xu ly tren toan bo run."""
    total = 0
    seen_quality, seen_probe = {}, {}
    for line in text.splitlines():
        m = RE_QUALITY.search(line)
        if m:
            seen_quality[m.group(1)] = int(m.group(2))
            continue
        m = RE_PROBE.search(line)
        if m:
            name, repeat, idx = m.group(1), int(m.group(2)), int(m.group(3))
            seen_probe[name] = 32 + (repeat - 1) * 128 + idx
    for name in ORDER:
        if name in seen_probe:
            total += QUALITY[name] + seen_probe[name]
        elif name in seen_quality:
            total += seen_quality[name]
    stage = "dang khoi dong"
    if seen_probe:
        last = [n for n in ORDER if n in seen_probe][-1]
        stage = f"{last} latency probe ({seen_probe[last]}/{PROBE_PER_SET})"
    elif seen_quality:
        last = [n for n in ORDER if n in seen_quality][-1]
        stage = f"{last} quality ({seen_quality[last]:,}/{QUALITY[last]:,})"
    return total, stage


def bar(fraction, width=34):
    filled = int(fraction * width)
    return "#" * filled + "." * (width - filled)


def render():
    runs = find_runs()
    if not runs:
        return "Chua co run nao. Thu muc runs/ chua duoc tao."
    lines = []
    for model, direction, log in runs:
        text = log.read_text(encoding="utf-8", errors="replace")
        active = direction.endswith(".in_progress")
        label = direction.replace(".in_progress", "")
        scores = RE_SCORE.findall(text)
        if RE_DONE.search(text) or not active:
            lines.append(f"  {model} {label:10s} HOAN THANH")
            for name, chrf, bleu in scores:
                lines.append(f"      {name:14s} chrF++={chrf}  BLEU={bleu}")
            continue
        done, stage = done_count(text)
        started = datetime.fromtimestamp(log.stat().st_ctime)
        elapsed = (datetime.now() - started).total_seconds()
        frac = done / TOTAL if TOTAL else 0
        rate = elapsed / done if done else 0
        remain = (TOTAL - done) * rate
        eta = datetime.now() + timedelta(seconds=remain)
        lines.append(f"  {model} {label} - DANG CHAY")
        lines.append(f"    [{bar(frac)}] {frac * 100:5.1f}%   {done:,} / {TOTAL:,} cau")
        lines.append(f"    giai doan : {stage}")
        lines.append(f"    da chay   : {timedelta(seconds=int(elapsed))}")
        lines.append(f"    toc do    : {rate:.2f} s/cau")
        if done:
            lines.append(f"    con lai   : ~{timedelta(seconds=int(remain))}  (xong luc {eta:%H:%M %d/%m})")
        for name, chrf, bleu in scores:
            lines.append(f"    XONG {name:14s} chrF++={chrf}  BLEU={bleu}")
    return "\n".join(lines)


if __name__ == "__main__":
    try:
        while True:
            body = render()
            sys.stdout.write("\033[2J\033[H")
            print(f"Tien do baseline Phase 06 - {datetime.now():%H:%M:%S}")
            print("=" * 60)
            print(body)
            print("=" * 60)
            print("Ctrl+C de thoat (khong anh huong run dang chay)")
            sys.stdout.flush()
            time.sleep(15)
    except KeyboardInterrupt:
        print("\nDa thoat theo doi.")
