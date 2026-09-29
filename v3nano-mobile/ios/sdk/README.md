# VieNeuTTS iOS SDK (Sprint 3 scaffold)

Vị trí module theo `docs/_supermeo/_plan/001-mvp/05-sdk-ios.md` §5.2.

## Trạng thái

| Thành phần | Trạng thái |
|---|---|
| `Package.swift` (SPM VieNeuTTS + VieNeuTTSCore) | scaffold |
| `OrtEngineBridge.h/.mm` — ORT C++ API, BOOL `ctx_mask` | scaffold, **thân chưa bind** |
| `VieNeuTts.swift` public API | phác thảo |
| TextSegmenter / FlowScheduler / TrebleFilter C++ | **chưa port** — bản đúng đang ở Android `com.vieneu.mobile.core` + `device-validation/.../NanoEngineTests.mm` |

## Bài học bắt buộc giữ (vòng 2)

ObjC wrapper `onnxruntime-swift-package-manager` (kể cả 1.24.2) **không** expose
`ORTTensorElementDataTypeBool` (GH #31, #41). `ctx_mask` phải đi qua
`Ort::Value::CreateTensor(..., ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL)` trong `.mm`.

Bản inference đã PASS (simulator + real device) nằm ở
`../device-validation/DeviceValidationTests/NanoEngineTests.mm` — khi port vào
`VieNeuTTSCore`, device-validation gọi lại core, xóa bản sao (một nguồn sự thật).

## Build

```bash
# Binary ORT: tải onnxruntime.xcframework theo ../device-validation/README.md
# (không commit — xem v3nano-mobile/.gitignore)
swift build   # hoặc xcodebuild -scheme VieNeuTTS
```
