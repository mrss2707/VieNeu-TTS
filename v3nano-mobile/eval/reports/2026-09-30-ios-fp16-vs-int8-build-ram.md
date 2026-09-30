# iOS demo A/B — FP16·8 vs INT8·12 (comfort DSP)

Device: iPhone 11 (`00008030-001C046A34E8802E`), Debug build, ORT CPU.
Voice: Mỹ Duyên. Paragraph: Đấu phá (4 chunks). Both paths: AudioComfortChain.

## Build size (device-agnostic, from Debug .app payload)

| Item | FP16 | INT8 | Δ |
|------|------|------|---|
| 4× ONNX on disk | 141.2 MB | 72.4 MB | **−68.8 MB (−49%)** |
| vector_estimator | 74 MB | 38 MB | −36 MB |
| codec_decoder | 47 MB | 24 MB | −23 MB |
| text_encoder | 13 MB | 6.6 MB | −6.4 MB |
| duration_predictor | 360 KB | 192 KB | −0.2 MB |
| App base (code + ORT + demo assets, excl. graphs) | 32.9 MB | 32.9 MB | — |
| **Ship single-precision app** | **≈174 MB** | **≈105 MB** | **−69 MB** |

Demo APK/app on the device bundles **both** sets (≈247 MB) so one install can A/B quality.

Prior Android gzip package gate numbers (same graphs):
- FP16 gzip 130.6 MB (pass ≤150)
- INT8 gzip 62.7 MB (pass, ~2× smaller)

## RAM (phys_footprint)

Shown live on-screen after each synthesize (`RAM … MB`).
Metric: `task_info(TASK_VM_INFO).phys_footprint` — same as Instruments “Memory”.

| Moment | What to read |
|--------|----------------|
| After both engines load | status line `RAM sau khi nạp` |
| After FP16·8 synth | status `OK FP16 · 8 steps … RAM … MB` |
| After INT8·12 synth | status `OK INT8 · 12 steps … RAM … MB` |

INT8 weights are QUInt8 on disk but dequantize to float activations at runtime;
expect **peak RAM similar to or slightly below FP16**, not 2× smaller. The big
win is **download/install size**, not necessarily peak RSS.

## UI

- `Đọc — FP16 · 8 steps` — comfort DSP, baseline quality/speed on iOS
- `Đọc — INT8 · 12 steps` — comfort DSP, smaller graphs + more Euler steps

## Android decision (this session)

**Chốt: INT8 + 12 steps + comfort DSP + Mỹ Duyên** (RTF ≈ 0.35 on PHP110).
Demo keeps 8/16-step buttons for A/B only.
