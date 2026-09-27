# Plan 001 — VieNeu-TTS v3 Nano Mobile

- **Trạng thái:** Draft kế hoạch kỹ thuật
- **Phạm vi:** Android ARM64 và iOS/iPadOS trên thiết bị thật
- **Model mục tiêu:** một dòng model mới, tách biệt với v3 Nano hiện tại
- **Nguồn gốc:** lấy `main` làm baseline tham chiếu; phát triển độc lập trên `develop`
- **Đối tượng:** team phát triển Mobile/Edge
- **Không thay đổi trong plan này:** v3 Turbo, v3 Nano hiện tại, API server, model legacy và các public behavior đang phát hành

---

## 1. Tóm tắt điều hành

Mục tiêu là tạo một biến thể **v3 Nano Mobile** được thiết kế riêng cho thiết bị ARM mobile, ưu tiên chạy offline, ít RAM, ít nóng máy và giữ chất lượng đọc truyện/sách ổn định. Đây không phải là việc đổi tên hoặc chỉnh trực tiếp `v3 Nano` hiện tại; model, artifact, runtime adapter, voice package, benchmark và API mobile phải có namespace/version riêng để team khác vẫn có thể phát triển `main` và sau này merge feature vào `develop` mà không làm hỏng mobile branch.

Chiến lược an toàn là đi theo thứ tự:

1. Đóng băng baseline v3 Nano hiện tại từ `main`.
2. Tạo execution path mobile-only bằng ONNX Runtime Mobile trước.
3. Tách preset voice khỏi cloning/denoiser để giảm package và RAM.
4. Tối ưu graph, buffer và quantization theo từng bước có benchmark hồi quy.
5. Chỉ distill/QAT/prune sau khi đã có số đo trên thiết bị thật.
6. Đóng gói SDK native Kotlin/Swift với chunk prefetch, cache và thermal policy.
7. Chỉ merge ngược các feature thực sự generic, không merge mobile-specific behavior vào model cũ.

---

## 2. Mục tiêu và tiêu chí thành công

### 2.1. Mục tiêu chức năng

- Chạy offline trên Android ARM64 và iOS/iPadOS.
- Không cần Python, PyTorch, Hugging Face download hoặc server runtime.
- Tối ưu cho preset voice ở phiên bản đầu.
- Đọc văn bản dài bằng chunk generation và playback prefetch.
- Có pause/cancel/resume/background handling.
- Giữ cùng semantic contract về text normalization, G2P, voice ID và model revision với hệ sinh thái VieNeu khi phù hợp.
- Cho phép mở rộng cloning thành module tùy chọn, không đưa vào core mobile package.

### 2.2. Mục tiêu hiệu năng bản MVP

Các con số dưới đây là **target để kiểm chứng**, không phải cam kết trước khi benchmark:

| Chỉ số | Target MVP | Cách đo |
|---|---:|---|
| Sample rate | 24 kHz mono | Output contract |
| Package preset-only | ≤150 MB | Kích thước asset nén và giải nén |
| Peak RSS trên thiết bị tầm trung | ≤300–400 MB | Native memory profiler |
| RTF trên thiết bị tầm trung | ≤0,5 | Thời gian sinh / thời lượng audio |
| Cold start model | ≤3 giây | Từ load request đến ready |
| Thời gian bắt đầu phát | ≤2 giây sau khi có text | Bao gồm 1–2 chunk buffer |
| Playback liên tục | ≥30 phút | Không underrun trong điều kiện mạng không cần thiết |
| Nhiệt | Không throttling nghiêm trọng trong 30 phút | Android thermal API / Instruments |
| Chất lượng | Không giảm đáng kể so với baseline | ASR, listening test, lỗi phát âm |

Mỗi target phải được ghi theo **device tier**, không chỉ ghi một con số trung bình:

- Tier A: flagship iOS/Android.
- Tier B: thiết bị tầm trung phổ biến.
- Tier C: thiết bị thấp nhất được hỗ trợ.

### 2.3. Tiêu chí chất lượng tối thiểu

- Không NaN, crash, deadlock hoặc audio buffer không hợp lệ.
- Không tăng rõ rệt lỗi đọc số, tên riêng, dấu câu và câu thoại.
- Không tăng lặp từ, bỏ từ hoặc babble so với baseline.
- Speaker identity của preset ổn định giữa các chunk.
- Không có click/pop rõ rệt tại boundary.
- Chênh lệch loudness giữa các chunk nằm trong ngưỡng QA định trước.
- Output deterministic khi `seed` và model revision giống nhau, trong giới hạn backend cho phép.

---

## 3. Nguyên tắc kiến trúc bắt buộc

### 3.1. Tách model mới khỏi model cũ

