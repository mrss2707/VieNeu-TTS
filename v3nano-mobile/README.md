# v3nano-mobile — VieNeu-TTS v3 Nano trên mobile

Chạy TTS tiếng Việt **trên máy** (offline) với model v3 Nano: nhanh, nhẹ, nghe lâu không gắt.

## Mục tiêu

| | |
|---|---|
| **Chất lượng** | Giọng rõ, tự nhiên; hậu kỳ comfort DSP (EQ + noise gate + spectral) để nghe lâu không mệt |
| **Tốc độ** | RTF ≤ 0.5 trên máy tầm trung (gate production) |
| **Dung lượng** | INT8 ~72 MB graph (gzip package ~63 MB) — dưới gate 150 MB |
| **Runtime** | CPU (ORT), preset voice, steps=8–12, không cần mạng |

**Production path (đã chốt, 2026-09-30):** INT8 · 12 Euler steps · giọng Mỹ Duyên · comfort EQ + noise gate + spectral STFT · peak −1 dBFS.

## Cấu trúc hệ thống

```text
Text → [host: sea-g2p / phone IDs] → 4× ONNX graph → PCM → comfort DSP → loa
         (chưa on-device)              flow-matching     EQ + gate + spectral
```

4 graph ONNX: `text_encoder` → `duration_predictor` → `vector_estimator` (×steps) → `codec_decoder`.

```text
v3nano-mobile/
  android/sdk/             # SDK sản phẩm (Kotlin core thuần JVM)
  android/device-validation/  # App test trên máy thật (không ship)
  ios/device-validation/   # App demo iOS (ORT C++)
  flutter/                 # Plugin Flutter (mirror profile Kotlin)
  host/                    # CLI: export voice/phone IDs, convert FP16/INT8, build package, ASR
  package/                 # Artifact model (weights không commit)
  eval/                    # Corpus, golden contract, reports
  tests/                   # Contract tests Python
```

## Tech stack

| Layer | Dùng |
|-------|------|
| Model | ONNX (4 graph) · flow-matching Euler 8–12 steps · CFG 3.0 · sway −1 |
| Inference | ONNX Runtime CPU (Android / iOS / host) |
| SDK Android | Kotlin thuần (FlowScheduler, ChunkJoiner, AudioComfortFilter, ModelRegistry) |
| iOS demo | Objective-C++ + ORT C++ API |
| Flutter | Dart profile → native |
| DSP hậu kỳ | Treble LP 6 kHz · presence 3 kHz · shelf 5.5 kHz · spectral STFT · noise gate · tanh soft-clip |
| Host | Python (export tensors, convert precision, build/validate package, ASR quality) |
| Voice | 11 preset (speaker_emb 192 + style 50×256); thêm giọng = clip 3–8 s → 2 file `.bin` |

## Notes / yêu cầu

1. **Không commit weights** (`*.onnx`, `*.bin` lớn, build output) — regenerate bằng `host/`.
2. **Report quyết định** = `eval/reports/`; file cũ → `eval/reports-archive/`. Nguồn sự thật chi tiết: `docs/_supermeo/_summary/001-v3-nano-mobile.md`.
3. **Precision trên device:** FP16 chậm ~5× trên Android ORT CPU; INT8 ≈ FP32 speed, size −50%. iOS FP16 ≈ parity.
4. **Comfort DSP mặc định ON** (`GenerationProfile` rev 4: `comfortFilter` + `noiseFloor` + `spectralDenoise`).
5. **Phone IDs** export sẵn từ host (sea-g2p chưa chạy on-device) — demo dùng `export_demo_paragraph.py`.
6. **Thêm giọng:** `host/export_voice_bins.py --audio ref.wav --name "..."` (clip 3–8 s, 1 người, sạch).
7. **Device validation** là app test, không phải SDK. Foreground activity bắt buộc trên OnePlus/Oplus (process background bị freeze).

## Gate chất lượng

| Gate | Ngưỡng |
|------|--------|
| RTF | ≤ 0.5 |
| Package gzip | ≤ 150 MB |
| WER / CER (baseline) | ≤ 10.86 / 8.12 ±1 |
| NaN / hard-fail | 0 |
