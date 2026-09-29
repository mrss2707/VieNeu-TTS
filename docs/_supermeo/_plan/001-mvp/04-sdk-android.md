# 04 — SDK Android (Kotlin)

## 4.1. Thiết lập module & cấu trúc code

```
v3nano-mobile/android/                            # một root cho v3 Nano Mobile (xem v3nano-mobile/README.md)
├── device-validation/                            # GIỮ NGUYÊN — app validate engine trên device (tài sản validation)
│                                                 # package com.vieneu.mobile.validation
└── sdk/                                          # SẢN PHẨM — module Gradle độc lập (rootProject: vieneu-mobile-sdk)
    ├── build.gradle.kts                          # namespace com.vieneu.mobile · artifactId: com.vieneu:vieneu-mobile-sdk
    └── src/
        ├── main/java/com/vieneu/mobile/
        │   ├── api/            // public API ổn định (đóng băng sau Giai đoạn 0)
        │   │   ├── VieNeuTts.kt        // entry point (builder pattern)
        │   │   ├── Reader.kt           // enqueue/play/pause/cancel/setPrefetchDepth/metrics
        │   │   ├── VoiceManager.kt
        │   │   └── Metrics.kt / EngineError.kt
        │   ├── core/           // logic thuần (không Android, test trên JVM) — ĐÃ CÓ
        │   │   ├── TextSegmenter.kt         // port normalize_to_chunks_v3_with_gaps
        │   │   ├── ChunkJoiner.kt           // edge_silence + pause_pad_samples + join (silence_ps)
        │   │   ├── TrebleFilter.kt          // Butterworth bậc 2, 6kHz, mix 50%
        │   │   ├── FlowScheduler.kt         // tg = u + sway*(cos(π/2·u) − 1 + u) — steps/sway từ config
        │   │   ├── DeterministicRng.kt      // seed → chuỗi giống Python reference
        │   │   └── ModelRegistry.kt         // manifest, checksum, install/rollback (pure-JVM testable)
        │   ├── engine/         // ORT binding
│           │   │   ├── OrtEngine.kt             // một session mỗi graph; createSession(path) sau copy asset
│           │   │   ├── SessionBuilder.kt        // provider ladder: CPU → XNNPACK → [NNAPI opt-in]
│           │   │   └── TensorPool.kt            // reuse buffer, tránh copy JNI
│           │   ├── player/
│           │   │   ├── AudioPlayer.kt           // AudioTrack streaming 24kHz mono
│           │   │   ├── AudioFocusManager.kt
│           │   │   └── GenerationQueue.kt       // bounded + priority + cancel-at-boundary
│           │   ├── lifecycle/
│           │   │   ├── ThermalPolicy.kt         // 3 tầng (§1.7) — PowerManager thermal + callback
│           │   │   └── MemoryPressureHandler.kt
│           │   └── internal/
│           ├── main/assets/                      // model P1 (chỉ metadata pointer — KHÔNG commit weights)
│           ├── test/java/                        // unit tests JVM: TextSegmenter, ChunkJoiner, FlowScheduler,
│           │                                      // TrebleFilter vs golden vectors Python, ModelRegistry corrupt/rollback
│           └── androidTest/java/                 // instrumented: engine smoke, lifecycle, multi-provider
```

App mẫu / foreground host (bài học OplusHansManager): dùng chính `device-validation/` hoặc thêm `v3nano-mobile/android/sample-app/` khi có UI thật.

**Nguyên tắc:**
- `core/` **không import android.*** → test chạy trên JVM CI, không cần emulator.
- `api/` là lớp duy nhất app thấy. Không expose ORT, tensor, session.
- Tái sử dụng logic đã đúng trong `NanoBenchmarkCore.kt` của device-validation (chunk join, sway, multi-provider) — chuyển vào `core/engine` rồi **device-validation gọi lại SDK**, xóa bản sao.

## 4.2. Public API (Kotlin, phác thảo ký hiệu)

```kotlin
// Entry + config (mọi thứ rẻ-để-sửa đi qua đây, có version)
val tts = VieNeuTts.Builder(context)
    .setProfile(GenerationProfile(    // GenerationProfile có profileRevision
        steps = 8, sway = -1f, cfg = 3.0f,
        trebleFilterEnabled = true,   // Butterworth 6kHz mix 50%
        maxCharsPerChunk = 140
    ))
    .setProviders(listOf(ProviderId.CPU, ProviderId.XNNPACK)) // thứ tự ưu tiên; NNAPI opt-in
    .setThermalPolicy(ThermalPolicy.defaultTiers())
    .build()

val model = tts.modelManager().install(packageUri)   // checksum → atomic → probe-load → activate
tts.modelManager().load(ModelProfile(threads = 2))   // create sessions theo profile

val reader = tts.newReader()
reader.enqueue("Cả đoạn truyện dài...")             // tự chunk 140 ký tự
reader.play()
reader.pause()   // giữ queue
reader.cancel()  // hủy job + flush + dọn cache chunk
reader.setPrefetchDepth(3)
reader.metrics().subscribe { m -> logJson(m) }       // RTF/RAM/thermal/cache/error
tts.voiceManager().listPresets()                     // metadata index
tts.voiceManager().select("duc_tri")                 // hủy job cũ, flush, đổi conditioning (không session mới)
```