Không sửa hoặc ghi đè các artifact hiện tại của v3 Nano. Model mobile phải có namespace riêng, ví dụ:

```text
vieneu-v3-nano-mobile/
  manifest.json
  model_mobile.onnx
  model_mobile.onnx.data
  codec_mobile.onnx
  voices_mobile.json
```

Mã nguồn nên đặt dưới namespace riêng, ví dụ:

```text
src/vieneu_mobile/
  runtime/
  preprocessing/
  voices/
  playback/
  packaging/
```

Không đổi behavior của:

- `vieneu.v3nano.V3Nano`.
- Artifact directory của v3 Nano hiện tại.
- Public factory mode cũ.
- Preset voice schema cũ nếu chưa có migration contract.
- Các test đang xác nhận behavior của model cũ.

Nếu cần dùng code chung, tạo module adapter/interface mới hoặc import một utility ổn định; không copy rồi âm thầm diverge. Nếu utility hiện tại chưa đủ ổn định, mobile giữ adapter riêng và ghi rõ lý do.

### 3.2. Tách execution profile khỏi training model

Có ba lớp version độc lập:

1. **Model revision:** trọng số, graph và quantization.
2. **Runtime revision:** ONNX Runtime Mobile/CoreML/NNAPI adapter.
3. **SDK revision:** API Kotlin/Swift, cache, playback và lifecycle.

Một app phải kiểm tra compatibility matrix trước khi load:

```text
model_revision + runtime_revision + sdk_revision + target_abi
```

### 3.3. Preset-first

Bản MVP chỉ đóng gói preset voice. Các thành phần sau phải là optional package:

- Speaker encoder.
- Codec encoder.
- Reference/style encoder.
- Denoiser.
- Voice enrollment UI.
- Voice embedding persistence.

Điều này giảm kích thước, RAM, attack surface và độ phức tạp pháp lý. Cloning chỉ mở sau khi core preset path ổn định trên thiết bị thật.

### 3.4. Mobile là chunk streaming, không phải frame streaming

Không cố tái sử dụng scheduler CUDA hoặc biến Nano thành frame-level stream ngay từ đầu. Mobile dùng pipeline:

```text
text segment N+1 → generate in background → local buffer → playback
text segment N   → playback
```

Chunk nên đủ dài để giảm overhead nhưng đủ ngắn để cancel và retry; baseline ban đầu là 3–8 giây audio hoặc 80–180 ký tự, sau đó chọn bằng benchmark corpus.

---

## 4. Phạm vi kỹ thuật

### 4.1. In scope

- Export/đóng gói graph mobile từ v3 Nano baseline.
- ONNX Runtime Mobile ARM64.
- XNNPACK CPU execution path.
- CoreML Execution Provider trên iOS nếu graph tương thích.
- NNAPI thử nghiệm trên Android với CPU fallback rõ ràng.
- INT8 weight-only/dynamic quantization.
- Static shape và memory reuse.
- Native Android Kotlin/Java adapter.
- Native iOS Swift adapter.
- Chunk queue, prefetch, cancellation và local audio cache.
- Device benchmark, quality regression và thermal test.
- Model manifest, checksum và rollback profile.

### 4.2. Out of scope cho MVP

- Thay thế hoặc deprecate v3 Nano hiện tại.
- Chỉnh Turbo hoặc legacy model.
- Multi-user server batching.
- Cloud voice cloning.
- Training long-form prosody hoàn chỉnh.
- NNAPI/NPU là dependency bắt buộc.
- CoreML/NNAPI-only deployment không có CPU fallback.
- Đóng gói nguyên Python runtime trên mobile.
- Tối ưu cho mọi ARM board ngoài Android/iOS target matrix.

---

## 5. Kiến trúc runtime đề xuất

```text
Application
   │
   ├── VieNeuMobileSDK
   │     ├── ModelRegistry
   │     ├── TextSegmenter
   │     ├── VoiceManager
   │     ├── GenerationQueue
   │     ├── AudioCache
   │     ├── ThermalPolicy
   │     └── PlaybackBridge
   │
   ├── Platform adapter
   │     ├── Android: ORT Mobile + XNNPACK (+ optional NNAPI)
   │     └── iOS: ORT Mobile + CPU (+ optional CoreML EP)
   │
   └── Model package
         ├── text encoder
         ├── duration predictor
         ├── vector estimator
         ├── codec decoder
         ├── preset voice assets
         └── manifest/checksums
```

### 5.1. Model loading

