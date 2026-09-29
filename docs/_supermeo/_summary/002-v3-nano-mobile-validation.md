# Plan 002 — Validation Plan cho v3 Nano Mobile

> **⚠️ ARCHIVED (2026-09-29):** Tài liệu này **không còn được dùng làm protocol thực thi**. Phase validation 1 đã đóng; mọi dữ liệu, kết luận, nguyên tắc kiểm thử còn hiệu lực và backlog nhúng vào MVP đã gom tại [`001-v3-nano-mobile.md`](./001-v3-nano-mobile.md) §0–§0.13. File này giữ nguyên làm archive lịch sử — không cập nhật thêm.

- **Trạng thái:** Draft kế hoạch kiểm thử giả thiết
- **Phụ thuộc:** `001-v3-nano-mobile.md`
- **Phạm vi:** v3 Nano Mobile mới, preset voice trước; Android ARM64 và iOS/iPadOS
- **Baseline:** v3 Nano hiện tại từ `main` tại một commit/model revision được khóa
- **Branch:** thực thi trên `develop`; không thay đổi behavior hoặc artifact của v3 Nano cũ
- **Mục tiêu:** bác bỏ sớm các giả thiết sai trước khi đầu tư vào export, quantization, distillation hoặc SDK production

---

## 1. Mục tiêu validation

Tài liệu này không nhằm chứng minh model mobile chắc chắn tốt; nó nhằm trả lời có hệ thống các câu hỏi:

1. Model v3 Nano hiện tại có chạy đúng trên ARM mobile không?
2. Việc tách preset-only có đủ giảm package/RAM mà không cần train lại không?
3. INT8/FP16 có giữ được chất lượng đọc truyện/sách không?
4. Giảm flow steps có làm chất lượng hoặc độ ổn định suy giảm vượt ngưỡng không?
5. Chunk generation có đủ mượt để thay thế frame-level streaming trên mobile không?
6. CoreML/NNAPI có thật sự giúp nhanh hơn CPU/XNNPACK hay chỉ tạo fallback và overhead?
7. Thiết bị tầm trung có chạy liên tục mà không thermal throttle nghiêm trọng không?
8. Những feature nào có thể merge từ `main` vào `develop` mà không phá mobile reproducibility?

Mỗi giả thiết phải có:

- baseline;
- biến thử nghiệm;
- metric;
- ngưỡng pass/fail;
- artifact bằng chứng;
- quyết định sau test;
- owner và commit/model revision.

Không được kết luận dựa trên cảm nhận của một thiết bị hoặc một câu mẫu.

---

## 2. Nguyên tắc kiểm thử

### 2.1. Không thay đổi nhiều biến cùng lúc

Mỗi vòng test chỉ thay đổi một nhóm yếu tố:

- graph/export;
- precision;
- flow steps/CFG;
- execution provider;
- thread count;
- chunk size;
- SDK buffering.

Nếu thay đổi nhiều yếu tố đồng thời, không thể biết nguyên nhân chất lượng hoặc hiệu năng thay đổi.

### 2.2. So sánh với baseline cố định

Mọi báo cáo phải ghi:

```text
main_base_commit
baseline_model_revision
candidate_model_revision
export_tool_version
opset
runtime_version
sdk_version
provider
precision
steps
cfg
thread_count
```

Baseline output phải được lưu bằng checksum; không tạo lại baseline giữa các phase trừ khi có migration report.

### 2.3. Ưu tiên thiết bị thật

- macOS ARM: kiểm tra nhanh graph, packaging và CPU compatibility.
- Simulator: kiểm tra lifecycle, API và asset loading.
- Android/iPhone thật: quyết định RTF, RAM, battery và thermal.

Simulator không được dùng để tuyên bố model đạt target mobile.

### 2.4. Chất lượng và hiệu năng là hai gate độc lập

Một candidate nhanh hơn nhưng đọc sai, méo tiếng hoặc không ổn định là **fail**. Một candidate chất lượng tốt nhưng không đạt RAM/thermal cũng **fail**, không được gọi là production-ready.

### 2.5. Fail fast và giữ fallback

Mỗi candidate mobile phải có đường quay về:

```text
INT8 candidate → FP16 candidate → FP32/reference
NNAPI/CoreML → XNNPACK/CPU
new graph → previous mobile graph
mobile SDK revision → previous SDK revision
```

---

## 3. Bộ dữ liệu kiểm thử

## 3.1. Corpus bắt buộc

Tạo corpus versioned, có license rõ ràng, gồm tối thiểu:

### A. Câu thông thường

- Câu ngắn, vừa và dài.
- Dấu chấm, phẩy, chấm hỏi, chấm than.
- Đoạn văn kể chuyện.
- Paragraph nhiều câu.

### B. Văn bản audiobook

- Văn xuôi hiện đại.
- Truyện cổ/tự sự.
- Mô tả dài.
- Đoạn hội thoại nhiều lượt.
- Chuyển tiếp giữa narrator và nhân vật.
- Đoạn có dấu ngoặc kép và ngoặc đơn.

