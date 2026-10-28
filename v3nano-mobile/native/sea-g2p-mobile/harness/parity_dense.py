"""Dense parity: random dictionary words (vi/en/common) in 8-word lines; Python reference vs C-ABI binary."""
import sys, random, subprocess, pathlib
ROOT = pathlib.Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "src")); sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "src-upstream/scripts"))
import seap
from vieneu_utils.phonemize_text import phonemize_text_with_emotions
cli, seap_bin, dictp, nlines = sys.argv[1], pathlib.Path(sys.argv[2]), sys.argv[3], int(sys.argv[4])
merged, common, _ = seap.load_bin(seap_bin)
rnd = random.Random(7)
keys = [k for k in merged if k.isalpha()]
en = [k for k in keys if merged[k].startswith("<en>")]; vi = [k for k in keys if not merged[k].startswith("<en>")]
cm = list(common)
lines = []
for i in range(nlines):
    pool = (vi if i % 3 == 0 else en if i % 3 == 1 else vi + en)
    w = [rnd.choice(pool) for _ in range(6)] + [rnd.choice(cm) for _ in range(2)]
    rnd.shuffle(w); lines.append(" ".join(w))
ref = [phonemize_text_with_emotions(l) for l in lines]
p = subprocess.run([cli, dictp, "1"], input="\n".join(lines) + "\n", capture_output=True, text=True, encoding="utf-8")
got = p.stdout.split("\n")[:len(lines)]
bad = [i for i in range(len(lines)) if ref[i] != got[i]]
print(p.stderr.strip()); print(f"lines={len(lines)} words={len(lines)*8} mismatches={len(bad)}")
for i in bad[:3]: print("  ", lines[i][:70], "\n   py :", ref[i][:90], "\n   cli:", got[i][:90])
sys.exit(1 if bad else 0)
