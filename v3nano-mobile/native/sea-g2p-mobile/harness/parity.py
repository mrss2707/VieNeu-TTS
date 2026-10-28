"""Parity: Python sea_g2p (reference) vs a C-ABI harness binary, per corpus line.
usage: parity.py <g2p_cli_binary> <dict.bin> [extra args to cli...]"""
import json, subprocess, sys, glob, pathlib
ROOT = pathlib.Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "src"))
from vieneu_utils.phonemize_text import phonemize_text_with_emotions  # noqa
cli, dictp = sys.argv[1], sys.argv[2]
lines = []
for f in ["smoke", "stress", "audiobook_short"]:
    for l in open(ROOT / "v3nano-mobile/eval/corpus" / f"{f}.jsonl", encoding="utf-8"):
        t = json.loads(l)["text"].replace("\n", " ").strip()
        if t: lines.append(t)
# extra: chunked long text + mixed english
lines += ["Hôm nay tôi dùng Facebook và email để gửi deadline cho team marketing.",
          "Ngày 12/03/2024 anh ấy trả 1.500.000đ cho 3kg gạo, tăng 15% so với năm ngoái.",
          "Liên hệ support@vieneu.ai hoặc truy cập https://vieneu.ai/docs nhé."]
ref = [phonemize_text_with_emotions(t) for t in lines]
p = subprocess.run([cli, dictp] + sys.argv[3:], input="\n".join(lines) + "\n",
                   capture_output=True, text=True, encoding="utf-8")
got = p.stdout.split("\n")[:len(lines)]
print(p.stderr.strip())
bad = [(i, lines[i], ref[i], got[i]) for i in range(len(lines)) if ref[i] != got[i]]
print(f"lines={len(lines)} mismatches={len(bad)}")
for i, t, r, g in bad[:5]:
    print(f"--[{i}] {t[:80]}\n  py : {r[:120]}\n  cli: {g[:120]}")
sys.exit(1 if bad else 0)
