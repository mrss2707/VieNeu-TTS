"""Benchmark smoke.jsonl trên macOS ARM64 CPU, N lần lặp/câu — protocol 002 §8.

"Không lấy lần chạy nhanh nhất làm kết quả; báo cáo median, p95 và variance."
Vòng validation trước (2026-09-28-macos-arm64-fp32.json) chỉ chạy 1 câu 1 lần;
script này chạy toàn bộ smoke.jsonl x --runs (mặc định 3) trên 1 voice cố định,
cộng thêm per-graph RSS load (profile_graph_rss.py logic) — kết quả không dùng để
mở bất kỳ gate nào, chỉ là bằng chứng bổ sung cho H1/H2/H7 (macOS CPU, không phải
Android/iOS thiết bị thật).

Chạy: uv run --no-sync python v3nano-mobile/host/run_smoke_benchmark.py
"""
from __future__ import annotations

import json
import statistics
import sys
import time
from pathlib import Path

import numpy as np
import psutil

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "src"))

MODEL_DIR = (
    Path.home() / ".cache" / "huggingface" / "hub"
    / "models--pnnbao-ump--VieNeu-TTS-v3-Nano" / "snapshots"
    / "aba295eb96a6fa6003ebe417cc1f2802a7adc1dc"
)
VOICE_NAME = "Minh Quân"
N_RUNS = 3


def rss_mb() -> float:
    return psutil.Process().memory_info().rss / 1024 / 1024


def load_graphs_with_rss_profile():
    """Load 4 core graph tuần tự, đo delta RSS sau mỗi graph — làm sâu H7."""
    import onnxruntime as ort

    so = ort.SessionOptions()
    so.log_severity_level = 3
    prov = ["CPUExecutionProvider"]

    order = ["text_encoder.onnx", "duration_predictor.onnx", "vector_estimator.onnx", "codec_decoder.onnx"]
    per_graph_rss = {}
    rss_before_all = rss_mb()
    prev_rss = rss_before_all
    for name in order:
        t0 = time.time()
        ort.InferenceSession(str(MODEL_DIR / name), so, providers=prov)
        load_ms = (time.time() - t0) * 1000
        cur_rss = rss_mb()
        per_graph_rss[name] = {
            "load_ms": round(load_ms, 1),
            "rss_after_mb": round(cur_rss, 1),
            "rss_delta_mb": round(cur_rss - prev_rss, 1),
        }
        prev_rss = cur_rss
    return per_graph_rss, rss_before_all, prev_rss


