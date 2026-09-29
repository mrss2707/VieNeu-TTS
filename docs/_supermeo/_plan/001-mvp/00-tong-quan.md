# 00 — Tổng quan: chiến lược, quyết định đã chốt, dữ liệu nền

> Nguồn sự thật: `docs/_supermeo/_summary/001-v3-nano-mobile.md`. Mọi số liệu ở đây là **số đo thật** đã được ghi nhận trong 9 vòng validation (2026-09-27 → 2026-09-29). Tài liệu này tóm tắt để thực thi — khi cần chi tiết lịch sử, quay lại summary.

## 0.1. Chiến lược: MVP trước, validation nhúng

**Đã chốt (2026-09-29, §0.13):** dừng mô hình "verify hoàn toàn theo protocol 002 rồi mới xây". Bây giờ:

```
Xây MVP trước  +  các hạng mục validate còn lại = Definition of Done của từng sprint
```

Lý do (phân tích chi phí đã ghi ở §0.13):

| Đường đi | Tới sản phẩm dùng được | Nhược điểm chính |
|---|---|---|
| Verify hoàn toàn trước → build | ~20–31 tuần | Nhóm H5/H12/H15–H18 bị chặn bởi chính MVP chưa tồn tại |
| **MVP trước → improve dần (đã chọn)** | **~12–18 tuần + spike de-risk 2 tuần** | Chỉ rủi ro nếu chốt sai quyết định *đắt-để-sửa* → giải quyết bằng spike |

**Nguyên tắc phân biệt quyết định:**

| Loại quyết định | Ví dụ | Cách xử lý |
|---|---|---|
| Đắt-để-sửa (không đảo ngược được) | Package format, ngưỡng thiết bị tối thiểu, voice asset schema/manifest, chunk-pipeline contract | **Chốt bằng spike trước khi code SDK** |
| Rẻ-để-sửa | `steps`, filter, provider, thread/prefetch depth | Cải dần bằng cấu hình (config-driven) |

→ Mọi thứ "rẻ-để-sửa" phải được thiết kế **thay đổi qua config/flags, không qua sửa code**.

## 0.2. Các quyết định đã chốt (đừng tranh luận lại trong MVP)

| # | Quyết định | Giá trị | Nguồn |
|---|---|---|---|
| D1 | Cấu hình generation mặc định | **`steps=8, sway=-1`** + treble filter (Butterworth bậc 2, cutoff 6 kHz, mix 50% với bản gốc) | §0.10 |
| D2 | Android provider | **CPU (ưu tiên) → XNNPACK (fallback)**; NNAPI chỉ là optional experiment | §0.11 |
| D3 | iOS provider | **CPU (duy nhất)**; CoreML loại — Metal texture limit (seq 29184 > 16384 trên iPhone 11) | §0.11 |
| D4 | Precision ban đầu | **FP16 cả 4 graph** (sau spike xác nhận trên device); fallback ladder: INT8 → FP16 → FP32 | §0.12 |
| D5 | Package hình dạng | **Preset-only (P1)**: text encoder + duration predictor + vector estimator + codec decoder + 11 voice assets. Cloning/denoiser → optional package sau MVP | §3.3 |
| D6 | Chunk pipeline | `max_chars=140`/chunk, ghép bằng `join_audio_chunks` + silence gap — **port sang native bắt buộc** (bài học bug "nuốt chữ" vòng 8) | §0.8 |
| D7 | Runtime baseline | ONNX Runtime Mobile; macOS host là fast-gate, **không** thay thế device test | §8 |
| D8 | Giọng mặc định SDK | **Khuyến nghị chuyển từ "Minh Quân" (WER 19.63%)** sang "Đức Trí" (3.12%) hoặc "Mạnh Dũng" (5.04%) — cần chốt chính thức trong spike | §0.12 |
| D9 | Branch | Phát triển trên `develop`; `main` là baseline tham chiếu; ID model mới `v3-nano-mobile` | §0, §3.1 |