- Không tải model từ Hugging Face khi offline mode đang hoạt động.
- Hỗ trợ model bundled và model downloaded package có checksum.
- Dùng memory mapping hoặc asset streaming nếu platform/runtime hỗ trợ.
- Không load cloning graphs khi preset-only.
- Load lazy codec nếu architecture cho phép, nhưng phải đo trade-off cold start/RAM.
- Chỉ giữ một session cho mỗi graph; không tạo session riêng cho từng request.
- Chỉ dùng một generation job active trên thiết bị trong MVP, trừ khi benchmark chứng minh nhiều job có lợi.

### 5.2. Threading

- Thread count configurable theo device tier.
- Không tạo thread pool riêng cho từng ONNX session.
- Mặc định dùng worker background duy nhất cho synthesis.
- Playback thread không được block bởi inference.
- Khi app vào background hoặc thiết bị nóng, giảm thread/prefetch thay vì tạo thêm worker.

### 5.3. Memory

- Reuse input/output tensor buffer.
- Tránh copy giữa Kotlin/Swift, JNI và native layer.
- Giải phóng intermediate tensor ngay sau mỗi graph nếu không cần giữ.
- Không giữ toàn bộ chapter waveform trong RAM.
- Cache audio theo chunk trên disk, giới hạn dung lượng và LRU.
- Dùng bounded queue, không để producer sinh vô hạn khi player bị pause.
- Log peak allocation theo graph và theo device.

### 5.4. Playback

- Audio cache giữ tối thiểu 2 chunk trước thời điểm phát.
- Nếu buffer còn thấp, ưu tiên generation hơn prefetch xa.
- Nếu buffer đủ dài, hạ mức ưu tiên generation để tiết kiệm pin.
- Chunk boundary dùng fade/pause policy hiện tại nhưng phải có QA click/pop.
- Hỗ trợ dừng generation ngay khi user đổi chapter, voice hoặc speed.

---

## 6. Lộ trình triển khai theo phase

## Phase 0 — Thiết lập branch và baseline

**Mục tiêu:** bảo đảm team mobile có baseline bất biến và không làm nhiễu model cũ.

### Công việc

1. Tạo branch làm việc từ commit đã chọn trên `main`, ví dụ:
   - `develop`: integration branch của team mobile.
   - `feature/v3-nano-mobile-*`: branch ngắn hạn cho từng task.
2. Ghi `main_base_commit` vào model manifest và benchmark report.
3. Snapshot artifact/checksum của v3 Nano hiện tại.
4. Chạy test hiện tại của v3 Nano trên macOS ARM và Linux/CI.
5. Xuất một corpus audiobook cố định và reference outputs.
6. Định nghĩa device matrix, target RAM, RTF và quality gates.
7. Tạo namespace/model ID mới; không dùng lại tên artifact cũ.

### Deliverables

- Baseline manifest.
- Baseline audio corpus.
- Baseline metrics JSON/CSV.
- Device matrix.
- Branch ownership và integration policy.
- Danh sách feature generic có thể upstream.

### Gate G0

Không bắt đầu quantization khi baseline chưa reproducible hoặc khi output của model cũ chưa được lưu kèm revision/checksum.

---

## Phase 1 — Mobile compatibility prototype

**Mục tiêu:** chứng minh graph hiện tại có thể chạy native trên ARM mobile.

### Công việc

1. Chạy ONNX checker và shape inference cho mọi graph.
2. Kiểm tra external data file và relative path sau packaging.
3. Tạo một CLI/native harness nhỏ:
   - Load manifest.
   - Load graph.
   - Chạy một text sample.
   - Decode waveform.
   - Xuất latency và memory.
4. Test ONNX Runtime CPU/XNNPACK trên macOS ARM.
5. Test CoreML EP trên iOS prototype.
6. Test NNAPI trên Android prototype, nhưng luôn có CPU fallback.
7. Ghi lại unsupported operators, graph partition và provider fallback.
8. So sánh output native với Python reference bằng tolerance phù hợp.

### Deliverables

- `mobile-harness` chạy được trên macOS ARM.
- Android ARM64 smoke app.
- iOS device smoke app.
- Operator compatibility report.
- Model load/package validation script.

### Gate G1

- Tất cả graph load được trên CPU ARM64.
- Không có unsupported operator trong execution path chính.
- Không có silent fallback ngoài danh sách được chấp thuận.
- Output không NaN/Inf và waveform có duration hợp lệ.
- iOS/Android thật chạy được ít nhất một chunk.

---

## Phase 2 — Tách preset-only mobile package

**Mục tiêu:** giảm kích thước và RAM trước khi thay đổi trọng số.

### Công việc

1. Tạo export chỉ gồm:
   - text encoder;
   - duration predictor;
   - vector estimator;
   - codec decoder;
   - tokenizer/phoneme metadata;
   - preset voice assets.
