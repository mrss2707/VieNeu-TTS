# 07 — Chiến lược kiểm thử & ba project kiểm thử (Android / iOS / Flutter)

Kiểm thử là một phần của DoD mỗi sprint (chiến lược "validation nhúng"). Tài liệu này: (1) phân tầng kiểm thử, (2) ngưỡng chấp nhận, (3) chi tiết ba project kiểm thử, (4) quy trình chạy.

## 7.1. Bốn trục kiểm thử (bắt buộc đủ cả bốn)

| Trục | Nội dung | Nguồn gốc |
|---|---|---|
| **Model correctness** | checker/shape, determinism, reference comparison, NaN/Inf, duration | Protocol 002 §… & Phase 7 summary |
| **Audio quality** | ASR/WER-CER, bỏ/lặp từ, số/ngày tháng/tên riêng, dấu câu, code-switching, speaker consistency, chunk boundary, listening test | H9 |
| **Device/runtime** | tier A/B/C, provider, cold/warm, RAM, 30 phút, thermal, pin, background, low storage, kill/resume | H5–H8 |
| **SDK reliability** | cancel giữa mọi phase, pause/resume, switch voice/chapter, package hỏng/checksum sai/ABI sai, provider init fail, disk full | H12/H18 |

**Nguyên tắc đo (kế thừa 002):** một biến một lần · không suy luận simulator→device · quality và hiệu năng là hai gate độc lập · báo **median/p95/variance**, không lấy lần nhanh nhất · artifact machine-readable + reviewer ký.

## 7.2. Ngưỡng chấp nhận MVP (đặt trước, ghi vào CI)

| Gate | Ngưỡng |
|---|---|
| Hard-fail (bất kỳ đâu) | 0 crash/ANR/NaN/Inf/checksum-sai/underrun-lặp |
| RTF (Tier B, steps=8) | median ≤ 0.5 |
| Package | P1 FP16 ≤ 150 MB (nén, đo cả Android AAB + iOS IPA) |
| WER/CER | candidate ≤ baseline (9.86/7.12) +1 điểm tuyệt đối; riêng voice critical không vượt quá ngưỡng phê riêng |
| Determinism | seed=42 → max_diff = 0.00 (cross-platform: pcm checksum khớp giữa Android/iOS/macOS — chốt được thì H14 PASS) |
| Playback | 30 phút liên tục không underrun (Tier B nếu có device) |
| Thermal | không CRITICAL trong 30 phút; policy tầng 3 hoạt động (log bucket) |
| Memory | peak RSS ghi lại (target 300–400MB là hướng cải thiện, không chặn release nếu quality/thermal ổn) |

## 7.3. Tầng test theo tốc độ (kim tự tháp)

```text
L0 host (mili-giây → giây, chạy mọi PR):      unit core (chunk/join/sway/filter/registry) + golden vector + manifest validator
L1 host-heavy (phút):                          port-compare Python-vs-native 40 case corpus + ASR quality check macOS
L2 emulator/simulator (chục phút, nightly):    instrumented/XCTest lifecycle + multi-provider (kết quả kèm nhãn "ảo hóa — chỉ hard-fail")
L3 device thật (giờ, thủ công/nightly):        RTF/RAM/thermal/pin + listening review — nguồn sự thật duy nhất cho hiệu năng
```

## 7.4. Project kiểm thử Android (chi tiết)

**Vị trí:** `v3nano-mobile/android/device-validation/` (giữ) + `v3nano-mobile/android/sdk/src/{test,androidTest}` và (khi có) `v3nano-mobile/android/sample-app/`.

### A. Unit tests (JVM — CI mỗi PR)

```text
TextSegmenterTest        — 40 case corpus: số chunk, gaps khớp golden từ export_corpus_tensors.py
ChunkJoinerTest          — silence_ps padding, edge_silence khớp Python (sai lệch ±1 sample)
FlowSchedulerTest        — tg công thức sway cho steps ∈ {8,12,16} khớp NumPy golden đến 1e-6
TrebleFilterTest         — đáp ứng Butterworth bậc 2/6kHz vs scipy golden; power không đổi >3dB
DeterministicRngTest     — chuỗi ngẫu nhiên khớp numpy rng theo seed
ModelRegistryTest        — install đúng checksum OK / sai checksum E_MODEL_CHECKSUM; corrupt file E_MODEL_CORRUPT;
                           rollback về revision cũ sau probe-load fail; interrupted install không để half-state
VoiceIndexTest           — 11 voice load metadata, chọn voice không tạo session (đếm session qua fake engine)
```

### B. Instrumented tests (`connectedDebugAndroidTest` — emulator CI / device không-OEM-quirk)

```text
EngineSmokeTest          — load 4 graph qua createSession(path); ctx_mask BOOL; 1 chunk steps=8 không NaN
ProviderFallbackTest     — XNNPACK init fail (mock) → tự rớt CPU + log đúng
LifecycleTest            — cancel ở giữa generate (giả lập bằng queue kích hoạt), pause/resume, đổi voice giữa chừng
CacheTest                — LRU evict, quota, xóa khi cancel
```

### C. Benchmark device thật (foreground Activity — bắt buộc vì OplusHansManager)