### C. Phát âm khó

- Tên người, địa danh, thương hiệu.
- Số, ngày tháng, tiền tệ, phần trăm.
- Viết tắt.
- Từ Hán Việt và từ hiếm.
- Từ tiếng Anh, tên riêng nước ngoài và code-switching.
- URL/email nếu sản phẩm cần hỗ trợ.

### D. Robustness

- Text rỗng hoặc chỉ có whitespace.
- Emoji/ký hiệu lạ.
- Unicode normalization khác nhau.
- Câu cực ngắn.
- Câu dài vượt chunk target.
- Dấu câu liên tiếp.
- Nội dung có từ OOV.

### E. Long-form

- Một chapter 5 phút.
- Một chapter 15 phút.
- Một chapter 30 phút.
- Ít nhất một bài có thoại và tên riêng dày đặc.

### 3.2. Corpus split

- `smoke`: 20–50 câu, chạy mỗi commit.
- `regression`: 300–1.000 câu, chạy mỗi candidate.
- `audiobook`: các đoạn 5/15/30 phút.
- `stress`: câu dài, OOV, code-switching và punctuation edge cases.
- `thermal`: text đủ sinh liên tục 30–60 phút.

Không để test set và calibration/training set trùng nhau khi đánh giá quantization hoặc distillation.

### 3.3. Voice matrix

MVP nên chọn ít nhất:

- 3 preset voice khác nhau về giới tính/character.
- 1 voice thiên về đọc tự nhiên.
- 1 voice có tốc độ hoặc năng lượng khác biệt.
- 1 voice dùng để kiểm tra speaker consistency.

Nếu chỉ có một voice, không được kết luận quantization hoặc model optimization giữ chất lượng cho toàn bộ preset set.

---

## 4. Giả thiết và thí nghiệm

## H1 — Graph v3 Nano hiện tại chạy đúng trên ARM mobile

**Giả thiết:** toàn bộ graph cần cho preset inference chạy được trên ARM64 mà không có unsupported operator hoặc silent fallback nguy hiểm.

### Test

1. ONNX checker và shape inference.
2. Load graph bằng ONNX Runtime trên macOS ARM.
3. Load bằng Android ARM64 harness.
4. Load bằng iOS device harness.
5. Chạy smoke corpus với CPU/XNNPACK.
6. Ghi execution provider partition và operator fallback.

### Metric

- Graph load success.
- Số unsupported operator.
- Số node fallback.
- Output NaN/Inf.
- Output shape/duration.
- Crash/hang rate.

### Pass

- Tất cả graph preset-only load được.
- Không có unsupported operator.
- Fallback ngoài danh sách cho phép bằng 0.
- Smoke output hợp lệ trên Android/iOS thật.

### Nếu fail

- Không quantize hoặc distill.
- Tách graph gây lỗi.
- Thay operator bằng mobile-compatible implementation.
- Giữ CPU/XNNPACK làm reference provider.

---

## H2 — Tách preset-only đủ giảm package và RAM

**Giả thiết:** loại cloning/denoiser/reference assets khỏi core package giúp đạt budget mà không cần thay đổi model weights.

### Test matrix

| Candidate | Cloning | Denoiser | Precision | Mục đích |
|---|---:|---:|---|---|
| P0 | Có | Có | FP32 | Reference đầy đủ |
| P1 | Không | Không | FP32 | Preset-only |
| P2 | Không | Không | FP16/mixed | Đo lợi ích precision |
| P3 | Optional download | Optional | FP16/mixed | Kiểm tra modular package |

### Metric

- Compressed package MB.
- Installed/uncompressed MB.
- Peak RSS khi load model.
- Peak RSS khi generate.
- Cold-start time.
- Số graph/session được load.
- Disk cache usage.

### Pass

P1/P2 phải giảm đáng kể so với P0 và không làm quality regression; nếu preset-only vẫn vượt RAM budget, cần profile tensor trước khi training lại.

### Nếu fail

- Không thêm feature mới.
- Dùng allocation trace để tìm graph/tensor chiếm RAM.
- Kiểm tra duplicate session, external data mapping và buffer lifetime.

---

## H3 — FP16/mixed precision giữ chất lượng tốt hơn INT8 toàn phần

**Giả thiết:** mixed precision có thể đạt phần lớn lợi ích kích thước/RAM nhưng an toàn hơn INT8 toàn bộ, đặc biệt ở codec/output-sensitive layers.

### Candidates

- FP32 reference.
- FP16 toàn model nếu provider hỗ trợ.
- INT8 weight-only text encoder.
- INT8 weight-only duration predictor.
- INT8 vector estimator + FP16 codec.
- INT8 toàn model.
- Mixed INT8/FP16/FP32.

### Metric chất lượng

