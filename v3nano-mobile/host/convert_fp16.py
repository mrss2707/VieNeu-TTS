"""Spike S1 — chuyển 4 graph core v3 Nano sang FP16 cho package mobile P1.

Đọc 4 graph FP32 từ HF snapshot PIN (không bao giờ dùng `latest`,
xem docs/_supermeo/_plan/001-mvp/03-model-package.md §3.1):
  text_encoder.onnx, duration_predictor.onnx, vector_estimator.onnx, codec_decoder.onnx

Mỗi graph: onnxruntime.transformers.float16 (keep_io_types=True — giữ IO FP32/bool/int64,
chỉ cast trọng số + activation bên trong sang FP16) -> onnx.checker.check_model ->
onnx.shape_inference.infer_shapes. Ghi report kích thước FP32->FP16 (JSON + stdout).

Output: v3nano-mobile/package/fp16/ (chặn *.onnx khỏi git bằng .gitignore của thư mục cha).

Chạy: uv run --no-sync python v3nano-mobile/host/convert_fp16.py [--out-dir PATH]
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "src"))

MODEL_DIR = (
    Path.home() / ".cache" / "huggingface" / "hub"
    / "models--pnnbao-ump--VieNeu-TTS-v3-Nano" / "snapshots"
    / "aba295eb96a6fa6003ebe417cc1f2802a7adc1dc"
)

BASE_MODEL_REVISION = "aba295eb96a6fa6003ebe417cc1f2802a7adc1dc"

GRAPH_FILES = [
    "text_encoder.onnx",
    "duration_predictor.onnx",
    "vector_estimator.onnx",
    "codec_decoder.onnx",
]


def main():
    import onnx
    from onnxruntime.transformers import float16 as ort_fp16

    parser = argparse.ArgumentParser()
    parser.add_argument("--out-dir", type=str, default=str(ROOT / "v3nano-mobile" / "package" / "fp16"))
    parser.add_argument("--model-dir", type=str, default=str(MODEL_DIR))
    parser.add_argument(
        "--only",
        nargs="+",
        choices=GRAPH_FILES,
        default=None,
        help="Chỉ convert các graph này sang FP16; graph ngoài danh sách copy nguyên bản FP32 "
             "(D4 mixed-precision sau khi S1 fail RTF trên Android CPU).",
    )
    parser.add_argument(
        "--copy-fp32",
        nargs="*",
        choices=GRAPH_FILES,
        default=None,
        help="Cùng --only: các graph giữ FP32 (copy từ model dir). Mặc định = GRAPH_FILES - only.",
    )
    args = parser.parse_args()

    src_dir = Path(args.model_dir)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    if not src_dir.is_dir():
        raise SystemExit(f"Model dir not found: {src_dir}")

    if args.only is not None:
        only = set(args.only)
        copy_fp32 = set(args.copy_fp32 if args.copy_fp32 is not None else (set(GRAPH_FILES) - only))
        mode = "mixed"
    else:
        only = set(GRAPH_FILES)
        copy_fp32 = set()
        mode = "all_fp16"

    report = {
        "tool": "v3nano-mobile/host/convert_fp16.py",
        "base_model_revision": BASE_MODEL_REVISION,
        "precision_from": "fp32",
        "precision_to": "fp16" if mode == "all_fp16" else "mixed_fp16_fp32",
        "mode": mode,
        "fp16_graphs": sorted(only),
        "fp32_graphs": sorted(copy_fp32),
        "converter": "onnxruntime.transformers.float16 (keep_io_types=True)",
        "graphs": [],
        "copied_non_graph_files": [],
    }
    # OnnxV3NanoEngine đọc config.json + constants.npz từ chính onnx_dir (null conditioning
    # cho CFG + vocab/bos/eos) — copy để thư mục FP16 là model dir HOÀN CHỈNH chạy được.
    for extra in ("config.json", "constants.npz"):
        (out_dir / extra).write_bytes((src_dir / extra).read_bytes())
        report["copied_non_graph_files"].append(extra)
    for name in GRAPH_FILES:
        src = src_dir / name
        if not src.is_file():
            raise SystemExit(f"Missing graph: {src}")
        size_fp32 = src.stat().st_size
        if name in copy_fp32:
            dst = out_dir / name
            dst.write_bytes(src.read_bytes())
            print(f"[convert] {name}: copy FP32 ({size_fp32:,} bytes)", flush=True)
            report["graphs"].append({
                "file": f"graphs/{name}",
                "fp32_bytes": size_fp32,
                "fp16_bytes": size_fp32,
                "ratio": 1.0,
                "precision": "fp32",
                "checker": "SKIPPED_COPY",
                "shape_inference": "SKIPPED_COPY",
                "convert_seconds": 0.0,
            })
            continue
        print(f"[convert] {name}: loading ({size_fp32:,} bytes fp32)...", flush=True)
        t0 = time.monotonic()
        model = onnx.load(str(src))
        model_fp16 = ort_fp16.convert_float_to_float16(model, keep_io_types=True)
        # keep_io_types=True chèn Cast nodes không theo topological order (bug đã gặp của
        # onnxruntime.transformers.float16) -> sắp xếp lại trước khi checker chạy.
        from onnxruntime.transformers.onnx_model import OnnxModel
        wrapped = OnnxModel(model_fp16)
        wrapped.topological_sort()
        model_fp16 = wrapped.model
        onnx.checker.check_model(model_fp16)
        onnx.shape_inference.infer_shapes(model_fp16, check_type=True, strict_mode=True)
        dst = out_dir / name
        onnx.save(model_fp16, str(dst))
        size_fp16 = dst.stat().st_size
        dt = time.monotonic() - t0
        print(f"[convert] {name}: OK — {size_fp32:,} -> {size_fp16:,} bytes "
              f"({size_fp16 / size_fp32:.1%}) in {dt:.1f}s", flush=True)
        report["graphs"].append({
            "file": f"graphs/{name}",
            "fp32_bytes": size_fp32,
            "fp16_bytes": size_fp16,
            "ratio": round(size_fp16 / size_fp32, 6),
            "precision": "fp16",
            "checker": "PASS",
            "shape_inference": "PASS",
            "convert_seconds": round(dt, 1),
        })

    total_fp32 = sum(g["fp32_bytes"] for g in report["graphs"])
    total_fp16 = sum(g["fp16_bytes"] for g in report["graphs"])
    report["total_fp32_bytes"] = total_fp32
    report["total_fp16_bytes"] = total_fp16
    report["total_ratio"] = round(total_fp16 / total_fp32, 6)

    report_path = out_dir / "fp16_conversion_report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[convert] total {total_fp32:,} -> {total_fp16:,} bytes "
          f"({total_fp16 / total_fp32:.1%})")
    print(f"[convert] report: {report_path}")


if __name__ == "__main__":
    main()