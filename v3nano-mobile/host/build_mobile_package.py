"""Spike S1 — đóng gói P1 preset-only FP16 theo docs/_supermeo/_plan/001-mvp/03-model-package.md
§3.1/§3.2. Layout:

    v3nano-mobile/package/pkg/
    ├── manifest.json                 # schema v1 — xem §3.2
    ├── graphs/{text_encoder,duration_predictor,vector_estimator,codec_decoder}.onnx   # FP16
    ├── assets/
    │   ├── tokenizer/tokenizer.json  # vocab + emotion tags + bos/eos/pad (UTF-8 JSON)
    │   ├── constants.npz             # null_spk/null_style cho CFG (giữ tương thích engine port)
    │   └── voices/
    │       ├── voices_v3_nano_mobile.json      # index 11 voice + default duc_tri
    │       └── <voice_id>/{speaker_emb.bin, style.bin}   # 192 floats + 50×256 floats (LE float32)
    ├── checksums.txt                 # sha256 mọi file nội bộ
    └── LICENSE                       # attribution inventory stub (§3.5)

Open question 1 của plan (vo_codes.bin vs tokenizer.json): CHỐT dùng `assets/tokenizer/tokenizer.json`
(là cấu trúc UTF-8 JSON tự mô tả — schema version + vocab + emotion tags + bos/eos/pad),
dễ đọc từ SDK Kotlin thuần JVM hơn blob nhị phân không tự mô tả. Ghi khoá `tokenizer`
trong `manifest.input_contract` để schema v1 đóng băng naming này.

Không commit: dir `v3nano-mobile/package/` có .gitignore chặn *.onnx + fp16/ + pkg/.

Chạy: uv run --no-sync python v3nano-mobile/host/build_mobile_package.py
"""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
import tarfile
import tempfile
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
BASE_MODEL_REVISION = "aba295eb96a6fa6003ebe417cc1f2802a7adc1dc"
FP16_DIR = ROOT / "v3nano-mobile" / "package" / "fp16"
PKG_DIR = ROOT / "v3nano-mobile" / "package" / "pkg"

GRAPH_FILES = [
    "text_encoder.onnx",
    "duration_predictor.onnx",
    "vector_estimator.onnx",
    "codec_decoder.onnx",
]

# Voice id (ASCII slug) -> display name — là khoá ngoài tới presets của model gốc.
VOICE_SLUGS = {
    "Adam": "adam",
    "Ái Hân": "ai_han",
    "Mỹ Duyên": "my_duyen",
    "Đức Trí": "duc_tri",
    "Hữu Quân": "huu_quan",
    "Xuân Tiên": "xuan_tien",
    "Mai Anh": "mai_anh",
    "Trúc Ly": "truc_ly",
    "Anh Khôi": "anh_khoi",
    "Minh Quân": "minh_quan",
    "Mạnh Dũng": "manh_dung",
}

STYLE_HINTS = {
    "Phong cách tin tức": "news",
    "Phong cách đọc truyện": "story",
    "Phong cách kể chuyện": "storytelling",
    "Phong cách tự nhiên": "natural",
}

WER_REFERENCE_REPORT = (
    ROOT / "v3nano-mobile" / "eval" / "reports"
    / "2026-09-28-macos-arm64-asr-quality-steps8-allvoices.json"
)

# Quality tier theo 03-model-package.md §3.3: tier 1 < 6%, tier 2 < 10%, tier 3 còn lại.
def quality_tier(wer: float) -> int:
    return 1 if wer < 0.06 else (2 if wer < 0.10 else 3)


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def git_head() -> str:
    try:
        out = subprocess.run(
            ["git", "-C", str(ROOT), "rev-parse", "HEAD"],
            check=True, capture_output=True, text=True,
        ).stdout.strip()
        return out
    except Exception:
        return "unknown"


def style_hint_for(description: str) -> str:
    for key, hint in STYLE_HINTS.items():
        if key in description:
            return hint
    return "natural"