- ASR WER/CER hoặc phoneme error.
- Tỷ lệ bỏ/lặp từ.
- Duration error.
- Clipping/NaN/Inf.
- Loudness và pitch drift.
- Listening test mù.
- Speaker similarity theo từng voice.

### Pass

Candidate chỉ pass nếu quality delta nằm trong ngưỡng đã phê duyệt ở cả smoke và regression corpus; không có lỗi catastrophic dù average metric tốt.

### Nếu fail

- Giữ codec và output-sensitive layers ở FP16/FP32.
- Dùng per-channel hoặc weight-only quantization.
- Tạo calibration set đại diện hơn.
- Không phát hành INT8 cho provider/device gây lỗi; fallback theo manifest.

---

## H4 — Giảm flow steps xuống 4–8 vẫn đạt chất lượng audiobook

**Giả thiết:** mobile có thể dùng ít steps hơn mà không làm chất lượng đọc dài giảm quá mức.

### Matrix

```text
steps ∈ {4, 6, 8, 12, 16}
cfg   ∈ {0, baseline, mobile-default}
```

Giữ model/precision/provider cố định khi chạy matrix đầu tiên.

### Metric

- RTF.
- First playable chunk latency.
- ASR/WER/CER.
- Prosody/listening score.
- Tỷ lệ artifact/babbling.
- Chunk duration stability.
- Boundary continuity.

### Pass

Chọn mức steps thấp nhất nhưng quality không vượt regression threshold và RTF đạt target trên Tier B. Không chọn `steps=4` chỉ vì nhanh nếu long-form score fail.

### Nếu fail

- Dùng 8/12 steps làm quality profile.
- Distill student dành riêng cho 4–8 steps.
- Điều chỉnh CFG hoặc schedule trước khi giảm thêm steps.

---

## H5 — Chunk prefetch thay thế được frame-level streaming trên mobile

**Giả thiết:** người nghe không nhận thấy gián đoạn nếu app sinh trước 2–4 chunk và quản lý buffer đúng.

### Test

1. Sinh chunk 3, 5 và 8 giây.
2. Prefetch depth 1, 2, 4.
3. Đo playback dưới cold start, warm start và thermal state.
4. Pause/resume, seek, đổi chapter, cancel giữa generation.
5. Giả lập memory pressure và background/foreground.

### Metric

- Time to first playable audio.
- Underrun count.
- Buffer depth theo thời gian.
- Audio gap tại chunk boundary.
- Cancel latency.
- RAM và battery.

### Pass

Không underrun trong 30 phút trên target Tier B ở điều kiện bình thường; cancellation không làm playback treo; buffer có thể phục hồi sau một chunk chậm.

### Nếu fail

- Tăng chunk size hoặc prefetch depth.
- Giảm steps/provider overhead.
- Thêm quality/economy profile.
- Không cố triển khai frame streaming trước khi xác định bottleneck.

---

## H6 — XNNPACK là baseline ổn định hơn NNAPI/CoreML

**Giả thiết:** accelerator provider không mặc định nhanh hơn CPU/XNNPACK vì graph partition, copy và unsupported ops có thể tạo overhead.

### Test matrix

| Platform | Provider |
|---|---|
| macOS ARM | CPU, XNNPACK, CoreML nếu hỗ trợ |
| Android | CPU, XNNPACK, NNAPI |
| iOS | CPU, XNNPACK, CoreML |

### Metric

- Provider initialization time.
- Nodes chạy trên provider chính.
- Fallback nodes.
- RTF và first chunk latency.
- Peak RSS.
- Thermal/battery.
- Crash/timeout rate.

### Pass

Provider chỉ được chọn làm default khi có lợi ích đo được trên ít nhất hai thiết bị target cùng platform và không có stability/quality regression.

### Nếu fail

- Chọn XNNPACK/CPU làm default.
- Giữ CoreML/NNAPI là optional experiment.
- Ghi provider limitation vào manifest và release notes.

---

## H7 — Buffer reuse và static shapes giảm RAM thực sự

**Giả thiết:** phần lớn peak RAM đến từ allocation/intermediate tensor, có thể giảm bằng static shape và reuse.

### Test

- Dynamic shape reference.
- Static shape một bucket.
- Static shape nhiều bucket.
- Reuse input/output buffer bật/tắt.
- Một session so với nhiều session.
- Chunk length ngắn/vừa/dài.

### Metric

- Peak RSS.
- Allocation count.
- Allocation churn.
- Generation latency.
- Fragmentation.
- Output correctness.

### Pass

Candidate giảm peak RSS/churn mà không tăng latency hoặc gây lỗi khi chuyển bucket.

### Nếu fail

- Giữ dynamic path nếu static bucket làm package/latency xấu hơn.
- Profile từng graph thay vì tối ưu toàn SDK.

---

## H8 — Mobile có thể chạy 30–60 phút mà không thermal throttle nghiêm trọng

**Giả thiết:** với thread count, prefetch và steps phù hợp, thiết bị tầm trung giữ được playback ổn định.

### Protocol

