"""ASR/WER-CER proxy quality check trên corpus smoke — bắt đầu mở H9 (protocol 002).

KHÔNG phải listening test người thật (một phần yêu cầu chính thức của H9) và ASR bản
thân cũng có sai số nhận dạng riêng — WER/CER ở đây là proxy đo lỗi phát âm/đọc sai,
không phải ground truth tuyệt đối. Chỉ chạy trên macOS CPU (ASR không chạy trên
Android/iOS harness trong vòng này).

faster-whisper + jiwer được cài TẠM vào .venv qua `uv pip install faster-whisper jiwer`
— KHÔNG được thêm vào pyproject.toml/uv.lock (đây là công cụ đo lường validation, không
phải dependency runtime sản phẩm).

Chạy: uv run --no-sync python v3nano-mobile/host/run_asr_quality_check.py [--steps N] [--sway F] [--report-suffix NAME]

Mặc định --steps 16 --sway 0.0 (baseline). Dùng --steps 8 --sway -1.0 để đo quality cho
candidate H4 đã đạt RTF target trên thiết bị thật (vòng 6, xem
docs/_supermeo/_summary/001-v3-nano-mobile.md mục 0.6).

Dùng V3NanoVieNeuTTS.infer() (KHÔNG gọi thẳng OnnxV3NanoEngine.infer()) — bản trước gọi
engine.infer() trực tiếp, bỏ qua bước chia chunk (normalize_to_chunks_v3_with_gaps,
max_chars=140) mà pipeline thật luôn làm, nên câu s07 (217 ký tự, duy nhất trong
smoke.jsonl vượt 140 ký tự) bị "nuốt chữ" giả trong report WER/CER trước đó (xem
docs/_supermeo/_summary/001-v3-nano-mobile.md mục 0.8). watermark tắt (apply_watermark=False)
để giữ nguyên hành vi đo trước đó (không liên quan đến bug chunking).
"""
from __future__ import annotations

import argparse
import json
import sys
import tempfile
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "src"))

MODEL_DIR = (
    Path.home() / ".cache" / "huggingface" / "hub"
    / "models--pnnbao-ump--VieNeu-TTS-v3-Nano" / "snapshots"
    / "aba295eb96a6fa6003ebe417cc1f2802a7adc1dc"
)
ASR_MODEL_NAME = "small"

# 3 voice khác gender/character từ voice_matrix.json — đúng §3.3: "nếu chỉ có một
# voice, không được kết luận... cho toàn bộ preset set".
VOICES_TO_CHECK = ["Minh Quân", "Ái Hân", "Mỹ Duyên"]


