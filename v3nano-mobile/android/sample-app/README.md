# Sample app Android (Sprint 3–4)

**Hiện dùng chung harness:** `../device-validation/` — MainActivity **foreground**
(bài học OplusHansManager: instrumented test bị OEM freeze trên OnePlus).

## Tích hợp SDK (mẫu)

```kotlin
val tts = VieNeuTts.Builder()
    .profile(GenerationProfile())                 // steps=8, sway=-1, treble on
    .modelRegistry(ModelRegistry(filesDir.resolve("models")))
    .engine(OrtSynthesisEngine())                 // device-validation
    .phoneEncoder(PhoneEncoder.fromTokenizerJson(tokenizerJson))
    .build()

tts.load(modelDir)
val reader = tts.newReader()
val result = reader.enqueueSynthesize(text, spk, style, seed = 0)
// PCM FloatArray 24 kHz mono + Metrics(result.metrics.toJson())
```

Hoặc path phones đã phonemize (2a contract):

```kotlin
reader.enqueuePhones(phoneChunks, gaps, spk, style, seed = 0)
```

## Benchmark device thật (S3/S6)

1. `gradle :app:installDebug` trong `device-validation/`
2. `adb shell am start -n com.vieneu.mobile.validation/.MainActivity`
3. Soak: `--ei loops 12` + `v3nano-mobile/host/device_soak_monitor.py`
4. Report JSON → `v3nano-mobile/eval/reports/`

Playback streaming (`AudioPlayer` / `GenerationQueue`) đã có trong SDK core —
wire vào MainActivity khi có UI thật (không chặn contract tests).
