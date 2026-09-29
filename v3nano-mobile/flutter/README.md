# Flutter bridge — vieneu_tts

Thin wrapper (06-sdk-flutter.md): **không port engine**. Native SDK là nguồn sự thật.

```text
v3nano-mobile/flutter/
└── packages/vieneu_tts/     # plugin Dart + host Kotlin/Swift scaffold
```

## Trạng thái

| Thành phần | Trạng thái |
|---|---|
| Dart API (VieNeuTts / Reader / VoiceManager / Metrics / EngineError) | ✅ scaffold + unit test |
| Pigeon codegen | ⏳ chờ `pigeon` config |
| Android host `VieNeuTtsPlugin.kt` | scaffold |
| iOS host `VieNeuTtsPlugin.swift` | scaffold |
| example/ + integration_test | ⏳ (cần device — Sprint 6) |

## Test

```bash
cd v3nano-mobile/flutter/packages/vieneu_tts
flutter test
```

Unit test mock `invokeHandler` — không cần device/SDK native.