**Chốt sau Spike Giai đoạn 0:** tên package cuối, cơ chế lắng nghe (Flow vs callback), builder chi tiết.

## 4.3. ORT Engine chi tiết

1. **Dependency:** `com.microsoft.onnxruntime:onnxruntime-android:1.24.4` (bump khi có; ghi `runtime_revision` vào manifest). `abiFilters = listOf("arm64-v8a")` — không đóng `armeabi-v7a` khi chưa có lý do (thiết bị ARM64 phổ biến, đỡ package).
2. **Dependency injection ORT:** không `System.loadLibrary` thủ công; để AAR lo.
3. **Load:** copy asset theo revision ra `context.filesDir/models/<revision>/` → `OrtSession(env, path, opts)` — **bắt buộc dùng path, không dùng `createSession(byte[])`** (OOM Dalvik đã gặp ở vòng 2 với graph 155 MB).
4. **Provider ladder (đã chốt D2):** theo thứ tự profile: `CPUExecutionProvider` → `addXnnpack(emptyMap())` → `addNnapi()` (chip check + try/catch init). Nếu rớt provider → **log rõ** (provider thật, lý do) vào Metrics.
5. **Input BOOL:** Kotlin dùng session trực tiếp không gặp vấn đề wrapper như iOS — nhưng kiểm tra test `ctx_mask` BOOL ở mọi graph version bump (contract test).
6. **Tensor:** `TensorPool` reuse `FloatBuffer` cho input/output; input int64/bool dùng buffer riêng kích thước cố định. Một generation job active → không cần pool lớn.
7. **Session count:** đúng 4 session (mỗi graph một), tồn tại suốt `load()`. Voice switch **không** tạo session.

## 4.4. Generation & playback

- **GenerationQueue:** `ArrayBlockingQueue<ChunkJob>(prefetchDepth)` + scheduler ưu tiên chunk gần (`schedulerNear`). `cancel()` = báo cờ + worker kết thúc ở biên chunk hiện tại, không giết giữa tensor.
- **AudioTrack:** streaming mode, 24 kHz mono PCM 16-bit; buffer đủ 2 chunk trước khi phát; `publishProgress`.
- **AudioFocus:** yêu cầu focus khi play, pause khi mất focus (có callback).
- **Background:** không dùng WorkManager cho synthesis (cần liên tục); dùng **foreground service với notification** nếu app cần đọc khi screen off — optional cho integrator, SDK cung cấp helper nhưng không ép.
- **Speed:** thay đổi tốc độ ở playback (resample/AudioTrack playback rate), **không** đổi model. Phạm vi 0.75–1.5× (chốt ngưỡng ở QA listening).

## 4.5. Thermal & memory policy

- ThermalPolicy 3 tầng theo §1.7; tín hiệu từ `PowerManager.addThermalStatusListener` + `ThermalManager` (API 29+), fallback `ACTION_BATTERY_CHANGED` + đồng hồ nội bộ cho API thấp.
- Memory: `ComponentCallbacks2.onTrimMemory` → giảm prefetch, flush cache xa, giữ session.
- Khi thermal Tầng 3: thread=1, prefetch=2, thêm idle nhỏ giữa chunk (giá trị config ở `ThermalPolicy.defaultTiers()`), ghi thermal bucket vào metrics.

## 4.6. Kiểm thử Android (tóm tắt — chi tiết `07-kiem-thu.md` §4)

| Tầng | Công cụ | Nội dung |
|---|---|---|
| Unit (JVM) | JUnit5 + truth | core thuần: chunk/gap/filter/sway golden vectors; ModelRegistry corrupt/missing/wrong-checksum |
| Instrumented | `connectedAndroidTest` trên emulator CI | engine smoke, lifecycle (cancel giữa phase), multi-provider fallback |
| Device thật | sample-app + `NanoBenchmarkCore` (foreground Activity) + adb polling | RTF/RAM/thermal đúng protocol; **không dùng instrumented test trên OnePlus** (OplusHansManager) |
| Contract | JVM test vs golden tensor từ `v3nano-mobile/host/export_corpus_tensors.py` | output sai lệch < tolerance |

Mọi bước: artifact machine-readable vào `v3nano-mobile/eval/reports/`.

## 4.7. Checklist trước khi đóng băng SDK Android

- [ ] Build sạch `./gradlew assembleRelease` cho arm64-v8a, không cảnh báo lỗi
- [ ] 111 pytest baseline + core JVM tests + contract tests xanh trong CI
- [ ] Load model qua `createSession(path)`; provider fallback log đúng
- [ ] Sample app chạy 30 phút không underrun trên thiết bị thật (Sprint 4 DoD)
- [ ] AAR chứa API docs (KDoc) + mẫu tích hợp; không ship model weights trong AAR (tải/registry riêng)