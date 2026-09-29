# 05 — SDK iOS (Swift)

## 5.1. Quyết định nền

- **Runtime:** ONNX Runtime C++ API trực tiếp (Objective-C++ bridging) — **không** dùng `onnxruntime-swift-package-manager` cho phần tensor (lý do: thiếu BOOL tensor — xem §5.3). Có thể dùng SPM để kéo binary `onnxruntime.xcframework`, nhưng lớp gọi inference là C++ API của chúng ta.
- **Provider:** CPU EP (duy nhất — D3). CoreML giữ optional sau khi có static-shape graph fix (không chặn MVP).
- **Min target:** iOS 15.0+ (chốt với spike; arm64e thiết bị thật đã test OK).

## 5.2. Cấu trúc module

```
v3nano-mobile/ios/
├── device-validation/                        # GIỮ NGUYÊN — app validate engine (tài sản validation)
│   ├── project.yml                           # xcodegen sinh DeviceValidation.xcodeproj
│   ├── DeviceValidation/                     # app target (bundle com.vieneu.mobile.validation.app)
│   └── DeviceValidationTests/                # XCTest (NanoEngineTests.mm — ORT C++ API)
└── sdk/                                      # MỚI (Sprint 3) — SPM: VieNeuTTS + VieNeuTTSCore
    └── VieNeuTTS/
        ├── Package.swift                     # SPM: VieNeuTTS + target VieNeuTTSCore (C++)
        ├── Sources/
        │   ├── VieNeuTTS/                    # Swift layer
        │   │   ├── VieNeuTts.swift           # entry (builder như Android)
        │   │   ├── Reader.swift              # enqueue/play/pause/cancel/setPrefetchDepth/metrics
        │   │   ├── VoiceManager.swift
        │   │   ├── GenerationProfile.swift   # steps/sway/cfg/filter/maxChars — config versioned
        │   │   ├── Metrics.swift / EngineError.swift
        │   │   ├── Player/AudioPlaybackEngine.swift   # AVAudioEngine + interruption handling
        │   │   ├── Lifecycle/ThermalPolicy.swift      # NSProcessInfoThermalStateDidChangeNotification
        │   │   └── Cache/AudioCache.swift             # FileManager LRU + quota + revision path
        │   └── VieNeuTTSCore/                # C++ via Objective-C++
        │       ├── OrtEngineBridge.h/mm      # ORT C++ API: session per graph, BOOL tensors, pool
        │       ├── ChunkPipeline.cpp/h       # port chunk split + join (bản đúng nằm trong device-validation)
        │       ├── FlowScheduler.cpp         # tg = u + sway*(cos(π/2·u) − 1 + u)
        │       ├── TrebleFilter.cpp          # Butterworth bậc 2, 6kHz, mix 50%
        │       └── DeterministicRng.cpp      # khớp Python reference
        └── Tests/
            ├── VieNeuTTSCoreTests/           # XCTest: golden vector comparisons (không cần device)
            └── VieNeuTTSTests/               # API + lifecycle (dùng simulator/CI; device khi có)
```

Lưu ý: bản port C++ chunk/join/sway **đã đúng** trong `device-validation/DeviceValidationTests/NanoEngineTests.mm` (vòng 8) — chuyển sang `VieNeuTTSCore`, device-validation gọi lại core này, xóa bản sao (một nguồn sự thật).

## 5.3. Vấn đề BOOL tensor `ctx_mask` (bài học vòng 2 — bắt buộc xử lý)

