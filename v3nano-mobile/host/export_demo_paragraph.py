"""Export phone IDs cho demo paragraph của UI iOS (nút “Đọc đoạn văn”).

Chạy (cần .venv + model HF snapshot):
  .venv/bin/python v3nano-mobile/host/export_demo_paragraph.py

Output: v3nano-mobile/ios/device-validation/DeviceValidation/nano_test_input/demo/
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "src"))

MODEL_DIR = (
    Path.home() / ".cache" / "huggingface" / "hub"
    / "models--pnnbao-ump--VieNeu-TTS-v3-Nano" / "snapshots"
    / "aba295eb96a6fa6003ebe417cc1f2802a7adc1dc"
)

DEMO_TEXT = (
    "Năm mười lăm tuổi, Tiêu Viêm từng là thiên tài trẻ nhất của gia tộc Tiêu. "
    "Thế nhưng, chỉ sau một đêm, đấu khí trong cơ thể hắn đột nhiên biến mất không dấu vết. "
    "Bốn năm sau, thiếu niên năm nào đứng giữa quảng trường, mặc cho ánh mắt chế giễu của tộc nhân, "
    "hắn siết chặt nắm đấm — nhất định phải lấy lại tất cả những gì đã mất."
)
VOICE_KEY = "Mỹ Duyên"
VOICE_DIR_NAME = "My_Duyen"


def main() -> None:
    from vieneu.v3nano import OnnxV3NanoEngine
    from vieneu_utils.phonemize_text import (
        normalize_to_chunks_v3_with_gaps,
        phonemize_text_with_emotions,
    )

    engine = OnnxV3NanoEngine(local_dir=str(MODEL_DIR), threads=0)
    voices = json.loads(
        (ROOT / "src" / "vieneu" / "assets" / "voices_v3_nano.json").read_text(encoding="utf-8")
    )
    preset = voices["presets"][VOICE_KEY]

    out = (
        ROOT
        / "v3nano-mobile"
        / "ios"
        / "device-validation"
        / "DeviceValidation"
        / "nano_test_input"
        / "demo"
    )
    (out / "voices" / VOICE_DIR_NAME).mkdir(parents=True, exist_ok=True)
    (out / "cases" / "demo_para").mkdir(parents=True, exist_ok=True)

    (out / "null_spk.bin").write_bytes(engine.null_spk.astype(np.float32).tobytes())
    (out / "null_style.bin").write_bytes(engine.null_style.astype(np.float32).tobytes())
    spk = np.asarray(preset["speaker_emb"], dtype=np.float32)
    style = np.asarray(preset["style"], dtype=np.float32)
    (out / "voices" / VOICE_DIR_NAME / "spk.bin").write_bytes(spk.tobytes())
    (out / "voices" / VOICE_DIR_NAME / "style.bin").write_bytes(style.tobytes())

    global_meta = {
        "voice": VOICE_KEY,
        "bos_id": int(engine.bos),
        "eos_id": int(engine.eos),
        "pad_id": int(engine.pad),
        "flow_fps": engine.fps,
        "sample_rate": engine.SAMPLE_RATE,
        "max_chunk_seconds": 15.0,
        "steps": 8,
        "cfg": 3.0,
        "sway": -1.0,
    }
    (out / "global_meta.json").write_text(
        json.dumps(global_meta, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    chunks, gaps = normalize_to_chunks_v3_with_gaps(DEMO_TEXT, max_chars=140)
    if not chunks:
        chunks, gaps = [DEMO_TEXT], []
    case_dir = out / "cases" / "demo_para"
    (case_dir / "chunks_meta.json").write_text(
        json.dumps({"n_chunks": len(chunks), "gaps": gaps}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    for k, chunk_text in enumerate(chunks):
        cd = case_dir / f"chunk{k}"
        cd.mkdir(exist_ok=True)
        ph = phonemize_text_with_emotions(chunk_text) if chunk_text.strip() else ""
        ids = engine.encode_phones(ph).astype(np.int64).reshape(-1)
        (cd / "ids.bin").write_bytes(ids.tobytes())
        (cd / "meta.json").write_text(
            json.dumps(
                {
                    "corpus": "demo",
                    "case_id": "demo_para",
                    "category": "ui_demo",
                    "chunk_index": k,
                    "text": chunk_text,
                    "ids_len": int(ids.shape[0]),
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
        print(f"chunk{k} ids={len(ids)}")
    (out / "paragraph.txt").write_text(DEMO_TEXT, encoding="utf-8")
    print(f"demo -> {out.relative_to(ROOT)}  n_chunks={len(chunks)} gaps={gaps}")


if __name__ == "__main__":
    main()