def build_text_transform():
    import jiwer
    return jiwer.transforms.Compose([
        jiwer.transforms.ToLowerCase(),
        jiwer.transforms.RemovePunctuation(),
        jiwer.transforms.RemoveMultipleSpaces(),
        jiwer.transforms.Strip(),
        jiwer.transforms.ReduceToListOfListOfWords(),
    ])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--steps", type=int, default=16)
    parser.add_argument("--sway", type=float, default=0.0)
    parser.add_argument("--report-suffix", type=str, default="")
    parser.add_argument("--treble-filter", action="store_true",
                        help="Áp gentle low-pass treble filter (v3nano-mobile/host/apply_treble_filter.py) "
                             "lên audio trước khi đưa vào ASR — đo WER/CER có đổi so với bản thô không.")
    parser.add_argument("--onnx-dir", type=str, default=None,
                        help="Thư mục chứa ONNX models (FP32/FP16). Mặc định: HF snapshot FP32.")
    parser.add_argument("--all-voices", action="store_true",
                        help="Test tất cả 11 preset voices thay vì chỉ 3 mặc định.")
    args = parser.parse_args()

    import soundfile as sf
    import jiwer
    from faster_whisper import WhisperModel

    from vieneu.v3nano import V3NanoVieNeuTTS
    if args.treble_filter:
        from apply_treble_filter import apply_treble_filter

    onnx_dir = args.onnx_dir if args.onnx_dir else str(MODEL_DIR)
    tts = V3NanoVieNeuTTS(onnx_dir=onnx_dir, threads=0, steps=args.steps, cfg=3.0)
    sample_rate = tts.sample_rate

    voices_to_check = VOICES_TO_CHECK
    if args.all_voices:
        voices_to_check = list(tts._preset_voices.keys())
        print(f"Testing all {len(voices_to_check)} preset voices: {voices_to_check}")

    smoke_path = ROOT / "v3nano-mobile" / "eval" / "corpus" / "smoke.jsonl"
    cases = [json.loads(line) for line in smoke_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    # Bỏ câu quá ngắn (<3 từ) — ASR/WER không ổn định trên câu 1-2 từ, và protocol 002
    # cũng không yêu cầu đo WER cho câu "Xin chào." kiểu smoke rất ngắn.
    cases = [c for c in cases if len(c["text"].split()) >= 3]

    print(f"Loading faster-whisper '{ASR_MODEL_NAME}' (CPU, int8)...")
    asr = WhisperModel(ASR_MODEL_NAME, device="cpu", compute_type="int8")

    transform = build_text_transform()

    all_results = []
    with tempfile.TemporaryDirectory() as tmpdir:
        for voice_name in voices_to_check:
            for case in cases:
                text = case["text"]
                wav = tts.infer(text, voice=voice_name, steps=args.steps, sway=args.sway,
                                 apply_watermark=False)
                if wav.size == 0:
                    continue
                if args.treble_filter:
                    wav = apply_treble_filter(wav, sample_rate)

                wav_path = Path(tmpdir) / f"{voice_name}_{case['id']}.wav"
                sf.write(str(wav_path), wav, sample_rate)

                segments, _info = asr.transcribe(str(wav_path), language="vi")
                transcript = " ".join(s.text for s in segments).strip()

                try:
                    wer = jiwer.wer(text, transcript, reference_transform=transform, hypothesis_transform=transform)
                    cer = jiwer.cer(text, transcript)
                except ValueError:
                    # transcript rỗng (ASR không nhận ra gì) -> WER/CER = 1.0 (toàn bộ sai)
                    wer, cer = 1.0, 1.0

                all_results.append({
                    "voice": voice_name,
                    "case_id": case["id"],
                    "category": case.get("category"),
                    "original_text": text,
                    "asr_transcript": transcript,
                    "wer": round(wer, 4),
                    "cer": round(cer, 4),
                    "audio_seconds": round(float(wav.size / sample_rate), 3),
                })

    def summarize(key: str, subset=None):
        vals = [r[key] for r in (subset or all_results)]
        if not vals:
            return None
        return {
            "mean": round(sum(vals) / len(vals), 4),
            "min": round(min(vals), 4),
            "max": round(max(vals), 4),
            "n": len(vals),
        }

    by_voice = {}
    for voice_name in voices_to_check:
        subset = [r for r in all_results if r["voice"] == voice_name]
        by_voice[voice_name] = {"wer": summarize("wer", subset), "cer": summarize("cer", subset)}

    by_category = {}
    for cat in sorted(set(r["category"] for r in all_results)):
        subset = [r for r in all_results if r["category"] == cat]
        by_category[cat] = {"wer": summarize("wer", subset), "cer": summarize("cer", subset)}

    worst_cases = sorted(all_results, key=lambda r: -r["wer"])[:10]

    report = {
        "report_date": "2026-09-28",
        "main_base_commit": "c1390abbdb2eedcdf58eafb546966c06ce27af71",
        "develop_commit": "1d700d3d08a251aedd8c4b34e4456a152b3f8eae",
        "model_revision": "pnnbao-ump/VieNeu-TTS-v3-Nano@aba295eb96a6fa6003ebe417cc1f2802a7adc1dc",
        "asr_model": f"Systran/faster-whisper-{ASR_MODEL_NAME} (ctranslate2, int8, CPU)",
        "jiwer_version": jiwer.__version__ if hasattr(jiwer, "__version__") else "unknown",
        "device": "macOS Darwin 25.6.0, Apple Silicon arm64 (host, not a mobile device)",
        "corpus": "v3nano-mobile/eval/corpus/smoke.jsonl (câu >=3 từ)",
        "voices_tested": voices_to_check,
        "steps": args.steps, "cfg": 3.0, "sway": args.sway,
        "hypothesis": ["H9-partial-asr-proxy", "H4-quality-check"] if args.steps != 16 else ["H9-partial-asr-proxy"],

        "overall": {"wer": summarize("wer"), "cer": summarize("cer")},
        "by_voice": by_voice,
        "by_category": by_category,
        "worst_10_cases_for_manual_audit": [
            {k: c[k] for k in ["voice", "case_id", "category", "original_text", "asr_transcript", "wer", "cer"]}
            for c in worst_cases
        ],
        "per_case": all_results,

        "what_this_proves": (
            f"WER/CER thật (không phải hard-fail check) trên {len(cases)} câu smoke x "
            f"{len(voices_to_check)} voice ({len(all_results)} data point), steps={args.steps} "
            f"sway={args.sway}, dùng faster-whisper small làm ASR proxy."
        ),
        "what_this_does_NOT_prove": (
            "KHÔNG phải listening test người thật — một phần yêu cầu chính thức của H9 theo "
            "protocol 002 (đánh giá speaker consistency, pause duration, pitch/energy jump, "
            "human preference score không đo được bằng ASR). ASR bản thân có sai số nhận dạng "
            "(WER/CER cao có thể do ASR nghe nhầm, không hẳn do TTS phát âm sai — cần nghe lại "
            "thủ công các case WER cao trước khi kết luận). Corpus chỉ 20 câu (MVP, xem "
            "v3nano-mobile/eval/corpus/manifest.json), không phải quy mô regression 300-1000 câu. "
            "Chạy trên macOS CPU host, không phải thiết bị mobile thật."
        ),
    }

    out_dir = ROOT / "v3nano-mobile" / "eval" / "reports"
    out_dir.mkdir(parents=True, exist_ok=True)
    suffix = f"-{args.report_suffix}" if args.report_suffix else ""
    out_path = out_dir / f"2026-09-28-macos-arm64-asr-quality{suffix}.json"
    out_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print(json.dumps(report["overall"], indent=2))
    print(json.dumps(by_voice, indent=2, ensure_ascii=False))
    print(f"Report written to: {out_path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