2. Tách cloning/denoiser/reference graph thành optional package.
3. Tạo `voices_mobile.json` tối giản chỉ chứa asset cần inference.
4. Xóa dependency runtime không cần thiết khỏi mobile package.
5. Dùng static shape theo các bucket chunk length được chọn từ corpus.
6. Preallocate/reuse tensor buffer.
7. Đo riêng model package size, decompressed size, peak RSS và cold start.
8. Kiểm tra license/attribution của từng artifact trước khi phân phối.

### Deliverables

- Preset-only model package.
- Optional voice module specification.
- Mobile asset manifest/checksum.
- Memory profile trước/sau khi tách module.

### Gate G2

Package preset-only phải đạt hoặc tiến gần budget định ra mà không làm quality regression vượt ngưỡng. Nếu không đạt, không được vội quantize toàn bộ; trước tiên phải xác định graph/tensor nào chiếm RAM.

---

## Phase 3 — Tối ưu graph và runtime không đổi trọng số

**Mục tiêu:** lấy hiệu năng/RAM an toàn bằng các thay đổi ít ảnh hưởng chất lượng.

### Công việc

1. Freeze graph optimization settings và export tool version.
2. Constant folding, dead-node elimination và operator fusion nếu an toàn.
3. Chuyển dynamic axes không cần thiết thành static shape.
4. Tách graph theo chunk bucket để giảm shape overhead.
5. Dùng ORT graph optimization phù hợp mobile.
6. Thử ORT format/mobile optimization nếu vẫn giữ output đúng.
7. Điều chỉnh allocator arena và memory pattern sau khi benchmark.
8. Đo CPU/XNNPACK/CoreML/NNAPI từng provider, không chỉ đo tổng app.
9. Kiểm tra thread count 1/2/4/6 theo device tier.
10. Đo hiệu quả của `steps=4/6/8/12/16` độc lập với quantization.

### Deliverables

- Optimized FP32/FP16 mobile package.
- Runtime configuration theo device tier.
- Benchmark report theo provider.
- Quyết định provider mặc định cho Android và iOS.

### Gate G3

Chỉ chọn provider/optimization nếu latency giảm mà quality, stability và thermal không xấu hơn. NNAPI/CoreML không được trở thành dependency bắt buộc nếu partition khiến hiệu năng kém hoặc behavior không ổn định.

---

## Phase 4 — Quantization có kiểm soát

**Mục tiêu:** giảm package/RAM mà giữ chất lượng.

### Thứ tự thử nghiệm

1. Weight-only INT8 cho text encoder.
2. Weight-only INT8 cho duration predictor.
3. Weight-only INT8 cho vector estimator.
4. Đánh giá codec decoder riêng; không mặc định quantize codec.
5. Nếu cần, mixed precision: model INT8, sensitive projection/codec FP16/FP32.
6. Chỉ dùng dynamic activation quantization khi provider hỗ trợ ổn định trên target.
7. Không bật INT8 cho toàn bộ graph chỉ vì kích thước giảm.

### Công việc

- Chọn calibration corpus đại diện cho audiobook, không chỉ câu ngắn.
- Calibration bao gồm tiếng Việt, số, tên riêng, thoại và code-switching.
- So sánh output từng graph và end-to-end.
- Kiểm tra drift theo voice preset.
- Kiểm tra model trên CPU/XNNPACK/CoreML/NNAPI riêng.
- So sánh quantized model với FP16/FP32 reference.
- Ghi rõ quantization toolchain, opset, calibration revision.

### Deliverables

- INT8 candidate packages.
- Calibration dataset manifest.
- Per-layer/per-graph error report.
- Quality and performance comparison.
- Fallback policy về FP16/FP32.

### Gate G4

Chỉ phát hành INT8 khi:

- không có audio corruption trên toàn bộ smoke corpus;
- ASR/WER, duration và listening score đạt ngưỡng;
- RTF/RAM cải thiện đủ lớn để bù rủi ro;
- mọi device tier có fallback hợp lệ;
- model manifest chỉ rõ backend và precision được hỗ trợ.

---

## Phase 5 — Distillation và training mobile-specific

**Mục tiêu:** giảm compute thực sự thay vì chỉ nén artifact.

Phase này chỉ bắt đầu khi các bottleneck đã được đo và không giải quyết được bằng runtime/graph/quantization.

### Đề xuất

1. Dùng v3 Nano hiện tại hoặc model chất lượng cao hơn làm teacher.
2. Tạo dataset gồm câu ngắn, câu dài, paragraph và chunk liên tiếp.
3. Distill duration, latent/velocity prediction và output acoustic representation.
4. Huấn luyện riêng target 4–8 flow steps.
5. Giảm hoặc loại CFG trong mobile profile nếu chất lượng vẫn đạt.
6. Áp dụng QAT sau khi kiến trúc/step count ổn định.
7. Thử structured pruning chỉ khi runtime tận dụng được sparsity.
8. Huấn luyện continuity loss cho boundary giữa hai chunk.
9. Không thay đổi model ID của v3 Nano hiện tại; mobile model có revision mới riêng.