## 0.3. Dữ liệu đo canonical (nền để đặt ngưỡng sprint)

> ⚠️ Số WER/CER chỉ tin các giá trị "pipeline đã sửa" (sau vòng 8, có chunking + join đúng).

### Hiệu năng trên thiết bị thật

| Metric | Android (OnePlus PHP110, Dimensity 9200-class) | iOS (iPhone 11, A13) | Target MVP |
|---|---:|---:|---:|
| RTF median `steps=8/sway=-1`, CPU | **0.3239** (p95 0.4182) | **0.2936** | ≤ 0.5 ✅ đạt cả hai |
| RTF median `steps=8`, XNNPACK | 0.3528 (p95 0.4704) | — | ≤ 0.5 ✅ |
| RTF median `steps=8`, NNAPI | 0.4222 (p95 0.6608) | — | ❌ p95 vượt → loại khỏi default |
| RTF median `steps=16`, CPU (baseline) | 0.6077 | 0.7035 | ❌ vượt target |

### Tài nguyên

| Metric | Giá trị đo |
|---|---|
| Model bundle (HF) | `pnnbao-ump/VieNeu-TTS-v3-Nano@aba295eb…`, 7 graph + config + constants.npz, ~420 MB, opset 17 (speaker_encoder: 14) |
| Package FP32 4-graph | 268.6 MB → **FP16 134.6 MB** (giảm 2.0×, chất lượng không xấu đi) |
| Package P1 preset-only FP32 | 281.8 MB raw / 261.8 MB gzip — vượt target 150 MB gần 2× → **FP16 là đường đi chính** |
| Per-graph RAM (macOS host) | text_encoder 68.5 MB · duration_predictor 0.2 MB · **vector_estimator 151.6 MB** · codec_decoder 68.9 MB |
| RAM thiết bị thật (Android) | peak RSS 1,089.5 MB / PSS 914.4 MB (CPU/XNNPACK ~670 MB; NNAPI ~910 MB) |
| Thermal (Android, 20.6 phút) | SKIN 44→50.6°C; **NONE→SEVERE sau ~4 phút**; không CRITICAL; pin ~15%/h |
| ORT optimization level | Giảm load time ~33% (548→370 ms) nhưng **RAM không đổi** → RAM do weights quyết định |

### Chất lượng (WER/CER canonical, n=54, pipeline đúng)

| Cấu hình | WER | CER |
|---|---:|---:|
| steps=16 (baseline đã sửa pipeline) | 8.50% | 7.77% |
| steps=12 | 9.66% | 8.21% |
| steps=8 raw | 10.79% | 9.84% |
| **steps=8 + treble filter (chốt)** | **9.86%** | **7.12%** |
| steps=8 FP16 (4 graph) | 9.96% | 7.96% |

### Voice matrix (11 voice, steps=8)

- Tốt nhất: **Đức Trí 3.12%** · kém nhất: Hữu Quân 20.29% (chênh 6.5× giữa các giọng)
- ⚠️ Giọng mặc định cũ "Minh Quân" = 19.63% — thuộc **nhóm kém** → xem quyết định D8
- Determinism: `seed=42` giống nhau tuyệt đối (max_diff=0.00); voice switch A→B→C→A không lẫn state

### Hạ tầng đã tồn tại (tái sử dụng, đừng viết lại từ đầu)

```text
v3nano-mobile/android/device-validation/   # app validate device — NanoBenchmarkCore + MainActivity (foreground)
v3nano-mobile/ios/device-validation/       # app validate device — XcodeGen/ObjC++ NanoEngineTests
v3nano-mobile/host/                         # build_corpus, export_corpus_tensors, run_*_benchmark, run_asr_quality_check, apply_treble_filter
v3nano-mobile/eval/corpus/             # smoke 20 câu + stress 20 câu + audiobook_short + voice_matrix + manifest
v3nano-mobile/eval/reports/*.json      # toàn bộ report vòng 1–9
```