def main():
    import onnx
    import onnxruntime

    parser = argparse.ArgumentParser()
    parser.add_argument("--fp16-dir", type=str, default=str(FP16_DIR))
    parser.add_argument("--out-dir", type=str, default=str(PKG_DIR))
    parser.add_argument("--model-dir", type=str, default=str(MODEL_DIR))
    args = parser.parse_args()

    fp16_dir = Path(args.fp16_dir)
    model_dir = Path(args.model_dir)
    out_dir = Path(args.out_dir)

    for d, what in ((fp16_dir, "FP16 dir"), (model_dir, "model dir")):
        if not d.is_dir():
            raise SystemExit(f"{what} not found: {d} (chạy v3nano-mobile/host/convert_fp16.py trước)")

    if out_dir.exists():
        import shutil
        shutil.rmtree(out_dir)
    (out_dir / "graphs").mkdir(parents=True)
    (out_dir / "assets" / "tokenizer").mkdir(parents=True)
    (out_dir / "assets" / "voices").mkdir(parents=True)

    # ── graphs: copy 4 FP16 files ──────────────────────────────────────────────
    graphs = {}
    for name in GRAPH_FILES:
        src = fp16_dir / name
        if not src.is_file():
            raise SystemExit(f"FP16 graph missing: {src}")
        dst = out_dir / "graphs" / name
        dst.write_bytes(src.read_bytes())
        graphs[name.replace(".onnx", "")] = {
            "file": f"graphs/{name}",
            "sha256": sha256_of(dst),
            "size_bytes": dst.stat().st_size,
        }

    # ── tokenizer.json (open question 1: CHỐT định dạng này, không dùng vo_codes.bin) ──
    cfg = json.loads((model_dir / "config.json").read_text(encoding="utf-8"))
    tokenizer = {
        "schema_version": "1",
        "format": "utf8-json",
        "bos_id": int(cfg["bos_id"]),
        "eos_id": int(cfg["eos_id"]),
        "pad_id": int(cfg["pad_id"]),
        "emotion_tags": dict(cfg.get("emotion_tags", {})),
        "vocab": dict(cfg["vocab"]),
        "flow_fps": float(cfg.get("flow_fps", 24000 / 256 / 6)),
        "sample_rate": int(cfg.get("sample_rate", 24000)),
        "provenance": f"pnnbao-ump/VieNeu-TTS-v3-Nano@{BASE_MODEL_REVISION} config.json",
    }
    tok_path = out_dir / "assets" / "tokenizer" / "tokenizer.json"
    tok_path.write_text(json.dumps(tokenizer, ensure_ascii=False, indent=2), encoding="utf-8")

    # null conditioning cho CFG — engine port cần null_spk/null_style (small file)
    (out_dir / "assets" / "constants.npz").write_bytes((model_dir / "constants.npz").read_bytes())

    # ── voices: index + per-voice conditioning files ──────────────────────────
    voices_src = json.loads((ROOT / "src" / "vieneu" / "assets" / "voices_v3_nano.json").read_text(encoding="utf-8"))
    wer_ref = {}
    if WER_REFERENCE_REPORT.is_file():
        rep = json.loads(WER_REFERENCE_REPORT.read_text(encoding="utf-8"))
        for name, s in (rep.get("by_voice") or {}).items():
            wer_ref[name] = s.get("wer", {}).get("mean")

    voices_index = []
    for name, preset in voices_src["presets"].items():
        slug = VOICE_SLUGS[name]
        vdir = out_dir / "assets" / "voices" / slug
        vdir.mkdir(parents=True, exist_ok=True)
        spk = np.asarray(preset["speaker_emb"], dtype=np.float32).reshape(-1)
        style = np.asarray(preset["style"], dtype=np.float32)
        spk_path = vdir / "speaker_emb.bin"
        style_path = vdir / "style.bin"
        spk_path.write_bytes(spk.astype("<f4", copy=False).tobytes())
        style_path.write_bytes(style.astype("<f4", copy=False).tobytes())
        combined = spk.astype("<f4", copy=False).tobytes() + style.astype("<f4", copy=False).tobytes()
        comb_sha = hashlib.sha256(combined).hexdigest()
        wer = wer_ref.get(name)
        voices_index.append({
            "voice_id": slug,
            "display_name": name,
            "locale": "vi-VN",
            "gender": preset.get("gender", ""),
            "style_hint": style_hint_for(preset.get("description", "")),
            "quality_tier": quality_tier(wer) if wer is not None else None,
            "files": {
                "speaker_emb": f"voices/{slug}/speaker_emb.bin",
                "style": f"voices/{slug}/style.bin",
            },
            "sha256": comb_sha,
            "wer_reference": round(wer, 4) if wer is not None else None,
            "license": "repo",
            "provenance": "official",
        })

    voice_index_path = out_dir / "assets" / "voices" / "voices_v3_nano_mobile.json"
    voice_index_path.write_text(json.dumps(
        {
            "schema_version": "1",
            "default_voice_id": "duc_tri",   # D8: chốt giọng mặc định (spike kết luận)
            "voices": voices_index,
        },
        ensure_ascii=False, indent=2,
    ), encoding="utf-8")

    # ── manifest.json (schema v1 — mẫu 03-model-package.md §3.2) ───────────────
    manifest = {
        "schema_version": "1",
        "model_id": "v3-nano-mobile",
        "model_family": "vieuneu-tts",
        "model_revision": "0.1.0",
        "base_model_revision": BASE_MODEL_REVISION,
        "main_base_commit": git_head(),
        "precision": "fp16",
        "export_toolchain": (
            f"python {sys.version.split()[0]} / onnx {onnx.__version__} / "
            f"onnxruntime.transformers.float16 / ort {onnxruntime.__version__}"
        ),
        "supported_abi": ["arm64-v8a", "arm64e"],
        "min_sdk": {"android": 26, "ios": "15.0"},
        "sample_rate": 24000,
        "graphs": graphs,
        "input_contract": {
            "ctx_mask_dtype": "bool",
            "voice_speaker_emb_dim": 192,
            "voice_style_shape": [50, 256],
            "tokenizer": "assets/tokenizer/tokenizer.json",
        },
        "generation_defaults": {"steps": 8, "sway": -1.0, "cfg": 3.0, "max_chars_per_chunk": 140},
        "compatibility": {
            "runtime_revision_min": "ort-mobile-1.22",
            "sdk_revision_min": "0.1.0",
        },
        "release": {
            "released_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "channel": "beta",
            "rollback_to": "0.0.9",
            "known_limitations": [
                # D2 (00-tong-quan.md §0.11): NNAPI p95 vượt target trên thiết bị đã đo.
                "NNAPI p95 vượt target trên thiết bị đã đo — không chọn làm mặc định",
                # D3 (00-tong-quan.md §0.11): CoreML không tương thích vector_estimator.
                "CoreML không tương thích vector_estimator (Metal texture limit) — chờ static-shape graph",
            ],
        },
    }
    manifest_path = out_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

    # ── LICENSE stub (§3.5 — inventory trước khi phân phối, kể cả beta) ────────
    license_text = """VieNeu-TTS v3 Nano Mobile package — attribution inventory (03-model-package.md §3.5)

| Artifact                        | Nguồn                                          | License                 | Được phân phối?        |
|---------------------------------|------------------------------------------------|-------------------------|------------------------|
| 4 graph ONNX (FP16 từ v3 Nano)  | HF pnnbao-ump/VieNeu-TTS-v3-Nano (pin revision)| ghi theo mô hình gốc    | cần xác nhận lại legal |
| tokenizer.json (vocab/emotion)  | config.json của repo mô hình trên              | theo repo               | cần xác nhận           |
| 11 voice asset (emb + style)    | src/vieneu/assets/voices_v3_nano.json          | theo repo               | cần xác nhận           |
| ONNX Runtime Mobile             | download.onnxruntime.ai                        | MIT                     | OK                    |

Điều kiện trước khi phân phối BẤT KỲ artifact nào (kể cả beta): hoàn tất cột
license + kèm attribution text tương ứng. File này là stub — CHƯA phải inventory
hoàn chỉnh.
"""
    (out_dir / "LICENSE").write_text(license_text, encoding="utf-8")

    # ── kích thước: raw + gzip (proxy đóng gói store; AAB/Play compression đo sau) ──
    # build_report.json ghi TRƯỚC checksums.txt để mọi file trong gói đều có checksum.
    raw_total = sum(p.stat().st_size for p in out_dir.rglob("*") if p.is_file())
    with tempfile.NamedTemporaryFile(suffix=".tar.gz", delete=False) as tf:
        tmp_path = Path(tf.name)
    try:
        with tarfile.open(tmp_path, "w:gz") as tar:
            tar.add(out_dir, arcname=out_dir.name)
        gz_size = tmp_path.stat().st_size
    finally:
        tmp_path.unlink(missing_ok=True)

    summary = {
        "package_dir": str(out_dir.relative_to(ROOT)),
        "manifest": "schema v1",
        "graphs_fp16_sha256": graphs,
        "n_voices": len(voices_index),
        "default_voice_id": "duc_tri",
        "size_raw_bytes": raw_total,
        "size_tar_gz_bytes": gz_size,
        "gzip_within_150mb_target": gz_size <= 150 * 1024 * 1024,
        "note": "AAB/Play/App Store compression chưa đo (chưa có bundletool) — báo raw+gzip, đo store sau.",
    }
    (out_dir / "build_report.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

    # ── checksums.txt: sha256 mọi file nội bộ (trừ chính checksums.txt) ───────
    lines = []
    for p in sorted(x for x in out_dir.rglob("*") if x.is_file() and x.name != "checksums.txt"):
        lines.append(f"{sha256_of(p)}  {p.relative_to(out_dir)}")
    (out_dir / "checksums.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"Package built: {out_dir.relative_to(ROOT)}")


if __name__ == "__main__":
    main()