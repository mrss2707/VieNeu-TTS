"""Sprint 1 — xuất golden contract machine-readable cho JVM tests (sdk/src/test) và
contract tests Python (v3nano-mobile/tests/test_chunk_contract.py).

Output: v3nano-mobile/eval/golden/
    chunk_split.json    — 40 case corpus frozen (smoke+stress) × normalize_to_chunks_v3_with_gaps(max_chars=140)
    join_gaps.json      — bảng gaps_to_silence + vector ghép tổng hợp (edge_silence/pause_pad_samples)
    sway_schedule.json  — tg = u + sway*(cos(pi/2*u)-1+u) cho steps {8,12,16} × sway {-1, 0}
    treble_sos.json     — hệ số SOS scipy butter bậc 2 / 6 kHz / fs=24 kHz
    treble_impulse.json — đáp ứng xung (filter, chưa mix) 256 mẫu + tone tổng hợp 500 mẫu
    phone_ids.json      — encode_phones cho chuỗi thường / emotion / OOV / rỗng

Mọi giá trị sinh từ model PIN (pnnbao-ump/VieNeu-TTS-v3-Nano@aba295eb…)
và corpus frozen corpus-mvp-v1. Golden chỉ cover đường DETERMINISTIC (quyết định RNG (b):
không port PCG64/ziggurat bit-exact — determinism chỉ gate nội bộ platform).

Xem doc trước khi sửa: docs/_supermeo/_plan/001-mvp/ (plan đã duyệt).

Chạy: uv run --no-sync python v3nano-mobile/host/export_golden_contract.py [--out-dir v3nano-mobile/eval/golden]
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "src"))
sys.path.insert(0, str(ROOT / "v3nano-mobile" / "host"))  # apply_treble_filter

MODEL_DIR = (
    Path.home() / ".cache" / "huggingface" / "hub"
    / "models--pnnbao-ump--VieNeu-TTS-v3-Nano" / "snapshots"
    / "aba295eb96a6fa6003ebe417cc1f2802a7adc1dc"
)
SAMPLE_RATE = 24_000
STEPS_VALUES = [8, 12, 16]
SWAY_VALUES = [-1.0, 0.0]
MAX_CHARS = 140


def _sway_tg(steps: int, sway: float) -> list[float]:
    n = max(1, int(steps))
    u = np.linspace(0.0, 1.0, n + 1, dtype=np.float64)
    tg = u + float(sway) * (np.cos(np.pi / 2.0 * u) - 1.0 + u)
    return [float(x) for x in tg]


def export_chunk_split(out_dir: Path) -> None:
    from vieneu_utils.phonemize_text import normalize_to_chunks_v3_with_gaps

    cases = []
    for corpus in ("smoke", "stress"):
        path = ROOT / "v3nano-mobile" / "eval" / "corpus" / f"{corpus}.jsonl"
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line:
                continue
            entry = json.loads(line)
            chunks, gaps = normalize_to_chunks_v3_with_gaps(entry["text"], max_chars=MAX_CHARS)
            cases.append({
                "case_id": f"{corpus}_{entry['id']}",
                "text": entry["text"],
                "n_chunks": len(chunks),
                "chunks": chunks,
                "gaps": gaps,
                "gap_silence_seconds": [
                    None if g is None else None for g in gaps
                ],  # placeholder replaced below — giữ key để cấu trúc ổn định
            })
    for c in cases:
        del c["gap_silence_seconds"]
    result = {
        "schema_version": "1",
        "source": "v3nano-mobile/eval/corpus/{smoke,stress}.jsonl (frozen corpus-mvp-v1)",
        "function": "vieneu_utils.phonemize_text.normalize_to_chunks_v3_with_gaps",
        "max_chars": MAX_CHARS,
        "model_revision": "pnnbao-ump/VieNeu-TTS-v3-Nano@aba295eb96a6fa6003ebe417cc1f2802a7adc1dc",
        "cases": cases,
    }
    (out_dir / "chunk_split.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")


def export_chunk_split_structural(out_dir: Path) -> None:
    """Structural golden cho Kotlin TextSegmenter (Sprint 2b) — identity normalize.

    Cùng component Python (split_into_sentences / pack / punc_norm local /
    classify / merge) nhưng KHÔNG qua sea-g2p Normalizer (Rust — không port
    bit-exact). Full-pipeline lock vẫn nằm ở chunk_split.json + Python tests.
    """
    from vieneu_utils.core_utils import (
        _classify_gap, pack_sentences_into_chunks, split_into_sentences,
    )
    from vieneu_utils.phonemize_text import _merge_short_chunks, normalize_to_chunks_v3_with_gaps

    def punc_norm_local(chunk: str) -> str:
        c = chunk.strip()
        if not c:
            return c
        if len(c.split()) < 5:
            return c.rstrip(".,!?;:…") + "."
        return c if c[-1] in ",.!?" else c + "."

    def structural_only(text: str, max_chars: int = MAX_CHARS, min_chunk_chars: int = 20):
        if not text:
            return [], []
        chunks: list[str] = []
        gaps: list[str] = []
        paragraphs = [p for p in text.replace("\r\n", "\n").replace("\r", "\n").split("\n") if p.strip()]
        for para in paragraphs:
            sentences = split_into_sentences(para)
            if not sentences:
                continue
            para_chunks = pack_sentences_into_chunks(sentences, max_chars=max_chars)
            if not para_chunks:
                continue
            if chunks:
                gaps.append("para")
            for j, ch in enumerate(para_chunks):
                if j > 0:
                    gaps.append("sentence")
                chunks.append(ch)
        chunks = [punc_norm_local(c) for c in chunks]
        gaps = [g if g == "para" else _classify_gap(chunks[i]) for i, g in enumerate(gaps)]
        return _merge_short_chunks(chunks, gaps, min_chunk_chars)

    cases = []
    for corpus in ("smoke", "stress"):
        path = ROOT / "v3nano-mobile" / "eval" / "corpus" / f"{corpus}.jsonl"
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line:
                continue
            entry = json.loads(line)
            chunks, gaps = structural_only(entry["text"])
            full_c, full_g = normalize_to_chunks_v3_with_gaps(entry["text"], max_chars=MAX_CHARS)
            cases.append({
                "case_id": f"{corpus}_{entry['id']}",
                "text": entry["text"],
                "sentences": split_into_sentences(entry["text"]),
                "structural_chunks": chunks,
                "structural_gaps": gaps,
                "full_chunks": full_c,
                "full_gaps": full_g,
            })
    result = {
        "schema_version": "1",
        "function": "structural (split_into_sentences + pack + punc_norm_local + classify + merge)",
        "max_chars": MAX_CHARS,
        "min_chunk_chars": 20,
        "cases": cases,
    }
    (out_dir / "chunk_split_structural.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")


def export_join_gaps(out_dir: Path) -> None:
    from vieneu_utils.core_utils import (
        V3_GAP_SILENCE, gaps_to_silence, edge_silence, pause_pad_samples, join_audio_chunks,
    )

    rng = np.random.default_rng(1234)
    sr = SAMPLE_RATE

    def synth(n_samples: int, lead_s: float, tail_s: float, amp: float = 0.3):
        """Vector float32 dài n_samples: im lặng lead_s giây, giữa nhiễu biên độ amp,
        im lặng tail_s giây — dùng để đo đúng nhánh edge_silence/pause_pad_samples."""
        lead = int(lead_s * sr)
        tail = int(tail_s * sr)
        n = max(lead + tail + sr // 10, n_samples)
        wav = np.zeros(n, dtype=np.float32)
        body = rng.standard_normal(max(1, n - lead - tail)).astype(np.float32) * amp
        wav[lead:n - tail] = body[: max(0, n - lead - tail)]
        return wav.tolist()

    pairs = [
        # (tên, prev, next, pause_s) — pause_s lấy từ bảng gaps_to_silence theo loại gap
        ("no_pause_needed", synth(4800, 0.30, 0.30), synth(4800, 0.25, 0.25), V3_GAP_SILENCE["minor"]),
        ("minor_pad", synth(4800, 0.05, 0.05), synth(4800, 0.05, 0.05), V3_GAP_SILENCE["minor"]),
        ("sentence_pad", synth(4800, 0.10, 0.10), synth(4800, 0.05, 0.05), V3_GAP_SILENCE["sentence"]),
        ("para_pad", synth(2400, 0.02, 0.02), synth(2400, 0.0, 0.0), V3_GAP_SILENCE["para"]),
        ("fully_silent_prev", [0.0] * 2400, synth(2400, 0.10, 0.10), V3_GAP_SILENCE["sentence"]),
        ("fully_silent_next", synth(2400, 0.05, 0.05), [0.0] * 2400, V3_GAP_SILENCE["minor"]),
    ]
    rows = []
    for name, prev, nxt, pause_s in pairs:
        prev_np = np.asarray(prev, dtype=np.float32)
        next_np = np.asarray(nxt, dtype=np.float32)
        lead_prev, tail_raw = edge_silence(prev_np, sr)
        tail_prev = prev_np.size if lead_prev == prev_np.size else tail_raw
        lead_next, _ = edge_silence(next_np, sr)
        pad = pause_pad_samples(prev_np, next_np, sr, pause_s)
        joined = join_audio_chunks([prev_np, next_np], sr, silence_ps=[pause_s])
        rows.append({
            "name": name,
            "sample_rate": sr,
            "prev": prev, "next": nxt,
            "prev_samples": int(prev_np.size), "next_samples": int(next_np.size),
            "pause_seconds": pause_s,
            "prev_lead_silence": int(lead_prev),
            "prev_tail_silence": int(tail_prev),
            "next_lead_silence": int(lead_next),
            "pad_samples": int(pad),
            "joined_samples": int(joined.size),
        })
    result = {
        "schema_version": "1",
        "source": "vieneu_utils.core_utils (edge_silence/pause_pad_samples/join_audio_chunks silence_ps)",
        "sample_rate": sr,
        "gap_silence": {k: v for k, v in sorted(V3_GAP_SILENCE.items())},
        "gaps_to_silence": {"para": gaps_to_silence(["para"])[0], "sentence": gaps_to_silence(["sentence"])[0],
                            "minor": gaps_to_silence(["minor"])[0], "unknown": gaps_to_silence(["lạ"])[0]},
        "pairs": rows,
    }
    (out_dir / "join_gaps.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")


def export_sway(out_dir: Path) -> None:
    result = {
        "schema_version": "1",
        "source": "src/vieneu/v3nano.py:267-268 (OnnxV3NanoEngine.infer) — áp cho cả t lẫn dt",
        "formula": "tg = u + sway*(cos(pi/2*u) - 1 + u); u = linspace(0,1,steps+1)",
        "float": "float64 (chuyển float32 khi tạo tensor t/dtc)",
        "schedules": [],
    }
    for steps in STEPS_VALUES:
        for sway in SWAY_VALUES:
            tg = _sway_tg(steps, sway)
            result["schedules"].append({
                "steps": steps, "sway": sway, "tg": tg,
                "t": tg[:-1], "dt": [tg[i + 1] - tg[i] for i in range(len(tg) - 1)],
            })
    (out_dir / "sway_schedule.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")


def export_treble(out_dir: Path) -> None:
    from scipy.signal import butter, sosfilt
    from apply_treble_filter import CUTOFF_HZ, FILTER_ORDER, MIX

    sos = butter(FILTER_ORDER, CUTOFF_HZ, btype="low", fs=SAMPLE_RATE, output="sos")

    impulse = np.zeros(256, dtype=np.float32)
    impulse[0] = 1.0
    filt_impulse = sosfilt(sos, impulse).astype(np.float32)

    rng = np.random.default_rng(9876)
    tone_n = 500
    t = np.arange(tone_n) / SAMPLE_RATE
    tone = (0.25 * np.sin(2 * np.pi * 440 * t) + 0.15 * np.sin(2 * np.pi * 1000 * t)).astype(np.float32)
    filt_tone = sosfilt(sos, tone).astype(np.float32)

    result = {
        "schema_version": "1",
        "source": "scipy.signal.butter(order=2, Wn=6000, btype=low, fs=24000, output=sos) + sosfilt; "
                  "v3nano-mobile/host/apply_treble_filter.py",
        "sample_rate": SAMPLE_RATE,
        "cutoff_hz": CUTOFF_HZ,
        "order": FILTER_ORDER,
        "mix": MIX,
        "sos": [[float(x) for x in sos[0]]],
        "impulse_input": impulse.tolist(),
        "impulse_filtered": [round(float(x), 8) for x in filt_impulse],
        "tone_input": tone.tolist(),
        "tone_filtered": [round(float(x), 6) for x in filt_tone],
        "note": "Kotlin TrebleFilter phải khớp impulse_filtered ±1e-4 (float32) và sos ±1e-12; "
                "đầu ra cuối = (1-mix)*x + mix*filtered.",
    }
    (out_dir / "treble_sos.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")


def export_phone_ids(out_dir: Path) -> None:
    from vieneu.v3nano import OnnxV3NanoEngine

    engine = OnnxV3NanoEngine(local_dir=str(MODEL_DIR), threads=0)
    samples = {
        "plain": "kəm̩ ˈon",                       # placeholder — thay bằng phonemize thật dưới đây
        "emotions": "<|emotion_1|>ɑ<|emotion_3|>b",  # emotion content đính chính sau
        "oov_mixed": "ɑ😀b",
        "empty": "",
    }
    # plain + emotions lấy từ CHUỖI phonemize THẬT của corpus frozen (s01, có emotion nếu có)
    from vieneu_utils.phonemize_text import phonemize_text_with_emotions, normalize_to_chunks_v3_with_gaps

    chunks, _gaps = normalize_to_chunks_v3_with_gaps(
        next(json.loads(l) for l in
             (ROOT / "v3nano-mobile" / "eval" / "corpus" / "smoke.jsonl").read_text(encoding="utf-8").splitlines()
             if l.strip())["text"],
        max_chars=MAX_CHARS)
    plain_ph = phonemize_text_with_emotions(chunks[0]) if chunks else "ɑ"
    # emotion sample: text đánh dấu [cười] -> <|emotion_1|> trong chuỗi phonemize
    emotion_ph = phonemize_text_with_emotions("[cười] xin chào")

    samples["plain"] = plain_ph
    samples["emotions"] = emotion_ph

    result = {
        "schema_version": "1",
        "source": "src/vieneu/v3nano.py:227-243 encode_phones — emotion map replace + drop OOV + bos/eos",
        "model_revision": "pnnbao-ump/VieNeu-TTS-v3-Nano@aba295eb96a6fa6003ebe417cc1f2802a7adc1dc",
        "bos_id": int(engine.bos), "eos_id": int(engine.eos), "pad_id": int(engine.pad),
        "emotion_tags": dict(engine.emotion_map),
        "vocab": dict(engine.vocab),
        "samples": {
            key: {"phones": ph, "ids": [int(x) for x in engine.encode_phones(ph).reshape(-1)]}
            for key, ph in samples.items()
        },
        "oov_note": "😀 (U+1F600) không nằm trong vocab — encode_phones phải DROP nó và chỉ cảnh báo một lần.",
    }
    (out_dir / "phone_ids.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out-dir", type=str, default=str(ROOT / "v3nano-mobile" / "eval" / "golden"))
    args = parser.parse_args()

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    export_chunk_split(out_dir)
    print("✅ chunk_split.json")
    export_chunk_split_structural(out_dir)
    print("✅ chunk_split_structural.json")
    export_join_gaps(out_dir)
    print("✅ join_gaps.json")
    export_sway(out_dir)
    print("✅ sway_schedule.json")
    export_treble(out_dir)
    print("✅ treble_sos.json")
    export_phone_ids(out_dir)
    print("✅ phone_ids.json")
    print(f"Golden artifacts -> {out_dir.relative_to(ROOT)}")


if __name__ == "__main__":
    main()