1. Sạc ở mức 50–80%, tháo nguồn nếu an toàn.
2. Độ sáng và audio route cố định.
3. Chạy cùng corpus 30 phút và 60 phút.
4. Ghi nhiệt, CPU/GPU utilization, clock, battery và RTF mỗi phút.
5. Lặp lại ở phòng nhiệt độ ổn định.
6. Chạy economy và quality profile.

### Metric

- RTF theo thời gian.
- Thermal state.
- Clock throttling.
- Battery drain %/hour.
- Underrun.
- Peak/average temperature nếu API cung cấp.
- Crash/ANR.

### Pass

Không có underrun hoặc crash; RTF cuối bài không vượt target quá ngưỡng đã định; nếu throttle, app phải tự chuyển sang economy policy.

### Nếu fail

- Giảm thread count.
- Giảm steps/prefetch.
- Hạ priority khi buffer đủ.
- Tạm dừng prefetch xa.
- Cần profile model nhẹ hơn hoặc distillation.

---

## H9 — Model mobile giữ pronunciation và long-form quality

**Giả thiết:** tối ưu runtime không làm tăng lỗi đọc trong corpus audiobook.

### Test

- So sánh baseline và candidate cùng text/voice/seed nếu hỗ trợ.
- Chạy ASR trên từng chunk và toàn chapter.
- Review thủ công các tên riêng/số/code-switching.
- Listening test mù cho narrator/dialogue.
- Đo chunk boundary.

### Metric

- CER/WER.
- Tỷ lệ word omission/repetition.
- Pronunciation pass rate theo danh sách từ khó.
- Pause duration error.
- Pitch/energy jump.
- Speaker similarity drift.
- Human preference score.

### Pass

Không chỉ nhìn average WER; mọi nhóm critical phải đạt ngưỡng riêng. Một candidate fail nếu có lỗi nghiêm trọng lặp lại ở tên riêng hoặc thoại dù average score vẫn tốt.

### Nếu fail

- Điều chỉnh segmentation/lexicon trước khi retrain.
- Tạo pronunciation dictionary theo book/user.
- Nếu do model/quantization, rollback precision hoặc tăng steps.

---

## H10 — Không cần train audio lại cho bản export đầu tiên

**Giả thiết:** export, module split, graph optimization và mixed precision đủ để đạt target mà không cần training mới.

### Test ladder

```text
L0: current FP32 export
L1: preset-only FP32
L2: optimized FP16/mixed
L3: INT8 candidate
L4: distilled/QAT candidate
```

Mỗi level chỉ được chuyển tiếp khi level trước đã có report.

### Pass

Nếu L2 hoặc L3 đạt package/RAM/quality target, dừng training; không train lại chỉ để thay đổi kiến trúc.

### Nếu fail

Chỉ mở training khi đã xác định rõ:

- metric nào fail;
- graph/layer nào gây bottleneck;
- training objective nào sửa được vấn đề;
- chi phí dữ liệu/license;
- kế hoạch rollback.

---

## H11 — Main có thể merge vào develop mà không phá mobile reproducibility

**Giả thiết:** có thể lấy bug fix/generic feature từ `main` mà vẫn duy trì output/contract mobile.

### Test

Trước và sau mỗi sync từ `main`:

1. Chạy test v3 Nano cũ.
2. Chạy mobile smoke/regression.
3. So sánh text normalization/G2P/chunk manifest.
4. Kiểm tra tokenizer/vocabulary/config checksum.
5. Rebuild mobile package.
6. So sánh reference output và benchmark.

### Pass

- Model cũ không thay đổi ngoài commit đã biết.
- Mobile output không thay đổi ngoài threshold.
- Artifact manifest và migration note cập nhật.
- Không có dependency/opset drift ngoài kế hoạch.

### Nếu fail

- Dừng merge vào `develop`.
- Tạo compatibility adapter hoặc pin utility cũ.
- Không sửa âm thầm mobile output để “làm test xanh”.

---

## H12 — Có thể rollback model/provider an toàn

**Giả thiết:** app có thể quay về candidate trước khi model mới/provider mới lỗi.

### Test

- Corrupt checksum.
- Thiếu external data.
- Provider initialization failure.
- Model load timeout.
- Memory pressure khi load.
- App update giữa chapter.
- Interrupted download.
- Rollback khi quality policy phát hiện lỗi.

### Pass

App chọn fallback hợp lệ, giữ playback hoặc báo lỗi rõ ràng; không để app crash hoặc mắc kẹt ở model half-installed.

### Nếu fail

Không phát hành model downloaded/optional package; chỉ bundled stable package được phép làm fallback.

---

## 5. Test ladder và cổng quyết định

```text
G0  Baseline reproducible
 ↓
G1  Graph/ABI compatibility
 ↓
G2  Preset-only packaging
 ↓
G3  FP16/static-shape/runtime optimization
 ↓
G4  Quantization
 ↓
G5  Flow-step/CFG optimization
 ↓
G6  Device quality + thermal
 ↓
G7  Native SDK lifecycle
 ↓
G8  Beta/release candidate
```

