"""Export voice conditioning (spk.bin + style.bin) cho mobile / demo.

Một giọng v3 Nano = speaker_emb [192] + style [50, 256] (LE float32).
Tool này sinh 2 file đó từ clip tham chiếu HOẶC từ preset có sẵn,
kèm index JSON để SDK/package tra cứu.

Chạy (cần .venv + model HF snapshot):

  # từ clip 3–8 s của người nói
  .venv/bin/python v3nano-mobile/host/export_voice_bins.py \\
      --audio /path/ref.wav --name "Lan Anh" \\
      --description "Nữ · Bắc · Phong cách tự nhiên" --gender female

  # từ preset đã có (không cần audio)
  .venv/bin/python v3nano-mobile/host/export_voice_bins.py \\
      --from-preset "Mỹ Duyên"

  # copy thẳng vào demo iOS + Android
  .venv/bin/python v3nano-mobile/host/export_voice_bins.py \\
      --from-preset "Mỹ Duyên" --install-demo My_Duyen

Output mặc định: v3nano-mobile/package/voices_out/<slug>/{spk.bin,style.bin}
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import unicodedata
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "src"))

MODEL_DIR = (
    Path.home() / ".cache" / "huggingface" / "hub"
    / "models--pnnbao-ump--VieNeu-TTS-v3-Nano" / "snapshots"
    / "aba295eb96a6fa6003ebe417cc1f2802a7adc1dc"
)

SPK_DIM = 192
STYLE_SHAPE = (50, 256)

DEMO_DIRS = [
    ROOT / "v3nano-mobile" / "ios" / "device-validation" / "DeviceValidation"
    / "nano_test_input" / "demo" / "voices",
    ROOT / "v3nano-mobile" / "android" / "device-validation" / "app" / "src"
    / "main" / "assets" / "nano_test_input" / "demo" / "voices",
]


def slugify(name: str) -> str:
    """'Mỹ Duyên' → 'My_Duyen' (bỏ dấu, underscore)."""
    nfkd = unicodedata.normalize("NFD", name)
    ascii_ = "".join(c for c in nfkd if unicodedata.category(c) != "Mn")
    ascii_ = ascii_.replace("đ", "d").replace("Đ", "D")
    return re.sub(r"[^\w]+", "_", ascii_, flags=re.UNICODE).strip("_")


def style_hint_for(description: str) -> str:
    d = description.lower()
    if "truyện" in d or "kể chuyện" in d or "story" in d:
        return "story"
    if "tin" in d or "news" in d:
        return "news"
    return "natural"


def load_or_encode(args) -> tuple[str, dict, np.ndarray, np.ndarray]:
    if args.from_preset:
        reg = json.loads(
            (ROOT / "src" / "vieneu" / "assets" / "voices_v3_nano.json").read_text(encoding="utf-8")
        )
        if args.from_preset not in reg.get("presets", {}):
            raise SystemExit(
                f"Preset '{args.from_preset}' không có. Có: {list(reg.get('presets', {}))}"
            )
        preset = reg["presets"][args.from_preset]
        name = args.from_preset
        meta = {
            "description": preset.get("description", args.description or ""),
            "gender": preset.get("gender", args.gender or ""),
            "provenance": "official",
        }
        spk = np.asarray(preset["speaker_emb"], dtype=np.float32).reshape(-1)
        style = np.asarray(preset["style"], dtype=np.float32)
    else:
        if not args.audio:
            raise SystemExit("Cần --audio hoặc --from-preset")
        if not args.name:
            raise SystemExit("Cần --name khi dùng --audio")
        from vieneu.v3nano import OnnxV3NanoEngine

        engine = OnnxV3NanoEngine(local_dir=str(MODEL_DIR), threads=0)
        # preclean như encode_reference trên V3NanoVieNeuTTS
        try:
            from vieneu.v3turbo import V3TurboVieNeuTTS

            clean = V3TurboVieNeuTTS._preclean_reference_audio(args.audio)
        except Exception:
            clean = args.audio
        spk, style = engine.prepare_reference(str(clean), denoise=not args.no_denoise)
        spk = np.asarray(spk, dtype=np.float32).reshape(-1)
        style = np.asarray(style, dtype=np.float32)
        name = args.name
        meta = {
            "description": args.description or "",
            "gender": args.gender or "",
            "provenance": "cloned-from-audio",
        }

    if spk.size != SPK_DIM:
        raise SystemExit(f"speaker_emb sai dim: {spk.size} (kỳ vọng {SPK_DIM})")
    if tuple(style.shape) != STYLE_SHAPE:
        if style.size == STYLE_SHAPE[0] * STYLE_SHAPE[1]:
            style = style.reshape(STYLE_SHAPE)
        else:
            raise SystemExit(f"style sai shape: {style.shape} (kỳ vọng {STYLE_SHAPE})")
    return name, meta, spk, style


def write_bins(vdir: Path, spk: np.ndarray, style: np.ndarray) -> dict:
    vdir.mkdir(parents=True, exist_ok=True)
    spk_le = spk.astype("<f4", copy=False)
    style_le = style.astype("<f4", copy=False)
    spk_path = vdir / "spk.bin"
    style_path = vdir / "style.bin"
    spk_path.write_bytes(spk_le.tobytes())
    style_path.write_bytes(style_le.tobytes())
    comb = spk_le.tobytes() + style_le.tobytes()
    return {
        "spk_bytes": spk_path.stat().st_size,
        "style_bytes": style_path.stat().st_size,
        "sha256": hashlib.sha256(comb).hexdigest(),
    }


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--audio", help="clip tham chiếu 3–8 s (cùng người nói)")
    src.add_argument("--from-preset", metavar="NAME", help="lấy embedding từ preset sẵn có")
    ap.add_argument("--name", help="tên giọng mới (bắt buộc với --audio)")
    ap.add_argument("--description", default="", help="vd: Nữ · Bắc · Phong cách tự nhiên")
    ap.add_argument("--gender", default="", choices=["", "male", "female"])
    ap.add_argument("--no-denoise", action="store_true")
    ap.add_argument(
        "--out-dir",
        type=Path,
        default=ROOT / "v3nano-mobile" / "package" / "voices_out",
        help="thư mục gốc ghi <slug>/{spk.bin,style.bin}",
    )
    ap.add_argument(
        "--install-demo",
        metavar="DIR_NAME",
        help="copy vào nano_test_input/demo/voices/<DIR_NAME>/ của iOS + Android demo",
    )
    ap.add_argument(
        "--append-registry",
        action="store_true",
        help="ghi thêm preset vào src/vieneu/assets/voices_v3_nano.json (host SDK)",
    )
    args = ap.parse_args()

    name, meta, spk, style = load_or_encode(args)
    slug = args.install_demo or slugify(name)
    out_vdir = args.out_dir / slug
    info = write_bins(out_vdir, spk, style)

    index_path = args.out_dir / "voices_index.json"
    index = {}
    if index_path.is_file():
        try:
            index = json.loads(index_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            index = {}
    index[name] = {
        "slug": slug,
        "description": meta["description"],
        "gender": meta["gender"],
        "style_hint": style_hint_for(meta["description"]),
        "provenance": meta["provenance"],
        "speaker_emb_dim": SPK_DIM,
        "style_shape": list(STYLE_SHAPE),
        "spk": f"{slug}/spk.bin",
        "style": f"{slug}/style.bin",
        "sha256": info["sha256"],
        "exported_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }
    index_path.write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding="utf-8")

    if args.install_demo:
        for demo_voices in DEMO_DIRS:
            dst = demo_voices / args.install_demo
            write_bins(dst, spk, style)
            print(f"demo → {dst.relative_to(ROOT)}")

    if args.append_registry:
        reg_path = ROOT / "src" / "vieneu" / "assets" / "voices_v3_nano.json"
        data = json.loads(reg_path.read_text(encoding="utf-8"))
        data.setdefault("presets", {})[name] = {
            "description": meta["description"],
            "gender": meta["gender"],
            "ref_seconds": 5.0,
            "speaker_emb": [round(float(x), 6) for x in spk.reshape(-1)],
            "style": [[round(float(x), 5) for x in row] for row in style],
        }
        reg_path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        print(f"registry → {reg_path.relative_to(ROOT)}")

    print(f"voice '{name}' slug={slug}")
    print(f"  spk  {info['spk_bytes']} B  {out_vdir / 'spk.bin'}")
    print(f"  style {info['style_bytes']} B  {out_vdir / 'style.bin'}")
    print(f"  sha256 {info['sha256']}")
    print(f"  index  {index_path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