### Deliverables

- Teacher/student training config.
- Dataset version và license record.
- Distilled checkpoint.
- Exported mobile graphs.
- Ablation report: steps, CFG, quantization, pruning, continuity.

### Gate G5

Không merge checkpoint distilled vào public v3 Nano. Chỉ promote sang mobile registry khi qua quality, device, thermal và regression gates.

---

## Phase 6 — Native SDK Android/iOS

**Mục tiêu:** biến model thành sản phẩm mobile có lifecycle đúng.

### Android

- Kotlin public API.
- Native C++/JNI layer nếu cần giảm copy.
- ARM64 ABI trước; chỉ mở armeabi-v7a nếu có lý do rõ ràng.
- ORT Mobile + XNNPACK baseline.
- NNAPI optional với provider capability check.
- Android AudioTrack/AudioFocus integration.
- WorkManager/coroutine background generation.
- Memory pressure và thermal callbacks.

### iOS

- Swift public API.
- Native wrapper quanh ORT Mobile.
- CoreML EP optional sau khi benchmark.
- AVAudioEngine/AVAudioPlayer integration.
- Audio session interruption/background handling.
- Memory warning handling.
- Task cancellation và app lifecycle.

### SDK API tối thiểu

```text
ModelManager.install(manifest)
ModelManager.load(profile)
VoiceManager.listPresets()
Reader.enqueue(segment)
Reader.play()
Reader.pause()
Reader.cancel()
Reader.setPrefetchDepth(n)
Reader.metrics()
```

Public API không được expose các tensor nội bộ hoặc phụ thuộc trực tiếp vào class Python cũ.

### Gate G6

- App không crash khi background/foreground.
- Cancel giải phóng queue, session state và file tạm.
- Memory warning làm giảm prefetch hoặc dừng generation an toàn.
- Audio interruption/resume không tạo click hoặc deadlock.
- Không có network request trong offline mode.

---

## Phase 7 — Validation, beta và phát hành

### Test matrix

#### Model correctness

- Graph checker.
- Shape/type checker.
- Deterministic smoke test.
- Reference output comparison.
- NaN/Inf/clipping check.
- Duration/frame-count check.

#### Audio quality

- ASR/WER hoặc CER.
- Tỷ lệ bỏ từ/lặp từ.
- Tên riêng, số, ngày tháng, tiền tệ.
- Dấu câu và pause.
- Code-switching.
- Speaker consistency.
- Listening test mù A/B.
- Chunk boundary continuity.

#### Device/runtime

- Android Tier A/B/C.
- iPhone/iPad tối thiểu hai generation.
- CPU, XNNPACK, CoreML EP, NNAPI khi có.
- Cold/warm start.
- RAM đỉnh và memory pressure.
- 30 phút đọc liên tục.
- Thermal throttling.
- Battery drain.
- Background/foreground.
- Low storage.
- Process kill/resume.

#### SDK reliability

- Cancel giữa mỗi phase.
- Pause/resume.
- Voice/model switch.
- Chapter switch.
- Corrupt/incomplete model package.
- Checksum mismatch.
- Unsupported device ABI.
- Provider initialization failure.
- Disk full.

### Phát hành theo kênh

1. Internal harness.
2. Internal Android/iOS app.
3. Closed beta trên device matrix.
4. Small production cohort với telemetry opt-in.
5. Public release preset-only.
6. Optional cloning package sau khi core ổn định.

Mỗi release phải có:

- model revision;
- SDK revision;
- runtime/provider;
- precision;
- opset/export tool version;
- supported ABI/OS;
- checksum;
- known limitations;
- rollback artifact.

---

## 7. Branch và hợp tác giữa hai team

### 7.1. Quy ước branch

- `main`: nguồn baseline ổn định, release và behavior hiện tại.
- `develop`: integration branch của team mobile, fork từ `main`.
- `feature/v3-nano-mobile/<topic>`: branch ngắn hạn của team mobile.
- `release/v3-nano-mobile/<version>`: chỉ dùng khi chuẩn bị mobile artifact/SDK release.

Không phát triển mobile trực tiếp trên `main`. Không force-push shared branch. Không squash hoặc rewrite commit đã được team khác dùng nếu chưa thống nhất.

### 7.2. Rule merge từ main vào develop

Team mobile định kỳ merge hoặc rebase có kiểm soát từ `main` vào `develop`, nhưng phải thực hiện theo thứ tự:

1. Tạo snapshot benchmark trước khi sync.
2. Fetch commit mới của `main`.
3. Merge vào branch sync riêng, không merge thẳng vào release branch.
4. Chạy test của model cũ và mobile regression.
5. Kiểm tra thay đổi ở text normalization, asset schema, dependency, opset và public API.
6. Cập nhật compatibility adapter nếu cần.
7. Ghi migration note và commit `main` đã tích hợp.
8. Chỉ merge vào `develop` sau khi quality gate pass.

### 7.3. Phân loại thay đổi khi merge

#### Có thể upstream vào main sau review

- Bug fix generic đã có test.
- Text normalization/G2P cải thiện không phá behavior cũ.
- Utility độc lập, không phụ thuộc mobile runtime.
- Benchmark harness và corpus metadata không chứa artifact độc quyền.
- Documentation về model contract.

#### Chỉ giữ ở develop/mobile

- ONNX Mobile export.
- Native Android/iOS code.
- Mobile-specific quantization.
- Thermal policy.
- Chunk prefetch/player/cache.
- CoreML/NNAPI provider logic.
- Mobile package manifest.
- Distilled mobile checkpoint.
- Feature flag chỉ phục vụ thiết bị.

#### Không được merge tự động

- Thay đổi model weights.
- Thay đổi preset voice asset.
- Đổi tokenizer/vocabulary/opset.
- Đổi segmentation/pause behavior có ảnh hưởng audio.
- Đổi dependency runtime.
- Đổi public API v3 Nano hiện tại.

### 7.4. Biên giới thư mục đề xuất

```text
src/vieneu_mobile/              # mobile-only
mobile/android/                 # Android SDK/app harness
mobile/ios/                     # iOS SDK/app harness
tools/mobile/                   # export, validate, benchmark
models/v3_nano_mobile/          # manifest/metadata, không commit weights lớn
benchmarks/mobile/              # corpus manifest và reports
tests/mobile/                   # mobile contract tests
docs/_supermeo/_summary/        # kế hoạch và quyết định
```

Nếu repository chưa có các thư mục này, tạo theo từng phase; không đổi cấu trúc source của `src/vieneu/v3nano.py` trong MVP.

---

## 8. Bộ công cụ kiểm thử trên macOS ARM

macOS ARM được dùng làm **fast compatibility gate**, không thay thế device testing.

### Kiểm tra nhanh trên macOS ARM

- ONNX checker/shape inference.
- ORT CPU và XNNPACK session.
- CoreML provider smoke test nếu môi trường hỗ trợ.
- Reference output comparison.
- Model package/checksum validation.
- Static shape và memory buffer tests.
- Native Swift harness.
- Benchmark cold/warm latency.

### Không được suy luận từ simulator

Không dùng macOS hoặc iOS Simulator để kết luận:

- RTF trên iPhone thật.
- Peak RAM thực tế.
- Battery drain.
- Thermal throttling.
- NNAPI behavior.
- NPU/GPU performance.

Các kết luận đó phải lấy từ thiết bị thật và ghi model/OS/provider cụ thể.

### Artifact benchmark

Mỗi benchmark report nên lưu dạng machine-readable:

```json
{
  "model_revision": "v3-nano-mobile-0.1.0",
  "main_base_commit": "<sha>",
  "sdk_revision": "<version>",
  "device": "<model>",
  "os": "<version>",
  "provider": "xnnpack",
  "precision": "int8-mixed",
  "steps": 8,
  "cold_start_ms": 0,
  "first_audio_ms": 0,
  "rtf": 0.0,
  "peak_rss_mb": 0.0,
  "thermal_state": "nominal",
  "quality_gate": "pass"
}
```

---

## 9. Quan sát và telemetry

Mặc định không thu thập nội dung text hoặc audio người dùng. Nếu có telemetry opt-in, chỉ ghi metadata tối thiểu:

- model/SDK/provider revision;
- device tier/model;
- latency và RTF;
- peak memory bucket;
- thermal state bucket;
- error code;
- cache hit/miss;
- cancellation reason.

Không ghi:

- raw text;
- reference audio;
- speaker embedding;
- waveform;
- voice clone asset;

trừ khi người dùng chủ động bật diagnostic upload và có consent rõ ràng.

---

## 10. Rủi ro và phương án giảm thiểu

