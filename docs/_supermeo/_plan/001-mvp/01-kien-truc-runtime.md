# 01 — Kiến trúc runtime tổng thể

Tài liệu này là "bản thiết kế nhà" — mọi module SDK, manifest, contract ở các tài liệu sau đều neo vào đây. Đọc trước khi code bất kỳ module nào.

## 1.1. Ba lớp version độc lập (bắt buộc)

Không bao giờ gộp version model/runtime/SDK làm một. Mọi artifact, log, report phải ghi đủ ba:

```
1. Model revision     — trọng số, graph, quantization          (vnm-0.1.0, tokenizer, opset)
2. Runtime revision   — ORT Mobile / provider adapter           (ort-1.24.x, ep-cpu/xnnpack)
3. SDK revision       — API Kotlin/Swift/Dart, cache, playback  (sdk-0.x.y, queue, thermal policy)
```

App kiểm tra **compatibility matrix** trước khi load:

```text
model_revision + runtime_revision + sdk_revision + target_abi (+ precision + voice_asset_revision)
```

Mismatch (ví dụ voice asset cũ + model mới) → **từ chối rõ ràng + lỗi có mã**, không tự ý "chạy thử xem sao". Đây cũng là nền của H11 (sync `main`→`develop` không làm drift tokenizer/G2P mà không ai biết).

## 1.2. Sơ đồ tổng thể

```text
┌──────────────────────────── Application (demo app / integrator) ────────────────────────────┐
│                                        Flutter / native UI                                     │
└──────────────────────────────────────┬──────────────────────────────────────────────────────┘
                                       │ public API duy nhất (Kotlin / Swift / Dart)
┌──────────────────────────────────────▼──────────────────────────────────────────────────────┐
│  vieneu-mobile-sdk / VieNeuTTS (core tách platform — logic thuần, test được không cần thiết bị)             │
│                                                                                              │
│   ModelRegistry    TextSegmenter    VoiceManager    GenerationQueue                         │
│   (manifest,        (chunk split,     (preset index,  (bounded, priority,                   │
│    checksum,         gap calc)         switch safety)   cancel, prefetch)                    │
│   install/rollback)                                                          │                │
│   PlayerState      MetricsCollector  ThermalPolicy    AudioCache                                  │
│   (idle/loading/    (RTF, RAM,        (economy tiers,  (LRU disk, quota,                    │
│    playing/paused)  events)           thread budget)   checksum path)                        │
└──────────────────────────────────────┬──────────────────────────────────────────────────────┘
                                       │ API ổn định của engine (port từ Python — contract cố định)
┌──────────────────────────────────────▼──────────────────────────────────────────────────────┐
│  Engine Core (native) — port 1:1 từ OnnxV3NanoEngine/V3NanoVieNeuTTS                         │
│                                                                                              │
│   TextNormalizer+G2P   PhoneEncoder   DurationPredict   FlowVector(8 steps,sway=-1)          │
│   CodecDecoder         TrebleFilter   ChunkJoin(silence gap)   SeedRandom(determinism)       │
└──────────────────────────────────────┬──────────────────────────────────────────────────────┘
                                       │ Ort::Session (một session cho mỗi graph)
┌──────────────────────────────────────▼──────────────────────────────────────────────────────┐
│  Platform adapter                                                                             │
│   Android: ORT Mobile ARM64 + CPU EP (ưu tiên) / XNNPACK (fallback) / NNAPI (opt-in)          │
│   iOS:     ORT C++ API + CPU EP (duy nhất; CoreML opt-in sau graph-fix)                      │
│   Flutter: không có engine riêng — gọi SDK native qua Pigeon (xem 06-sdk-flutter.md)         │
└──────────────────────────────────────┬──────────────────────────────────────────────────────┘
┌──────────────────────────────────────▼──────────────────────────────────────────────────────┐
│  Model package (preset-only P1, FP16)                                                         │
│   text_encoder.onnx · duration_predictor.onnx · vector_estimator.onnx · codec_decoder.onnx   │
│   vo_codes.bin (tokenizer/G2P) · voices_v3_nano_mobile.json · manifest.json + checksums      │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

**Nguyên tắc vàng:**
- Core tách platform → test logic (chunk split, gap calc, determinism, voice switch) chạy được trên JVM/CI **không cần thiết bị**.
- Adapter nền tảng chỉ làm đúng một việc: "đưa tensor vào ORT, lấy tensor ra". Không đặt business logic ở đây.
- Flutter **không** port engine — một engine native, nhiều mặt UI. Mọi nỗ lực port engine lần hai là tech-debt nhân đôi (chúng ta đã trả giá bằng bài học port chunking thiếu ở vòng 8).

## 1.3. Mobile là chunk streaming, không phải frame streaming

```text
text segment N+1 → generate ở background worker → decode → treble filter → local buffer
text segment N   → đang phát
```

- Baseline: `max_chars=140`/chunk (giữ nguyên hợp đồng `normalize_to_chunks_v3_with_gaps`), ghép với silence gap → **port 1:1**, không "cải tiến" — mọi sai lệch so với Python = rủi ro quality mới. Có test comparing với Python output (tolerance quy định ở `07-kiem-thu.md`).
- Chunk đủ dài để giảm overhead, đủ ngắn để cancel nhanh và retry rẻ.
- Playback thread **không bao giờ** chờ inference. Buffer luôn đi trước ít nhất **2 chunk** (giá trị config, điều chỉnh theo tier).

## 1.4. Luồng dữ liệu chuẩn một lần đọc

```text
enqueue(text)
  → TextSegmenter.normalizeToChunks(max_chars=140)          [CPU rẻ, đồng bộ]
  → với mỗi chunk (theo thứ tự):
       GenerationQueue.push(chunk, priority=near          ) [bounded queue, hủy được]
  → worker đơn (single synthesis worker):
       phone-encode → duration → vector-flow (steps=8, sway=-1, cfg=3.0, seed từ job)
       → codec decode 24kHz mono → treble filter → padding gap
       → AudioCache.write(chunk_id, pcm)                    [disk LRU, quota]
       → queue.publish(chunk_ready)                         [PlayerBridge nhận theo thứ tự]
  → PlayerBridge (AudioTrack/AVAudioEngine):
       buffer ≥ 2 chunk → phát; buffer thấp → ưu tiên chunk gần; buffer đủ → hạ mức ưu tiên (pin)
  → hết text → đóng gap cuối + trả completion metric
