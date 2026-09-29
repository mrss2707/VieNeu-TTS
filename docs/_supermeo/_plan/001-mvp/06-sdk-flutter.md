# 06 — Plugin Flutter (wrapper SDK native)

## 6.1. Nguyên tắc cốt lõi

**Flutter không port engine.** Một engine native (Kotlin/Swift ở `04`/`05`), plugin Flutter chỉ là **cầu nối mỏng**:

- Không có inference trong Dart, không copy model, không đóng gói model riêng cho Flutter.
- Mọi logic quality-sensitive (chunk, join, sway, filter, determinism) nằm đúng một nơi duy nhất: core native → Flutter tự động đúng y hệt Android/iOS native.
- Lợi ích cho team: nhà phát triển Flutter (phổ biến cho app đọc truyện) dùng SDK mà không cần viết Kotlin/Swift; kiểm thử Flutter đồng thời là kiểm thử tích hợp cho SDK native.

## 6.2. Cấu trúc thư mục

```text
mobile/flutter/
├── packages/
│   └── vieneu_tts/                              # plugin (pub package)
│       ├── pubspec.yaml                         # name: vieneu_tts
│       ├── lib/
│       │   ├── vieneu_tts.dart                  # export
│       │   ├── src/
│       │   │   ├── vieneu_tts_plugin.dart       # nối MethodChannel ↔ platform
│       │   │   ├── reader.dart                  # Reader (Dart) — facade gọi channel
│       │   │   ├── voice_manager.dart
│       │   │   ├── model_manager.dart
│       │   │   ├── generation_profile.dart
│       │   │   ├── metrics.dart                 # stream metrics từ native
│       │   │   └── engine_error.dart            # map error code native → Dart exception có loại
│       │   └── src/pigeon/                      # sinh code từ Pigeon (đừng sửa tay)
│       │       ├── api.dart / api.g.dart        # Dart side
│       ├── android/                             # Kotlin host: gọi SDK Android (module Gradle)
│       │   └── ios/                             # Swift host: gọi SDK iOS (SPM)
│       └── test/                                # widget/unit test + mock Pigeon host
└── example/                                     # app mẫu Flutter + integration_test/
    ├── lib/main.dart                            # UI đơn giản: nhập text, chọn giọng, play/pause
    └── integration_test/                        # chạy trên device thật (chính là test project Flutter)
```

**Giao tiếp native↔Dart:** Pigeon (type-safe, sinh code hai phía, không dùng JSON thủ công). Audio không đi qua channel (PCM lớn — copy không cần thiết): **playback nằm hoàn toàn phía native**; Dart chỉ gửi lệnh và nhận event (tiến độ, hết bài, lỗi, metrics).

## 6.3. Bề mặt API Dart (phác thảo)

```dart
final tts = VieNeuTts(
  profile: const GenerationProfile(
    steps: 8, sway: -1.0, cfg: 3.0,
    trebleFilterEnabled: true, maxCharsPerChunk: 140,
  ),
  providerOrder: const [NativeProvider.cpu, NativeProvider.xnnpack],
);

await tts.modelManager.install(assetPath: 'assets/model.vnm.bundle');
await tts.modelManager.load();

final reader = await tts.createReader();
await reader.enqueue('Cả đoạn truyện dài...');
await reader.play();
await reader.pause();                       // giữ queue
await reader.cancel();                      // hủy job + flush
await reader.setPrefetchDepth(3);

await for (final m in reader.metrics) { log(m.rtf, m.thermalBucket); }
final voices = await tts.voiceManager.listPresets();
await tts.voiceManager.select('duc_tri');   // hủy job cũ, flush, đổi conditioning
```

- Kiểu lỗi Dart map 1:1 với bảng mã lỗi native (xem `08` §mã lỗi) — `EngineError.checksum`..., không có `PlatformException` thô lọt ra người dùng.
- Event: `ReaderEvent.onProgress/onDone/onUnderrunWarning/onError(EngineError)`.
- Tất cả cấu hình "rẻ-để-sửa" truyền qua `GenerationProfile` (có `profileRevision`) — khớp native.

## 6.4. Host Android (plugin)

- Plugin Gradle thêm dependency vào module SDK: `implementation(project(":vieneu-mobile-sdk"))`.
- `VieNeuTtsPlugin.kt`: tạo một instance `VieNeuTts` (singleton, gắn `applicationContext`), map từng lệnh channel → API SDK, đăng ký listener → trả event về Dart (via `EventChannel` cho metrics/events, method call cho lệnh).
- Foreground service/audio focus: do SDK xử lý; plugin không can thiệp thêm.

## 6.5. Host iOS (plugin)

- `Package.swift` của plugin phụ thuộc `VieNeuTTS` (SPM) — hoặc podspec nếu team chọn CocoaPods (chốt 1 cách, đừng duy trì cả hai).
- `VieNeuTtsPlugin.swift`: singleton `VieNeuTts`, chuyển lệnh Pigeon → SDK, `FlutterEventChannel` cho events/metrics.

## 6.6. Kiểm thử Flutter (cũng là mục §5 của `07-kiem-thu.md`)

| Tầng | Công cụ | Nội dung |
|---|---|---|
| Unit (không native) | `flutter test` + mock Pigeon host | facade call đúng chuỗi lệnh; map lỗi đúng mã; profile truyền đúng field |
| Integration | `integration_test` chạy trên **Android/iOS thật** | enqueue→play→event onDone; pause/resume không lặp chunk; cancel giữa đoạn dài không crash; voice switch đúng giọng; determinism (2 lần chạy seed=42 → checksum PCM giống — phía native trả giá trị băm, không gửi PCM qua channel); error checksum sai |
| Đối chiếu chất lượng | xuất WAV từ native lưu file (do plugin mở helper chỉ cho test build) → chạy `run_asr_quality_check.py` | WER/CER khớp candidate chốt |

Lưu ý testing: cho test build, plugin expose method ẩn `debugExportLastChunkWav(path)` để so sánh audio — **không** có trong release build.

## 6.7. Đóng gói & phân phối Flutter

- pub package chỉ chứa Dart + thin hosts (vài chục KB); **model không nằm trong plugin** — app Flutter tự tải bundle theo cơ chế của `ModelManager` (assets hoặc registry — cùng cơ chế native).
- Version chart: `vieneu_tts` phiên bản đi theo SDK revision; `pubspec.yaml` + compatibility check khi gọi `load()`.
- Document cho integrator: cách ghép bundle vào assets, permissions Android cần thiết, background audio trên iOS.
- CI: `flutter analyze` + `flutter test` mỗi PR; `flutter drive`/integration_test chạy nightly trên device sẵn có.

## 6.8. Checklist trước khi đóng băng plugin

- [ ] Pigeon schema versioned; thêm phương thức mới = bump minor, không xóa field cũ trong minor cũ
- [ ] Không có `PlatformException` thô lọt API Dart; mọi lỗi qua `EngineError` có mã
- [ ] Example app build sạch cho Android + iOS; integration test xanh trên ít nhất 1 device mỗi platform
- [ ] Metrics/event không gửi dữ liệu nhạy cảm qua channel (chỉ metadata — §1.10)
- [ ] `debugExportLastChunkWav` bị loại khỏi release (guard `kReleaseMode`)