| Rủi ro | Mức độ | Giảm thiểu |
|---|---|---|
| ONNX operator không tương thích CoreML/NNAPI | Cao | CPU/XNNPACK baseline, provider capability check, device test |
| Quantization làm méo tiếng | Cao | Calibration corpus, mixed precision, fallback FP16/FP32 |
| RAM tăng do nhiều session/buffer | Cao | Một session/graph, reuse buffer, bounded queue, memory profile |
| Thermal throttling sau vài phút | Cao | Prefetch vừa phải, giới hạn thread, thermal policy, test 30 phút |
| Nano chất lượng thấp với code-switching | Cao | Corpus riêng, pronunciation lexicon, không hạ steps quá sớm |
| Chunk boundary không tự nhiên | Cao | Boundary QA, fixed segmentation, continuity tuning |
| Merge main làm đổi tokenizer/G2P | Cao | Compatibility contract, snapshot baseline, migration test |
| Model mobile bị nhầm với model cũ | Cao | Namespace, manifest, model ID và artifact directory riêng |
| Simulator cho kết quả giả tốt | Trung bình | Device matrix bắt buộc trước beta |
| App bị kill giữa generation | Trung bình | Job cancellation, checkpoint chunk, resume từ cache |
| Gói cloning làm phình app | Trung bình | Optional download module, preset-only MVP |
| License/artifact không đủ cho redistribution | Cao | License inventory và legal review trước distribution |
| Cache chiếm hết disk | Trung bình | LRU, quota, cleanup và disk-full handling |
| Provider fallback âm thầm làm chậm | Cao | Log/report provider partition, fail hoặc cảnh báo rõ ràng |

---

## 11. Definition of Done cho MVP

MVP chỉ được coi là hoàn thành khi tất cả điều kiện sau đạt:

- Có model ID và artifact namespace mới, không thay đổi v3 Nano cũ.
- Có manifest/checksum/revision đầy đủ.
- Preset-only package chạy offline trên Android ARM64 và iOS device.
- Có CPU/XNNPACK fallback hoạt động.
- CoreML/NNAPI được kiểm thử và fallback rõ ràng nếu chưa dùng làm mặc định.
- Có SDK queue/prefetch/cache/cancel cơ bản.
- Có test graph, test audio, test lifecycle và test memory.
- Có benchmark ít nhất trên một flagship và một mid-range mỗi platform.
- Có report 30 phút playback liên tục.
- Không vượt quality regression threshold đã phê duyệt.
- Có rollback về package FP16/FP32.
- Có tài liệu integration với `main` và release procedure.
- Có license/attribution inventory cho toàn bộ artifact phân phối.

---

## 12. Các quyết định cần chốt trước khi triển khai

1. Device thấp nhất được hỗ trợ là model nào?
2. Package được bundled hay download sau khi cài app?
3. Có bắt buộc voice cloning trong bản đầu không? Khuyến nghị: không.
4. Có chấp nhận 24 kHz cho mobile không? Khuyến nghị: có.
5. Quality threshold dùng listening test, ASR hay cả hai?
6. Android provider mặc định là XNNPACK hay NNAPI fallback?
7. iOS provider mặc định là CPU hay CoreML sau benchmark?
8. Có cho phép telemetry opt-in không?
9. Mobile artifact registry đặt ở đâu và ai quản lý signing/checksum?
10. Chính sách merge generic fixes từ `develop` ngược về `main` là gì?

---

## 13. Khuyến nghị cuối

Hướng an toàn nhất là phát hành **v3 Nano Mobile preset-only, 24 kHz, chunk-prefetch, ONNX Runtime Mobile, XNNPACK/CPU fallback**, trước khi đụng đến cloning, NPU hoặc distillation sâu. Việc giảm RAM nên bắt đầu bằng tách module, static shape, buffer reuse và runtime optimization; INT8, distillation và QAT chỉ được chấp nhận sau khi có benchmark trên thiết bị thật. `main` phải tiếp tục là nguồn gốc ổn định của model cũ, còn `develop` là integration line của mobile với model ID, artifact, test suite và release pipeline riêng.

---

## 14. Mở rộng hệ sinh thái nhiều giọng trên mobile

### 14.1. Nguyên tắc base model + voice asset

Mobile không nên đóng gói một model đầy đủ cho từng giọng. Kiến trúc chuẩn là:

```text
Một Nano Mobile base model
        +
Nhiều voice asset nhỏ, tải/bật độc lập
        +
Voice manager chỉ giữ asset đang dùng trong RAM
```

Base model chịu trách nhiệm text encoder, duration predictor, vector estimator và codec decoder; voice asset chỉ chứa thông tin conditioning cần để điều khiển speaker/style. Việc này cho phép mở rộng số lượng giọng mà không nhân bản trọng số model hoặc làm tăng RAM theo số voice đã cài.

### 14.2. Các loại voice package

#### A. Official bundled voices

Một số giọng phổ biến được bunded trong app để dùng ngay khi offline. Chỉ đưa metadata và conditioning asset cần thiết vào app; không đưa speaker encoder/codec encoder nếu người dùng không cần tạo voice mới.

#### B. Official downloadable voice packs