- `duration_predictor.onnx` và `vector_estimator.onnx` nhận input `ctx_mask` kiểu **BOOL**.
- ObjC wrapper của `microsoft/onnxruntime-swift-package-manager` (kể cả 1.24.2) không expose `ORTTensorElementDataTypeBool` (GitHub issues #31, #41).
- **Giải pháp đã áp dụng và phải giữ:** gọi thẳng ORT C++ API:
  `Ort::Value::CreateTensor(..., ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL)` trong file Objective-C++.

Thiết kế bridge:

```objc
// OrtEngineBridge.mm (chỉ dẫn ý tưởng)
NS_SWIFT_NAME(OrtEngine)
@interface VNMEngine : NSObject
- (nullable instancetype)initWithModelDir:(NSString *)dir
                                  profile:(VNMGenerationProfile *)profile
                                    error:(NSError **)error;
- (nullable NSData *)synthesize:(NSArray<NSNumber *> *)phoneIds
                      speaker:(NSData *)speakerEmb
                         style:(NSData *)style
                          seed:(int64_t)seed
                         error:(NSError **)error;   // trả về PCM 24kHz mono
@end
```

- Mọi kiểu tensor được bridge thủ công (BOOL dùng `std::vector<bool>`/buffer 1-byte → ORT BOOL).
- Thêm **contract test**: nếu upgrade ORT lên bản đã hỗ trợ BOOL wrapper, test vẫn phải pass với cả hai đường — không đổi bridge âm thầm.

## 5.4. Audio & lifecycle

- **Playback:** `AVAudioEngine` + `AVAudioPlayerNode`, schedule từng buffer chunk (24 kHz mono); hoặc `AVAudioPlayer` cho MVP đơn giản — chốt trong spike (ưu tiên PlayerNode vì cancel chính xác, không click).
- **Interruption:** `AVAudioSession.interruptionNotification` → pause khi bị phone call, resume khi kết thúc; config category `.playback`, mode `.spokenAudio`; xử lý route change (rút tai nghe → pause).
- **Background:** generation trong background thread bị iOS treo → nếu cần đọc khi khóa màn hình, integrator phải dùng background audio mode đúng; SDK expose callback `prefetchUnderrunWarning` để app biết. MVP mặc định: đọc foreground + prefetch đủ sâu.
- **Memory warning:** `UIApplication.didReceiveMemoryWarningNotification` → giảm prefetch, flush cache xa (không bỏ session — đắt để tạo lại).
- **Thermal:** `NSProcessInfo.ProcessInfoThermalStateDidChange` → 3 tầng như §1.7.

## 5.5. Public API (Swift, phác thảo)

```swift
let tts = try VieNeuTts.Builder()
    .profile(GenerationProfile(steps: 8, sway: -1.0, cfg: 3.0,
                               trebleFilterEnabled: true, maxCharsPerChunk: 140))
    .provider(.cpu)
    .thermalPolicy(.defaultTiers)
    .build()

try tts.modelManager.install(from: packageURL)      // checksum + atomic + probe-load
try tts.modelManager.load()                          // session per graph
let reader = try tts.makeReader()
try reader.enqueue("Cả đoạn truyện dài...")
try reader.play()
reader.pause()
reader.cancel()
let metrics = reader.metrics()                       // RTF/RAM/thermal/cache/error — metadata only
tts.voiceManager.listPresets()
try tts.voiceManager.select("duc_tri")               // hủy job cũ, flush, đổi conditioning
```

Tiền lệ async: đổi sang `async/await` nếu SDK yêu cầu Swift concurrency — reader callback luồng chính, không block UI.

## 5.6. Đóng gói & phân phối

- **SPM binary target** cho `onnxruntime.xcframework` (pin version) + `VieNeuTTS` target chứa C++ core (custom `.cpp/.mm` trong SPM là hỗ trợ sẵn).
- Không ship model weights trong package; `ModelManager.install` nhận file/URL (bundled asset cho app hoặc registry).
- Ký: framework ký cùng app; registry tải về phải qua checksum (xem `08`).
- Privacy manifest (`PrivacyInfo.xcprivacy`) cho App Store — khai báo: không thu thập text/audio (SDK offline) + fingerprint các API iOS yêu cầu.

## 5.7. Kiểm thử iOS (tóm tắt — chi tiết `07-kiem-thu.md` §4)

| Tầng | Công cụ | Nội dung |
|---|---|---|
| Core C++ | XCTest (host/simulator CI) | `ChunkPipeline`/`FlowScheduler`/`TrebleFilter` vs golden vectors từ Python |
| Engine | XCTest | load 4 graph, BOOL `ctx_mask`, determinism seed=42, corrupt file fail |
| Lifecycle | XCTest + simulator | interrupt/resume, memory warning, thermal callback |
| Device thật | `xcodebuild test` như harness hiện tại | full corpus, provider CPU, chunking fix regression (`st17` 4 chunks) |
| QA audio | `run_asr_quality_check.py` trên WAV xuất từ device | WER/CER candidate theo ngưỡng Sprint 6 |

**Ghi chú benchmark:** CoreML thử lại **chỉ sau** static-shape graph (Phase 3 cũ) — trên simulator vô nghĩa với ANE, trên device: kiểm tra texture limit fix trước khi chọn.