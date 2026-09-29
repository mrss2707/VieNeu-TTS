"""Contract tests Python — Sprint 1 (kế hoạch 001-mvp).

Golden INLINE (giá trị chép từ v3nano-mobile/eval/golden/ — sinh bởi
v3nano-mobile/host/export_golden_contract.py), KHÔNG chỉ so file: tránh golden bị
sửa hàng loạt che regression. Các test cần ONNX model (pinned snapshot) tự
skip khi máy không có model (CI), phần pure-Python luôn chạy.

Quyết định RNG (b): determinism chỉ gate NỘI BỘ platform (seed -> max_diff=0);
không port PCG64/ziggurat bit-exact, cross-platform so bằng WER ±1 điểm.
"""
from __future__ import annotations

import json
import math
import sys
from pathlib import Path

import numpy as np
import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "src"))
sys.path.insert(0, str(ROOT / "v3nano-mobile" / "host"))

MODEL_DIR = (
    Path.home() / ".cache" / "huggingface" / "hub"
    / "models--pnnbao-ump--VieNeu-TTS-v3-Nano" / "snapshots"
    / "aba295eb96a6fa6003ebe417cc1f2802a7adc1dc"
)
GOLDEN_DIR = ROOT / "v3nano-mobile" / "eval" / "golden"
HAVE_MODEL = MODEL_DIR.is_dir()

# ── golden INLINE (chép từ export_golden_contract.py, 2026-09-29) ────────────

TG_STEPS8_SWAY_MINUS1 = [
    0.0, 0.0192147196, 0.0761204675, 0.1685303877, 0.2928932188,
    0.444429767, 0.6173165676, 0.804909678, 1.0,
]

SOS_GOLDEN = [[
    0.2928932188134525, 0.585786437626905, 0.2928932188134525,
    1.0, -1.8403695052228406e-16, 0.17157287525380993,
]]

IMPULSE_FILTERED_12 = [
    0.29289323, 0.58578646, 0.24264069, -0.10050506, -0.04163056,
    0.01724394, 0.00714268, -0.00295859, -0.00122549, 0.00050761,
    0.00021026, -8.709e-05,
]


# ── chunk split / gaps ────────────────────────────────────────────────────────

def test_gap_silence_table_inline():
    from vieneu_utils.core_utils import V3_GAP_SILENCE, gaps_to_silence
    assert V3_GAP_SILENCE == {"para": 0.70, "sentence": 0.50, "minor": 0.30}
    assert gaps_to_silence(["para", "sentence", "minor"]) == [0.70, 0.50, 0.30]
    # gap type lạ -> mặc định "sentence" (khớp joinAudioChunks Kotlin)
    assert gaps_to_silence(["lạ"]) == [0.50]


def test_normalize_multi_chunk_known_cases_inline():
    from vieneu_utils.phonemize_text import normalize_to_chunks_v3_with_gaps
    smoke = ROOT / "v3nano-mobile" / "eval" / "corpus" / "smoke.jsonl"
    stress = ROOT / "v3nano-mobile" / "eval" / "corpus" / "stress.jsonl"
    cases = {}
    for path in (smoke, stress):
        corpus = path.stem
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            e = json.loads(line)
            cases[f"{corpus}_{e['id']}"] = e["text"]

    # smoke_s07: 217 ký tự -> 2 chunk, ranh giới câu (golden đã xác nhận)
    chunks, gaps = normalize_to_chunks_v3_with_gaps(cases["smoke_s07"], max_chars=140)
    assert len(chunks) == 2 and len(gaps) == 1 and gaps == ["sentence"]
    assert [len(c) for c in chunks] == [94, 122]
    assert chunks[0].rstrip().endswith(".") and chunks[0] != chunks[1]
    # stress_st17: case 4 chunk (regression lock "nuốt chữ" — bug chunking vòng 8)
    chunks, gaps = normalize_to_chunks_v3_with_gaps(cases["stress_st17"], max_chars=140)
    assert len(chunks) == 4 and len(gaps) == 3

    # trường hợp ngắn: giữ nguyên 1 chunk
    chunks, gaps = normalize_to_chunks_v3_with_gaps("Xin chào.", max_chars=140)
    assert len(chunks) == 1 and gaps == []
    # rỗng / whitespace
    assert normalize_to_chunks_v3_with_gaps("", max_chars=140) == ([], [])
    assert normalize_to_chunks_v3_with_gaps("   \n  ", max_chars=140) == ([], [])

    # determinism (không cần model)
    for k in ("smoke_s07", "stress_st17", "smoke_s01"):
        a = normalize_to_chunks_v3_with_gaps(cases[k], max_chars=140)
        b = normalize_to_chunks_v3_with_gaps(cases[k], max_chars=140)
        assert a == b