`sample-app` có mode benchmark: `MainActivity` + `NanoBenchmarkCore` (dùng chung với SDK). Kịch bản:
1. Full corpus 40 case × 3 provider (CPU/XNNPACK/NNAPI-opt-in) × 3 lần → RTF median/p95/stddev + graph load + `any_hard_fail`.
2. `st17` (4 chunks) regression: audio_seconds ≈ 21.0, không NaN (khóa bug chunking).
3. Soak 30 phút + `adb shell dumpsys meminfo/thermalservice/battery` poll 15s → JSON như report `2026-09-28-android-real-device-ram-thermal.json`.

**Chạy:** `./gradlew sample-app:installDebug` + điều khiển qua adb input/UI; evidence lưu `evidence/` (đã có thư mục sẵn).

## 7.5. Project kiểm thử iOS (chi tiết)

**Vị trí:** `v3nano-mobile/ios/device-validation/` (giữ) + `v3nano-mobile/ios/sdk/VieNeuTTS/Tests/` và (khi có) `v3nano-mobile/ios/sample-app/`.

### A. XCTest host (CI — không cần device)

```text
ChunkPipelineTests       — golden vectors port (chunk split, join gaps, sway schedule, treble filter)
EngineBridgeTests        — load 4 graph từ bundle test fixture; BOOL ctx_mask đúng; determinism seed=42 hai lần bằng nhau
ModelManagerTests        — checksum sai từ chối; probe-load fail không kích hoạt
```

### B. XCTest device thật (`xcodebuild test` như harness hiện tại)

```text
testProviderCPU          — full 40 case × 3 lần, steps=8/sway=-1, chunking correct (giữ payload đúng từ vòng 8)
testProviderCoreML       — khóa lỗi hiện tại thành SKIP kèm lý do TextureLimit — KHÔNG xóa; bật lại khi graph static-shape
testLifecycle            — interruption (giả lập AVAudioSession notification), memory warning, thermal callback
```

**Chạy:** `xcodebuild test -scheme DeviceValidation -destination 'platform=iOS,id=<UDID>' -only-testing:...` (hoặc scheme của sample-app khi có).

## 7.6. Project kiểm thử Flutter (chi tiết)

**Vị trí:** `mobile/flutter/packages/vieneu_tts/test` + `mobile/flutter/example/integration_test`.

```text
# Unit (mock Pigeon — không cần device)
reader_test.dart              — enqueue gửi đúng chuỗi lệnh; pause giữ queue; cancel flush; setPrefetchDepth map đúng
error_mapping_test.dart       — từng mã lỗi native → EngineError đúng loại + message tiếng Việt
profile_test.dart             — GenerationProfile truyền đủ field qua channel (steps/sway/cfg/filter/maxChars)

# Integration (device thật — flutter test integration_test)
smoke_test.dart               — install bundle → list 11 voice → enqueue câu ngắn → onDone trong <30s
long_read_test.dart           — đoạn dài (>140 ký tự, bắt tạo 2+ chunks) → onDone; đếm event progress; không underrun warning
cancel_resume_test.dart       — cancel giữa đoạn dài; resume không lặp chunk đầu
voice_switch_test.dart        — đổi giọng A→B→A, onDone vẫn đúng; không crash
determinism_test.dart         — 2 lần chạy seed=42 → debugExportLastChunkWav → 2 file checksum giống nhau
error_test.dart               — bundle checksum sai → EngineError.checksum; không crash app
```

**Chạy:** `flutter test integration_test -d <device>` (mỗi platform ít nhất 1 device khi có; CI nightly nếu máy test có device).

## 7.7. Quality pipeline dùng chung (cả 3 platform)

Với mọi thay đổi có khả năng ảnh hưởng chất lượng (filter, steps, FP16, port engine):

1. Sinh audio candidate trên đúng target (macOS / Android real / iOS real — ghi rõ).
2. `v3nano-mobile/host/run_asr_quality_check.py` — **đã tham số hóa --steps/--sway/--treble-filter/--onnx-dir/--all-voices/--report-suffix**; phải chạy qua `V3NanoVieNeuTTS.infer()` (pipeline đúng — bài học vòng 8).
3. So sánh với baseline canonical WER/CER (bảng §0.3 của `00-tong-quan.md`); lưu JSON report vào `v3nano-mobile/eval/reports/`.
4. Listening review (≥2 người) — mẫu quy trình: chọn ngẫu nhiên 10 cặp candidate-vs-baseline, A/B mù, ghi préférence + ghi chú artifact; kết quả kèm report (thư mục `v3nano-mobile/eval/listening_test_*` đã có tiền lệ).

## 7.8. Tổ kiểm thử nào chặn phát hành?

Trước mỗi release channel: L0+L1 100% xanh · L2 xanh (hard-fail) · L3 tối thiểu trên devices khai báo trong release note · quality pipeline cho candidate cuối · listening review ký · license inventory ký. Mục nào không chạy được vì thiếu thiết bị → ghi `NOT RUN` + lý do vào release note (không bao giờ giả lập số).

## 7.9. Cải tiến liên tục (scale dễ hơn)

- Mọi test data dùng corpus versioned (`v3nano-mobile/eval/corpus/manifest.json`) — mở rộng 300–1000 câu là thêm file + bump manifest, không đổi mã test.
- Golden file sinh từ script (không dùng tay): `v3nano-mobile/host/export_corpus_tensors.py` mở rộng nếu corpus mới.
- Báo cáo luôn ghi `model_revision + runtime_revision + sdk_revision + device + provider + precision + steps` (mẫu JSON §8 summary) — cần cho so sánh lịch sử và H11.