### Quy tắc chuyển phase

- Không bỏ qua gate vì một benchmark đơn lẻ rất tốt.
- Mỗi gate cần report machine-readable và người review.
- Fail ở quality gate phải rollback precision/steps/provider trước khi đổi model.
- Fail ở thermal gate phải thử policy/runtime trước khi retrain.
- Fail ở graph compatibility phải sửa export/operator trước quantization.

---

## 6. Device matrix tối thiểu

### macOS ARM

- Một Mac Apple Silicon để chạy harness CPU/XNNPACK.
- Dùng làm fast gate cho graph, package và native API.
- Không dùng làm đại diện cho mobile thermal/battery.

### Android

Tối thiểu:

- Flagship ARM64 đời mới.
- Mid-range ARM64 phổ biến.
- Thiết bị thấp nhất dự kiến hỗ trợ.
- Ít nhất một thiết bị có NNAPI/NPU khác vendor nếu định hỗ trợ NNAPI.

Ghi rõ:

- SoC.
- RAM.
- Android version.
- ABI.
- Vendor driver.
- Provider/runtime version.

### iOS

Tối thiểu:

- Một iPhone flagship hiện đại.
- Một iPhone/iPad tầm trung hoặc đời cũ hơn trong support range.
- Một thiết bị có memory budget thấp hơn.

Ghi rõ:

- SoC/generation.
- iOS/iPadOS version.
- ORT/CoreML version.
- Thermal state.

---

## 7. Metrics và cách thu thập

### 7.1. Latency

- `model_load_ms`.
- `first_chunk_ready_ms`.
- `first_playable_audio_ms`.
- `chunk_generation_ms`.
- `end_to_end_chapter_ms`.
- `cancel_latency_ms`.

### 7.2. Throughput

- RTF tổng.
- RTF theo chunk length.
- RTF cold/warm.
- RTF trước/sau thermal throttle.
- Chunk underrun rate.

### 7.3. Memory

- Peak RSS.
- Native heap peak.
- ORT allocator peak.
- Model mapped memory.
- Audio cache usage.
- Temporary buffer peak.

### 7.4. Quality

- CER/WER/ASR confidence.
- Pronunciation pass rate.
- Duration deviation.
- Silence/pause deviation.
- Pitch/energy boundary jump.
- Artifact count.
- Listening preference.

### 7.5. Stability

- Crash-free runs.
- ANR/hang.
- Provider init failure.
- Session leak.
- Memory growth sau 30/60 phút.
- Background/foreground recovery.

---

## 8. Quy trình benchmark chuẩn

1. Xóa hoặc đánh dấu rõ model/audio cache theo test mode.
2. Reboot hoặc đưa thiết bị về trạng thái warm/cold đã định nghĩa.
3. Ghi device/OS/provider/runtime/SDK/model metadata.
4. Chạy warm-up theo protocol cố định.
5. Chạy smoke trước; nếu fail thì dừng candidate.
6. Chạy regression corpus tối thiểu ba lần.
7. Chạy long-form/thermal riêng.
8. Thu output audio và metrics machine-readable.
9. Chạy quality analysis.
10. Lưu log, checksum và report cùng candidate revision.
11. So sánh với baseline tự động.
12. Reviewer ký gate pass/fail.

Không lấy lần chạy nhanh nhất làm kết quả; báo cáo median, p95 và variance.

---

## 9. Hệ thống pass/fail đề xuất

### Hard fail

- Crash, ANR, deadlock.
- Unsupported operator trong required path.
- NaN/Inf, waveform invalid, audio corruption.
- Checksum/model integrity failure.
- Quality catastrophic trên critical corpus.
- Không rollback được.
- Memory leak tăng liên tục.
- Playback underrun lặp lại.

### Soft fail cần xử lý trước release

- RTF vượt target nhưng có đường tối ưu rõ ràng.
- Thermal throttle nhẹ.
- Quality giảm trong một nhóm voice hoặc text class.
- Provider accelerator không có lợi ích.
- Cold start cao nhưng warm path đạt.

### Pass có điều kiện

- Chỉ hỗ trợ một số device tier.
- Chỉ dùng XNNPACK/CPU.
- Chỉ bundled model, chưa downloaded model.
- Chỉ 8/12 steps, chưa economy profile.
- Chưa mở cloning.

Mọi pass có điều kiện phải ghi vào manifest/release notes, không ẩn trong code.

---

## 10. Artifact và thư mục validation

Đề xuất cấu trúc:

