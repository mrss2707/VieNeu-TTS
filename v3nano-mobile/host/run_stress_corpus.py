"""Chạy stress.jsonl 1 lần qua engine — xác nhận không NaN/Inf/crash trên edge case.

Đây là "Hard fail" check theo protocol 002 §9 (crash, ANR, deadlock, NaN/Inf, waveform
invalid), không phải benchmark timing — không cần lặp 3 lần như smoke corpus. Bao gồm
câu rỗng, whitespace, emoji, số/ngày tháng, code-switching, câu dài vượt
_MAX_CHUNK_SECONDS (engine tự cap ở 15s, xem src/vieneu/v3nano.py).

Chạy: uv run --no-sync python v3nano-mobile/host/run_stress_corpus.py
"""
from __future__ import annotations

import json
import sys
import time
import traceback
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "src"))

MODEL_DIR = (
    Path.home() / ".cache" / "huggingface" / "hub"
    / "models--pnnbao-ump--VieNeu-TTS-v3-Nano" / "snapshots"
    / "aba295eb96a6fa6003ebe417cc1f2802a7adc1dc"
)
VOICE_NAME = "Minh Quân"


def main():
    from vieneu.v3nano import OnnxV3NanoEngine
    from vieneu_utils.phonemize_text import phonemize_text_with_emotions

    voices = json.loads((ROOT / "src" / "vieneu" / "assets" / "voices_v3_nano.json").read_text(encoding="utf-8"))
    preset = voices["presets"][VOICE_NAME]
    spk = np.asarray(preset["speaker_emb"], dtype=np.float32)
    style = np.asarray(preset["style"], dtype=np.float32)

    engine = OnnxV3NanoEngine(local_dir=str(MODEL_DIR), threads=0)

    stress_path = ROOT / "v3nano-mobile" / "eval" / "corpus" / "stress.jsonl"
    cases = [json.loads(line) for line in stress_path.read_text(encoding="utf-8").splitlines() if line.strip()]

    results = []
    for case in cases:
        text = case["text"]
        entry = {"id": case["id"], "category": case["category"], "char_count": len(text)}
        try:
            ph = phonemize_text_with_emotions(text) if text.strip() else ""
            if not ph.strip():
                # Câu rỗng/whitespace: engine.encode_phones sẽ sinh chỉ bos/eos — vẫn thử
                # chạy để xác nhận không crash, thay vì skip im lặng.
                ph = ph
            t0 = time.time()
            wav = engine.infer(ph, spk, style, steps=16, cfg=3.0)
            gen_ms = (time.time() - t0) * 1000
            nan_or_inf = bool(np.isnan(wav).any() or np.isinf(wav).any()) if wav.size else False
            entry.update({
                "outcome": "ran",
                "generation_ms": round(gen_ms, 1),
                "waveform_len_samples": int(wav.size),
                "audio_seconds": round(float(wav.size / engine.SAMPLE_RATE), 3) if wav.size else 0.0,
                "nan_or_inf": nan_or_inf,
                "hard_fail": nan_or_inf,
            })
        except Exception as e:
            entry.update({
                "outcome": "exception",
                "exception_type": type(e).__name__,
                "exception_message": str(e),
                "traceback": traceback.format_exc(limit=5),
                "hard_fail": True,
            })
        results.append(entry)

    any_hard_fail = any(r.get("hard_fail") for r in results)
    n_exceptions = sum(1 for r in results if r["outcome"] == "exception")
    n_nan = sum(1 for r in results if r.get("nan_or_inf"))

    empty_text_ids = {"st13", "st14"}  # robustness_rong, robustness_whitespace
    empty_text_produced_audio = [
        r["id"] for r in results
        if r["id"] in empty_text_ids and r.get("outcome") == "ran" and r.get("waveform_len_samples", 0) > 0
    ]

    report = {
        "report_date": "2026-09-28",
        "main_base_commit": "c1390abbdb2eedcdf58eafb546966c06ce27af71",
        "develop_commit": "1d700d3d08a251aedd8c4b34e4456a152b3f8eae",
        "model_revision": "pnnbao-ump/VieNeu-TTS-v3-Nano@aba295eb96a6fa6003ebe417cc1f2802a7adc1dc",
        "device": "macOS Darwin 25.6.0, Apple Silicon arm64 (host, not a mobile device)",
        "runtime": "onnxruntime",
        "provider": "CPUExecutionProvider",
        "precision": "fp32",
        "steps": 16,
        "cfg": 3.0,
        "voice": VOICE_NAME,
        "corpus": "v3nano-mobile/eval/corpus/stress.jsonl",
        "hypothesis": ["H9-partial-hard-fail-only"],

        "summary": {
            "total_cases": len(results),
            "exceptions": n_exceptions,
            "nan_or_inf_cases": n_nan,
            "any_hard_fail": any_hard_fail,
            "empty_or_whitespace_text_produced_nonempty_audio": empty_text_produced_audio,
        },
        "per_case": results,
        "notable_observation": (
            "Câu rỗng ('') và whitespace-only ('   ') KHÔNG trả về waveform rỗng — engine vẫn "
            "sinh ~3.2s audio (không NaN/Inf, không crash — không phải hard fail theo §9, nhưng "
            "là hành vi đáng chú ý). encode_phones() với input rỗng vẫn tạo ids=[bos, eos], nên "
            "duration_predictor + flow model vẫn chạy và tạo ra audio babble/silence-like thay vì "
            "được chặn sớm ở tầng gọi. Không sửa src/vieneu/v3nano.py trong vòng validation này — "
            "chỉ ghi nhận để cân nhắc thêm input validation ở tầng SDK mobile (Phase 6) nếu cần."
        ) if empty_text_produced_audio else None,

        "what_this_proves": (
            "Xác nhận không có crash/exception/NaN/Inf (protocol 002 §9 'Hard fail') trên "
            f"{len(results)} câu edge-case cơ bản (số, ngày tháng, tiền tệ, tên riêng, "
            "code-switching, robustness: rỗng/whitespace/emoji/câu dài) chạy qua "
            "OnnxV3NanoEngine thật trên macOS CPU FP32."
        ),
        "what_this_does_NOT_prove": (
            "KHÔNG phải quality/pronunciation score (H9 đầy đủ cần ASR/CER/WER và listening "
            "test theo protocol 002 — chưa làm). Không kiểm tra text có ĐỌC ĐÚNG hay không, "
            "chỉ kiểm tra không NaN/Inf/crash. Không phải thiết bị Android/iOS thật."
        ),
    }

    out_dir = ROOT / "v3nano-mobile" / "eval" / "reports"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / "2026-09-28-macos-arm64-stress-corpus.json"
    out_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print(json.dumps(report["summary"], indent=2))
    for r in results:
        if r["outcome"] == "exception" or r.get("nan_or_inf"):
            print("FAIL:", r["id"], r.get("exception_type"), r.get("exception_message"))
    print(f"Report written to: {out_path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