def main():
    from vieneu.v3nano import OnnxV3NanoEngine
    from vieneu_utils.phonemize_text import phonemize_text_with_emotions

    # ── per-graph RSS profile (bước 3 của kế hoạch) — process riêng để không lẫn
    #    với RSS của engine chính bên dưới. Chạy trước, trong tiến trình này, rồi
    #    engine chính sẽ tạo lại session của chính nó (không tái sử dụng object).
    per_graph_rss, rss_before_profile, rss_after_profile = load_graphs_with_rss_profile()

    voices = json.loads((ROOT / "src" / "vieneu" / "assets" / "voices_v3_nano.json").read_text(encoding="utf-8"))
    preset = voices["presets"][VOICE_NAME]
    spk = np.asarray(preset["speaker_emb"], dtype=np.float32)
    style = np.asarray(preset["style"], dtype=np.float32)

    rss_before_engine = rss_mb()
    t_load0 = time.time()
    engine = OnnxV3NanoEngine(local_dir=str(MODEL_DIR), threads=0)
    cold_start_ms = (time.time() - t_load0) * 1000
    rss_after_engine_load = rss_mb()

    smoke_path = ROOT / "v3nano-mobile" / "eval" / "corpus" / "smoke.jsonl"
    cases = [json.loads(line) for line in smoke_path.read_text(encoding="utf-8").splitlines() if line.strip()]

    per_case_results = []
    all_rtf = []
    for case in cases:
        text = case["text"]
        ph = phonemize_text_with_emotions(text)
        run_rtfs = []
        run_gen_ms = []
        nan_or_inf_any = False
        audio_seconds_last = 0.0
        for run_idx in range(N_RUNS):
            t0 = time.time()
            wav = engine.infer(ph, spk, style, steps=16, cfg=3.0)
            gen_ms = (time.time() - t0) * 1000
            secs = len(wav) / engine.SAMPLE_RATE
            rtf = (gen_ms / 1000) / secs if secs > 0 else None
            if rtf is not None:
                run_rtfs.append(rtf)
                all_rtf.append(rtf)
            run_gen_ms.append(round(gen_ms, 2))
            if np.isnan(wav).any() or np.isinf(wav).any():
                nan_or_inf_any = True
            audio_seconds_last = float(secs)

        per_case_results.append({
            "id": case["id"],
            "category": case.get("category"),
            "char_count": len(text),
            "runs_per_case": N_RUNS,
            "generation_ms_per_run": run_gen_ms,
            "rtf_per_run": [round(r, 4) for r in run_rtfs] if run_rtfs else [],
            "audio_seconds": round(audio_seconds_last, 3),
            "nan_or_inf": nan_or_inf_any,
        })

    def summarize(values):
        if not values:
            return {"median": None, "p95": None, "stddev": None, "min": None, "max": None, "n": 0}
        sorted_v = sorted(values)
        p95_idx = min(len(sorted_v) - 1, int(round(0.95 * (len(sorted_v) - 1))))
        return {
            "median": round(statistics.median(sorted_v), 4),
            "p95": round(sorted_v[p95_idx], 4),
            "stddev": round(statistics.pstdev(sorted_v), 4) if len(sorted_v) > 1 else 0.0,
            "min": round(sorted_v[0], 4),
            "max": round(sorted_v[-1], 4),
            "n": len(sorted_v),
        }

    rtf_stats = summarize(all_rtf)
    any_nan = any(c["nan_or_inf"] for c in per_case_results)

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
        "corpus": "v3nano-mobile/eval/corpus/smoke.jsonl",
        "runs_per_case": N_RUNS,
        "hypothesis": ["H1-partial-macos-multi-run", "H2-per-graph-rss", "H7-partial"],

        "cold_start_ms": round(cold_start_ms, 1),
        "rss_before_engine_load_mb": round(rss_before_engine, 1),
        "rss_after_engine_load_mb": round(rss_after_engine_load, 1),
        "engine_load_rss_delta_mb": round(rss_after_engine_load - rss_before_engine, 1),

        "per_graph_load_rss_profile": {
            "note": (
                "Profile riêng (session độc lập với engine._so ở trên, load tuần tự 4 graph core "
                "để đo delta RSS mỗi graph). rss_delta_mb là RAM tăng thêm SAU KHI load graph đó, "
                "so với ngay trước khi load. Đây là bằng chứng cụ thể cho quyết định graph nào cần "
                "profile/quantize trước — không phải suy đoán từ size trên đĩa."
            ),
            "rss_before_mb": round(rss_before_profile, 1),
            "rss_after_all_4_graphs_mb": round(rss_after_profile, 1),
            "total_delta_mb": round(rss_after_profile - rss_before_profile, 1),
            "per_graph": per_graph_rss,
        },

        "rtf_summary": rtf_stats,
        "per_case": per_case_results,
        "any_nan_or_inf": any_nan,

        "what_this_proves": (
            f"RTF median/p95/stddev trên {len(cases)} câu smoke x {N_RUNS} lần lặp/câu "
            f"({rtf_stats['n']} data point) trên macOS CPU FP32 — thay thế cho '1 câu 1 lần' của "
            "vòng validation trước, đúng yêu cầu 'không lấy lần chạy nhanh nhất' của protocol 002 §8. "
            "Per-graph RSS load cho thấy graph nào chiếm RAM nhiều nhất khi load (không chỉ size trên đĩa)."
        ),
        "what_this_does_NOT_prove": (
            "Vẫn là macOS CPU host, KHÔNG phải Android/iOS thiết bị thật — không dùng RTF/RSS ở đây "
            "để tuyên bố target §2.2 của 001 đạt trên mobile thật. Corpus smoke.jsonl là corpus MVP "
            "(20 câu, xem v3nano-mobile/eval/corpus/manifest.json), không phải quy mô regression "
            "300-1000 câu protocol 002 đề xuất."
        ),
    }

    out_dir = ROOT / "v3nano-mobile" / "eval" / "reports"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / "2026-09-28-macos-arm64-fp32-smoke-corpus.json"
    out_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"RTF summary: {json.dumps(rtf_stats, indent=2)}")
    print(f"Any NaN/Inf: {any_nan}")
    print(f"Per-graph RSS delta (MB): " + ", ".join(f"{k}={v['rss_delta_mb']}" for k, v in per_graph_rss.items()))
    print(f"Report written to: {out_path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