```

**Cancel:** user đổi chương/giọng/tốc độ → `GenerationQueue.cancelAll()` → worker dừng job hiện tại ở **biên chunk tiếp theo** (không giết giữa tensor), flush audio queue, AudioCache xóa touched chunks. Đạt được nhờ: (1) một worker duy nhất, (2) chunk ngắn ≤~10s, (3) không giữ toàn bộ waveform chương trong RAM.

## 1.5. Concurrency & threading (rẻ-để-sửa → config theo tier)

| Quy tắc | Lý do |
|---|---|
| **Một** worker nền duy nhất cho synthesis (MVP) | Tránh race trên session; NNAPI/multi-job chỉ mở khi benchmark chứng minh lợi ích |
| **Một** session cho mỗi graph, dùng chung mọi request | RAM đo được: RAM ≈ weights; mỗi session thêm = RAM thêm |
| Không tạo thread-pool riêng cho từng ONNX session | ORT Mobile tự quản lý pool nội bộ |
| Thread count cấu hình theo tier (A:4, B:2, C:1 — chốt bằng benchmark) | Tránh đánh nhau với thermal |
| App vào background / máy nóng → **giảm** thread+prefetch, không tăng | Tiết kiệm pin, tránh SEVERE thermal (đã đo: SEVERE sau ~4 phút chạy suốt) |
| Ingress job (1 chunk cho phát ngay) độc lập queue ưu tiên | Đạt "≤2s sau khi có text" |

## 1.6. Memory

- Reuse input/output tensor buffer giữa các lần chạy; tránh copy Kotlin/Swift↔JNI↔native.
- Giải phóng intermediate tensor ngay sau mỗi graph.
- **Không** giữ waveform cả chương trong RAM — chỉ chunk hiện tại + buffer.
- Cache audio theo chunk trên **disk** (LRU + quota byte + checksum path theo model revision).
- Bounded queue (cấu hình sâu prefetch) — producer không chạy vô hạn khi user pause.
- Log peak allocation theo graph + theo device (metric bắt buộc trong `MetricsCollector`).
- ⚠️ Android: load graph bằng `createSession(path)` sau khi copy asset ra file cache (bài học OOM Dalvik vòng 2).

## 1.7. Thermal & pin (policy ba tầng)

Đo thật: SEVERE sau ~4 phút chạy full tốc, không CRITICAL, skin +6°C, pin ~15%/h. → ThermalPolicy là **component bắt buộc**, không phải nice-to-have:

```text
Tầng 1 "normal": steps=8, threads theo tier, prefetch depth  mặc định
Tầng 2 "warm"  : (thermal LIGHT→MODERATE) giảm prefetch, hạ thread
Tầng 3 "hot"   : (SEVERE) steps=8 giữ nguyên, thread=1, prefetch=2 chunk tối thiểu, thêm pause ngắn giữa chunk
```

- Tín hiệu: Android `PowerManager/thermal callbacks` + `dumpsys thermalservice` khi benchmark; iOS `NSProcessInfoThermalStateDidChangeNotification`.
- Tầng nào cũng **không** đổi sang steps cao hơn lén (đổi steps = đổi quality contract); chỉ điều chỉnh pacing/thread/prefetch.
- Sau spike soak 30 phút: nếu Tier A vẫn CRITICAL ở tầng 3 → xem `09-rui-ro` (risk R3).

## 1.8. Provider ladder & precision ladder (đã chốt D2/D3/D4)

```text
Android: CPU → XNNPACK (fallback) → [NNAPI opt-in, phải có capability check + tự rớt về CPU, log rõ]
iOS:     CPU → [CoreML opt-in SAU static-shape fix, tương tự NNAPI]
Precision: FP16 (mặc định sau spike) → FP32 reference (fallback) → INT8 (thử nghiệm sau G2)
```

Fallback **luôn log rõ ràng** (provider thực tế, lý do, latency) — không có "silent fallback" (gate G1).

## 1.9. API công khai tối thiểu (khung — chi tiết theo từng platform)

```text
ModelManager.install(manifest, package)    // verify checksum → atomic install → activate/rollback
ModelManager.load(profile)                 // tạo session theo profile (provider, threads, tier)
VoiceManager.listPresets()                 // index metadata (không load tensor)
VoiceManager.select(voiceId)               // preload conditioning; đổi giọng = hủy job, flush queue
Reader.enqueue(text)                       // chuẩn hóa + đẩy chunk vào queue
Reader.play() / pause() / cancel()
Reader.setPrefetchDepth(n) / setSpeed(x)   // speed: resample playback (không đổi model)
Reader.metrics()                           // RTF, RAM, thermal, cache hit/miss, error codes
```

Public API **không** expose tensor nội bộ, không phụ thuộc tên class Python cũ. Mọi tham số "rẻ-để-sửa" (`steps`, `sway`, `cfg`, filter on/off, provider) đi qua **profile/config object** có version, không phải hằng số trong code.

## 1.10. Mã lỗi & telemetry

- Error model: mã lỗi ổn định (ví dụ `E_MODEL_CHECKSUM`, `E_ABI_UNSUPPORTED`, `E_PROVIDER_INIT`, `E_CACHE_FULL`...) — danh sách đầy đủ ở `08-ci-cd-phat-hanh.md`.
- Telemetry mặc định **off**; khi opt-in chỉ ghi metadata (revision, tier, RTF, RAM bucket, thermal bucket, error code, cache hit/miss, lý do cancel). **Không bao giờ** ghi text/audio/embedding/waveform (xác nhận lại §9 summary — dữ liệu giọng là dữ liệu cá nhân).

## 1.11. "Dễ improve / scale" — các seam mở rộng cố ý

Những chỗ này được thiết kế mở ngay từ MVP, không phải refactor sau này:

| Seam | Mở rộng tương lai |
|---|---|
| `VoiceManager` chỉ giữ conditioning 1 voice | Voice pack download (H15), LRU voice cache, local enrollment (H16), adapter/LoRA (H17) — không phải đổi base model |
| `ModelRegistry` checksum + atomic install | Downloadable pack, rollback app-update giữa chương (H12) |
| `GenerationQueue` 1 worker | N-worker nếu benchmark nhiều job có lợi |
| Provider adapter interface | CoreML/NNAPI/INT8 thêm mà không đổi core |
| `PlayerBridge` abstraction | Thêm iOS/Android/Fuchsia sau |
| Corpus/report machine-readable | Mở rộng regression 300–1000 câu (H9) |
| Config profile theo tier | Thêm Tier C khi spike trả lời device floor |