```text
benchmarks/mobile/
  corpus/
    smoke.jsonl
    regression.jsonl
    audiobook.jsonl
    stress.jsonl
  manifests/
    baseline-main-<sha>.json
    v3-nano-mobile-<version>.json
  reports/
    <date>-<device>-<candidate>.json
    <date>-<device>-<candidate>.md
  audio/
    reference/
    candidates/
  scripts/
    validate_onnx.py
    compare_audio.py
    aggregate_metrics.py
    check_manifest.py

tests/mobile/
  test_manifest.py
  test_graph_compatibility.py
  test_reference_outputs.py
  test_chunk_contract.py
  test_fallback_policy.py

mobile/android/
mobile/ios/
tools/mobile/
```

Không commit model weights lớn vào Git nếu repository policy không cho phép; lưu registry URI, revision, checksum và download/install procedure.

---

## 11. Kế hoạch thực thi đề xuất

### Sprint 1 — Baseline và corpus

- Khóa main commit/model revision.
- Tạo corpus và voice matrix.
- Chạy reference output.
- Tạo manifest và metrics schema.
- Hoàn thiện smoke runner trên macOS ARM.

**Decision:** nếu baseline không reproducible, dừng.

### Sprint 2 — Graph compatibility

- ONNX checker/shape inference.
- ORT CPU/XNNPACK trên macOS ARM.
- Android/iOS minimal harness.
- Operator/fallback report.

**Decision:** nếu graph không chạy ARM, xử lý export/operator trước.

### Sprint 3 — Preset-only và memory profile

- Tách optional assets.
- Đo package/RSS/session count.
- Buffer reuse/static shape prototype.

**Decision:** xác định có cần quantization/training hay chưa.

### Sprint 4 — Precision/steps matrix

- FP32/FP16/mixed/INT8 candidates.
- Steps/CFG matrix.
- Regression quality.

**Decision:** chọn tối đa hai candidate tiếp tục.

### Sprint 5 — Device/thermal validation

- Android/iOS Tier A/B/C.
- 30–60 phút soak test.
- Battery/thermal/memory pressure.

**Decision:** chốt runtime/provider/profile.

### Sprint 6 — SDK lifecycle và rollback

- Prefetch/cache/cancel/background.
- Model install/checksum/rollback.
- Crash/stress test.

**Decision:** internal beta hoặc quay lại Phase 3/4.

---

## 12. Quy tắc sync `main` → `develop`

Mỗi lần `main` có thay đổi liên quan tới text, tokenizer, G2P, asset, dependency hoặc model:

1. Tạo `sync/main-<short-sha>` từ `develop`.
2. Merge commit mới từ `main` vào sync branch.
3. Chạy H11 trước khi merge vào `develop`.
4. Nếu tokenizer/config/model asset thay đổi, tạo baseline mới; không so sánh trực tiếp với baseline cũ mà không ghi migration.
5. Rebuild ít nhất một mobile candidate.
6. Chạy smoke + regression + manifest validation.
7. Review diff của output contract.
8. Merge vào `develop` chỉ khi không có hard fail.

Không merge:

- mobile checkpoint vào `main` chỉ vì benchmark tốt;
- mobile-specific provider code vào public v3 Nano path;
- thay đổi G2P/pause mà không có audiobook regression;
- artifact không có license/checksum.

Có thể upstream riêng:

- generic bug fix;
- benchmark tooling;
- text utility có backward-compatible test;
- documentation về contract.

---

## 13. Decision log template

Mỗi giả thiết phải có một record:

```markdown
## H4 — Reduced flow steps

- Owner: mobile-ml
- Candidate: v3-nano-mobile-0.1.0
- Baseline: v3-nano-main-<sha>
- Devices: <list>
- Variables: steps=4/6/8/12/16, cfg=<...>
- Metrics: RTF, first_chunk_ms, WER, boundary_score, RSS
- Pass threshold: <threshold>
- Result: pass / fail / conditional
- Evidence: <report paths/checksums>
- Decision: <next action>
- Reviewer: <name/date>
```

Không ghi “works well” hoặc “nhanh hơn” mà không có số liệu và artifact.

---

## 14. Kết luận và quyết định khuyến nghị

Validation nên chứng minh theo thứ tự: **chạy đúng → package/RAM → precision → steps → provider → long-form quality → thermal → SDK lifecycle**. Nếu preset-only FP16/mixed chạy đạt target, không cần train audio lại; chỉ mở distillation/QAT khi có metric cụ thể cho thấy export/quantization/runtime không đủ giải quyết. Kết quả cuối cùng phải là một model mobile có revision, manifest, benchmark, fallback và rollback độc lập, để `main` vẫn ổn định và `develop` có thể tiếp tục nhận các feature generic một cách có kiểm soát.

---

## 15. Validation cho hệ sinh thái nhiều voice asset

Các giả thiết dưới đây bổ sung cho hướng **một base model + nhiều voice asset nhỏ**. Mục tiêu là chứng minh số lượng voice tăng không làm tăng RAM runtime, không làm sai voice khi switching và không tạo lỗ hổng từ asset không tương thích.

## H13 — Nhiều voice asset không làm RAM tăng theo số voice cài đặt

**Giả thiết:** app có thể cài hàng chục voice nhưng chỉ voice đang dùng chiếm working memory.

