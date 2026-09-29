"""Spike S2 — monitor soak 30 phút trên Android device thật.

Poll định kỳ (mặc định 15s, khớp §0.11 của plan): `adb shell dumpsys meminfo <pkg>`,
`dumpsys thermalservice` (thermal bucket sót lại sau khi process chết — bài học OplusHansManager)
và `dumpsys battery` → JSON line mỗi sample + JSON tổng hợp cuối.

Soak bản thân chạy trong app (MainActivity intent extra `loops`, `--ei loops 12`);
tool này CHỈ thu thập tín hiệu ngoài, không điều khiển app.

Chạy: uv run --no-sync python v3nano-mobile/host/device_soak_monitor.py \
        --pkg com.vieneu.mobile.validation --duration-min 30 \
        --out v3nano-mobile/eval/reports/2026-09-29-android-real-device-soak-monitor.json \
        [--adb PATH_TO_ADB]
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

ADB_CANDIDATES = [
    shutil.which("adb"),
    "/opt/homebrew/share/android-commandlinetools/platform-tools/adb",
    "/usr/local/share/android-commandlinetools/platform-tools/adb",
]

_THERMAL_RE = re.compile(r"\b(CRITICAL|EMERGENCY|SEVERE|MODERATE|LIGHT|NONE)\b", re.IGNORECASE)


def find_adb() -> str:
    for cand in ADB_CANDIDATES:
        if cand and Path(cand).is_file():
            return cand
    raise SystemExit("adb not found — cài platform-tools hoặc truyền --adb")


def adb(adb_path: str, args: list[str], timeout: int = 30) -> str:
    try:
        out = subprocess.run([adb_path, *args], capture_output=True, text=True, timeout=timeout)
        return (out.stdout or "") + (out.stderr or "")
    except Exception as e:
        return f"ERROR: {e}"


def parse_meminfo(text: str, pkg: str) -> dict:
    # Tìm dòng "TOTAL PSS/RSS" của process mục tiêu (form biến đổi theo Android version).
    pss = rss = None
    in_target = False
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("**") and pkg in line:
            in_target = True
            continue
        if in_target:
            m = re.match(r"TOTAL(?: PSS:)?\s+(\d+)(?:\s+(\d+))?", line)
            if m:
                pss = int(m.group(1))
                rss = int(m.group(2)) if m.group(2) else None
                break
            if line.startswith("**") or line.startswith("App Summary"):
                break
    # Fallback: dumpsys meminfo --package trả thẳng 1 block
    if pss is None:
        for line in text.splitlines():
            if "TOTAL PSS" in line:
                pss = int(re.sub(r"\D", "", line))
            elif "TOTAL RSS" in line:
                rss = int(re.sub(r"\D", "", line))
    return {"pss_kb": pss, "rss_kb": rss}


_STATUS_NUM = {
    0: "NONE",
    1: "LIGHT",
    2: "MODERATE",
    3: "SEVERE",
    4: "CRITICAL",
    5: "EMERGENCY",
    6: "SHUTDOWN",
}


def parse_thermal(text: str) -> list[str]:
    buckets = {b.upper() for b in _THERMAL_RE.findall(text)}
    # OnePlus/Android 13+ dump: "Thermal Status: 3" (numeric ThermalManager status)
    m = re.search(r"Thermal Status:\s*(\d+)", text)
    if m:
        buckets.add(_STATUS_NUM.get(int(m.group(1)), f"UNKNOWN_{m.group(1)}"))
    return sorted(buckets)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--pkg", type=str, default="com.vieneu.mobile.validation")
    parser.add_argument("--interval", type=float, default=15.0,
                        help="Giây giữa 2 lần poll (default 15s, khớp plan §0.11)")
    parser.add_argument("--duration-min", type=float, default=30.0)
    parser.add_argument("--out", type=str, required=True)
    parser.add_argument("--adb", type=str, default="")
    args = parser.parse_args()

    adb_path = args.adb or find_adb()
    out_path = Path(args.out)
    if not out_path.is_absolute():
        out_path = ROOT / out_path
    out_path.parent.mkdir(parents=True, exist_ok=True)

    deadline = time.monotonic() + args.duration_min * 60
    samples: list[dict] = []
    print(f"soak monitor: pkg={args.pkg} interval={args.interval}s duration={args.duration_min}min")
    while time.monotonic() < deadline:
        ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        mem_text = adb(adb_path, ["shell", f"dumpsys meminfo {args.pkg}"])
        therm_text = adb(adb_path, ["shell", "dumpsys thermalservice"])
        batt_text = adb(adb_path, ["shell", "dumpsys battery"])
        temp = None
        m = re.search(r"temperature:\s*(\d+)", batt_text)
        if m:
            temp = int(m.group(1)) / 10.0
        level = None
        m = re.search(r"level:\s*(\d+)", batt_text)
        if m:
            level = int(m.group(1))
        sample = {
            "timestamp_utc": ts,
            "meminfo": parse_meminfo(mem_text, args.pkg),
            "thermal_buckets": parse_thermal(therm_text),
            "thermal_summary": "; ".join(x for x in therm_text.splitlines() if "temperatures" in x or "Status" in x)[:400],
            "battery_temperature_c": temp,
            "battery_level": level,
        }
        samples.append(sample)
        print(json.dumps(sample, ensure_ascii=False))
        time.sleep(args.interval)

    all_buckets = sorted({b for s in samples for b in s["thermal_buckets"]})
    passes = "CRITICAL" not in all_buckets and "EMERGENCY" not in all_buckets
    report = {
        "report_date": "2026-09-29",
        "tool": "v3nano-mobile/host/device_soak_monitor.py",
        "package": args.pkg,
        "interval_seconds": args.interval,
        "duration_minutes_requested": args.duration_min,
        "n_samples": len(samples),
        "thermal_buckets_seen": all_buckets,
        "thermal_pass_no_critical": passes,
        "samples": samples,
        "note": "soak app chạy qua intent extra loops (--ei loops 12); tool này chỉ poll ngoài, "
                "không điều khiển app.",
    }
    out_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"monitor done: {out_path}")


if __name__ == "__main__":
    main()