def test_chunk_split_golden_file_matches_frozen_corpus():
    golden = GOLDEN_DIR / "chunk_split.json"
    if not golden.is_file():
        pytest.skip("golden artifacts chưa được export (chạy v3nano-mobile/host/export_golden_contract.py)")
    from vieneu_utils.phonemize_text import normalize_to_chunks_v3_with_gaps
    data = json.loads(golden.read_text(encoding="utf-8"))
    assert len(data["cases"]) == 40
    for case in data["cases"]:
        chunks, gaps = normalize_to_chunks_v3_with_gaps(case["text"], max_chars=140)
        assert chunks == case["chunks"]
        assert gaps == case["gaps"]


# ── sway schedule ─────────────────────────────────────────────────────────────

def test_sway_schedule_inline_steps8():
    from vieneu.v3nano import OnnxV3NanoEngine
    # tái hiện công thức v3nano.py:267-268 (truth để port FlowScheduler Kotlin)
    steps, sway = 8, -1.0
    u = np.linspace(0.0, 1.0, steps + 1, dtype=np.float64)
    tg = u + sway * (np.cos(np.pi / 2 * u) - 1 + u)
    assert np.allclose(tg, TG_STEPS8_SWAY_MINUS1, atol=1e-6)
    # t lẫn dt đều phải từ lịch trình warp (không truyền thẳng u); t đúc float32
    # như v3nano.py:270-277 (làm tròn float32 là HÀNH VI ĐÚNG, không phải sai số)
    t = [np.array([tg[i]], dtype=np.float32) for i in range(steps)]
    dt = [np.float32(tg[i + 1] - tg[i]) for i in range(steps)]
    assert all(x[0] == np.float32(tg[i]) for i, x in enumerate(t))
    assert all(x == np.float32(tg[i + 1] - tg[i]) for i, x in enumerate(dt))
    assert sum(float(d) for d in dt) == pytest.approx(float(np.float32(1.0)), abs=1e-6)


def test_sway_schedule_inline_steps_12_16():
    golden = GOLDEN_DIR / "sway_schedule.json"
    if not golden.is_file():
        pytest.skip("golden artifacts chưa được export")
    data = json.loads(golden.read_text(encoding="utf-8"))
    for steps in (8, 12, 16):
        for sway in (-1.0, 0.0):
            sch = next(s for s in data["schedules"] if s["steps"] == steps and s["sway"] == sway)
            u = np.linspace(0.0, 1.0, steps + 1, dtype=np.float64)
            tg = u + sway * (np.cos(np.pi / 2 * u) - 1 + u)
            assert np.allclose(tg, sch["tg"], atol=1e-6), f"steps={steps} sway={sway}"
            assert np.allclose(np.array(sch["dt"]), np.diff(sch["tg"]), atol=1e-12)


# ── treble filter ─────────────────────────────────────────────────────────────

def test_treble_sos_inline():
    from scipy.signal import butter
    sos = butter(2, 6000, btype="low", fs=24000, output="sos")
    assert np.allclose(np.asarray(sos), np.asarray(SOS_GOLDEN), atol=1e-12)


def test_treble_impulse_inline():
    from scipy.signal import sosfilt
    from apply_treble_filter import apply_treble_filter, MIX
    impulse = np.zeros(256, dtype=np.float32)
    impulse[0] = 1.0
    filtered = sosfilt(np.asarray(SOS_GOLDEN), impulse).astype(np.float32)
    assert np.allclose(filtered[:12], IMPULSE_FILTERED_12, atol=1e-4)
    # mix 50%: out = (1-MIX)*x + MIX*filtered
    rng = np.random.default_rng(42)
    wav = rng.standard_normal(2000).astype(np.float32)
    out = apply_treble_filter(wav, 24000)
    expected = ((1.0 - MIX) * wav + MIX * sosfilt(np.asarray(SOS_GOLDEN), wav)).astype(np.float32)
    assert np.allclose(out, expected, atol=1e-6)
    # wav rỗng: passthrough
    assert apply_treble_filter(np.array([], dtype=np.float32), 24000).size == 0