### Test matrix

```text
1 base model + 1 bundled voice
1 base model + 10 bundled voices
1 base model + 50 indexed/downloaded voices
1 active voice + preload next voice
voice switch giữa chunk
```

### Metric

- Installed package size.
- Metadata index size.
- Voice asset cache size.
- Peak RSS khi load 1/10/50 voice.
- Peak RSS khi switch voice.
- Số ONNX session/model copy.
- Thời gian activate voice.

### Pass

Peak RSS khi index nhiều voice không tăng tuyến tính theo số voice; chỉ khi preload hoặc activate voice mới tăng trong budget định trước. Không tạo session hoặc duplicate base model cho từng voice.

### Nếu fail

- Chuyển voice asset sang memory-mapped/lazy load.
- Giảm metadata và loại bỏ field không cần runtime.
- Chỉ preload một voice kế tiếp khi buffer đủ dài.
- Kiểm tra leak khi switch/cancel.

---

## H14 — Voice asset tương thích và deterministic với base model

**Giả thiết:** asset được tạo cho đúng base model revision sẽ tạo cùng speaker/style behavior trên Android và iOS.

### Test

1. Tạo voice asset từ cùng reference/config.
2. Load trên macOS ARM, Android và iOS.
3. Chạy cùng text, seed, steps, cfg và provider phù hợp.
4. Thử asset đúng revision, sai revision, sai precision và sai sample rate.
5. Kiểm tra manifest/checksum trước khi activate.

### Metric

- Asset load success/failure reason.
- Output duration/shape.
- Speaker similarity.
- ASR/CER/WER.
- Waveform/feature distance trong tolerance.
- Sai asset bị từ chối đúng hay không.

### Pass

Asset đúng revision load được và giữ quality trong ngưỡng; asset sai revision bị từ chối rõ ràng trước inference, không crash và không tự động fallback im lặng sang voice khác.

### Nếu fail

- Tăng compatibility fields trong manifest.
- Version hóa voice schema.
- Viết migration tool offline thay vì migrate ngầm trong SDK.
- Pin voice asset vào model revision cụ thể.

---

## H15 — Official/downloadable voice pack có thể cài đặt atomic và rollback

**Giả thiết:** người dùng có thể tải nhiều voice pack mà không làm app rơi vào trạng thái package dở dang hoặc dùng nhầm checksum.

### Test

- Download interrupted.
- App bị kill khi đang giải nén.
- Disk full.
- Checksum sai.
- Manifest thiếu file.
- Voice pack bị xóa khi đang playback.
- App downgrade/upgrade.
- Rollback từ asset revision mới về cũ.

### Metric

- Install success/failure classification.
- Partial files còn lại.
- Active voice trước/sau update.
- Playback interruption.
- Rollback time.
- Disk quota compliance.

### Pass

Install dùng temporary directory rồi atomic rename; pack lỗi không xuất hiện trong active registry; voice đang phát tiếp tục an toàn hoặc chuyển sang fallback hợp lệ; rollback không cần tải lại base model.

### Nếu fail

- Thiết kế lại package transaction.
- Tách model/voice registry và active pointer.
- Thêm garbage collection cho partial download.

---

## H16 — Local reference enrollment tạo asset nhỏ mà không cần giữ raw audio

**Giả thiết:** có thể tạo custom voice asset trên thiết bị mà không đưa toàn bộ cloning runtime vào core preset package.

### Test matrix

```text
reference sạch 5–8 giây
reference có noise
reference nhiều người nói
reference quá ngắn/dài
local enrollment có/không denoise
```

Chạy riêng trên device tier thấp và cao; đo cả trường hợp enrollment một lần rồi synthesize nhiều chunk.

### Metric

- Enrollment latency.
- Peak RSS.
- Temporary disk usage.
- Asset size.
- Speaker similarity.
- Quality sau 1/10/100 chunk.
- Raw audio cleanup success.
- Battery/thermal cost.

### Pass

Enrollment tạo asset nhỏ, giải phóng raw audio theo policy, không ảnh hưởng preset inference sau đó và quality ổn định cho corpus đại diện. Nếu module cloning vượt RAM hoặc latency, không đưa vào core; chuyển sang optional download hoặc server enrollment.

### Nếu fail

- Giới hạn local enrollment cho thiết bị Tier A.
- Dùng server enrollment có consent.
- Chỉ phân phối voice asset đã tạo, không phân phối speaker/codec encoder.
- Giữ preset-only làm default.

---

## H17 — Adapter/LoRA đã merge hoặc distilled có lợi hơn reference asset thuần túy

**Giả thiết:** adapter/LoRA được train offline có thể cải thiện style/character ổn định, nhưng không đáng đánh đổi RAM, load time và compatibility.

### Candidate

- Base + preset voice asset.
- Base + local/server reference asset.
- Base + adapter runtime.
- Mobile checkpoint đã merge adapter.
- Distilled voice asset/conditioning nếu có.

