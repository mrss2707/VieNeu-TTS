"""Xuất input tensor (ids/spk/style) cho TOÀN BỘ smoke.jsonl + stress.jsonl, để harness
Android/iOS chạy hết corpus thay vì 1 câu hard-code như vòng 2.

Cấu trúc output (dùng chung cho cả 2 harness, copy thủ công vào assets/resources):

    <out_dir>/
      global_meta.json          # bos_id, eos_id, pad_id, flow_fps, sample_rate, steps, cfg
      null_spk.bin, null_style.bin
      voices/<voice_name>/spk.bin, style.bin
      cases/<corpus>_<id>/chunks_meta.json     # {"n_chunks": N, "gaps": [...]}, gaps[i] là
                                                # ranh giới GIỮA chunk i và i+1 ("para"/"sentence"/"minor")
      cases/<corpus>_<id>/chunk<k>/ids.bin, meta.json   (meta: text, ids_len — của RIÊNG chunk k)
      cases_index.json          # danh sách toàn bộ case id, để harness lặp qua

Câu ngắn hơn max_chars=140 (đa số corpus) vẫn ra đúng 1 chunk ("chunk0") — cấu trúc không đổi
hành vi cho các case đó. Câu dài hơn 140 ký tự (vd s07, st17) được CHIA CHUNK Ở ĐÂY, đúng như
V3NanoVieNeuTTS.infer() làm (normalize_to_chunks_v3_with_gaps) — trước bản vá này, cả harness
Android/iOS gọi thẳng OnnxV3NanoEngine.infer() cho nguyên câu dài, bỏ qua bước chia chunk +
join_audio_chunks(silence_ps=...) mà pipeline thật luôn làm, gây hiện tượng "nuốt chữ" không
liên quan gì đến steps/sway (xem docs/_supermeo/_summary/001-v3-nano-mobile.md mục 0.8).

Chạy: uv run --no-sync python v3nano-mobile/host/export_corpus_tensors.py [--steps N] [--sway F] [--out-dir NAME]

--steps/--sway ghi vào global_meta.json để harness Android/iOS đọc và tính đúng lịch trình
Euler warped (xem OnnxV3NanoEngine.infer trong src/vieneu/v3nano.py: tg = u + sway*(cos(pi/2*u)-1+u)).
ids/spk/style không phụ thuộc steps/sway nên không cần export lại — chỉ global_meta.json đổi.
"""
from __future__ import annotations

import argparse
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

# Voice dùng cho harness Android/iOS (multi-provider comparison) — 1 voice cố định để
# so sánh provider không bị nhiễu bởi thay đổi voice đồng thời (đúng §2.1 "không thay
# đổi nhiều biến cùng lúc").
DEFAULT_VOICE = "Minh Quân"


def _safe_name(name: str) -> str:
    return name.replace(" ", "_").replace("(", "").replace(")", "")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--steps", type=int, default=16)
    parser.add_argument("--sway", type=float, default=0.0)
    parser.add_argument("--out-dir", type=str, default="_exported_corpus_tensors")
    args = parser.parse_args()

    from vieneu.v3nano import OnnxV3NanoEngine
    from vieneu_utils.phonemize_text import phonemize_text_with_emotions, normalize_to_chunks_v3_with_gaps

    engine = OnnxV3NanoEngine(local_dir=str(MODEL_DIR), threads=0)
    voices = json.loads((ROOT / "src" / "vieneu" / "assets" / "voices_v3_nano.json").read_text(encoding="utf-8"))

    out_dir = ROOT / "v3nano-mobile" / "host" / args.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "voices").mkdir(exist_ok=True)
    (out_dir / "cases").mkdir(exist_ok=True)

    # constants.npz-derived null conditioning (voice-independent)
    (out_dir / "null_spk.bin").write_bytes(engine.null_spk.astype(np.float32).tobytes())
    (out_dir / "null_style.bin").write_bytes(engine.null_style.astype(np.float32).tobytes())

    preset = voices["presets"][DEFAULT_VOICE]
    spk = np.asarray(preset["speaker_emb"], dtype=np.float32)
    style = np.asarray(preset["style"], dtype=np.float32)
    voice_dir = out_dir / "voices" / _safe_name(DEFAULT_VOICE)
    voice_dir.mkdir(parents=True, exist_ok=True)
    (voice_dir / "spk.bin").write_bytes(spk.tobytes())
    (voice_dir / "style.bin").write_bytes(style.tobytes())

    global_meta = {
        "voice": DEFAULT_VOICE,
        "bos_id": engine.bos, "eos_id": engine.eos, "pad_id": engine.pad,
        "flow_fps": engine.fps,
        "sample_rate": engine.SAMPLE_RATE,
        "max_chunk_seconds": 15.0,
        "steps": args.steps, "cfg": 3.0, "sway": args.sway,
    }
    (out_dir / "global_meta.json").write_text(json.dumps(global_meta, ensure_ascii=False, indent=2), encoding="utf-8")

    corpus_files = {
        "smoke": ROOT / "v3nano-mobile" / "eval" / "corpus" / "smoke.jsonl",
        "stress": ROOT / "v3nano-mobile" / "eval" / "corpus" / "stress.jsonl",
    }

    MAX_CHARS = 140  # khớp mặc định max_chars của V3NanoVieNeuTTS.infer()

    cases_index = []
    multi_chunk_cases = []
    for corpus_name, path in corpus_files.items():
        entries = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
        for entry in entries:
            text = entry["text"]
            case_key = f"{corpus_name}_{entry['id']}"
            case_dir = out_dir / "cases" / case_key
            case_dir.mkdir(parents=True, exist_ok=True)

            if text.strip():
                chunks, gaps = normalize_to_chunks_v3_with_gaps(text, max_chars=MAX_CHARS)
            else:
                chunks, gaps = [], []
            if not chunks:
                # câu rỗng/whitespace: giữ hành vi cũ — 1 "chunk" trống, engine tự xử lý ids=[bos,eos]
                chunks = [text]

            chunks_meta = {"n_chunks": len(chunks), "gaps": gaps}
            (case_dir / "chunks_meta.json").write_text(
                json.dumps(chunks_meta, ensure_ascii=False, indent=2), encoding="utf-8"
            )

            for k, chunk_text in enumerate(chunks):
                chunk_dir = case_dir / f"chunk{k}"
                chunk_dir.mkdir(parents=True, exist_ok=True)
                ph = phonemize_text_with_emotions(chunk_text) if chunk_text.strip() else ""
                ids = engine.encode_phones(ph).astype(np.int64).reshape(-1)
                (chunk_dir / "ids.bin").write_bytes(ids.tobytes())
                chunk_meta = {
                    "corpus": corpus_name,
                    "case_id": entry["id"],
                    "category": entry.get("category"),
                    "chunk_index": k,
                    "text": chunk_text,
                    "ids_len": int(ids.shape[0]),
                }
                (chunk_dir / "meta.json").write_text(
                    json.dumps(chunk_meta, ensure_ascii=False, indent=2), encoding="utf-8"
                )

            cases_index.append(case_key)
            if len(chunks) > 1:
                multi_chunk_cases.append((case_key, len(chunks)))

    (out_dir / "cases_index.json").write_text(
        json.dumps({"cases": cases_index, "count": len(cases_index)}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print(f"Exported {len(cases_index)} cases to {out_dir.relative_to(ROOT)}")
    print(f"Voice used: {DEFAULT_VOICE}, steps={args.steps}, sway={args.sway}")


if __name__ == "__main__":
    main()