Giọng ít dùng hoặc chất lượng cao có thể tải theo nhu cầu. Mỗi pack phải có manifest, checksum, model compatibility, locale, license và kích thước; app tải atomic vào temporary directory rồi verify trước khi activate.

#### C. Custom local voice assets

Người dùng đưa reference audio vào app và thực hiện enrollment local khi có đủ runtime. Kết quả cần được chuyển thành một asset nhỏ, không giữ raw audio sau khi user không yêu cầu; asset được mã hóa hoặc bảo vệ bằng platform secure storage nếu có thông tin nhạy cảm.

#### D. Server-created adapter/voice assets

Với thiết bị yếu, server có thể enroll reference hoặc train adapter offline rồi gửi về một asset đã kiểm định. Mobile chỉ inference bằng base model + asset, không train LoRA và không chạy pipeline nặng trên thiết bị.

### 14.3. Voice asset manifest đề xuất

```json
{
  "voice_id": "vnm_story_female_01",
  "display_name": "Narrator 01",
  "model_family": "v3-nano-mobile",
  "base_model_revision": "<revision>",
  "asset_revision": "<revision>",
  "locale": "vi-VN",
  "sample_rate": 24000,
  "speaker_embedding": "speaker.bin",
  "style_tokens": "style.bin",
  "default_generation": {
    "steps": 8,
    "cfg": 3.0,
    "speed": 1.0
  },
  "license": "<license-id>",
  "provenance": "official|user-enrolled|server-enrolled",
  "sha256": "<checksum>",
  "size_bytes": 0
}
```

Manifest không được chứa raw reference audio mặc định. `base_model_revision`, precision và supported runtime phải được kiểm tra trước khi voice asset được load; asset không tương thích phải bị từ chối rõ ràng, không tự động dùng sai model.

### 14.4. RAM và lifecycle của voice asset

- Chỉ giữ một voice đang synthesis trong working memory ở MVP.
- Metadata của nhiều voice có thể giữ trong index nhỏ.
- Speaker/style tensor của voice kế tiếp được preload có giới hạn khi buffer hiện tại đủ dài.
- Voice LRU cache phải có quota theo số asset và tổng byte.
- Khi đổi voice, hủy generation cũ, flush audio queue và không dùng nhầm chunk của voice trước.
- Không tạo ONNX session mới cho mỗi voice; voice asset chỉ thay conditioning input.
- Dùng checksum và versioned cache path để tránh asset cũ bị dùng với base model mới.

### 14.5. Voice creation roadmap

1. **MVP:** official preset voices, bundled/downloadable.
2. **V1:** local reference enrollment với preset-compatible speaker/style asset.
3. **V2:** server enrollment và optional adapter đã merge/distill cho voice/style riêng.
4. **V3:** LoRA/adapter training offline; mobile chỉ nhận adapter đã kiểm định hoặc checkpoint đã merge.

Text prompt kiểu “giọng nữ trầm, kể chuyện chậm” không nên là MVP vì Nano chưa có voice generator có latent space được điều khiển bằng ngôn ngữ. Có thể hỗ trợ prompt sau này như lớp tìm kiếm/chọn voice hoặc ánh xạ sang preset/style asset; không tuyên bố đó là tạo speaker mới nếu chưa có training và evaluation tương ứng.

### 14.6. LoRA/adapter cho mobile

LoRA không nên chạy training trực tiếp trên Android/iOS. Training, merge, quantization và quality gate thực hiện offline/server; artifact mobile nên là một trong ba dạng:

- adapter nhỏ được runtime hỗ trợ và đã benchmark;
- adapter được merge vào một mobile checkpoint riêng;
- distilled voice asset chỉ gồm speaker/style conditioning nếu chất lượng đủ.

Mỗi adapter phải ghi base model revision, target modules, rank, precision, license và quality report. Không cho phép load adapter không rõ nguồn gốc hoặc không cùng revision vì có thể gây crash, sai voice hoặc tăng RAM ngoài dự kiến.

### 14.7. Privacy, license và safety

Voice asset có thể là dữ liệu nhận dạng cá nhân. SDK cần:

- yêu cầu user consent khi enroll voice;
- phân biệt official/community/user voice;
- lưu provenance và license trong manifest;
- hỗ trợ xóa asset và raw reference;
- không upload audio nếu offline mode;
- không gửi embedding/voice asset ra ngoài nếu chưa có consent;
- cảnh báo khi voice package có quyền phân phối hạn chế.

### 14.8. Cập nhật branch

Schema `voice asset`, `VoiceManager` interface và manifest validator là ứng viên có thể upstream nếu backward-compatible và có test. Official voice pack, mobile adapter, native cache, secure storage và provider-specific loading vẫn thuộc `develop`; không sửa schema voice cũ trên `main` chỉ để phục vụ mobile.