### Metric

- Package delta.
- Peak RSS khi load/switch adapter.
- Adapter activation latency.
- Speaker/style consistency trong 30 phút.
- ASR/pronunciation.
- RTF và thermal.
- Quality trên text ngoài training set.
- Rollback/fallback behavior.

### Pass

Chỉ giữ adapter nếu quality gain đo được đủ lớn so với asset thuần túy và không phá model compatibility; adapter phải ghi base revision, target modules, rank, precision, license và checksum.

### Nếu fail

- Merge adapter offline thành mobile checkpoint riêng.
- Distill style vào conditioning asset.
- Hoặc loại bỏ adapter khỏi mobile và dùng server-side personalization.

Không train LoRA trên mobile trong MVP; thiết bị chỉ load adapter/checkpoint đã ký và kiểm định.

---

## H18 — Voice switching không làm lẫn audio hoặc conditioning state

**Giả thiết:** đổi voice khi đang đọc không làm chunk đang phát dùng sai speaker hoặc chunk cũ bị đưa vào queue mới.

### Test

- Switch voice trước generation.
- Switch khi chunk đang generate.
- Switch khi chunk đang playback.
- Switch sau cancel.
- Switch giữa official pack và custom asset.
- Xóa voice đang active.

### Metric

- Voice ID gắn với từng chunk.
- Audio queue flush correctness.
- Cancel latency.
- Wrong-speaker occurrence.
- Crash/deadlock.
- Cache/session leak.

### Pass

Mọi audio chunk có immutable voice/model revision metadata; switch hủy hoặc hoàn tất generation theo policy rõ ràng, không có wrong-speaker chunk và không làm rò rỉ session/cache.

### Nếu fail

- Gắn generation ID + voice asset revision vào mọi job/chunk.
- Flush queue theo transaction.
- Không cho xóa asset đang được job giữ reference.

---

## 16. Bổ sung corpus và metrics cho nhiều voice

### Corpus voice

Mỗi voice phải được đánh giá trên:

- narration trung tính;
- narration giàu cảm xúc;
- hội thoại nhiều lượt;
- câu dài liên tục;
- câu ngắn chuyển tiếp;
- tên riêng/số/code-switching;
- đoạn có pause dài/ngắn.

### Metrics bổ sung

- `voice_asset_load_ms`.
- `voice_switch_ms`.
- `voice_cache_hit_rate`.
- `voice_cache_bytes`.
- `active_voice_revision`.
- `wrong_voice_chunk_count`.
- `asset_install_rollback_count`.
- `enrollment_peak_rss_mb`.
- `adapter_activation_ms`.
- `speaker_similarity_by_voice`.
- `style_consistency_by_voice`.

Không đánh giá nhiều voice chỉ bằng một speaker similarity trung bình; cần phát hiện voice nào bị suy giảm riêng sau quantization, provider fallback hoặc steps reduction.

---

## 17. Gate mở rộng cho voice ecosystem

### G9 — Voice asset contract

- Manifest schema đã version hóa.
- Base model/asset compatibility được validate.
- Checksum/license/provenance đầy đủ.
- Asset sai revision bị từ chối an toàn.

### G10 — Multi-voice memory

- 50 voice indexed không vượt metadata/disk budget.
- Chỉ active/preloaded voice chiếm working RAM.
- Voice switch không leak session/buffer.

### G11 — Optional custom voice

- Local enrollment hoặc server enrollment có consent.
- Raw audio cleanup đúng policy.
- Asset nhỏ, có checksum và xóa được.
- Preset-only app không bị phụ thuộc cloning graphs.

### G12 — Adapter/LoRA

- Chỉ mở sau khi reference asset baseline đã đo.
- Adapter/checkpoint có base revision và rollback.
- Không train trên mobile MVP.
- Quality gain đủ lớn so với package/RAM/latency cost.

---

## 18. Cập nhật decision matrix

| Tính năng | MVP | Sau validation | Không làm trong MVP |
|---|---:|---:|---:|
| Bundled official voices | Có | Mở rộng pack | — |
| Downloadable voice packs | Có thể giới hạn | Có sau G9/G10 | — |
| Custom local enrollment | Không bắt buộc | Sau H16 | Không đưa vào core sớm |
| Server-created voice asset | Không bắt buộc | Sau consent/compatibility | — |
| Runtime LoRA training | — | Không khuyến nghị | Không |
| Pretrained/merged adapter | — | Sau H17 | Không load asset không kiểm định |
| Text prompt tạo speaker mới | — | Nghiên cứu riêng | Không tuyên bố hỗ trợ |

Kết luận validation mới: **đa dạng giọng nên được đạt bằng voice asset ecosystem, không phải nhân bản base model hoặc training động trên mobile**. Nếu preset/downloadable asset đã đáp ứng nhu cầu, không cần mở cloning hoặc LoRA; chỉ thêm personalization khi có dữ liệu cho thấy speaker/style asset thuần túy không đủ.
