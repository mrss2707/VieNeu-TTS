"""Gentle low-pass treble filter — hậu xử lý cho candidate steps=8/sway=-1 (H4).

Nghe thử thủ công (vòng validation trước) cho thấy steps=8 "chói tai" hơn steps=16 dù
tỷ trọng phổ tần cao gần như giống hệt nhau — nguyên nhân là TỔNG năng lượng audio thấp
hơn ~9.5% (ratio≈0.905) khiến dải 1-3kHz nổi bật tương đối, không phải do model sinh ra
nhiều treble hơn về tỷ trọng. Bộ lọc dưới đây (Butterworth bậc 2, cutoff 6kHz, mix 50%
với bản gốc) đưa tỷ trọng phổ trở lại gần steps=16 mà không cần sinh lại audio — chi phí
đo được: 8.3ms cho 32.4s audio (RTF cost ≈0.00026, không đáng kể so với RTF sinh audio
~0.09 ở steps=8). Xem docs/_supermeo/_summary/001-v3-nano-mobile.md mục 0.8.

Dùng như module (``apply_treble_filter(wav, sr)``, ví dụ trong run_asr_quality_check.py
qua --treble-filter) hoặc CLI để lọc 1 file .wav.

Chạy CLI: uv run --no-sync python v3nano-mobile/host/apply_treble_filter.py IN.wav OUT.wav
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np

CUTOFF_HZ = 6000
FILTER_ORDER = 2
MIX = 0.5  # tỉ trọng bản đã lọc trong hỗn hợp cuối cùng


def apply_treble_filter(wav: np.ndarray, sr: int, cutoff_hz: float = CUTOFF_HZ,
                        order: int = FILTER_ORDER, mix: float = MIX) -> np.ndarray:
    """Low-pass Butterworth bậc 2 tại ``cutoff_hz``, trộn ``mix`` với bản gốc.

    Trộn 50% (không cắt cứng 100%) để giữ phụ âm rõ thay vì làm audio bị "nghẹt mũi".
    """
    from scipy.signal import butter, sosfilt
    if wav.size == 0:
        return wav
    sos = butter(order, cutoff_hz, btype="low", fs=sr, output="sos")
    filtered = sosfilt(sos, wav).astype(np.float32)
    return ((1.0 - mix) * wav + mix * filtered).astype(np.float32)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("in_wav", type=str)
    parser.add_argument("out_wav", type=str)
    parser.add_argument("--cutoff", type=float, default=CUTOFF_HZ)
    parser.add_argument("--mix", type=float, default=MIX)
    args = parser.parse_args()

    import soundfile as sf
    wav, sr = sf.read(args.in_wav, dtype="float32")
    filtered = apply_treble_filter(wav, sr, cutoff_hz=args.cutoff, mix=args.mix)
    sf.write(args.out_wav, filtered, sr)
    print(f"Filtered {args.in_wav} -> {args.out_wav} (cutoff={args.cutoff}Hz, mix={args.mix})")


if __name__ == "__main__":
    main()
