"""Prototype de-esser động 5–8 kHz — dùng để chọn tham số trên đầu ra THÔ của model.

Thuật toán (cũng là thứ được port sang Kotlin `AudioComfortFilter.applyDeEsser` và C++ iOS):
  1. Tách dải sibilant 5–8 kHz bằng band-pass (2 biquad RBJ: HP 5 kHz rồi LP 8 kHz) → s(t).
  2. Tách dải giọng 0.3–3.4 kHz (HP 300 Hz rồi LP 3.4 kHz) → v(t), CHỈ để đo năng lượng.
  3. Envelope năng lượng (one-pole, attack/release) của s và v → tỉ lệ R = E_s / E_v.
  4. Khi R vượt ngưỡng: gain_db = -(R_db - thresh_db) * (1 - 1/ratio), kẹp tối đa maxReduceDb.
     Gain được làm mượt (attack nhanh, release chậm) để không "lách tách".
  5. Chỉ trừ phần sibilant:  y = x - s + s * g   (dải ngoài 5–8 kHz giữ nguyên tuyệt đối).
Chạy sau chuỗi EQ, trước tanh/chuẩn hoá đỉnh.
"""
from __future__ import annotations

import math

import numpy as np

SR = 24_000


def rbj(kind: str, f0: float, q: float, sr: int = SR):
    w0 = 2 * math.pi * f0 / sr
    cw, sw = math.cos(w0), math.sin(w0)
    alpha = sw / (2 * q)
    if kind == "lp":
        b0, b1, b2 = (1 - cw) / 2, 1 - cw, (1 - cw) / 2
    elif kind == "hp":
        b0, b1, b2 = (1 + cw) / 2, -(1 + cw), (1 + cw) / 2
    else:
        raise ValueError(kind)
    a0, a1, a2 = 1 + alpha, -2 * cw, 1 - alpha
    return b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0


def biquad(x: np.ndarray, c) -> np.ndarray:
    b0, b1, b2, a1, a2 = c
    y = np.zeros_like(x, dtype=np.float64)
    z1 = z2 = 0.0
    for i, v in enumerate(x.astype(np.float64)):
        o = b0 * v + z1
        z1 = b1 * v + z2 - a1 * o
        z2 = b2 * v - a2 * o
        y[i] = o
    return y


def deess(
    x: np.ndarray,
    thresh_db: float = -6.0,
    ratio: float = 4.0,
    max_reduce_db: float = 9.0,
    env_attack_s: float = 0.0015,
    env_release_s: float = 0.015,
    gain_attack_s: float = 0.001,
    gain_release_s: float = 0.040,
    sr: int = SR,
    return_gain: bool = False,
):
    x = x.astype(np.float64)
    s = biquad(biquad(x, rbj("hp", 5000, 0.7071, sr)), rbj("lp", 8000, 0.7071, sr))
    v = biquad(biquad(x, rbj("hp", 300, 0.7071, sr)), rbj("lp", 3400, 0.7071, sr))
    ea, er = math.exp(-1 / (env_attack_s * sr)), math.exp(-1 / (env_release_s * sr))
    ga, gr = math.exp(-1 / (gain_attack_s * sr)), math.exp(-1 / (gain_release_s * sr))
    es = ev = 0.0
    g = 1.0
    out = np.empty_like(x)
    gains = np.empty_like(x)
    slope = 1.0 - 1.0 / ratio
    max_red = 10 ** (-max_reduce_db / 20)
    for i in range(len(x)):
        ps, pv = s[i] * s[i], v[i] * v[i]
        es = (ea * es + (1 - ea) * ps) if ps > es else (er * es + (1 - er) * ps)
        ev = (ea * ev + (1 - ea) * pv) if pv > ev else (er * ev + (1 - er) * pv)
        r_db = 10 * math.log10((es + 1e-12) / (ev + 1e-12))
        over = r_db - thresh_db
        target = 1.0
        if over > 0:
            target = max(max_red, 10 ** (-(over * slope) / 20))
        g = (ga * g + (1 - ga) * target) if target < g else (gr * g + (1 - gr) * target)
        out[i] = x[i] - s[i] + s[i] * g
        gains[i] = g
    out = out.astype(np.float32)
    return (out, gains.astype(np.float32)) if return_gain else out