**Bài học hạ tầng quan trọng đã trả giá (đọc kỹ trước khi code):**

1. **Android OnePlus/Oppo:** instrumented test đóng băng do `OplusHansManager` (OEM coi tiến trình không-UI là background). **Giải pháp:** benchmark chạy trong Activity foreground có UI + `FLAG_KEEP_SCREEN_ON` (xem vòng 5).
2. **iOS:** ObjC wrapper của `onnxruntime-swift-package-manager` (kể cả 1.24.2) **không expose tensor BOOL** → cần custom bridging ORT C++ API cho `ctx_mask` (xem vòng 2, chi tiết ở `05-sdk-ios.md`).
3. **Android:** `createSession(byte[])` OOM trên Dalvik heap với graph 155 MB → **phải copy asset ra file cache rồi `createSession(path)`**.
4. **Bug chunking:** gọi thẳng `OnnxV3NanoEngine.infer()` cho câu dài (>140 ký tự) làm model "nuốt chữ" — mọi path sinh âm (gồm cả công cụ ASR measure) phải đi qua chunk pipeline đúng (vòng 8).

## 0.4. Mục tiêu MVP (kiểm chứng, không phải cam kết)

| Nhóm | Mục tiêu |
|---|---|
| Chức năng | Offline trên Android ARM64 + iOS/iPadOS; preset-only; đọc văn bản dài bằng chunk streaming + prefetch; pause/cancel/resume/background; không cần Python/network |
| Package | Preset-only **≤150 MB** (FP16); RAM target 300–400 MB trên tier B (đang vượt, FP16 + chính sách là đường khắc phục) |
| Tốc độ | RTF ≤0.5 tier B; cold start ≤3 s; bắt đầu phát ≤2 s sau khi có text |
| Ổn định | ≥30 phút đọc liên tục không underrun; không thermal CRITICAL/hạn chế nghiêm trọng |
| Chất lượng | Không regression đáng kể so với baseline; không NaN/crash/babble; speaker ổn định giữa chunk; không click/pop ở boundary |
| Tương thích | Không đổi behavior `v3 Nano` cũ; test cũ phải tiếp tục pass (111 tests baseline) |

**Device tier bắt buộc ghi theo tier:** Tier A (flagship) / Tier B (tầm trung phổ biến) / Tier C (thấp nhất được hỗ trợ — chưa chốt, trả lời bằng spike).

## 0.5. Phạm vi

**In scope MVP:** export/đóng gói graph mobile từ baseline; ORT Mobile ARM64; XNNPACK; CoreML optional-experiment trên iOS / NNAPI optional trên Android (luôn có CPU fallback); FP16 (INT8 sau G2 nếu cần); static shape theo bucket; SDK Kotlin + Swift + wrapper Flutter; chunk queue/prefetch/cancel/cache; benchmark device + quality regression + thermal; manifest/checksum/rollback.

**Out of scope MVP:** cloning/denoiser (optional package sau); training/distill/QAT; multi-user server; thay thế v3 Nano cũ; đóng Python runtime lên mobile; hỗ trợ mọi board ARM.

## 0.6. Điều kiện phát hành MVP nhắc nhanh (chi tiết: `02-lo-trinh-sprint.md`)

- Namespace + manifest + checksum đầy đủ; preset-only chạy offline trên Android + iOS thật
- CPU/XNNPACK fallback hoạt động; CoreML/NNAPI được test và fallback rõ ràng
- Queue/prefetch/cache/cancel cơ bản; test graph/audio/lifecycle/memory trong CI
- Benchmark ≥1 flagship + ≥1 mid-range mỗi platform (đây là phần tiêu tốn nhất — sprint dành riêng)
- Report 30 phút playback liên tục; license/attribution inventory