# ── encode_phones ─────────────────────────────────────────────────────────────

@pytest.mark.skipif(not HAVE_MODEL, reason="không có ONNX model snapshot locally")
def test_phone_ids_inline():
    from vieneu.v3nano import OnnxV3NanoEngine
    engine = OnnxV3NanoEngine(local_dir=str(MODEL_DIR), threads=0)
    assert (engine.bos, engine.eos, engine.pad) == (1, 2, 0)
    # emotion map replace (<|emotion_k|> -> ①…) rồi mới tra vocab
    ids = engine.encode_phones("<|emotion_1|>").reshape(-1).tolist()
    assert ids == [1, engine.vocab["①"], 2]
    # OOV bị DROP (không fail cả request)
    assert engine.encode_phones("ɑ😀b").reshape(-1).tolist() == [1, 42, 16, 2]
    # rỗng -> chỉ bos/eos
    assert engine.encode_phones("").reshape(-1).tolist() == [1, 2]
    # phonemize thật của "xin chào" khớp golden phone_ids.json
    golden = GOLDEN_DIR / "phone_ids.json"
    if golden.is_file():
        data = json.loads(golden.read_text(encoding="utf-8"))
        for key, sample in data["samples"].items():
            assert engine.encode_phones(sample["phones"]).reshape(-1).tolist() == sample["ids"], key


@pytest.mark.skipif(not HAVE_MODEL, reason="không có ONNX model snapshot locally")
def test_infer_determinism_seed42_maxdiff_zero():
    """RNG (b): determinism NỘI BỘ platform — infer 2 lần seed=42 phải giống hệt nhau."""
    from vieneu.v3nano import V3NanoVieNeuTTS
    smoke = ROOT / "v3nano-mobile" / "eval" / "corpus" / "smoke.jsonl"
    cases = [json.loads(l) for l in smoke.read_text(encoding="utf-8").splitlines() if l.strip()]
    picked = [next(c for c in cases if c["id"] == i) for i in ("s01", "s02", "s03")]
    tts = V3NanoVieNeuTTS(onnx_dir=str(MODEL_DIR), threads=0, steps=8, cfg=3.0)
    for c in picked:
        a = tts.infer(c["text"], voice="Đức Trí", steps=8, sway=-1.0, seed=42, apply_watermark=False)
        b = tts.infer(c["text"], voice="Đức Trí", steps=8, sway=-1.0, seed=42, apply_watermark=False)
        assert a.shape == b.shape, c["id"]
        assert float(np.max(np.abs(a - b))) == 0.0, f"max_diff != 0 cho {c['id']}"


@pytest.mark.skipif(not HAVE_MODEL, reason="không có ONNX model snapshot locally")
def test_multi_chunk_seed_law_path():
    """Khóa đường multi-chunk của V3NanoVieNeuTTS.infer (seed law seed+i nằm trong
    _chunk_wavs — v3nano.py:424-430): sinh text >= 140 ký tự => >= 2 chunk, audio hợp lệ."""
    from vieneu.v3nano import V3NanoVieNeuTTS
    text = ("Câu một. Đây là câu hai. Rồi đến câu ba nữa. Và câu bốn. Sau đó là câu năm. "
            "Câu sáu tiếp theo. Câu bảy vẫn còn ngắn. Cuối cùng là câu tám để chắc chắn "
            "tóng thể dài hơn một trăm bốn mươi ký tự trở lên rồi mới dừng lại.")
    tts = V3NanoVieNeuTTS(onnx_dir=str(MODEL_DIR), threads=0, steps=8, cfg=3.0)
    from vieneu_utils.phonemize_text import normalize_to_chunks_v3_with_gaps
    chunks, _ = normalize_to_chunks_v3_with_gaps(text, max_chars=140)
    assert len(chunks) >= 2
    wav = tts.infer(text, voice="Đức Trí", steps=8, sway=-1.0, seed=42, apply_watermark=False)
    assert wav.size > 0 and not np.any(np.isnan(wav))