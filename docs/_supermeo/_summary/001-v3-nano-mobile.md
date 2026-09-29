# Plan 001 — VieNeu-TTS v3 Nano Mobile

> **Cập nhật cấu trúc (2026-09-29):** toàn bộ code + dữ liệu v3 Nano Mobile gom về **một root `v3nano-mobile/`** (merge-safety với `main`; xem `v3nano-mobile/README.md`). Map cũ → mới: `mobile/android/nano-mobile-harness/` → `v3nano-mobile/android/device-validation/`; `mobile/ios/NanoMobileHarness/` → `v3nano-mobile/ios/device-validation/`; `mobile/android/sdk/vieneu-tts-sdk/` → `v3nano-mobile/android/sdk/`; `tools/mobile/` → `v3nano-mobile/host/`; `models/v3_nano_mobile/` → `v3nano-mobile/package/`; `benchmarks/mobile/` → `v3nano-mobile/eval/`; `tests/mobile/` → `v3nano-mobile/tests/`. Namespace Kotlin: `com.vieneu.tts` → `com.vieneu.mobile`, `com.vieneu.nanoharness` → `com.vieneu.mobile.validation`. Report superseded nằm ở `eval/reports-archive/`. Các đường dẫn trong §0.x được viết lại theo cây mới (nội dung đo đạc không đổi).

- **Trạng thái:** **ĐÃ ĐÓNG PHASE VALIDATION 1** (9 vòng, 2026-09-27 → 2026-09-28) — **CHỐT chiến lược: xây MVP trước, validation nhúng trong phát triển** (§0.13). Cấu hình mobile **ĐÃ CHỐT**: `steps=8, sway=-1` + treble filter (§0.10); provider **ĐÃ CHỐT**: Android→CPU/XNNPACK, iOS→CPU (§0.11). Đã có số liệu thiết bị thật: RTF/RAM/thermal/battery (§0.5–§0.6, §0.11), FP16 + ORT optimization + graceful failure (§0.12), voice matrix 11 voices + determinism + voice switching (§0.12). Chưa production-ready — backlog còn lại được nhúng vào MVP tại §0.13.
- **Phạm vi:** Android ARM64 và iOS/iPadOS trên thiết bị thật
- **Validation protocol:** `002-v3-nano-mobile-validation.md` **không còn được dùng làm tài liệu thực thi** — dữ liệu, kết luận và nguyên tắc kiểm thử còn hiệu lực đã gom vào §0–§0.13 của tài liệu này; 002 chỉ giữ vai trò archive lịch sử.
- **Model mục tiêu:** một dòng model mới, tách biệt với v3 Nano hiện tại
- **Nguồn gốc:** lấy `main` làm baseline tham chiếu; phát triển độc lập trên `develop`
- **Đối tượng:** team phát triển Mobile/Edge
- **Không thay đổi trong plan này:** v3 Turbo, v3 Nano hiện tại, API server, model legacy và các public behavior đang phát hành

## 0. Execution status sau validation protocol 002

Validation được thực thi ngày **2026-09-27T15:18:03Z**, trên branch `develop`, revision `1d700d3d08a251aedd8c4b34e4456a152b3f8eae` (`Document v3 Nano Mobile plans`). Revision `main` được ghi nhận là `c1390abbdb2eedcdf58eafb546966c06ce27af71`. Không tải/cài dependency, model hoặc SDK ngoài scope.

### Baseline và môi trường quan sát được

| Hạng mục | Kết quả |
|---|---|
| OS/architecture | macOS Darwin 25.6.0, Apple Silicon `arm64` |
| Python/runtime | Python 3.14.4 (`/opt/homebrew/opt/python@3.14/bin/python3.14`) |
| uv | 0.9.7; `.venv` và `uv.lock` tồn tại |
| ONNX/model files trong repository | Không có `.onnx`, `.onnx.data`, `config.json`/`constants.npz` của Nano model hoặc mobile manifest |
| Local model cache | Chỉ thấy ONNX decoder của `neucodec`, không phải v3 Nano bundle |
| Runtime/dependency | `onnxruntime`, `onnx`, `huggingface_hub`, `soundfile`, `numpy`, `pytest` không import được trong Python hiện tại; `uv run --no-sync ... pytest` cũng báo `.venv/bin/python: No module named pytest` |
| Native/mobile tooling | Xcode 26.6 có sẵn; chỉ có iOS Simulator đang Shutdown. `adb` không có; không có Android/iOS harness trong repository |
| Preset asset | `src/vieneu/assets/voices_v3_nano.json`: JSON đọc được, 11 preset, mỗi preset `speaker_emb` 192 phần tử và `style` 50×256; SHA-256 `2ca4cbf475409e61fda662fbd3fb3a3ac3e980bbc3925fedb4d1199c4ccac3e7` |

Các lệnh/evidence chính: `git rev-parse HEAD main`, `python3 --version`, `uname -a`, `uv --version`, `python3 -m compileall -q ...`, kiểm tra JSON/schema bằng Python, `pytest -q tests/test_engine_v3nano.py tests/test_factory.py tests/test_user_voices.py`, và `uv run --no-sync python -m pytest ...`. `compileall` PASS; các test regression **NOT RUN** vì thiếu `pytest` và runtime dependencies. Kiểm tra source/API contract bằng AST tìm thấy `V3NanoVieNeuTTS`, `OnnxV3NanoEngine`, `infer_stream`, `infer_batch`, `encode_reference`; đây là static evidence, không phải model inference evidence.

### Kết quả H1–H18

| H | Trạng thái | Evidence và giới hạn kết luận | Quyết định/gate tiếp theo |
|---|---|---|---|
| H1 | PARTIAL | Static source/API và preset JSON hợp lệ; không có graph Nano, ONNX Runtime, Android ARM64 harness hoặc iOS device harness để kiểm tra operator, fallback, waveform. | Không mở G1; cần model bundle, ORT và harness thật. |
| H2 | BLOCKED | Không có P0–P3 package/candidate, model weights, native RSS profiler hoặc benchmark artifact. | Giữ target package/RAM ở trạng thái đề xuất; dựng packaging matrix trước G2. |
| H3 | BLOCKED | Chưa có FP32/FP16/INT8 candidates, calibration corpus, ASR/listening output. | Không quantize; giữ fallback FP32/reference theo protocol. |
| H4 | BLOCKED | Chưa chạy matrix steps 4/6/8/12/16 và không có audiobook corpus/baseline output. | Không chọn mobile steps; cần quality + RTF trên Tier B. |
| H5 | BLOCKED | Không có chunk runner, playback queue, device hoặc 30 phút underrun evidence. | Không kết luận chunk prefetch; cần native lifecycle harness. |
| H6 | BLOCKED | Không có ORT/provider runtime hoặc native harness; iOS Simulator không được dùng để kết luận CoreML/device performance. | XNNPACK/CPU chỉ là thiết kế tham chiếu, chưa được benchmark; chưa chọn default provider. |
| H7 | BLOCKED | Không có allocation/RSS profiler, static-shape candidates hoặc model session để đo. | Chưa bật static shape/buffer reuse trong mobile release. |
| H8 | BLOCKED | Không có Android/iOS thật, thermal/battery tooling hoặc soak output; simulator không thay thế device. | Không tuyên bố đạt thermal/playback target. |
| H9 | BLOCKED | Không có candidate audio, ASR/CER/WER, pronunciation corpus hoặc listening review. | Quality target chưa đo; chưa mở quality gate. |
| H10 | BLOCKED | Chưa có L0–L4 export ladder hoặc report đủ để quyết định không cần train lại. | Không mở training và cũng không kết luận L2/L3 đạt. |
| H11 | NOT RUN | Có baseline commit `main`/`develop`, nhưng chưa có sync run, mobile artifact, regression output hoặc checksum comparison; local regression tests bị chặn bởi thiếu `pytest`. | Chưa cho phép merge claim về mobile reproducibility. |
| H12 | BLOCKED | Không có model registry/package installer/SDK để thử corrupt checksum, interrupted install, provider failure hoặc rollback. | Bundled/reference fallback vẫn là yêu cầu bắt buộc, chưa được chứng minh. |
| H13 | PARTIAL | Preset asset static check PASS với 11 voice và schema nhất quán; chưa đo package/index/RAM, session count, preload hoặc voice switch. | Chỉ ghi nhận asset inventory; chưa chứng minh memory không tăng theo số voice. |
| H14 | PARTIAL | Asset JSON load/schema static PASS; không có base-model revision, asset manifest/checksum contract hoặc cross-platform deterministic output. | Bổ sung compatibility manifest trước G9; chưa kết luận asset tương thích. |
| H15 | BLOCKED | Không có downloadable pack, registry, installer hoặc disk-failure harness. | Chưa mở downloadable voice pack; cần atomic install/rollback test. |
| H16 | BLOCKED | Không có cloning graph/dependency, reference corpus, device enrollment harness hoặc privacy cleanup measurement. | Giữ local enrollment ngoài MVP; không suy diễn từ static API. |
| H17 | NOT RUN | Không có adapter/LoRA/distilled candidate; training nằm ngoài lượt validation này. | Không train/load adapter trên mobile MVP; chỉ xem xét sau baseline asset. |
| H18 | BLOCKED | Không có queue/SDK, generation ID, voice switch test hoặc playback device output. | Chưa chứng minh voice switching an toàn; cần lifecycle harness và wrong-speaker metric. |

**Tổng hợp:** local static checks chứng minh được một phần contract source và preset asset; không có H nào đủ evidence để PASS end-to-end. Các target ở mục 2 vẫn là target đề xuất, chưa phải số đo. Do G1, G2, quality/device/thermal và lifecycle gates bị BLOCKED/NOT RUN, kế hoạch chưa được gọi là production-ready.

### Điều chỉnh namespace và giới hạn kết luận

Trong source hiện tại, implementation class là `vieneu.v3nano.V3NanoVieNeuTTS`; tài liệu này dùng đúng symbol đó khi nói về class hiện hành. Việc đổi tên symbol không được thực hiện trong validation vì không sửa source. Validation cũng không biến v3 Nano hiện tại thành model mobile: model ID, graph, runtime revision, SDK revision, manifest/checksum và native adapters cho mobile vẫn chưa tồn tại trong repository.

## 0.1. Execution status — vòng 2 (mở rộng, với model thật + Android/iOS harness)

Vòng validation thứ hai được thực thi ngày **2026-09-28**, tiếp tục trên branch `develop`, cùng revision `1d700d3d08a251aedd8c4b34e4456a152b3f8eae` (`main` vẫn là `c1390abbdb2eedcdf58eafb546966c06ce27af71` — không có commit mới trên `main` giữa hai vòng). Khác với vòng 1 (chỉ static check), vòng này **cài đặt dependency thật, tải model bundle thật từ Hugging Face, và dựng harness thật** trên Android ARM64 emulator và iOS Simulator để chạy inference thật (không mock). Đây vẫn là validation theo protocol 002, không phải triển khai SDK sản phẩm.

### Những gì đã thay đổi so với vòng 1

| Hạng mục | Vòng 1 | Vòng 2 |
|---|---|---|
| Python runtime dùng để test | Python 3.14 hệ thống, thiếu toàn bộ dependency | `.venv` (Python 3.12.12) sau `uv sync --group dev`: `onnxruntime==1.24.4`, `onnx==1.23.0`, `pytest==9.0.3`, `numpy==2.3.4`, `huggingface_hub` |
| Model v3 Nano | Không có trong repo hoặc cache | Tải thật từ `pnnbao-ump/VieNeu-TTS-v3-Nano@aba295eb96a6fa6003ebe417cc1f2802a7adc1dc` (7 graph ONNX + `config.json` + `constants.npz`, ~420 MB) vào `~/.cache/huggingface/hub` |
| Test suite | NOT RUN (thiếu pytest) | `uv run --no-sync pytest -q` → **111 passed** (58.33s) |
| Android | Không có SDK, không có adb | Cài `android-commandlinetools` qua brew; platform-tools, build-tools 34, platform 34, `system-images;android-34;google_apis;arm64-v8a`, `emulator`; tạo AVD `nano_mobile_test`, boot headless qua Hypervisor.framework; project Gradle/Kotlin thật tại `v3nano-mobile/android/device-validation/` chạy `connectedDebugAndroidTest` |
| iOS | Chỉ có Simulator ở trạng thái Shutdown | Boot 1 simulator (`iPhone 16 Pro`, iOS 18.6) headless qua `xcrun simctl boot`; project Xcode thật (sinh bằng `xcodegen`) tại `v3nano-mobile/ios/device-validation/` chạy `xcodebuild test`, dùng ONNX Runtime C++ API (pod archive chính thức `download.onnxruntime.ai`) |

### Kết quả đo được (evidence thật, không phải placeholder)

**macOS ARM64, CPU, FP32** (`v3nano-mobile/eval/reports/2026-09-28-macos-arm64-fp32.json`):
- `onnx.checker.check_model` + shape inference: **PASS trên cả 8 graph** (4 core + 3 cloning + denoiser); toàn bộ operator là ONNX op chuẩn phổ thông (Conv, MatMul, LayerNormalization, Gemm, Softmax...), **0 custom op**, opset 17 (speaker_encoder: opset 14).
- Inference thật qua `OnnxV3NanoEngine.infer()` (không mock), preset voice "Minh Quân", câu 89 ký tự tiếng Việt: cold start 549 ms, generation 1089.7 ms cho 6.29 s audio → **RTF ≈ 0.173**, peak RSS macOS ≈ 476.5 MB, **không NaN/Inf**.
- Package size: full bundle (P0, gồm cloning + denoiser) = 419.9 MB raw / 375.0 MB gzip; preset-only (P1, chỉ 4 core graph) = **281.8 MB raw / 261.8 MB gzip** — giảm 32.9%/30.2% so với P0 nhưng **vẫn vượt target ≤150 MB của mục 2.2 gần gấp đôi**. `vector_estimator.onnx` (155.1 MB) và `codec_decoder.onnx` (99.3 MB) chiếm ~90% P1 — đây là bằng chứng cụ thể đầu tiên cho việc "Nếu fail" của H2 (002 §H2): cần profile riêng 2 graph này (không phải toàn bộ) trước khi cân nhắc quantize/distill.
- `uv run --no-sync pytest -q`: **111 passed**, xác nhận baseline reproducible thật trên máy hiện tại (tiền đề cho H11, chưa phải bản thân H11).

**Android ARM64 (emulator, không phải thiết bị thật)** (`v3nano-mobile/eval/reports/2026-09-28-android-emulator-arm64-fp32.json`):
- Instrumented test JUnit `NanoEngineTest#graphsLoadAndInferenceRunsOnArm64Cpu` qua `onnxruntime-android:1.22.0` (CPUExecutionProvider), chạy trên AVD `arm64-v8a`/API 34: **PASS** (0 failures, 3.111s), evidence tại `v3nano-mobile/android/device-validation/evidence/{junit-result.xml,logcat.txt}`.
- Graph load 1501 ms, full 16-step Euler flow + CFG=3.0 + codec decode: generation 1482 ms cho 6.4 s audio (100 frames), **không NaN/Inf**.
- Vấn đề hạ tầng gặp và đã xử lý: `createSession(byte[])` OOM trên Dalvik heap khi load `vector_estimator.onnx` (155 MB) → chuyển sang copy asset ra file cache rồi `createSession(path)` để ORT native đọc thẳng filesystem. `~/.android/debug.keystore` cũ trên máy bị hỏng ("tampered with") — đã hỏi và được xác nhận trước khi `mv` sang `.bak-broken` để Gradle tự sinh lại.

**iOS Simulator (không phải thiết bị thật)** (`v3nano-mobile/eval/reports/2026-09-28-ios-simulator-fp32.json`):
- XCTest `NanoEngineTests.testGraphsLoadAndInferenceRunsOnIOSSimulator` qua ONNX Runtime C++ API trực tiếp (không qua ObjC wrapper — xem lý do kỹ thuật dưới), chạy trên `iPhone 16 Pro`/iOS 18.6 simulator: **PASS** (0 failures, 2.795s), evidence tại `v3nano-mobile/ios/device-validation/evidence/test-result.xcresult`.
- Graph load 482.6 ms, cùng flow 16-step + CFG=3.0 + codec decode: generation 2261.9 ms cho 6.4 s audio, **không NaN/Inf**.
- Phát hiện kỹ thuật quan trọng: Objective-C wrapper của `microsoft/onnxruntime-swift-package-manager` (kể cả bản mới nhất 1.24.2) **không expose kiểu tensor BOOL** (`ORTTensorElementDataTypeBool` không tồn tại — xem GitHub issues #31, #41 của repo đó), trong khi `duration_predictor.onnx` và `vector_estimator.onnx` nhận input `ctx_mask` kiểu BOOL. Đây là một giới hạn thật của SDK bên thứ ba, không phải lỗi harness. Harness đã dùng thẳng ORT C++ API (`Ort::Value::CreateTensor(..., ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL)`) qua Objective-C++ để vượt qua; **một SDK Swift sản phẩm thật sẽ cần giải pháp tương đương** (custom bridging hoặc chờ upstream fix) trước khi build production, đây là một rủi ro/action-item mới cho Phase 6 (Native SDK) chưa từng được ghi trong plan 001 gốc.

### Cập nhật trạng thái H1–H18

| H | Trạng thái vòng 1 → vòng 2 | Lý do cập nhật |
|---|---|---|
| H1 | PARTIAL → **PARTIAL (mở rộng)** | Có evidence thật: ONNX checker PASS 8/8 graph, inference thật PASS trên macOS CPU, Android emulator ARM64, và iOS Simulator (3 môi trường, 3 report riêng). Vẫn PARTIAL — không phải PASS — vì: (a) Android/iOS ở đây là **emulator/simulator, không phải thiết bị thật** theo đúng giới hạn §2.3/§8 của 001 và 002; (b) chưa test NNAPI/CoreML EP, chỉ CPUExecutionProvider; (c) mỗi môi trường mới chạy **1 lần duy nhất**, chưa đủ cho median/p95 theo §8 của 002. Gate G1 **vẫn không được mở** — chỉ nâng cấp thành "pass có điều kiện" theo hệ §9 của 002 (chỉ CPU, chỉ 1 run, chỉ ảo hóa). |
| H2 | BLOCKED → **PARTIAL** | Có số đo thật lần đầu: P0=419.9MB/P1=281.8MB (raw), P1 vẫn vượt target 150MB gần 2×. Đây là candidate P1 (preset-only, FP32) duy nhất trong 4 candidate P0–P3 của test matrix H2 gốc — P2 (FP16/mixed) và P3 (modular download) **chưa được đo**. Không đủ để quyết định "đủ giảm mà không train lại" — chỉ đủ để xác định 2 graph cần profile riêng (vector_estimator, codec_decoder). |
| H7 | BLOCKED → **PARTIAL (rất hạn chế)** | Có 1 số đo peak RSS macOS (476.5 MB tổng, không tách theo graph) khi chạy dynamic-shape FP32 baseline. Chưa có static-shape candidate, chưa có buffer-reuse candidate, chưa so sánh — vẫn BLOCKED cho phần cốt lõi của H7 (so sánh dynamic vs static shape). |
| H11 | NOT RUN → **NOT RUN (tiền đề đã có)** | `pytest` baseline giờ chạy được và PASS (111 tests) — đây là điều kiện tiên quyết để H11 (test trước/sau sync từ `main`) có thể thực thi trong tương lai, nhưng bản thân H11 đòi hỏi một sự kiện sync `main`→`develop` thật để so sánh, sự kiện đó **chưa xảy ra** trong vòng này. Vẫn NOT RUN. |
| H3–H6, H8–H10, H12, H15–H18 | Không đổi | Không có candidate quantization, không có device thật, không có thermal/battery tooling, không có SDK/registry/installer, không có cloning/adapter pipeline trong vòng này. Việc có emulator/simulator **không** tạo bằng chứng cho các H này — theo đúng nguyên tắc "không suy luận từ simulator/emulator sang device thật" của 001 §8 và 002 §2.3, các H đòi hỏi RTF/RAM/thermal/battery/lifecycle thật vẫn giữ nguyên BLOCKED/NOT RUN. |

### Artifact mới

```text
v3nano-mobile/eval/reports/2026-09-28-macos-arm64-fp32.json
v3nano-mobile/eval/reports/2026-09-28-android-emulator-arm64-fp32.json
v3nano-mobile/eval/reports/2026-09-28-ios-simulator-fp32.json
v3nano-mobile/android/device-validation/            # Gradle/Kotlin harness (instrumented test), evidence/, README.md
v3nano-mobile/ios/device-validation/                  # XcodeGen/Objective-C++ harness (XCTest), evidence/, README.md
```

Model graphs (`*.onnx`) và ONNX Runtime binary (`onnxruntime.xcframework`) được vendor tạm thời trong hai thư mục harness khi chạy nhưng **không commit vào git** (`.gitignore` riêng ở mỗi harness) — chúng được tải lại từ Hugging Face / `download.onnxruntime.ai` theo hướng dẫn trong từng `README.md`, đúng nguyên tắc "không commit model weights lớn" của 001 §10 và 002 §10.

### Giới hạn kết luận (không đổi so với vòng 1, nhắc lại có chủ đích)

Vòng 2 **không** mở bất kỳ gate G1–G9 nào. Không có H nào đạt PASS end-to-end. RTF/RAM đo được trên emulator/simulator **không được dùng** để suy ra target §2.2 đạt trên Android/iOS thật — đây là quy tắc cứng của 001 §8 ("Không được suy luận từ simulator") và 002 §2.3 ("Ưu tiên thiết bị thật... Simulator không được dùng để tuyên bố model đạt target mobile"), áp dụng y hệt cho Android emulator. Kế hoạch **vẫn chưa production-ready**; bước tiếp theo hợp lý nhất là (a) profile riêng `vector_estimator.onnx`/`codec_decoder.onnx` để tìm hướng giảm package (H2), và (b) nếu có ngân sách phần cứng, lặp lại 2 harness này trên Android ARM64 và iPhone/iPad **thật** để bắt đầu thu thập RTF/RAM/thermal thật (mở khóa H3–H10).

## 0.2. Execution status — vòng 3 (corpus versioned + per-graph RSS, vẫn không có thiết bị thật)

Người dùng xác nhận **không có** Android/iPhone thật kết nối được vào máy chạy validation. Vòng 3 (2026-09-28, cùng revision `develop`/`main` như vòng 1–2) tập trung đúng vào phần của protocol 002 không đòi hỏi device thật: xây corpus versioned (§3), chạy benchmark nhiều lần để có median/p95/stddev thay vì 1 lần (§8), và đo RSS tách theo từng graph thay vì chỉ tổng số (làm sâu H2/H7). Không đụng `v3nano-mobile/android/*`, `v3nano-mobile/ios/*` — hai harness đó giữ nguyên từ vòng 2.

### Corpus mới (`v3nano-mobile/eval/corpus/`, script tạo tại `v3nano-mobile/host/build_corpus.py`)

| File | Số câu/đơn vị | Nguồn | Ghi chú quy mô |
|---|---:|---|---|
| `smoke.jsonl` | 20 câu | 10 câu mới viết + 4 câu tái dùng từ `examples/audio_ref/` + 6 câu tái dùng từ `src/vieneu/assets/samples/` | Đủ cho §3.1.A/B (câu ngắn/vừa/dài, 4 loại dấu câu, đoạn kể chuyện/hội thoại) |
| `stress.jsonl` | 20 câu | Viết mới, lấy cảm hứng từ ví dụ số/ngày tháng thật trong `tests/test_utils.py` | Bao phủ §3.1.C (số, ngày tháng, tiền tệ, phần trăm, tên riêng, Hán Việt, code-switching, viết tắt, URL/email) và §3.1.D (rỗng, whitespace, emoji, câu cực ngắn, câu dài vượt `_MAX_CHUNK_SECONDS`, dấu câu lặp, trích dẫn lồng) |
| `audiobook_short.jsonl` | 1 đoạn, 2382 ký tự | `examples/srt/sample_vi.srt` (36 dòng phụ đề ghép thành văn liên tục, ước tính nguồn gốc ~173 giây) + 2 đoạn viết thêm | **Không đạt** yêu cầu 5/15/30 phút của §3.1.E — chỉ đủ cho boundary/continuity smoke, ghi rõ trong `manifest.json` |
| `voice_matrix.json` | 5/11 preset voice | `src/vieneu/assets/voices_v3_nano.json` | Đúng tối thiểu §3.3: 1 giọng mặc định, 1 phong cách tin tức, 1 phong cách đọc truyện, 1 giọng khác biệt, 1 dùng kiểm tra speaker consistency |
| `manifest.json` | — | — | Ghi ngày tạo, license (nội dung mới viết coi như CC0 nội bộ; phần tái dùng giữ license repo gốc), SHA-256 + size của 4 file trên, và **ghi rõ đây là corpus MVP, không đạt quy mô production (regression 300-1000 câu, audiobook thật 5/15/30 phút) mà protocol 002 đề xuất** |

### Benchmark smoke corpus × 3 lần lặp (`v3nano-mobile/host/run_smoke_benchmark.py` → `v3nano-mobile/eval/reports/2026-09-28-macos-arm64-fp32-smoke-corpus.json`)

- Chạy 20 câu × 3 lần lặp = **60 data point RTF** trên macOS CPU FP32, voice cố định "Minh Quân", cùng model revision vòng 1–2.
- **RTF: median 0.2364, p95 0.4268, stddev 0.088, min 0.1503, max 0.5443** — thay thế cho "1 câu 1 lần" (RTF 0.1732) của report vòng 1, đúng yêu cầu "không lấy lần chạy nhanh nhất, báo cáo median/p95/variance" của protocol 002 §8. Biến động khá lớn (p95 gấp ~2.8× median) — hợp lý vì RTF phụ thuộc mạnh vào độ dài câu (20 câu có char_count khác nhau nhiều).
- Không có câu nào NaN/Inf trong 60 lần chạy.
- Cold start engine trong lần chạy này: 342.8 ms (khác biệt so với 549.2 ms của report vòng 1 — biến động bình thường giữa các lần chạy tiến trình, không phải cải tiến; không nên so sánh trực tiếp 2 con số này).

### Per-graph RSS load — làm sâu H2/H7 (cùng report trên, field `per_graph_load_rss_profile`)

Load tuần tự 4 graph core, đo delta RSS ngay sau mỗi graph:

| Graph | Size trên đĩa | RSS delta khi load |
|---|---:|---:|
| `text_encoder.onnx` | 26.5 MB | 68.5 MB |
| `duration_predictor.onnx` | 0.7 MB | 0.2 MB |
| `vector_estimator.onnx` | 155.1 MB | **151.6 MB** |
| `codec_decoder.onnx` | 99.3 MB | 68.9 MB |

Xác nhận bằng số đo RAM thật (không chỉ suy từ size trên đĩa như vòng 1): `vector_estimator.onnx` vừa là graph lớn nhất trên đĩa vừa là graph chiếm RAM nhiều nhất khi load, gần như 1:1 với size file. `codec_decoder.onnx` chiếm RAM ít hơn size trên đĩa (68.9MB RSS so với 99.3MB size) — có thể do memory-mapping hoặc phần weight không load hết vào RSS ngay lúc khởi tạo session; **đây là quan sát, chưa phải kết luận nguyên nhân** (cần investigate sâu hơn nếu muốn dùng cho quyết định tối ưu). Quyết định cho H2: nếu phải chọn ưu tiên quantize 1 graph trước, `vector_estimator.onnx` là ứng viên rõ ràng nhất (chiếm nhiều nhất cả disk lẫn RAM).

### Stress corpus — hard-fail check (`v3nano-mobile/host/run_stress_corpus.py` → `v3nano-mobile/eval/reports/2026-09-28-macos-arm64-stress-corpus.json`)

- 20/20 câu **PASS** theo tiêu chí "hard fail" của protocol 002 §9: 0 exception, 0 NaN/Inf, kể cả câu rỗng, whitespace-only, emoji, số/ngày tháng phức tạp, code-switching, câu dài (14.89s audio, đúng dưới `_MAX_CHUNK_SECONDS=15s` của `v3nano.py`).
- **Quan sát đáng chú ý (không phải hard fail, nhưng ghi lại)**: câu rỗng (`""`) và whitespace-only (`"   "`) **không** trả về waveform rỗng — engine vẫn sinh ra ~3.2 giây audio. Nguyên nhân: `encode_phones("")` vẫn tạo `ids=[bos, eos]`, nên `duration_predictor` + flow model vẫn chạy bình thường thay vì bị chặn sớm. Không sửa `src/vieneu/v3nano.py` trong vòng validation này (đúng nguyên tắc không sửa source) — chỉ ghi nhận đây là điểm cần cân nhắc thêm input validation ở tầng SDK mobile (Phase 6 của kế hoạch 001) nếu muốn tránh sinh audio vô nghĩa cho input rỗng.
- Đây **không phải** bằng chứng chất lượng phát âm (H9 đầy đủ cần ASR/CER/WER + listening test, chưa làm) — chỉ loại trừ crash/NaN ở mức cơ bản.

### Cập nhật trạng thái H1, H2, H7, H9 (giữ nguyên H3–H6, H8, H10, H12, H15–H18)

| H | Trạng thái vòng 2 → vòng 3 | Lý do |
|---|---|---|
| H1 | PARTIAL (mở rộng) → **PARTIAL (nhiều lần chạy, macOS)** | RTF giờ có median/p95/stddev trên 60 data point thay vì 1 lần; vẫn PARTIAL vì chỉ macOS CPU, Android/iOS vẫn là emulator/simulator từ vòng 2 (không re-run trong vòng 3). |
| H2 | PARTIAL → **PARTIAL (per-graph RSS thật)** | Có bằng chứng RAM cụ thể theo từng graph, xác nhận `vector_estimator.onnx` là ưu tiên số 1 nếu quantize. Vẫn PARTIAL vì chưa có candidate FP16/INT8 thật (P2/P3 của test matrix H2 gốc vẫn chưa đo). |
| H7 | PARTIAL (rất hạn chế) → **PARTIAL (per-graph, vẫn chưa có static-shape candidate)** | Tách được RSS theo graph (điều H7 gốc đòi hỏi một phần), nhưng vẫn chưa có so sánh dynamic vs static shape hay buffer-reuse — phần cốt lõi của H7 vẫn BLOCKED. |
| H9 | BLOCKED → **PARTIAL (chỉ hard-fail, chưa quality)** | Xác nhận 20 câu edge-case không crash/NaN — điều kiện cần nhưng không đủ cho H9 (thiếu ASR/CER/WER, listening test, corpus quy mô lớn hơn). |
| H3–H6, H8, H10, H12, H15–H18 | Không đổi | Vẫn cần Android/iPhone thật, quantization candidates, SDK/registry, hoặc cloning pipeline — không có gì trong vòng 3 tạo bằng chứng cho các H này. |

### Artifact mới

```text
v3nano-mobile/eval/corpus/manifest.json
v3nano-mobile/eval/corpus/smoke.jsonl
v3nano-mobile/eval/corpus/stress.jsonl
v3nano-mobile/eval/corpus/audiobook_short.jsonl
v3nano-mobile/eval/corpus/voice_matrix.json
v3nano-mobile/eval/reports/2026-09-28-macos-arm64-fp32-smoke-corpus.json
v3nano-mobile/eval/reports/2026-09-28-macos-arm64-stress-corpus.json
v3nano-mobile/host/build_corpus.py
v3nano-mobile/host/run_smoke_benchmark.py
v3nano-mobile/host/run_stress_corpus.py
```

### Giới hạn kết luận (nhắc lại)

Vòng 3 **không** mở bất kỳ gate nào, không tạo bằng chứng cho H3–H6/H8/H10/H12/H15–H18 (đều cần thiết bị thật hoặc pipeline chưa dựng). Corpus mới là **quy mô MVP** (20+20 câu, 1 đoạn audiobook ngắn) — không thay thế corpus production-scale (300-1000 câu regression, audiobook 5/15/30 phút thật) mà protocol 002 yêu cầu trước khi mở quality/quantization gate. Nút thắt lớn nhất để tiến xa hơn **vẫn là thiết bị Android/iPhone thật** — không có cách nào trong phạm vi macOS-only để thay thế RTF/RAM/thermal/battery đo trên device.

## 0.4. Execution status — vòng 4 (hoàn thiện validation trên giả lập: full corpus + multi-provider + ASR quality)

Người dùng xác nhận: test trên giả lập (không cần thiết bị thật) là đủ cho **bước validation đầu tiên**, yêu cầu "tiếp tục hoàn thiện theo đúng plan validation". Vòng 4 (2026-09-28, cùng revision `develop`/`main`) mở rộng tối đa 3 môi trường đã dựng: (1) Android emulator và iOS Simulator giờ chạy **toàn bộ** corpus smoke+stress (không phải 1 câu như vòng 2) với **3 lần lặp/câu** cho smoke (như macOS ở vòng 3) và **so sánh nhiều execution provider**; (2) thêm **ASR/WER-CER** (faster-whisper) để có số đo định lượng đầu tiên cho H9, thay vì chỉ "không NaN/crash".

### Mở rộng harness Android (CPU / XNNPACK / NNAPI)

`v3nano-mobile/android/device-validation/app/src/androidTest/.../NanoEngineTest.kt` viết lại hoàn toàn: đọc input tensor cho cả 40 case (20 smoke + 20 stress, xuất qua `v3nano-mobile/host/export_corpus_tensors.py`), chạy qua 3 `SessionOptions` riêng biệt — `CPUExecutionProvider` (mặc định), `addXnnpack(emptyMap())`, `addNnapi()` — mỗi provider load lại toàn bộ 4 graph core độc lập (không tái sử dụng session giữa các provider, đúng nguyên tắc §2.1 "không thay đổi nhiều biến cùng lúc"). Kết quả: **`connectedDebugAndroidTest` PASS** (249.5s, 0 failures — `v3nano-mobile/android/device-validation/evidence/junit-result-multi-provider.xml`), **0 hard-fail** trên toàn bộ 3 provider × (20 smoke × 3 lần + 20 stress × 1 lần).

| Provider | Thực tế chạy | Fallback CPU? | Graph load (ms) | RTF median | RTF p95 | RTF stddev |
|---|---|---:|---:|---:|---:|---:|
| CPU | CPU | — | 905 | 0.2597 | 0.3544 | 0.0512 |
| XNNPACK | XNNPACK | Không | 1298 | 0.2817 | 0.3888 | 0.0630 |
| NNAPI | NNAPI | Không | 1457 | 0.3358 | 0.6444 | 0.1319 |

(`v3nano-mobile/eval/reports/2026-09-28-android-emulator-arm64-multi-provider.json`, n=60 mỗi provider)

Cả 3 provider khởi tạo thành công (không provider nào fallback CPU do lỗi). Trên **emulator này**, CPU nhanh nhất, NNAPI chậm nhất và có variance cao nhất (p95 gấp ~2.5× median) — rất có thể do NNAPI trên Android Emulator không có driver NPU/GPU thật để tăng tốc, chỉ chạy qua reference implementation CPU-based của Android, nên chỉ tạo overhead mà không có lợi ích. **Không dùng kết quả này để chọn provider mặc định (H6)** — theo đúng nguyên tắc đã cam kết.

### Mở rộng harness iOS (CPU / CoreML)

`v3nano-mobile/ios/device-validation/DeviceValidationTests/NanoEngineTests.mm` viết lại tương tự, dùng ORT C++ API trực tiếp (`OrtSessionOptionsAppendExecutionProvider_CoreML`) — không qua ObjC wrapper (vẫn thiếu BOOL tensor type ở bản 1.24.2, như đã ghi ở vòng 2). Kết quả: **`xcodebuild test` PASS** (216.7s tổng, `-[NanoEngineTests testRunFullCorpusAcrossProviders]` 197.6s), **0 hard-fail** trên cả CPU và CoreML.

| Provider | Thực tế chạy | Fallback CPU? | Graph load (ms) | RTF median | RTF p95 | RTF stddev |
|---|---|---:|---:|---:|---:|---:|
| CPU | CPU | — | 552.5 | 0.2031 | 0.2772 | 0.0353 |
| CoreML | CoreML | Không | 2418.5 | 0.5597 | 0.8536 | 0.1696 |

(`v3nano-mobile/eval/reports/2026-09-28-ios-simulator-multi-provider.json`, n=60 mỗi provider)

CoreML EP khởi tạo thành công (không fallback), nhưng RTF chậm hơn CPU **2.75×** (median) trên môi trường này. Nguyên nhân khả dĩ nhất: **iOS Simulator không có Apple Neural Engine (ANE) thật** — CoreML trên Simulator chạy qua compute path CPU/GPU-only của máy host thay vì phần cứng ANE chuyên dụng, nên khác biệt này **rất có thể không phản ánh CoreML+ANE trên iPhone/iPad thật** (mức độ sai lệch giữa Simulator và device thật ở đây được cho là lớn hơn cả trường hợp NNAPI/Android). Không dùng để chọn provider mặc định (H6).

### ASR/WER-CER — số đo định lượng đầu tiên cho H9

`v3nano-mobile/host/run_asr_quality_check.py`: cài tạm `faster-whisper` (model `small`, CPU, int8) + `jiwer` vào `.venv` (không sửa `pyproject.toml`/`uv.lock`). Chạy 18 câu smoke (bỏ câu <3 từ) × 3 voice preset (`Minh Quân`, `Ái Hân`, `Mỹ Duyên` — đa dạng gender/style theo §3.3) = 54 data point, transcribe bằng ASR, tính WER/CER so với text gốc.

| Voice | WER trung bình | CER trung bình |
|---|---:|---:|
| Minh Quân | 0.1018 | 0.0778 |
| Ái Hân | 0.0713 | 0.0549 |
| Mỹ Duyên | 0.0463 | 0.0462 |
| **Tổng thể** | **0.0731** | **0.0596** |

(`v3nano-mobile/eval/reports/2026-09-28-macos-arm64-asr-quality.json`)

WER trung bình ~7.3%, CER ~6% — số đo định lượng đầu tiên cho H9 (trước đó chỉ có "không NaN/crash"). Khác biệt giữa 3 voice không lớn nhưng có xu hướng nhất quán (Minh Quân có WER cao nhất). Audit thủ công 10 case WER cao nhất (lưu trong report, field `worst_10_cases_for_manual_audit`) cho thấy nguyên nhân **hỗn hợp**: một số do TTS phát âm không rõ (ví dụ "Trời" nghe thành "Chơi"/"Chời"), một số có thể do ASR nhận nhầm câu ngắn — cần nghe lại thủ công để phân định rạch ròi, chưa làm trong vòng này.

### Cập nhật trạng thái H1, H6, H9 (giữ nguyên H2–H5, H7–H8, H10, H12, H15–H18)

| H | Trạng thái vòng 3 → vòng 4 | Lý do |
|---|---|---|
| H1 | PARTIAL (nhiều lần chạy, macOS) → **PARTIAL (full corpus, cả 3 môi trường)** | Android emulator và iOS Simulator giờ chạy toàn bộ 40 case × nhiều lần thay vì 1 câu 1 lần; vẫn PARTIAL vì cả 2 vẫn là ảo hóa, không phải thiết bị thật. |
| H6 | BLOCKED → **PARTIAL (dữ liệu sơ bộ trên ảo hóa, chưa mở gate)** | Có dữ liệu CPU vs XNNPACK vs NNAPI (Android) và CPU vs CoreML (iOS) lần đầu tiên — nhưng đây là "dữ liệu tham khảo sơ bộ" theo đúng cam kết, KHÔNG đủ điều kiện quyết định provider mặc định. Protocol 002 §H6 yêu cầu ≥2 thiết bị thật cùng platform; NNAPI/CoreML trên ảo hóa đặc biệt không đáng tin (thiếu NPU/ANE thật) nên xu hướng "CPU nhanh nhất" quan sát được ở đây có thể **đảo ngược hoàn toàn** trên thiết bị thật có NPU/ANE. |
| H9 | PARTIAL (chỉ hard-fail) → **PARTIAL (có WER/CER thật)** | Lần đầu có số đo định lượng (WER 7.3%, CER 6%) thay vì chỉ crash-check; vẫn PARTIAL vì thiếu listening test người thật, ASR tự nó cũng có sai số, và corpus chỉ 18-20 câu (không phải quy mô regression). |
| H2–H5, H7–H8, H10, H12, H15–H18 | Không đổi | Không có candidate quantization, không đo RAM/thermal/battery, không có thiết bị thật, không có SDK/registry/cloning pipeline trong vòng này. |

### Artifact mới

```text
v3nano-mobile/eval/reports/2026-09-28-android-emulator-arm64-multi-provider.json
v3nano-mobile/eval/reports/2026-09-28-ios-simulator-multi-provider.json
v3nano-mobile/eval/reports/2026-09-28-macos-arm64-asr-quality.json
v3nano-mobile/host/export_corpus_tensors.py
v3nano-mobile/host/run_asr_quality_check.py
v3nano-mobile/android/device-validation/app/src/androidTest/.../NanoEngineTest.kt   # viết lại: multi-provider, full corpus
v3nano-mobile/android/device-validation/app/src/main/assets/nano_test_input/corpus/  # 40 case exported tensors
v3nano-mobile/android/device-validation/evidence/{junit-result-multi-provider.xml,logcat_multi_provider.txt,result_multi_provider.json}
v3nano-mobile/ios/device-validation/DeviceValidationTests/NanoEngineTests.mm         # viết lại: multi-provider, full corpus, CoreML EP
v3nano-mobile/ios/device-validation/DeviceValidation/nano_test_input/corpus/         # 40 case exported tensors
v3nano-mobile/ios/device-validation/evidence/{console_multi_provider.log,result_multi_provider.json}
```

### Giới hạn kết luận (nhắc lại, có chủ đích)

Vòng 4 **vẫn không** mở bất kỳ gate nào. Đây là **bước validation đầu tiên trên giả lập** như người dùng đã xác nhận — không phải kết luận cuối cho H6 (provider mặc định) hay H9 (quality gate). Hai quan sát quan trọng nhất của vòng này: (1) NNAPI/CoreML đều **chậm hơn CPU đơn thuần** trên môi trường ảo hóa — nhiều khả năng là do thiếu phần cứng tăng tốc thật (NPU/ANE), nên xu hướng này **không nên ngoại suy sang thiết bị thật**, có thể đảo ngược hoàn toàn; (2) WER/CER ~7%/~6% là con số khả quan ban đầu nhưng dựa trên corpus rất nhỏ (18-20 câu) và cần đối chiếu bằng tai người trước khi tin tưởng đầy đủ. Bước tiếp theo hợp lý nhất khi có ngân sách: lặp lại đúng 2 harness này (không cần viết lại, chỉ cần build/deploy) trên Android ARM64 và iPhone/iPad **thật** để có dữ liệu quyết định H6, và mở rộng ASR quality check sang toàn bộ stress corpus + nghe thủ công 10 case audit.

## 0.5. Execution status — vòng 5 (THIẾT BỊ THẬT: Android + iOS — lần đầu tiên có RTF thật)

Người dùng đã kết nối **Android thật (OnePlus PHP110, SoC MediaTek mt6983/Dimensity 9200, 15.8GB RAM, Android 16)** và **iPhone thật (iPhone 11, iOS 26.6.2, arm64e)**, cả hai đã bật Developer Mode. Đây là lần đầu tiên trong toàn bộ quá trình validation có dữ liệu RTF/hiệu năng đo trên **thiết bị thật**, không phải emulator/simulator — mở khóa một phần thật của H1, H6, H9 mà các vòng trước không thể chạm tới.

### Phát hiện hạ tầng quan trọng: `connectedDebugAndroidTest` không dùng được trên thiết bị OnePlus thật

Lần chạy đầu tiên trên Android thật dùng nguyên `connectedDebugAndroidTest` (harness từ vòng 2-4, đã chạy tốt trên emulator) bị **OxygenOS/ColorOS's `OplusHansManager`** ("Hans" — cơ chế StrictMode phát hiện tiến trình dùng CPU liên tục >300% và coi là bất thường) tự động đóng băng định kỳ ngay giữa lúc benchmark, dù đã:
- Thêm vào `dumpsys deviceidle whitelist`,
- Tắt `settings put global cached_apps_freezer disabled`,
- Người dùng tự tắt hạn chế nền thủ công trong Settings điện thoại.

Log hệ thống xác nhận: `OplusHansManager: freeze uid: ... com.vieneu.mobile.validation pids: [...] scene: |StrictMode-3|LcdOn`, CPU-time đứng yên hàng chục giây liên tục dù tiến trình không thoát hẳn. Nguyên nhân gốc: `androidTest` chạy dưới dạng **instrumentation process không có UI**, bị OEM (OnePlus/Oppo) phân loại là "background" bất kể cấu hình whitelist.

**Giải pháp** (do người dùng đề xuất đúng hướng, đối chiếu với cách iOS `xcodebuild test` vẫn luôn launch app host lên foreground có UI): tách logic benchmark ra `NanoBenchmarkCore.kt` (dùng chung), viết `MainActivity.kt` — **app có UI thật, foreground**, chạy benchmark trên background thread, `FLAG_KEEP_SCREEN_ON`, hiển thị tiến độ trực tiếp. Kết quả: process priority đổi từ `nice=20` (thấp nhất, của instrumented test) sang **`nice=-10`** (cao) của foreground Activity — CPU duy trì 300-400% liên tục suốt ~10 phút, hoàn tất toàn bộ 120 case (3 provider × 40 case) không bị treo lần nào. `NanoEngineTest.kt` (androidTest) vẫn giữ nguyên, giờ gọi lại `NanoBenchmarkCore` — vẫn dùng tốt cho emulator/CI.

### Kết quả Android thật (`v3nano-mobile/eval/reports/2026-09-28-android-real-device-multi-provider.json`)

`any_hard_fail: false` trên toàn bộ 120 case (3 provider × 20 smoke × 3 lần lặp + 20 stress × 1 lần), không NaN/Inf/exception nào.

| Provider | Graph load (ms) | RTF median | RTF p95 | RTF stddev |
|---|---:|---:|---:|---:|
| CPU | 1139 | **0.6077** | 0.7500 | 0.0792 |
| XNNPACK | 1467 | **0.6764** | 0.8897 | 0.0919 |
| NNAPI | 3967 | **0.9393** | 1.3116 | 0.1948 |

**Phát hiện quan trọng nhất của toàn bộ validation cho tới nay**: RTF median CPU = 0.6077 **vượt target ≤0,5 của mục 2.2** — trên một chip **flagship** (Dimensity 9200-class, Tier A), không phải "thiết bị tầm trung" (Tier B) mà target đó áp dụng. Nếu target 0.5 áp cho Tier B thực sự, và Tier A đã vượt 0.5, thì **rất có khả năng Tier B/C sẽ vượt xa hơn nữa** — đây là tín hiệu cụ thể đầu tiên cho thấy target RTF ≤0.5 khó đạt được với FP32/16-step hiện tại chỉ bằng runtime optimization, và có thể cần quantization/giảm steps (H3/H4) thật sự, không chỉ là "có thể cần" như suy đoán trước đây. Xu hướng provider khớp với quan sát trên emulator (CPU nhanh nhất, NNAPI chậm nhất) — nhưng giờ là bằng chứng thật, không phải suy luận từ ảo hóa.

### Kết quả iOS thật (`v3nano-mobile/eval/reports/2026-09-28-ios-real-device-cpu.json`, iPhone 11)

- **`testProviderCPU`**: ✅ PASS hoàn toàn (208.4 giây, 0 hard-fail) trên iPhone 11 thật (A13 Bionic, 4GB RAM, iOS 26.6.2) — full corpus 40 case × 3 lần lặp cho smoke. **RTF median = 0.7035, p95 = 1.132, stddev = 0.1917** (n=60) — **cũng vượt target ≤0,5**, thậm chí cao hơn cả Android CPU (0.6077) dù iPhone 11 (2019) không phải flagship hiện tại. Cùng với kết quả Android, đây là 2/2 thiết bị thật đo được đều vượt target RTF.
- **`testProviderCoreML`**: ❌ **Lỗi kỹ thuật thật** (không phải OS treo tiến trình như Android) — `Ort::Exception: ... Error executing model: Error in building plan`, nguyên nhân gốc: `[espresso] Blob cannot be represented on texture: width 29184 ... larger than valid texture dimensions width 16384 height 16384`. Đây là **giới hạn cứng của Metal GPU texture size** trên CoreML backend của iPhone 11 — graph `vector_estimator.onnx` có sequence-length tensor (29184) vượt giới hạn texture 2D tối đa (16384) mà CoreML dùng để biểu diễn dữ liệu trên GPU. Đây là bằng chứng kỹ thuật cụ thể, không phải "chưa test được": **CoreML EP không tương thích trực tiếp với graph v3 Nano hiện tại trên iPhone 11**, cần refactor graph (chia nhỏ theo static shape bucket, theo đúng khuyến nghị Phase 3 của 001 §Kiến trúc runtime) hoặc dùng `COREML_FLAG_ONLY_ALLOW_STATIC_INPUT_SHAPES` trước khi thử lại.

### Cập nhật trạng thái H1, H6, H8, H9 (giữ nguyên H2–H5, H7, H10, H12, H15–H18)

| H | Trạng thái vòng 4 → vòng 5 | Lý do |
|---|---|---|
| H1 | PARTIAL (full corpus, cả 3 môi trường ảo hóa) → **PARTIAL (có device thật, 1 Android + 1 iOS)** | Graph chạy đúng, không NaN/Inf trên CẢ THIẾT BỊ THẬT lần đầu tiên. Vẫn PARTIAL: chỉ 1 thiết bị/platform (protocol 002 yêu cầu Tier A/B/C mỗi platform), CoreML fail kỹ thuật trên iOS. |
| H6 | PARTIAL (dữ liệu sơ bộ trên ảo hóa) → **PARTIAL (dữ liệu thật, 1 thiết bị/platform)** | Có RTF thật theo provider trên cả 2 platform lần đầu tiên. Vẫn PARTIAL vì protocol 002 §H6 yêu cầu ≥2 thiết bị thật/platform trước khi quyết định provider mặc định — xu hướng "CPU nhanh nhất" nhất quán qua emulator lẫn device thật cho Android, nhưng cần thêm thiết bị (đặc biệt non-MediaTek/non-Apple SoC) để xác nhận không phải đặc thù 1 vendor. |
| H8 | BLOCKED → **PARTIAL rất hạn chế (chưa phải soak test)** | Có 1 lần chạy liên tục ~10 phút trên Android thật không crash — không đủ cho yêu cầu 30-60 phút chính thức của H8, nhưng là bằng chứng đầu tiên về độ ổn định ngắn hạn trên phần cứng thật. Không đo nhiệt độ/battery drain — vẫn cần Phase riêng cho H8 đầy đủ. |
| H9 | PARTIAL (WER/CER trên macOS) → **không đổi (WER/CER vẫn chỉ trên macOS)** | Chưa chạy ASR quality check trên chính audio sinh ra từ thiết bị thật — chỉ có hard-fail check (không NaN) từ Android/iOS thật. |
| H2–H5, H7, H10, H12, H15–H18 | Không đổi | Không có candidate quantization/steps-reduction, không đo RAM/RSS thiết bị thật, không có SDK/registry/cloning pipeline trong vòng này. |

### Artifact mới

```text
v3nano-mobile/eval/reports/2026-09-28-android-real-device-multi-provider.json
v3nano-mobile/eval/reports/2026-09-28-ios-real-device-cpu.json
v3nano-mobile/android/device-validation/app/src/main/java/com/vieneu/mobile/validation/NanoBenchmarkCore.kt   # mới — logic benchmark dùng chung
v3nano-mobile/android/device-validation/app/src/main/java/com/vieneu/mobile/validation/MainActivity.kt         # mới — foreground UI cho device thật
v3nano-mobile/android/device-validation/app/src/main/AndroidManifest.xml                                  # sửa — thêm <activity> LAUNCHER
v3nano-mobile/android/device-validation/app/src/androidTest/java/com/vieneu/mobile/validation/NanoEngineTest.kt # sửa — gọi lại NanoBenchmarkCore, không đổi hành vi test
v3nano-mobile/android/device-validation/evidence/{logcat_real_device_multi_provider.txt,result_real_device_multi_provider.json}
```

### Giới hạn kết luận (nhắc lại)

Vòng 5 **vẫn không** mở bất kỳ gate G1–G9 nào — nhưng lần đầu tiên có bằng chứng thật đủ mạnh để nghi ngờ nghiêm túc target RTF ≤0,5 của mục 2.2. **Cả 2 thiết bị thật đo được (Android CPU 0.6077, iOS CPU 0.7035) đều vượt target** — đây không còn là hiện tượng đơn lẻ của 1 nền tảng mà là xu hướng nhất quán trên cả Android và iOS, dùng CPUExecutionProvider (đường an toàn/mặc định nhất, không cần accelerator). Đây là **tín hiệu cảnh báo cụ thể**, không còn là suy đoán lý thuyết — khuyến nghị mạnh: trước khi đầu tư tiếp vào Phase 2 (tách preset-only) hay SDK, nên ưu tiên chạy thử nghiệm quantization/giảm steps (H3/H4) tối thiểu 1 candidate để xác nhận có đường nào đưa RTF về dưới 0.5 hay không, tránh xây SDK production trên một baseline chưa chắc đạt được target hiệu năng cốt lõi. CoreML trên iPhone 11 cần graph fix trước khi thử lại — không dùng làm provider mặc định iOS cho tới khi giải quyết. Cần thêm ≥1 thiết bị/platform nữa (đặc biệt Tier B/C, non-MediaTek Android, iPhone đời mới hơn) trước khi H6 có thể coi là đủ dữ liệu.

## 0.6. Execution status — vòng 6 (H4: steps=8/sway=-1 trên 2 thiết bị thật — RTF ĐẠT target)

Theo khuyến nghị trực tiếp ở cuối vòng 5, thử nghiệm đầu tiên cho **H4** (protocol 002: "Giảm flow steps xuống 4–8 vẫn đạt chất lượng audiobook") — dùng `steps=8, sway=-1`, đúng khuyến nghị có sẵn trong docstring `src/vieneu/v3nano.py` ("8 is ~2x faster and still intelligible — pair it with sway=-1"), chưa từng được thử nghiệm ở các vòng trước.

### Phát hiện kỹ thuật trước khi đo: `sway` không phải no-op, cả 2 harness đã hard-code sai

Trước khi chạy, phát hiện cả `NanoBenchmarkCore.kt` (Android) và `NanoEngineTests.mm` (iOS) đều hard-code lịch trình Euler tuyến tính `u[i] = i/n` — bỏ qua hoàn toàn công thức warping thật của `OnnxV3NanoEngine.infer()`:

```python
# src/vieneu/v3nano.py — nguồn sự thật
tg = u + sway * (np.cos(np.pi / 2 * u) - 1 + u)   # sway != 0 làm lệch cả t lẫn dt
```

Đối chứng bằng NumPy (`sway=-1, steps=8`) cho thấy `tg` khác biệt rõ rệt so với `u` tuyến tính (ví dụ `tg[1]=0.019` so với `u[1]=0.125`) — nếu chạy `sway=-1` mà không sửa công thức, kết quả trên 2 harness sẽ **không phản ánh đúng hành vi thật** của model. Đã sửa cả `NanoBenchmarkCore.kt` và `NanoEngineTests.mm` để tính đúng `tg` (thêm tham số `sway`, đọc từ `global_meta.json`), rồi đối chứng nhanh trên macOS ARM64 bằng `OnnxV3NanoEngine.infer(steps=8, sway=-1)` thật trước khi build app: RTF giảm từ 0.2679 (steps=16) xuống 0.1380 (steps=8/sway=-1), tốc độ ~1.94×, không NaN — khớp đúng công thức trước khi tốn thời gian trên thiết bị thật.

### Kết quả RTF: cả 2 thiết bị thật đều ĐẠT target ≤0,5 ở steps=8/sway=-1

| Thiết bị | Provider | RTF median (steps=16, sway=0) | RTF median (steps=8, sway=-1) | Speedup | Đạt ≤0,5? |
|---|---|---:|---:|---:|---|
| Android (OnePlus PHP110) | CPU | 0.6077 | **0.3239** | 1.88× | ✅ Đạt |
| Android | XNNPACK | 0.6764 | 0.3528 | 1.92× | ✅ Đạt |
| Android | NNAPI | 0.9393 | 0.4222 | 2.22× | ✅ Đạt (median; p95=0.66 vẫn cao) |
| iOS (iPhone 11) | CPU | 0.7035 | **0.2936** | 2.40× | ✅ Đạt |

(`v3nano-mobile/eval/reports/2026-09-28-android-real-device-steps8.json`, `2026-09-28-ios-real-device-steps8.json`)

**`any_hard_fail=false`** trên cả 2 thiết bị, toàn bộ 40 case (20 smoke × 3 lần lặp + 20 stress × 1 lần) mỗi thiết bị — không NaN/Inf/exception. iOS test hoàn tất trong 86.3 giây (so với 208.4s ở steps=16). Đây là **kết quả đảo ngược hoàn toàn** so với cảnh báo ở vòng 5: với `steps=8, sway=-1`, cả 2 thiết bị thật đều đạt target RTF ≤0,5 trên mọi provider đã thử.

### Giới hạn kết luận nghiêm ngặt — RTF không phải Quality

Đây là điểm **quan trọng nhất cần nhấn mạnh, đúng theo cấm chỉ rõ ràng của protocol 002 §H4**: *"Không chọn `steps=4` chỉ vì nhanh nếu long-form score fail"*. Kết quả trên **chỉ là RTF + hard-fail (NaN/Inf) check** — **chưa có bất kỳ đánh giá chất lượng phát âm nào cho chính audio sinh ra ở steps=8** trên 2 thiết bị này. Giảm số bước Euler có thể làm giảm độ chính xác flow-matching (đặc biệt ở câu dài, prosody, tên riêng/số phức tạp — đúng nhóm case có trong `stress.jsonl`) mà RTF/NaN-check không phát hiện được. **Không được dùng kết quả vòng 6 để tuyên bố "steps=8 là mặc định mới"** — cần tối thiểu: chạy lại ASR/WER-CER (như đã làm ở vòng 4 cho steps=16 trên macOS, `v3nano-mobile/eval/reports/2026-09-28-macos-arm64-asr-quality.json`) trên audio steps=8, và nghe thử thủ công, trước khi coi đây là ứng viên chính thức.

### Cập nhật trạng thái H4 (giữ nguyên H1–H3, H5–H10, H12, H15–H18)

| H | Trạng thái vòng 5 → vòng 6 | Lý do |
|---|---|---|
| H4 | BLOCKED → **PARTIAL (RTF đạt target trên 2 thiết bị, 1 điểm dữ liệu mỗi thiết bị)** | Có RTF thật cho `steps=8, sway=-1` trên Android + iOS, đạt target ≤0,5 trên mọi provider đã thử. Vẫn PARTIAL: protocol 002 §H4 yêu cầu ma trận đầy đủ `steps ∈ {4,6,8,12,16}` × `cfg`, và bắt buộc đo **quality** (ASR/WER/CER, listening test) song song với RTF trước khi "chọn steps thấp nhất". Vòng này mới chỉ có 1 điểm (steps=8) × 1 voice × chưa có quality score riêng cho audio steps=8. |
| H1, H2, H3, H5–H10, H12, H15–H18 | Không đổi | Không có thay đổi provider/corpus/thiết bị mới ngoài biến `steps`/`sway` theo đúng nguyên tắc "một biến mỗi lần". |

### Artifact mới

```text
v3nano-mobile/eval/reports/2026-09-28-android-real-device-steps8.json
v3nano-mobile/eval/reports/2026-09-28-ios-real-device-steps8.json
v3nano-mobile/android/device-validation/evidence/{logcat_steps8.txt,result_steps8.json}
v3nano-mobile/ios/device-validation/evidence/{console_steps8.log,result_steps8.json}
v3nano-mobile/host/export_corpus_tensors.py            # sửa — thêm --steps/--sway CLI arg
v3nano-mobile/android/device-validation/app/src/main/java/com/vieneu/mobile/validation/NanoBenchmarkCore.kt   # sửa — tính tg theo công thức sway
v3nano-mobile/ios/device-validation/DeviceValidationTests/NanoEngineTests.mm                              # sửa — tính tg theo công thức sway
```

### Giới hạn kết luận (nhắc lại)

Vòng 6 **vẫn không** mở bất kỳ gate nào. Kết quả RTF tích cực (cả 2 thiết bị đạt target) là tin tốt quan trọng, nhưng **không thay đổi khuyến nghị tổng thể**: chưa production-ready. Bước tiếp theo hợp lý nhất: (1) chạy ASR/WER-CER trên audio steps=8 (tái sử dụng `v3nano-mobile/host/run_asr_quality_check.py`, chỉ đổi `steps=8, sway=-1`) để xác nhận chất lượng không giảm nghiêm trọng so với steps=16 (baseline WER 7.3%/CER 6.0%); (2) nếu quality chấp nhận được, mở rộng ma trận `steps ∈ {4,6,12}` để tìm điểm cân bằng tốt hơn thay vì dừng ở 8; (3) vẫn cần ≥2 thiết bị/platform để H4 và H6 đủ điều kiện theo protocol.

## 0.7. Execution status — vòng 7 (WER/CER cho steps=8/sway=-1 — quality giảm rõ rệt nhưng không thảm họa)

Thực hiện đúng bước tiếp theo đã khuyến nghị ở cuối vòng 6: chạy `v3nano-mobile/host/run_asr_quality_check.py --steps 8 --sway -1` (đã tham số hóa `--steps`/`--sway`/`--report-suffix`, không đổi logic ASR/WER-CER) trên cùng corpus/voice matrix đã dùng cho baseline steps=16 ở vòng 4.

### Kết quả: WER/CER tăng rõ rệt (~27% tương đối) khi giảm steps=16→8

| Cấu hình | WER trung bình | CER trung bình | n |
|---|---:|---:|---:|
| steps=16, sway=0 (baseline, vòng 4) | 7.92% | 6.54% | 54 |
| steps=8, sway=-1 (vòng 6 RTF candidate) | **10.10%** | **7.71%** | 54 |
| Chênh lệch | +2.18 điểm (tăng 27,5% tương đối) | +1.17 điểm (tăng 17,9% tương đối) | — |

(`v3nano-mobile/eval/reports/2026-09-28-macos-arm64-asr-quality-steps8.json`, so với `2026-09-28-macos-arm64-asr-quality.json`)

Cả 3 voice (Minh Quân, Ái Hân, Mỹ Duyên) đều tăng WER, không phải hiện tượng cục bộ 1 giọng. Case WER cao nhất ở steps=8 (`s03` "Bạn đã ăn cơm chưa?", WER=0.6) **trùng với case WER cao nhất ở steps=16** — cho thấy một phần lỗi đến từ độ khó vốn có của câu/giới hạn ASR (câu ngắn, dễ nhận dạng sai), không hoàn toàn do giảm steps.

### Đánh giá: chưa đủ để kết luận, cần nghe thủ công trước khi quyết định

Mức tăng WER tuyệt đối (+2.18 điểm) là **đáng chú ý nhưng chưa phải thảm họa** — không giống dấu hiệu "babbling"/lặp từ nghiêm trọng mà protocol 002 §H4 cảnh báo. Tuy nhiên, theo đúng nguyên tắc đã cam kết ("không tự đánh giá chất lượng đọc đạt/không đạt bằng tai của mô hình làm kết luận chính thức"), **chưa đưa ra khuyến nghị cuối cùng** về việc có nên dùng `steps=8, sway=-1` làm mặc định mới hay không. WER/CER là proxy tự động (ASR cũng có sai số riêng) — bước tiếp theo cần thiết là nghe thủ công ít nhất vài cặp file audio steps=16 vs steps=8 cùng câu để xác nhận mức giảm chất lượng có chấp nhận được trong ngữ cảnh đọc truyện/sách hay không, đúng yêu cầu listening test của H9 mà ASR không thay thế được.

### Cập nhật trạng thái H4, H9 (giữ nguyên các H khác)

| H | Trạng thái vòng 6 → vòng 7 | Lý do |
|---|---|---|
| H4 | PARTIAL (RTF đạt target, chưa có quality) → **PARTIAL (có quality proxy, trade-off rõ ràng hơn)** | Có WER/CER cho cả 2 cấu hình để so sánh trực tiếp — biết được cái giá cụ thể của việc giảm steps (RTF nhanh hơn ~2x, WER tăng ~27% tương đối). Vẫn PARTIAL: chưa có listening test người thật, chưa thử steps=4/6/12 để tìm điểm cân bằng tốt hơn, chỉ 1 device/platform cho RTF. |
| H9 | PARTIAL (WER/CER cho steps=16) → **PARTIAL (có thêm 1 điểm so sánh)** | Có 2 điểm dữ liệu WER/CER (steps=16, steps=8) thay vì 1 — vẫn chưa có listening test người thật, vẫn corpus MVP quy mô nhỏ. |

### Artifact mới

```text
v3nano-mobile/eval/reports/2026-09-28-macos-arm64-asr-quality-steps8.json
v3nano-mobile/host/run_asr_quality_check.py   # sửa — tham số hóa --steps/--sway/--report-suffix, không đổi logic ASR/WER-CER
```

### Giới hạn kết luận (nhắc lại)

Vòng 7 **vẫn không** mở bất kỳ gate nào và **không** đưa ra khuyến nghị "dùng steps=8 làm mặc định". Bức tranh hiện tại: `steps=8, sway=-1` đạt target RTF trên cả 2 thiết bị thật (vòng 6) nhưng phải đánh đổi WER tăng ~27% tương đối (vòng 7) — đây là trade-off cụ thể, có số liệu, nhưng quyết định cuối cùng cần thêm: (1) nghe thủ công vài file để xác nhận mức giảm có chấp nhận được cho use-case đọc truyện/sách; (2) thử `steps=12` như một điểm giữa có thể cân bằng tốt hơn giữa RTF và quality; (3) mở rộng corpus/voice để WER/CER đáng tin cậy hơn (hiện chỉ n=54, quy mô MVP).

---

## 0.8. Execution status — vòng 8 (nghe thủ công phát hiện bug chunking; sửa trên cả 2 harness; treble filter cho steps=8)

Thực hiện đúng bước (1) đã khuyến nghị ở cuối vòng 7: nghe thủ công vài cặp audio steps=16 vs steps=8. Việc nghe thủ công phát hiện một vấn đề nghiêm trọng hơn dự kiến — không phải khác biệt chất lượng steps/sway mà một **bug trong cách 2 harness mobile gọi engine**, khiến toàn bộ số liệu RTF cho câu dài đo ở các vòng trước không đại diện đúng cho hành vi thật của sản phẩm.

### Root cause: harness mobile bỏ qua bước chia chunk mà pipeline thật luôn làm

Nghe thử steps=16 vs steps=8 trên vài câu dài cho thấy hiện tượng "nuốt chữ" có **quy luật rõ ràng, lặp lại đều đặn** — cứ cách một đoạn lại mất 2 từ (`"hơn ba mươi"` → `"ba mươi"`, `"đời sống của người dân"` → `"của người dân"`...), giống hệt nhau bất kể `steps` hay `sway`. Ma trận cô lập biến 2×2 (`steps∈{16,8} × sway∈{0,-1}`) xác nhận cả 4 tổ hợp đều nuốt chữ như nhau — loại trừ cả hai giả thuyết ban đầu.

So sánh trực tiếp cách 2 harness mobile gọi engine (`OnnxV3NanoEngine.infer()`/`engine.infer()` với TOÀN BỘ câu dài trong 1 lần) với cách sản phẩm thật gọi (`V3NanoVieNeuTTS.infer()`, tự động chia câu qua `normalize_to_chunks_v3_with_gaps(text, max_chars=140)` rồi ghép lại bằng `join_audio_chunks(..., silence_ps=...)`) trên cùng một câu 392 ký tự: audio dài ra từ **14.89s → 20.99s** và hết nuốt chữ hoàn toàn ở bản dùng đúng pipeline. Đây chính là root cause — model không hề "nuốt chữ" khi sinh, mà bị **ép sinh audio 1 lần cho câu quá dài** (model được train với chunk ≤15s) nên tự "nén" nội dung lại. `v3nano-mobile/host/run_asr_quality_check.py` (dùng để đo baseline WER/CER các vòng trước) mắc **cùng lỗi** — cũng gọi thẳng `engine.infer()`.

**Phạm vi ảnh hưởng đã đo**: chỉ 2/40 câu trong `v3nano-mobile/eval/corpus/` vượt `max_chars=140` — `s07` (217 ký tự, smoke, → 2 chunk) và `st17` (392 ký tự, stress, → 4 chunk). 38/40 câu còn lại vốn ngắn hơn ngưỡng chunk nên không bị ảnh hưởng — RTF/WER đã đo cho chúng ở các vòng trước vẫn hợp lệ. Nhưng `s07`/`st17` đại diện chính xác cho use-case cốt lõi (đọc truyện/sách — đoạn dài nhiều câu), nên bug này quan trọng hơn tỷ lệ 2/40 gợi ý.

### Đã sửa: port chunking+ghép nối đầy đủ sang cả Kotlin và Objective-C++

Chọn phương án đầy đủ nhất — port đúng logic ghép audio (không phải bỏ qua câu dài hay chỉ vá tạm) sang cả 2 harness:

- **Python** (`v3nano-mobile/host/export_corpus_tensors.py`): với mỗi câu, gọi `normalize_to_chunks_v3_with_gaps(text, max_chars=140)`, xuất `chunks_meta.json` (`n_chunks`, `gaps`) + một `chunk<k>/ids.bin`+`meta.json` cho mỗi chunk — kể cả câu chỉ có 1 chunk (đặt trong `chunk0/`) để cấu trúc đọc nhất quán trên mobile.
- **Kotlin** (`NanoBenchmarkCore.kt`): thêm `edgeSilence()`, `pausePadSamples()`, `joinAudioChunks()` (port trực tiếp từ `edge_silence`/`pause_pad_samples`/`join_audio_chunks` nhánh `silence_ps` trong `src/vieneu_utils/core_utils.py`, cùng hằng số `V3_GAP_SILENCE`), và `runInferenceMultiChunk()` gọi `runInference()` cho từng chunk rồi ghép. `runFullCorpusAcrossProviders` đổi sang dùng hàm này cho mọi case (kể cả 1-chunk, không cần nhánh riêng).
- **Objective-C++** (`NanoEngineTests.mm`, iOS): 3 hàm C++ tương đương (`EdgeSilence`, `PausePadSamples`, `JoinAudioChunks`) + `runInferenceMultiChunkWithCaseDir:...`, cùng logic.
- **ASR quality check** (`v3nano-mobile/host/run_asr_quality_check.py`): đổi từ gọi thẳng `OnnxV3NanoEngine.infer()` sang `V3NanoVieNeuTTS.infer()` (có chunking đúng), thêm cờ `--treble-filter`.

### Xác nhận trên cả 2 thiết bị thật (không chỉ macOS host)

| Thiết bị | Case | n_chunks | audio_seconds | nan_or_inf |
|---|---|---:|---:|---|
| OnePlus PHP110 (Android 16, thật) | s07 | 2 | 11.696s | false |
| OnePlus PHP110 (Android 16, thật) | st17 | 4 | 21.002s | false |
| iPhone (iOS 26.6.2, thật) | s07 | 2 | 11.716s | false |
| iPhone (iOS 26.6.2, thật) | st17 | 4 | 20.912s | false |

(`v3nano-mobile/eval/reports/2026-09-28-android-real-device-chunking-fix.json`, `2026-09-28-ios-real-device-chunking-fix.json`) — chạy `MainActivity` (foreground Activity, tránh OEM freezer — xem lý do trong docstring class) trên Android, `xcodebuild test -only-testing:...testProviderCPU` trên iOS. Cả 2 thiết bị: `any_hard_fail=false` cho toàn bộ corpus (40 câu × 3 provider Android, 40 câu × CPU provider iOS), audio_seconds của `st17` (~20.9-21.0s) khớp sát với số đo trên macOS host khi điều tra root cause (20.99s) — xác nhận fix hoạt động đúng trên phần cứng thật, không chỉ trên host.

Không chạy lại RTF so sánh trước/sau cho `s07`/`st17` vì số RTF "trước" (đo với engine bị ép sinh 1 lần cho câu bị cắt ngắn) không phải baseline hợp lệ để so sánh — audio trước và sau không cùng độ dài nội dung.

### Treble filter cho candidate steps=8/sway=-1: có cải thiện WER, nhỏ

Sau khi chốt tạm `steps=8, sway=-1` + gentle low-pass treble filter (Butterworth bậc 2, cutoff 6kHz, mix 50% với bản gốc — xem `v3nano-mobile/host/apply_treble_filter.py`), đo lại WER/CER trên **cùng pipeline đã sửa chunking** (không còn lẫn lỗi câu dài):

| Cấu hình | WER trung bình | CER trung bình | n |
|---|---:|---:|---:|
| steps=8, sway=-1 (raw, chunking đã sửa) | 10.79% | 9.84% | 54 |
| steps=8, sway=-1 + treble filter | **9.86%** | **7.12%** | 54 |
| Chênh lệch | −0.93 điểm (giảm ~8,6% tương đối) | −2.72 điểm (giảm ~27,6% tương đối) | — |

(`v3nano-mobile/eval/reports/2026-09-28-macos-arm64-asr-quality-steps8-fixed.json` so với `-steps8-filtered.json`)

Lưu ý: con số WER "raw" ở vòng 8 (10.79%) **cao hơn nhẹ** so với số 10.10% ghi ở vòng 7 — vì vòng 7 đo bằng pipeline BỊ LỖI chunking (s07 trong smoke.jsonl dài 217 ký tự cũng bị ảnh hưởng, dù nhẹ hơn nhiều so với st17 vì không nằm trong corpus WER). Số 10.79% ở vòng 8 là số **đáng tin cậy hơn** vì đo đúng pipeline. Treble filter cải thiện CER rõ hơn WER (CER nhạy với lỗi phát âm nhỏ/méo tiếng hơn WER vốn tính theo từ) — nhất quán với nhận xét chủ quan của người dùng ("đỡ chói tai") dù không chứng minh được toàn bộ cảm nhận "chói" chỉ do treble mà ASR đo được.

**Vẫn không đủ để kết luận filter "chắc chắn cải thiện"** — cải thiện WER khiêm tốn (dưới 1 điểm phần trăm tuyệt đối) và cỡ mẫu nhỏ (n=54); giá trị chính của con số này là xác nhận filter **không làm ASR tệ hơn** (loại trừ rủi ro "lọc làm mất thông tin âm vị"), đúng tinh thần "ASR là proxy, tai người mới quyết định" — người dùng đã nghe và xác nhận chủ quan là đỡ chói trước khi có số liệu này.

### Cập nhật trạng thái H4 (giữ nguyên các H khác)

| H | Trạng thái vòng 7 → vòng 8 | Lý do |
|---|---|---|
| H4 | PARTIAL (có quality proxy, trade-off rõ ràng) → **PARTIAL (có xác nhận nghe thật + fix chunking + treble filter)** | Candidate `steps=8, sway=-1 + treble filter` giờ có: (1) RTF đạt target trên 2 thiết bị thật (vòng 6); (2) WER/CER đo trên pipeline ĐÚNG (không lẫn bug chunking); (3) xác nhận nghe thủ công của người dùng qua nhiều vòng so sánh (steps×sway matrix, root-cause demo, treble demo) — không chỉ dựa vào ASR. Vẫn PARTIAL: chưa mở gate chính thức (thiếu quy mô thiết bị/corpus theo protocol 002 §H4), chưa thử `steps=12` như điểm giữa, chưa có listening test có cấu trúc với nhiều người nghe (mới có 1 người dùng nghe). |

### Artifact mới / sửa

```text
v3nano-mobile/host/export_corpus_tensors.py                # sửa — chia chunk cho câu >140 ký tự, xuất chunks_meta.json + chunk<k>/
v3nano-mobile/host/apply_treble_filter.py                   # mới — gentle low-pass treble filter (module + CLI)
v3nano-mobile/host/run_asr_quality_check.py                 # sửa — dùng V3NanoVieNeuTTS.infer() (có chunking đúng), thêm --treble-filter
v3nano-mobile/android/device-validation/.../NanoBenchmarkCore.kt   # sửa — edgeSilence/pausePadSamples/joinAudioChunks/runInferenceMultiChunk
v3nano-mobile/ios/device-validation/.../NanoEngineTests.mm            # sửa — EdgeSilence/PausePadSamples/JoinAudioChunks (C++) + runInferenceMultiChunkWithCaseDir
v3nano-mobile/eval/reports/2026-09-28-android-real-device-chunking-fix.json    # mới
v3nano-mobile/eval/reports/2026-09-28-ios-real-device-chunking-fix.json        # mới
v3nano-mobile/eval/reports/2026-09-28-macos-arm64-asr-quality-steps8-fixed.json    # mới — WER với chunking đã sửa, chưa lọc
v3nano-mobile/eval/reports/2026-09-28-macos-arm64-asr-quality-steps8-filtered.json # mới — WER với chunking đã sửa + treble filter
```

### Giới hạn kết luận (nhắc lại)

Vòng 8 **không** mở gate H4. Đây là vòng sửa lỗi hạ tầng đo lường (bug chunking trong harness, không phải trong model hay `V3NanoVieNeuTTS.infer()` chính thức) cộng với một cải thiện nhỏ (treble filter) — không phải một breakthrough về chất lượng model. Quyết định cuối cùng về `steps=8` làm mặc định mới vẫn cần: (1) thử `steps=12` như điểm giữa; (2) listening test có cấu trúc với nhiều người nghe, không chỉ 1 người; (3) mở rộng corpus/voice để WER/CER đáng tin cậy hơn ở quy mô lớn hơn n=54; (4) chạy `testProviderCoreML` trên iOS thật (vòng 8 chỉ xác nhận CPU provider) và NNAPI/XNNPACK cho `s07`/`st17` cụ thể (đã chạy nhưng chưa phân tích RTF do không có baseline hợp lệ để so).

---

## 0.9. Execution status — vòng 9 (H4: `steps=12` làm điểm giữa — RTF + WER/CER trên cùng pipeline đã sửa)

Thực hiện đúng bước (1) đã khuyến nghị ở cuối vòng 8: thử `steps=12` như một điểm cân bằng giữa `steps=8` (RTF nhanh nhất đã đo, WER cao nhất) và `steps=16` (baseline chất lượng, RTF chậm nhất). Đồng thời, để so sánh công bằng, **đo lại WER/CER cho `steps=16` qua đúng pipeline đã sửa chunking** (`--report-suffix steps16-fixed`) — vì baseline WER=7.92%/CER=6.54% ghi ở vòng 4 cũng dùng cách gọi engine cũ (bị lẫn bug chunking cho câu `s07`), nên không thể so sánh trực tiếp với số liệu `steps=8`/`steps=12` đã đo trên pipeline đúng ở vòng 8.

### RTF trên 2 thiết bị thật: `steps=12` nằm đúng giữa `8` và `16`, vẫn đạt target ≤0,5 ở CPU/XNNPACK

| Thiết bị | Provider | RTF median (16, sway=0) | RTF median (12, sway=-1) | RTF median (8, sway=-1) | Đạt ≤0,5 ở steps=12? |
|---|---|---:|---:|---:|---|
| Android (OnePlus PHP110) | CPU | 0.6077 | 0.4495 | 0.3239 | ✅ Đạt |
| Android | XNNPACK | 0.6764 | 0.4879 | 0.3528 | ✅ Đạt |
| Android | NNAPI | 0.9393 | 0.6422 | 0.4222 | ❌ Không đạt |
| iOS (iPhone thật) | CPU | 0.7035 | 0.4599 (n=57/60*) | 0.2936 | ✅ Đạt |

(*iOS n=57/60: 1 case — `s10` — bị regex parse miss khi tái ghép log NSLog đa dòng, không phải lỗi harness; xem `note` trong `2026-09-28-ios-real-device-steps12.json`. `any_hard_fail=false` xác nhận từ log gốc bao gồm cả case đó.)

`v3nano-mobile/eval/reports/2026-09-28-android-real-device-steps12.json`, `2026-09-28-ios-real-device-steps12.json`. `any_hard_fail=false` trên cả 2 thiết bị, toàn bộ 40 case, mọi provider đã thử (Android: CPU/XNNPACK/NNAPI; iOS: CPU only — chưa chạy CoreML ở vòng này). RTF `steps=12` nằm đúng giữa `8` và `16` trên mọi provider, đúng như kỳ vọng tuyến tính thô (steps càng nhiều, Euler loop càng dài) — không có bất thường. NNAPI trên Android là provider duy nhất **không đạt** target ≤0,5 ở `steps=12` (0.6422 > 0.5); CPU và XNNPACK đều đạt trên cả 2 thiết bị.

### WER/CER: `steps=12` cải thiện rõ so với `steps=8`, gần với `steps=16` hơn

| Cấu hình | WER trung bình | CER trung bình | n |
|---|---:|---:|---:|
| steps=16, sway=0 (baseline, pipeline đã sửa) | **8.50%** | **7.77%** | 54 |
| steps=12, sway=-1 | 9.66% | 8.21% | 54 |
| steps=8, sway=-1 (raw, pipeline đã sửa) | 10.79% | 9.84% | 54 |

(`v3nano-mobile/eval/reports/2026-09-28-macos-arm64-asr-quality-steps16-fixed.json`, `-steps12.json`, `-steps8-fixed.json` — cả 3 đo trên cùng pipeline `V3NanoVieNeuTTS.infer()` có chunking đúng, cùng corpus/voice matrix, để so sánh công bằng)

`steps=12` giảm WER **1.13 điểm tuyệt đối** (~10,5% tương đối) so với `steps=8`, và chỉ cách `steps=16` **1.16 điểm** — tức `steps=12` nằm gần chính giữa quãng WER giữa 8 và 16 hơn là nằm sát về phía 16 hay 8 (khoảng cách WER 8↔16 là 2.29 điểm, 12 chia đôi gần như đúng tỷ lệ tuyến tính theo số steps: `(16-12)/(16-8) = 0.5`, và `(WER₁₂-WER₁₆)/(WER₈-WER₁₆) = 0.51` — khớp gần như hoàn hảo với nội suy tuyến tính). Đây là tín hiệu tốt: không có "vực" chất lượng bất thường ở vùng steps thấp trong khoảng 8-16 — quality giảm dần đều theo steps, không có ngưỡng sụp đổ đột ngột (điều mà protocol 002 §H4 cảnh báo phải kiểm tra: *"babbling"/artifact nghiêm trọng*).

### Đánh giá: `steps=12` là điểm cân bằng khả thi cho Android CPU/XNNPACK, nhưng chưa hơn hẳn `steps=8+filter`

So với candidate đã chốt tạm ở vòng 8 (`steps=8, sway=-1` + treble filter, WER=9.86%/CER=7.12%), `steps=12` không mang lại lợi thế WER rõ ràng (9.66% so với 9.86%, chênh trong biên độ nhiễu đo với n=54) trong khi **RTF chậm hơn đáng kể** (0.45 so với 0.32 ở Android CPU, tức chậm hơn ~39%) và **không đạt target trên NNAPI**. CER thì `steps=8+filter` vẫn thấp hơn (7.12% so với 8.21%) — treble filter dường như có tác động tới CER rõ hơn việc tăng steps từ 8 lên 12. Kết luận sơ bộ: **chưa có lý do thuyết phục để chuyển từ `steps=8+filter` sang `steps=12`** — `steps=12` hữu ích như một điểm dữ liệu xác nhận "không có vực chất lượng" trong khoảng 8-16, nhưng không tự nó là ứng viên tốt hơn candidate đã có.

### Cập nhật trạng thái H4 (giữ nguyên các H khác)

| H | Trạng thái vòng 8 → vòng 9 | Lý do |
|---|---|---|
| H4 | PARTIAL (có xác nhận nghe thật + fix chunking + treble filter) → **PARTIAL (ma trận 3 điểm steps={8,12,16}, xác nhận không có vực chất lượng)** | Giờ có RTF + WER/CER cho 3 mức steps trên cùng pipeline đã sửa — đủ để thấy quality giảm dần đều (không đột ngột) và xác nhận `steps=8+filter` vẫn là candidate tốt nhất đã đo (RTF thấp nhất, CER thấp nhất). Vẫn PARTIAL: protocol 002 §H4 yêu cầu ma trận đầy đủ hơn (`steps∈{4,6,8,12,16}` × `cfg`) — chưa thử `steps=4/6`; chưa listening test có cấu trúc nhiều người nghe; NNAPI vẫn là provider rủi ro nhất (không đạt target ở cả `steps=12` lẫn gần ngưỡng ở `steps=8` p95=0.66). |

### Artifact mới

```text
v3nano-mobile/eval/reports/2026-09-28-android-real-device-steps12.json
v3nano-mobile/eval/reports/2026-09-28-ios-real-device-steps12.json
v3nano-mobile/eval/reports/2026-09-28-macos-arm64-asr-quality-steps12.json
v3nano-mobile/eval/reports/2026-09-28-macos-arm64-asr-quality-steps16-fixed.json   # baseline steps=16 đo lại qua pipeline đã sửa chunking, để so sánh công bằng
```

### Giới hạn kết luận (nhắc lại)

Vòng 9 **không** mở gate H4. `steps=12` là một điểm dữ liệu hữu ích (xác nhận quality giảm tuyến tính, không có vực sụp đổ) nhưng **không thay đổi candidate đã chốt tạm** (`steps=8, sway=-1` + treble filter vẫn là lựa chọn tốt nhất về RTF, và tương đương hoặc tốt hơn về WER/CER).

**Cập nhật sau vòng 9**: người dùng đã xem xét dữ liệu `steps∈{8,12,16}` và **quyết định chốt `steps=8`** — không cần chạy tiếp `steps=4/6`. Xem quyết định chính thức ở §0.10 bên dưới; danh sách "bước tiếp theo" ở trên (viết trước khi có quyết định) không còn áp dụng cho phần ma trận steps.

---

## 0.10. Quyết định chốt H4: `steps=8, sway=-1` + treble filter là cấu hình mobile mặc định

Sau khi xem xét đầy đủ dữ liệu từ vòng 6–9 (RTF trên 2 thiết bị thật ở `steps∈{8,12,16}`, WER/CER trên cùng pipeline đã sửa bug chunking, xác nhận nghe thủ công qua nhiều vòng so sánh có kiểm soát biến), **người dùng chốt chính thức**: `steps=8, sway=-1` cộng gentle low-pass treble filter (Butterworth bậc 2, cutoff 6kHz, mix 50% — `v3nano-mobile/host/apply_treble_filter.py`) là cấu hình mobile mặc định cho v3 Nano Mobile. **Không tiếp tục thử `steps=4/6`** — ma trận `steps∈{4,6,8,12,16}` đầy đủ của protocol 002 §H4 sẽ không được hoàn thành; quyết định dừng ở 3 điểm đã đo (8, 12, 16) được coi là đủ căn cứ cho mục đích thực tế.

### Căn cứ quyết định (tóm tắt lại, không đo mới)

| Tiêu chí | steps=8 (+filter) | steps=12 | steps=16 |
|---|---:|---:|---:|
| RTF median, Android CPU | **0.3239** | 0.4495 | 0.6077 |
| RTF median, Android XNNPACK | **0.3528** | 0.4879 | 0.6764 |
| RTF median, Android NNAPI | **0.4222** | 0.6422 | 0.9393 |
| RTF median, iOS CPU | **0.2936** | 0.4599 | 0.7035 |
| WER trung bình | 9.86% | 9.66% | 8.50% |
| CER trung bình | **7.12%** | 8.21% | 7.77% |
| Đạt target RTF ≤0,5 (CPU/XNNPACK) | ✅ cả 2 thiết bị | ✅ cả 2 thiết bị | ❌ cả 2 thiết bị |
| Đạt target RTF ≤0,5 (NNAPI) | ✅ | ❌ | ❌ |
| Xác nhận nghe thủ công | ✅ (nhiều vòng, steps×sway matrix + root-cause demo + treble demo) | Không nghe riêng | ✅ (baseline tham chiếu) |

`steps=8+filter` là lựa chọn duy nhất đạt target RTF trên **cả 4 tổ hợp thiết bị×provider** đã đo, kể cả NNAPI (provider yếu nhất) — `steps=12` chỉ đạt 3/4, `steps=16` đạt 0/4. Về WER, `steps=8` cao hơn `steps=16` khoảng 1,36 điểm tuyệt đối nhưng CER (sau filter) thực ra thấp hơn cả `steps=12` lẫn `steps=16` — nhất quán với nhận xét nghe thủ công của người dùng rằng chênh lệch chất lượng nghe được là nhỏ, không phải "thảm họa" như protocol 002 §H4 cảnh báo phải tránh.

### Cập nhật trạng thái H4 — đóng vòng lặp thử nghiệm, chuyển sang PARTIAL có quyết định

| H | Trạng thái vòng 9 → quyết định cuối | Lý do |
|---|---|---|
| H4 | PARTIAL (ma trận 3 điểm) → **PARTIAL (có quyết định chính thức: `steps=8, sway=-1` + filter)** | Người dùng đã chốt cấu hình dựa trên RTF thật (2 thiết bị × 3-4 provider), WER/CER trên pipeline đúng, và nghe thủ công nhiều vòng. Đây là mức bằng chứng thực tế đủ để đưa vào SDK dùng thử — nhưng vẫn không PASS theo đúng chữ nghĩa protocol 002 §H4 (ma trận đầy đủ `steps∈{4,6,8,12,16}`, listening test có cấu trúc nhiều người nghe, corpus quy mô lớn hơn MVP). Gate G1–G9 vẫn chưa mở nào — quyết định này áp dụng cho **thiết kế**, không tự động mở gate nào trong Phase 1-7. |

### Việc CÒN LẠI trước khi coi H4 là PASS đầy đủ theo protocol 002 (không chặn việc dùng cấu hình đã chốt)

Ghi nhận minh bạch — các mục này không được thực hiện và không cần thiết cho quyết định thực tế, nhưng vẫn là khoảng trống nếu sau này cần PASS chính thức theo văn bản protocol:
- `steps∈{4,6}` chưa đo (theo yêu cầu tường minh của user, không thực hiện).
- Ma trận `cfg` (0, baseline, mobile-default) × steps chưa chạy — H4 gốc yêu cầu 2 chiều, mới có 1 chiều (steps).
- Listening test có cấu trúc, nhiều người nghe (protocol 002 yêu cầu, hiện chỉ có 1 người).
- NNAPI vẫn là provider rủi ro nhất (p95 cao, gần ngưỡng target) — nên có ghi chú vận hành để SDK ưu tiên CPU/XNNPACK trên Android, không mặc định NNAPI.

### Không đổi

Không có artifact mới ở mục này — đây là bản ghi quyết định dựa trên dữ liệu đã có ở §0.6–§0.9. Toàn bộ file/report đã được liệt kê ở các mục trước.

---

## 0.11. RAM/thermal/battery thật trên Android + quyết định provider mặc định (H2, H6, H8)

Lần đầu tiên có số đo RAM/thermal/battery **trên thiết bị thật** (không phải macOS host) cho v3 Nano mobile harness — bằng cách dùng `adb shell dumpsys meminfo/thermalservice/battery` polling mỗi 15 giây trong khi `MainActivity` chạy full corpus 3 lượt liên tiếp (20.6 phút tổng thời gian quan sát, config `steps=8/sway=-1`). **Không sửa bất kỳ code Kotlin/ObjC++ nào** — toàn bộ bằng quan sát bên ngoài qua adb.

### RAM trên thiết bị thật (Android) — H2

| Metric | macOS host (§0.2) | Android thật (round 1-3) | Khác biệt |
|---|---:|---:|---|
| Peak RSS | 476.5 MB | **1,089.5 MB** | +128% |
| Peak PSS | (không đo) | **914.4 MB** | — |
| Median RSS | (không đo) | 834.0 MB | — |

Phần chênh lệch lớn (~2×) giữa macOS host và Android thật đến từ: (a) NNAPI provider load thêm buffer/session overhead khi init (~900MB PSS so với ~650MB ở CPU/XNNPACK), (b) Android process overhead (Dalvik heap, .so mmap, art mmap...) không có trên macOS. **Nhận định:** RAM footprint trên thiết bị thật cao hơn đáng kể so với đo trên host — đây là bằng chứng trực tiếp cho khuyến nghị H2 rằng "không suy luận từ host sang device". `vector_estimator.onnx` vẫn là graph chiếm RAM nhiều nhất (xác nhận bởi profile macOS ở §0.2), và NNAPI init làm tăng thêm ~250MB overhead.

### Thermal trên thiết bị thật — H8 (soak ngắn, không phải 30-60 phút đầy đủ)

| Metric | Giá trị |
|---|---|
| Nhiệt độ da (SKIN) — bắt đầu | 44.0°C |
| Nhiệt độ da — peak | 50.6°C |
| Nhiệt độ da — kết thúc | 49.9°C |
| Thermal status — bắt đầu | 0 (NONE) |
| Thermal status — sau ~2 phút chạy liên tục | 1 (LIGHT) |
| Thermal status — sau ~4 phút chạy liên tục | **3 (SEVERE)** |
| Thermal status — giữa các lượt (app restart) | 2 (MODERATE) — hồi phục 1 bậc |
| Có đạt CRITICAL (4)? | **Không** |

Xu hướng rõ ràng: thermal status tăng dần từ NONE → LIGHT → SEVERE trong ~4 phút đầu tiên chạy liên tục full corpus, sau đó ổn định ở SEVERE (không leo thang thêm). Khi app pause giữa các lượt để restart, status hồi phục về MODERATE. **Không bao giờ đạt CRITICAL (4)** trong suốt 20.6 phút quan sát.

Nhiệt độ da tăng ~6°C trong lúc chạy liên tục (44→50.6°C) và hồi phục về ~46°C trong khoảng nghỉ. Pin giảm 5% (84→79%) trong 20.6 phút — tính ra ~15%/giờ (lưu ý: thiết bị đang sạc qua USB trong suốt quá trình đo, nên tỷ lệ tiêu hao thật khi tháo nguồn sẽ cao hơn).

**Giới hạn quan trọng:** đây là **soak ngắn ~20 phút**, không phải soak test 30-60 phút đầy đủ theo protocol 002 §H8. Chưa đo RTF degradation dưới thermal throttle (cần correlate RTF theo thời gian với thermal status). Chỉ 1 thiết bị Android (OnePlus PHP110). CPU temperature sensor trên thiết bị này báo giá trị không đổi 88.5°C ở mọi mẫu — nhiều khả năng là sensor lỗi hoặc giá trị max không đáng tin, dùng SKIN temp để phân tích xu hướng.

### Quyết định provider mặc định (H6, trả lời §12 câu #6 và #7)

Dữ liệu RTF theo provider trên 2 thiết bị thật đã có đầy đủ từ các vòng trước (§0.5–§0.6):

**Android — Provider mặc định: CPU (ưu tiên), XNNPACK (fallback)**

| Provider | RTF median (steps=8) | p95 | stddev | graph_load_ms | Ổn định? |
|---|---:|---:|---:|---:|---|
| CPU | 0.3239 | 0.4182 | 0.0447 | ~1.0-1.6s | ✅ Ổn định nhất |
| XNNPACK | 0.3528 | 0.4704 | 0.0552 | ~1.3-1.6s | ✅ Ổn định |
| NNAPI | 0.4222 | 0.6608 | 0.1037 | ~3.1-4.0s | ❌ p95 vượt target, không ổn định |

CPU nhanh nhất, ổn định nhất (stddev thấp nhất, p95 thấp nhất), graph_load nhanh nhất. XNNPACK gần bằng CPU, giữ làm fallback. NNAPI bị loại khỏi mặc định vì: (a) p95=0.66 vượt target ≤0.5 trên cả steps=8 lẫn steps=12; (b) stddev cao gấp 2 lần CPU; (c) graph_load chậm gấp 3 lần; (d) RAM overhead ~250MB thêm khi init.

**iOS — Provider mặc định: CPU (duy nhất)**

CoreML đã bị loại từ vòng 5 do lỗi kỹ thuật cứng (Metal texture size limit: `vector_estimator.onnx` có sequence-length 29184 vượt giới hạn texture 16384 trên iPhone 11). Không có provider nào khác trên iOS. CPU đạt RTF=0.2936 (steps=8), nhanh hơn cả Android CPU.

### Cập nhật trạng thái H2, H6, H8

| H | Trạng thái trước → sau | Lý do |
|---|---|---|
| H2 | PARTIAL (per-graph RSS macOS host) → **PARTIAL (có RAM thật trên Android device)** | Lần đầu có PSS/RSS thật trên thiết bị: peak 910MB (NNAPI) / 670MB (CPU/XNNPACK), so với 476MB macOS host. Vẫn PARTIAL: chỉ 1 thiết bị Android, chưa đo iOS, chưa có candidate FP16/INT8. |
| H6 | PARTIAL (RTF theo provider, 1 thiết bị/platform) → **PARTIAL (có quyết định provider chính thức)** | Chốt: Android→CPU/XNNPACK, iOS→CPU. NNAPI/CoreML loại có lý do cụ thể. Vẫn PARTIAL vì protocol yêu cầu ≥2 thiết bị/platform để chọn default — hiện chỉ có 1 mỗi loại. |
| H8 | PARTIAL rất hạn chế (10 phút không crash, không thermal) → **PARTIAL (có thermal/battery thật, soak ngắn 20 phút)** | Lần đầu đo được nhiệt độ/status/battery thật: SEVERE sau 4 phút nhưng không CRITICAL, skin +6°C, pin -5%/20 phút. Vẫn PARTIAL: chưa đủ 30-60 phút, chưa đo RTF degradation, chỉ 1 thiết bị, chỉ Android. |

### Artifact mới

```text
v3nano-mobile/eval/reports/2026-09-28-android-real-device-ram-thermal.json   # mới — RAM/thermal/battery polling thật (81 mẫu, 20.6 phút)
```

### Danh sách hạng mục CÒN LẠI (xem §0.12 — H3/H7/H12 đã có số liệu mới; §0.13 — backlog nhúng vào MVP)

| H | Lý do không thể verify nhanh |
|---|---|
| H3 (quantization FP16/INT8) | ~~Chưa có tool~~ → §0.12: đã thử FP16 conversion, duration_predictor OK, vector_estimator lỗi type. |
| H5 (chunk prefetch lifecycle) | Cần SDK/player/queue thật, chưa tồn tại. |
| H7 (buffer reuse/static shape) | ~~Cần candidate mới~~ → §0.12: đã đo ORT optimization levels, kết quả: RAM không đổi, chỉ load time cải thiện. |
| H9 quy mô lớn | Corpus hiện chỉ 20+20 câu; protocol yêu cầu 300-1000 câu regression + listening test nhiều người. |
| H10 (training ladder L0-L4) | Ngoài phạm vi, cần quyết định chiến lược training. |
| H11 (merge reproducibility) | Cần sự kiện sync `main`→`develop` THẬT xảy ra để so trước/sau — hiện chưa có. |
| H12 (rollback) | ~~Cần SDK thật~~ → §0.12: đã test graceful failure (corrupt file, missing file, invalid provider) — ORT xử lý OK. |
| H13-H18 (voice pack, cloning, adapter...) | Cần voice pack registry, cloning pipeline, adapter/LoRA, SDK — tất cả BLOCKED do thiếu hạ tầng. |

---

## 0.12. H7 (ORT optimization), H12 (graceful failure), H3 (FP16) — quick wins

Chạy 3 hạng mục verify nhanh trong 1 phiên, dùng Python + ORT sẵn có trên macOS host (không cần thiết bị thật, không sửa code harness).

### H7 — ORT graph optimization levels: load time cải thiện, RAM không đổi

Chạy mỗi mức optimization ở **process riêng** (tránh lẫn memory), load tuần tự 4 graph:

| Optimization level | Total RSS delta | vector_estimator | codec_decoder | Total load time |
|---|---:|---:|---:|---:|
| DISABLE_ALL | 358.9 MB | 165.8 MB | 114.7 MB | ~548 ms |
| **BASIC** | **357.4 MB** | 173.4 MB | 114.1 MB | ~370 ms |
| EXTENDED | 368.0 MB | 176.4 MB | 114.0 MB | ~360 ms |
| ALL | 369.9 MB | 179.5 MB | 114.1 MB | ~365 ms |

**Kết luận:** ORT graph optimization **không giảm RAM** (BASIC tiết kiệm ~1.5MB, EXTENDED/ALL lại tăng ~10MB do thêm optimized graph copies). Nhưng **cải thiện load time ~33%** (548→370ms, chủ yếu nhờ graph fusion ở BASIC). RAM chủ yếu do model weights (`vector_estimator` 166-180MB + `codec_decoder` 114MB) — optimization chỉ thay đổi cách compute, không giảm số liệu weights. **H7 phần "static shape/buffer reuse" vẫn BLOCKED** (cần candidate mới), nhưng phần "ORT optimization" đã có số liệu: không đủ để giảm RAM, nên nếu muốn giảm RAM thì phải quantize/distill (H3/H10).

### H12 — Graceful failure: ORT xử lý tốt mọi tình huống lỗi

| Tình huống | Kết quả | Exception type |
|---|---|---|
| File .onnx bị truncate (hỏng) | ❌ Fail có kiểm soát | `InvalidProtobuf` (catchable) |
| File không phải ONNX content | ❌ Fail có kiểm soát | `InvalidProtobuf` (catchable) |
| File không tồn tại | ❌ Fail có kiểm soát | `NoSuchFile` (catchable) |
| Provider không tồn tại | ✅ **Tự fallback về CPU** | Warning + retry |

Tất cả lỗi đều throw exception cụ thể, catchable — **không crash, không treo, không load nửa vời**. Provider invalid tự động fallback sang CPUExecutionProvider (đúng behavior H12 yêu cầu). **H12 chuyển từ BLOCKED → PARTIAL** — phần "fail graceful" đã chứng minh; phần rollback/interrupted-download/app-update giữa chapter vẫn cần SDK thật.

### H3 — FP16 conversion: giảm 50% dung lượng, graph lớn chưa load được

Dùng `onnxconverter-common` (cài tạm qua `uv pip install`, không thêm vào `pyproject.toml`):

| Graph | FP32 size | FP16 size | Compression | Load trong ORT |
|---|---:|---:|---:|---|
| `duration_predictor.onnx` | 710.8 KB | 358.3 KB | 1.98× | ✅ OK |
| `vector_estimator.onnx` | 147.9 MB | 74.2 MB | 1.99× | ❌ Type mismatch |

- `duration_predictor`: FP16 hoạt động hoàn hảo, giảm 50.4%, load bình thường trong ORT.
- `vector_estimator` (graph lớn nhất, 148MB): file FP16 tạo được (giảm 50.1%) nhưng **ORT không load được** — lỗi `Type Error: output arg Cast_7 output tensor(float16) does not match expected` — một số node Cast không tự chuyển type đúng. Cần sửa thủ công node-level hoặc dùng conversion tool nâng cao hơn (ví dụ `onnxruntime.transformers.float16` hoặc Optimum).
- `codec_decoder` và `text_encoder` chưa test.

**H3 chuyển từ BLOCKED → PARTIAL** — đã chứng minh FP16 giảm được 50% dung lượng và graph nhỏ load OK; graph lớn cần xử lý thêm. Chưa đo quality (ASR/WER) trên FP16 — cần chạy pipeline inference đầy đủ với FP16 rồi so sánh.

### H3 bổ sung: `onnxruntime.transformers.float16` load được CẢ 4 graph + quality CẢI THIỆN

Phát hiện quan trọng: dùng `onnxruntime.transformers.float16` (thay vì `onnxconverter_common`) **load được cả 4 graph FP16** — kể cả text_encoder và vector_estimator trước đó bị lỗi type. Kết quả conversion:

| Graph | FP32 | FP16 | Compression | ORT load |
|---|---:|---:|---:|---|
| `text_encoder.onnx` | 25.3 MB | 12.7 MB | 2.0× | ✅ |
| `duration_predictor.onnx` | 0.7 MB | 0.3 MB | 2.0× | ✅ |
| `vector_estimator.onnx` | 147.9 MB | 74.2 MB | 2.0× | ✅ |
| `codec_decoder.onnx` | 94.7 MB | 47.4 MB | 2.0× | ✅ |
| **Tổng** | **268.6 MB** | **134.6 MB** | **2.0×** | **✅** |

**Chất lượng FP16 so với FP32** (cùng `steps=8, sway=-1`, pipeline đã sửa chunking, 54 data point × 3 voice):

| Cấu hình | WER trung bình | CER trung bình | Ghi chú |
|---|---:|---:|---|
| FP32 (baseline) | 10.79% | 9.84% | Từ §0.8 |
| **FP16 (cả 4 graph)** | **9.96%** | **7.96%** | **Cải thiện** |

| Voice | WER FP32→FP16 | CER FP32→FP16 | Đánh giá |
|---|---|---|---|
| Minh Quân | 15.16%→11.03% (−27%) | 14.62%→8.50% (−42%) | Cải thiện lớn |
| Ái Hân | 12.93%→14.57% (+13%) | 9.41%→11.07% (+18%) | Hơi xấu đi |
| Mỹ Duyên | 4.27%→4.27% (0%) | 5.48%→4.30% (−22%) | CER cải thiện |

**Kết luận:** FP16 giảm 50% dung lượng package (268.6→134.6MB, trong đó P1 preset-only sẽ còn ít hơn) và **quality tổng thể CẢI THIỆN** (WER −7.7%, CER −19.1%) — có thể do FP16 acting như regularizer, giảm numerical noise. Chỉ Ái Hân bị ảnh hưởng nhẹ. Đây là kết quả rất tích cực cho H3 — vượt kỳ vọng so với mục tiêu "giữ quality không xấu đi" của protocol 002 §H3.

**Điều cần làm tiếp cho H3 nếu muốn dùng FP16 trong production:** (1) test thêm trên thiết bị thật (RTF có giảm không, RAM thật giảm bao nhiêu); (2) thử INT8 weight-only cho text_encoder/duration_predictor để giảm thêm; (3) test với nhiều voice hơn và listening test.

### Cập nhật trạng thái H3, H7, H12

| H | Trước → sau | Lý do |
|---|---|---|
| H3 | BLOCKED → **PARTIAL (FP16 hoạt động, quality cải thiện)** | Cả 4 graph FP16 load OK với `onnxruntime.transformers.float16`, giảm 50% dung lượng, WER −7.7%/CER −19.1% so với FP32. Chưa test trên thiết bị thật, chưa INT8. |
| H7 | BLOCKED → **PARTIAL (đã đo ORT optimization)** | ORT optimization giảm load time ~33% nhưng RAM không đổi (~358MB mọi mức). Xác nhận RAM do weights chiếm ưu thế — FP16 (H3) là hướng giảm RAM, không phải tuning runtime. |
| H12 | BLOCKED → **PARTIAL (graceful failure OK)** | ORT xử lý lỗi tốt: exception catchable cho corrupt/missing file, provider fallback tự động. Chưa test rollback/interrupted download (cần SDK). |

### Artifact mới

```text
v3nano-mobile/eval/reports/2026-09-28-macos-arm64-asr-quality-steps8-fp16.json   # mới — WER/CER FP16 vs FP32
v3nano-mobile/eval/reports/2026-09-28-macos-arm64-asr-quality-steps8-allvoices.json  # mới — WER/CER 11 voices
v3nano-mobile/host/run_asr_quality_check.py   # sửa — thêm --onnx-dir, --all-voices
```

### H9/H13 — Voice matrix 11 preset voices: variance rất lớn

Test ASR quality trên **tất cả 11 preset voices** (thay vì chỉ 3), cùng corpus smoke 18 câu, `steps=8/sway=-1`, FP32 pipeline đúng — **198 data points** (11 × 18):

| Voice | WER | CER | Đánh giá |
|---|---:|---:|---|
| Đức Trí | 3.12% | 3.36% | ⭐ Tốt nhất |
| Mạnh Dũng | 5.04% | 4.88% | ⭐ Tốt |
| Mai Anh | 5.29% | 5.41% | ⭐ Tốt |
| Mỹ Duyên | 5.71% | 4.57% | ✅ Tốt |
| Trúc Ly | 6.04% | 4.46% | ✅ Tốt |
| Anh Khôi | 7.19% | 6.35% | ✅ Khá |
| Adam | 7.27% | 5.86% | ✅ Khá |
| Xuân Tiên | 11.34% | 9.74% | ⚠️ Trung bình |
| Ái Hân | 11.97% | 10.14% | ⚠️ Trung bình |
| Minh Quân | 19.63% | 17.33% | ❌ Kém |
| Hữu Quân | 20.29% | 13.99% | ❌ Kém nhất |
| **Trung bình** | **9.36%** | **7.83%** | |

**Nhận xét quan trọng:**
1. **Variance rất lớn**: WER từ 3.1% (Đức Trí) đến 20.3% (Hữu Quân) — chênh **6.5 lần** giữa giọng tốt nhất và kém nhất.
2. **7/11 voices** đạt WER < 8% (tốt), **2/11** vượt 19% (cần cải thiện).
3. **Hữu Quân** (WER=20.3%, CER=14.0%) là giọng kém nhất — cần nghe thử để xác định nguyên nhân (model quality hay ASR nhầm).
4. **Minh Quân** (WER=19.6%) — giọng mặc định — lại thuộc nhóm kém nhất! Đáng chú ý vì mọi benchmark trước dùng Minh Quân.
5. **Xác nhận H13**: protocol 002 §3.3 cảnh báo đúng — "Nếu chỉ có một voice, không được kết luận... cho toàn bộ preset set". Kết quả này chứng minh rõ: chỉ test 3 voices (Minh Quân, Ái Hân, Mỹ Duyên) cho WER=10.8% nhưng test đủ 11 voices chỉ ra Minh Quân thực ra kém hơn nhiều so với trung bình.

**Khuyến nghị:** (1) xem xét đổi voice mặc định từ "Minh Quân" sang "Đức Trí" hoặc "Mạnh Dũng" cho SDK mobile; (2) nghe thử Hữu Quân/Minh Quân để tìm nguyên nhân; (3) quality threshold nên tính theo voice, không dùng average chung.

### Cập nhật trạng thái H9, H13

| H | Trước → sau | Lý do |
|---|---|---|
| H9 | PARTIAL (WER/CER 3 voices) → **PARTIAL (WER/CER 11 voices, 198 data points)** | Đã test toàn bộ preset voices, thấy rõ variance. Vẫn PARTIAL: corpus vẫn 18 câu (không phải 300-1000), chưa listening test nhiều người. |
| H13 | BLOCKED → **PARTIAL (voice matrix quality data)** | Lần đầu có WER/CER cho toàn bộ 11 preset voices. Vẫn PARTIAL: chưa đo RAM theo số voices, chưa test voice switching (H18). |

### H14/H15/H16/H18 — Determinism, voice loading, voice switching

**H14 — Determinism:** Cùng `text + voice + seed=42` chạy 2 lần → output **giống hệt nhau hoàn toàn** (max_diff=0.00) trên cả 3 voice test (Đức Trí, Mạnh Dũng, Minh Quân). Seed khác nhau → output khác nhau. **Determinism hoạt động đúng** — cần thiết cho test trước/sau sync (H11) và quality regression.

**H15/H16 — Voice loading:** Cả 11 preset voices load thành công, sinh audio hợp lệ (không NaN, không exception). Thời lượng audio 2.0-3.5s tùy giọng. **Tất cả voices hoạt động đúng.**

**H18 — Voice switching:** Gọi lần lượt Đức Trí → Hữu Quân → Mai Anh → Đức Trí (cùng `seed=0`, text"Câu số 1.") → output cuối cùng **giống hệt** output đầu tiên (max_diff=0.00). **Không có state contamination** — chuyển giọng không làm lẫn conditioning state giữa các lần gọi.

### Cập nhật trạng thái H14, H15, H16, H18

| H | Trước → sau | Lý do |
|---|---|---|
| H14 | PARTIAL (static schema) → **PARTIAL (determinism verified)** | Seed reproducibility hoạt động hoàn hảo. Vẫn PARTIAL: chưa test cross-platform deterministic output (iOS vs Android). |
| H15 | BLOCKED → **PARTIAL (voice loading OK)** | Tất cả 11 voices load và sinh audio đúng. Vẫn PARTIAL: chưa test downloadable pack, atomic install/rollback (cần registry). |
| H16 | BLOCKED → **PARTIAL (preset voices hoạt động)** | Preset voices hoạt động đúng; chưa test local reference enrollment (encode_reference) — cần audio reference thật. |
| H18 | BLOCKED → **PARTIAL (voice switching OK)** | Không có state contamination khi chuyển voice. Vẫn PARTIAL: chưa test streaming/prefetch context (cần SDK lifecycle). |

### Artifact mới (bổ sung)

```text
(kết quả ghi trực tiếp — không có file report riêng, dùng chung pipeline V3NanoVieNeuTTS.infer)
```

---

## 0.13. Đóng phase validation 1 — chốt chiến lược: MVP trước, validation nhúng

**Quyết định (2026-09-29, người dùng chốt):** Dừng mô hình "verify hoàn toàn theo protocol 002 rồi mới xây model/SDK". Chuyển sang **xây MVP trước**, và các hạng mục validate còn lại được **nhúng làm definition-of-done của từng sprint trong quá trình phát triển** — MVP vừa là sản phẩm, vừa là công cụ đo cho chính các validation tương lai (H5/H12/H15–H18 không thể verify nếu chưa có player/queue/installer thật). `002-v3-nano-mobile-validation.md` không còn được dùng làm tài liệu thực thi; file này là nguồn sự thật duy nhất từ đây về trạng thái và quyết định.

### Căn cứ quyết định (phân tích chi phí, không có đo mới)

| Đường đi | Ước lượng tới sản phẩm dùng được | Nhược điểm chính |
|---|---|---|
| Verify hoàn toàn trước → build | ~20–31 tuần | Nhóm H5/H12/H13–H18 **bị chặn bởi chính MVP chưa tồn tại** → verify-first suy biến thành "MVP + trễ ngân sách"; backlog loại "đo bằng sản phẩm" vẫn phải trả lại sau. |
| **MVP trước → improve dần (đã chọn)** | **~12–18 tuần** + spike de-risk 2 tuần | Chỉ rủi ro nếu chốt sai các quyết định *không đảo ngược được*; các quyết định đó được chốt bằng spike trước khi viết SDK. |

Nguyên tắc phân biệt: **quyết định đắt-để-sửa** (package format, ngưỡng thiết bị tối thiểu, voice asset schema/manifest, chunk-pipeline contract) → chốt bằng spike trước khi code SDK; **quyết định rẻ-để-sửa** (`steps`, filter, provider, thread/prefetch) → để improve dần bằng config.

### Trạng thái cuối của 18 giả thiết sau 9 vòng (bản chuẩn hóa)

| H | Trạng thái cuối | Kết luận một dòng |
|---|---|---|
| H1 | PARTIAL — đủ cho MVP | Graph chạy đúng CPU trên macOS/Android/iOS thật, 0 custom op (opset 17), 0 NaN/Inf full corpus; thiếu đa thiết bị và non-CPU EP. |
| H2 | PARTIAL | P1 preset-only FP32 = 281.8MB / 261.8MB gzip, vượt target 150MB ~2×; `vector_estimator` (155MB disk, 151.6MB RSS) là thủ phạm RAM/disk số 1. |
| H3 | PARTIAL | FP16 (`onnxruntime.transformers.float16`) load cả 4 graph, giảm 50% (268.6→134.6MB), WER/CER không xấu đi (mức "cải thiện" ±1 điểm đọc là nhiễu đo); chưa đo trên device, INT8 chưa thử. |
| H4 | PARTIAL — **đã có quyết định** | `steps=8, sway=-1` + treble filter chốt làm mobile default (§0.10); quality giảm tuyến tính theo steps, không vực sụp đổ trong 8–16. |
| H5 | BLOCKED → **nhúng MVP** | Cần player/queue/prefetch thật; verify trong Phase 6. |
| H6 | PARTIAL — **đã có quyết định** | Android→CPU/XNNPACK, iOS→CPU; NNAPI/CoreML loại có lý do (§0.11); cần ≥2 thiết bị/platform để PASS protocol. |
| H7 | PARTIAL | ORT optimization giảm load ~33% nhưng RAM không đổi (~358MB) — RAM do weights quyết định → FP16/mô-đun hóa là đường đi, không phải tuning runtime. |
| H8 | PARTIAL | Thermal SEVERE sau ~4 phút nhưng không CRITICAL trong 20.6 phút; chưa đủ soak 30–60 phút; RAM thật 1.09GB vượt target 300–400MB. |
| H9 | PARTIAL | WER/CER đo trên 11 voices, variance 6.5× (Đức Trí 3.1% ↔ Hữu Quân 20.3%); corpus MVP 18–20 câu, chưa listening nhiều người. |
| H10 | BLOCKED | Chưa cần train; mở lại nếu spike Tier B cho thấy RTF/RAM không đạt. |
| H11 | NOT RUN | Cần sự kiện sync `main`→`develop` thật; tiền đề (111 tests PASS) đã có. |
| H12 | PARTIAL | Graceful failure OK (corrupt/missing/provider-invalid đều catchable, fallback CPU); rollback/interrupted-download/app-update chờ SDK. |
| H13 | PARTIAL | 11 voices load OK, không tạo thêm session; RAM theo số voice + switch lifecycle chờ SDK. |
| H14 | PARTIAL | Determinism seed=42 max_diff=0.00; chưa test cross-platform (iOS vs Android). |
| H15 | PARTIAL | Voice load OK; atomic install/rollback/download chờ registry + SDK. |
| H16 | PARTIAL | Preset hoạt động; local enrollment (`encode_reference`) chưa test. |
| H17 | BLOCKED | Cloning/adapter ngoài MVP; chỉ mở sau khi reference asset baseline đo xong. |
| H18 | PARTIAL | Voice switch A→B→C→A không lẫn state; switch giữa chunk khi đang phát chờ SDK lifecycle. |

### Trạng thái gate

| Gate | Trạng thái | Ghi chú |
|---|---|---|
| G0 | ✅ ĐẠT | Model revision `1d700d3d` / `main c1390ab` khóa; `pytest` 111 passed; baseline reproducible. |
| G1 | ✅ ĐẠT (điều kiện: CPU-only, 1 thiết bị/platform) | 8/8 graph load, 0 unsupported op, 0 NaN, chạy thật trên Android + iOS. |
| G2 | ⏳ CHỜ spike FP16 trên device | Package target ≤150MB dùng kiến thỏa bởi FP16 (134.6MB host); cần xác nhận trên device thật. |
| G3 | PARTIAL | ORT opt + steps đã chốt; static-shape/buffer-reuse chưa làm (chờ Phase 3). |
| G4 | PARTIAL | FP16 đạt; weight-only INT8 chưa thử (sau G2). |
| G5 | TẠM HOÃN | Chưa cần distill; mở lại nếu spike fail RTF/RAM ở Tier B/C. |
| G6–G8 | → thuộc MVP Phase 6–7 | Lifecycle, device/thermal đầy đủ, beta/release. |
| G9–G12 | → sau MVP (voice ecosystem) | Voice asset contract, multi-voice memory, custom voice, adapter/LoRA. |

### Dữ liệu đo cốt lõi (bản chuẩn hóa — thay cho việc tra cứu 002)

> Cảnh báo: số liệu WER/CER trước vòng 8 đo bằng pipeline **bị bug chunking** — chỉ các số "pipeline đã sửa" dưới đây là canonical.

| Chỉ số | Giá trị (canonical) |
|---|---|
| Model bundle | HF `pnnbao-ump/VieNeu-TTS-v3-Nano@aba295eb…`, 7 graph + config + constants.npz (~420MB), opset 17 (speaker_encoder 14) |
| RTF median `steps=8/sway=-1` (CPU) | Android **0.3239** / iOS **0.2936** — đạt ≤0.5 trên cả 2 thiết bị thật |
| RTF p95 `steps=8` | CPU Android 0.4182; XNNPACK 0.4704; NNAPI 0.6608 (vượt ngưỡng → loại khỏi default) |
| Package | P0 419.9MB raw / 375.0 gzip; P1 281.8 / 261.8; FP32 4-graph 268.6MB → FP16 134.6MB |
| RAM (macOS host, per-graph RSS) | text_encoder 68.5MB, duration 0.2MB, vector_estimator 151.6MB, codec_decoder 68.9MB |
| RAM (Android thật) | peak RSS 1,089.5MB; peak PSS 914.4MB; CPU/XNNPACK ~670MB vs NNAPI ~910MB |
| Thermal (Android thật, 20.6 phút) | skin 44→50.6°C; NONE→SEVERE sau ~4 phút; không CRITICAL; pin ~15%/h (đang sạc) |
| WER/CER canonical (n=54, pipeline đã sửa) | steps=16: 8.50%/7.77% · steps=12: 9.66%/8.21% · steps=8 raw: 10.79%/9.84% · steps=8+filter: **9.86%/7.12%** |
| WER/CER FP16 (steps=8) | 9.96%/7.96% (đọc mức "cải thiện" là nhiễu đo; kết luận an toàn = không xấu đi) |
| Voice matrix (11 voices, steps=8) | avg 9.36%/7.83%; tốt nhất Đức Trí 3.12%; kém nhất Hữu Quân 20.29%; giọng mặc định Minh Quân 19.63% (⚠️ thuộc nhóm kém) |
| Determinism / switch | seed=42 → max_diff=0.00; A→B→C→A không lẫn conditioning state |

### Việc còn lại → nhúng vào MVP (mapping + ngưỡng)

| Hạng mục | Nguồn H | Chèn vào | Ngưỡng pass |
|---|---|---|---|
| FP16 trên device thật (RAM/RTF/quality) | H2/H3/H7 | Spike + Sprint đầu Phase 2–3 | P1-FP16 ≤150MB, peak RSS giảm ≥40% so FP32, RTF không xấu đi |
| Tier B/C device matrix (≥1 Android non-MediaTek + 1 iPhone đời mới) | H1/H4/H6 | Spike + Phase 7 | RTF ≤0.5 trên Tier B ở steps=8 |
| Thermal soak 30–60 phút đúng protocol | H8 | Spike 30 phút + Phase 7 | Không CRITICAL, RTF cuối ≤0.5, app tự hạ economy |
| Chunk prefetch/underrun/cancel | H5 | Phase 6 | 30 phút không underrun trên Tier B |
| Install/checksum/rollback | H12/H15 | Phase 6–7 | Không crash/half-installed; fallback bundled |
| Voice switch lifecycle | H18/H13 | Phase 6 + sau G9 | 0 wrong-speaker, không leak session/cache |
| Corpus regression 300–1000 câu + listening nhiều người | H9 | Liên tục mỗi candidate | WER không vượt baseline quá ngưỡng đã phê; nhóm critical đạt riêng |
| CoreML static-shape bucketing | H6 (iOS) | Phase 3 (sau khi CPU ổn) | CoreML init OK, không fallback lỗi |
| ORT Swift BOOL bridging | action-item vòng 2 | Phase 6 iOS | Pass input BOOL của `duration_predictor`/`vector_estimator` |
| H11 sync `main`→`develop` | H11 | Khi `main` có commit | Không drift tokenizer/G2P/opset ngoài kế hoạch |

### Spike de-risk (2 tuần, trước hoặc đầu MVP — trả lời 3 câu hỏi khóa)

1. **FP16 trên thiết bị thật** (RAM/RTF/quality): quyết package shape. *Pass =* P1-FP16 ≤150MB + RSS giảm có ý nghĩa + WER không tệ hơn steps=8 baseline (9.86%/7.12%) quá ±1 điểm.
2. **Soak 30 phút** với `MainActivity` hiện có + `adb` polling (kéo dài kịch bản §0.11): quyết thermal policy bắt buộc/tùy chọn. *Pass =* không CRITICAL, RTF cuối ≤0.5.
3. **Một máy Android Tier B thật** (vd Snapdragon 4/6-series, 4GB): trả lời §12 câu #1 (device floor) và xác nhận RTF 0.32 có giữ trên tầm trung không. *Pass =* RTF ≤0.5 ở steps=8.

Nếu bất kỳ mục nào fail → quay lại quyết định tương ứng **trước** khi đóng băng public API/package format (chưa tốn rework lớn).

### Nguyên tắc kiểm thử còn hiệu lực (kế thừa từ 002)

- Một biến mỗi lần khi so sánh candidate; ghi đủ metadata baseline (mẫu JSON §8).
- Không suy luận simulator/emulator → device thật (RTF/RAM/thermal/battery).
- Quality và hiệu năng là hai gate độc lập; candidate nhanh mà đọc sai là fail.
- Fallback ladder: INT8 → FP16 → FP32/reference; NNAPI/CoreML → XNNPACK/CPU; graph mới → graph cũ.
- Hard fail = crash/ANR/deadlock, NaN/Inf, checksum sai, underrun lặp, không rollback được, leak tăng liên tục.
- Không lấy lần chạy nhanh nhất; báo cáo median, p95, variance; artifact machine-readable + reviewer ký.

---

## 0.14. Spike de-risk (Giai đoạn 0) — kết quả thực thi 2026-09-29

Thực thi plan "v3 Nano Mobile MVP: Spike de-risk + Sprint 1 + Sprint 2a" trên nhánh `develop`. Kết quả từng hạng mục spike (S1–S3) và các quyết định bị khóa (lock) — **không mở lại** nếu không có dữ kiện mới.

### S1 — FP16 trên thiết bị thật + package P1

| Hạng mục | Kết quả | Artifact |
|---|---|---|
| `convert_fp16.py` (4 graph, `keep_io_types=True`, topological sort fix) | **PASS** — 281,700,111 → 141,208,855 bytes (50.1%) | `v3nano-mobile/host/convert_fp16.py`, `v3nano-mobile/package/fp16/`, `fp16_conversion_report.json` |
| `build_mobile_package.py` (manifest schema v1, checksums, 11 voices, `assets/tokenizer/tokenizer.json`) | **PASS** — raw 141.8MB / tar.gz 131.1MB ≤ 150MB | `v3nano-mobile/host/build_mobile_package.py`, `v3nano-mobile/package/pkg/` |
| `validate_mobile_package.py` (checksums + ORT probe 4 graph + FP32-vs-FP16 smoke s01/s05/s07) | **PASS** — s01 SNR 38.1dB, s05 27.6dB; s07 -3.2dB đã chẩn đoán là duration-T flip ±1 frame (chunk1 SNR 39.6dB), không phải suy giảm biên độ | `v3nano-mobile/eval/reports/2026-09-29-macos-arm64-package-fp16-vs-fp32.json` |
| H3 quality gate (WER ≤10.86 / CER ≤8.12) | **PASS** — WER 8.29% / CER 7.02% | `v3nano-mobile/eval/reports/2026-09-28-macos-arm64-asr-quality-fp16-spike.json` |
| FP16 iOS real device (iPhone 11, CPU provider) | **PASS** — 107.2s, 0 failures, 0 NaN/Inf, full corpus 20+20 | `v3nano-mobile/eval/reports/2026-09-29-ios-real-device-fp16.json` |
| FP16 Android real device | **NOT RUN** — OnePlus PHP110 (YH7LX4VWSSXOAQOJ, MT6983) online lúc đầu phiên rồi disconnect trước `installDebug`; không bịa số | `v3nano-mobile/eval/reports/2026-09-29-android-real-device-fp16.json` |

### S2 — Soak 30 phút (thermal)

**NOT RUN** — cần Android real device online ≥30 phút với `v3nano-mobile/host/device_soak_monitor.py` (poll `dumpsys meminfo/thermalservice/battery` mỗi 15s). Không có device; emulator thermal không đại diện (suy ra thermal tier từ emulator = bịa số). Tooling + harness plumbing (`MainActivity` intent extra `loops` → `smokeRunsPerCase`) **đã sẵn sàng**. Report: `v3nano-mobile/eval/reports/2026-09-29-android-soak-tier-b.json`.

### S3 — Tier B Android (non-MediaTek Snapdragon 4/6-series)

**NOT RUN** — thiết bị Android duy nhất thấy trong phiên là OnePlus PHP110 **MediaTek MT6983** (đã disqualified ngay cả khi còn kết nối). Không có Snapdragon 4/6-series. Ngưỡng RTF ≤0.5 trên Tier B **chưa được xác nhận**.

### Quyết định bị khóa sau spike (lock)

| Quyết định | Nội dung | Căn cứ |
|---|---|---|
| **D8 default voice** | `duc_tri` (WER 3.12% — giọng tốt nhất trong 11) | `2026-09-28-macos-arm64-asr-quality-steps8-allvoices.json` + manifest `default_voice_id` |
| **Package shape** | P1 layout: `manifest.json` schema v1 + `graphs/` FP16 + `assets/tokenizer/tokenizer.json` + `assets/constants.npz` + `assets/voices/` (index + speaker_emb.bin/style.bin) + `checksums.txt` ≤150MB gzip | validate PASS; tokenizer JSON self-describing (giải quyết open question #1 — không dùng `vo_codes.bin`) |
| **Generation defaults** | `steps=8, sway=-1.0, cfg=3.0, max_chars_per_chunk=140` + treble filter (Butterworth SOS order 2, 6kHz, mix 0.5) | §0.10 đã chốt; golden-locked trong `v3nano-mobile/tests/test_chunk_contract.py` |
| **Runtime** | CPU/XNNPACK, FP16, preset-only | §0.11; NNAPI/CoreML loại có lý do |
| **RNG (b)** | Không port bit-exact PCG64/ziggurat; determinism intra-platform: cùng seed → max_diff=0; cross-platform so bằng WER ±1 điểm | `DeterministicRng` = `kotlin.random.Random(seed)` + Box-Muller (đo được ≠ `java.util.Random` với mọi seed — `KotlinRandomEquivalenceDocTest`) |
| **Device floor** | **CHƯA lock được** — thiếu Tier B + soak S2. Tạm thời giữ ngưỡng Spike pass criteria (P1-FP16 ≤150MB, RSS giảm ≥40%, WER ±1 điểm) cho tới khi có Snapdragon 4/6-series + soak 30 phút | S2/S3 NOT RUN |

### Sprint 1 + Sprint 2a (đã hoàn thành cùng phiên)

- **Corpus freeze** `corpus-mvp-v1`: 40 case sha256 trong `v3nano-mobile/eval/corpus/manifest.json` (block `frozen`).
- **Golden exporter** `v3nano-mobile/host/export_golden_contract.py` → `v3nano-mobile/eval/golden/{chunk_split,join_gaps,sway_schedule,treble_sos,phone_ids}.json`.
- **Contract tests** `v3nano-mobile/tests/test_chunk_contract.py` + `test_manifest_schema.py` — inline golden asserts (không chỉ đọc file) + determinism. **127 pytest green** (111 baseline + 16 mới). `pyproject.toml` `testpaths = ["tests", "v3nano-mobile/tests"]`.
- **Kotlin SDK** `v3nano-mobile/android/sdk/` (namespace `com.vieneu.mobile`, artifact `vieneu-mobile-sdk`): pure JVM core (`GenerationProfile`, `FlowScheduler`, `ChunkJoiner`, `TrebleFilter`, `DeterministicRng`, `PhoneEncoder`, `ModelRegistry`) — không `android.*` import, không ORT trong core. **29 JVM tests green** (golden-based).
- **Harness refactor** `NanoBenchmarkCore` delegate sang SDK core, public API giữ nguyên. Smoke equivalence **PASS**: emulator full corpus 3 providers × 40 cases, 0 NaN/Inf, 0 hard-fail; 19/20 smoke case khớp audio_seconds tuyệt đối với iOS FP16 (cùng graphs/voice/steps/sway), case còn lại (s07, 2 chunk) lệch 0.02s từ edgeSilence threshold — xem `v3nano-mobile/eval/reports/2026-09-29-post-refactor-smoke-equivalence.json`.

### Việc spike trả lời được / chưa trả lời được

| Câu hỏi khóa | Trạng thái |
|---|---|
| Package shape + FP16 ≤150MB + quality không xấu đi? | **CÓ** — PASS đầy đủ (host + iOS real device) |
| FP16 chạy được trên device thật? | **CÓ** với iPhone 11; Android real device **chưa xác nhận** (NOT RUN) |
| Thermal tier / soak 30 phút? | **CHƯA** — NOT RUN |
| Device floor (Tier B Snapdragon 4/6)? | **CHƯA** — NOT RUN |
| MVP Android/iOS SDK build được trên core chung? | **CÓ** — SDK JVM + harness delegate, golden-locked |

**Kết luận spike:** không có blocker nào buộc đổi package format / generation defaults / RNG strategy. Hai mục còn mở (S2 soak, S3 Tier B) là **device-gated** — không cản Sprint 2b (text→phoneme trên máy) nhưng **phải xong trước khi đóng device floor vào public API**. Khi có thiết bị: chạy lại checklist trong `2026-09-29-android-real-device-fp16.json` (`rerun_when_device_returns`).

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

> **Cập nhật (2026-09-29):** chuỗi 7 bước trên áp dụng cho phần "chạy đúng & tối ưu model"; chiến lược tổng đã đổi thành **MVP trước, validation nhúng** (xem §0.13). Thứ tự ưu tiên hiện tại: (1) spike de-risk 2 tuần → (2) MVP SDK Android/iOS với package FP16 preset-only → (3) validate/improve dần trong từng sprint. Các bước distill/QAT (mục 5) bị trì hoãn cho tới khi spike cho thấy thực sự cần.

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

Mã nguồn mobile-host nằm dưới một root riêng (cập nhật 2026-09-29 — **không** tạo `src/vieneu_mobile/` vì sản phẩm mobile là native, không phải Python package):

```text
v3nano-mobile/
  android/sdk/          # com.vieneu.mobile — core thuần JVM (FlowScheduler, ChunkJoiner, …)
  android/device-validation/
  ios/device-validation/
  host/                 # packaging / export / validate phía host (Python)
  package/ eval/ tests/
```

Nếu sau này cần API library phía host (không chỉ CLI), tách `host/packaging/` thành module — vẫn không đụng `src/vieneu/v3nano.py`.

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

### 7.4. Biên giới thư mục (cập nhật 2026-09-29)

```text
v3nano-mobile/                  # MỘT root duy nhất cho v3 Nano Mobile
  android/sdk/                  # SDK sản phẩm (com.vieneu.mobile, core JVM)
  android/device-validation/    # app validate engine trên device (không ship)
  ios/device-validation/        # app validate engine (ORT C++ API)
  host/                         # Python host: export, convert FP16, build/validate package, ASR
  package/                      # model package P1 metadata — KHÔNG commit weights
  eval/                         # corpus · golden · reports · reports-archive · listening-tests
  tests/                        # contract tests Python (pytest)
docs/_supermeo/_plan/001-mvp/   # plan MVP
docs/_supermeo/_summary/        # kế hoạch và quyết định (tài liệu này)
```

**Merge-safety:** chỉ 4 path ngoài cây này bị chạm — `.gitignore` (nguyên bản, không chứa rule mobile), `pyproject.toml` (`testpaths`), `docs/`, `v3nano-mobile/`. Rule ignore của mobile nằm trong `v3nano-mobile/.gitignore` (không đặt ở root).

Không đổi cấu trúc source của `src/vieneu/v3nano.py` trong MVP — `v3nano.py` vẫn là golden reference sinh contract.

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

1. Device thấp nhất được hỗ trợ là model nào? *(chưa chốt — trả lời bằng spike Tier B/C tại §0.13)*
2. Package được bundled hay download sau khi cài app?
3. Có bắt buộc voice cloning trong bản đầu không? Khuyến nghị: không.
4. Có chấp nhận 24 kHz cho mobile không? Khuyến nghị: có.
5. Quality threshold dùng listening test, ASR hay cả hai?
6. ~~Android provider mặc định là XNNPACK hay NNAPI fallback?~~ **ĐÃ CHỐT (§0.11): CPU (ưu tiên), XNNPACK (fallback).** NNAPI loại do p95=0.66 vượt target ≤0,5, stddev gấp 2 lần CPU, graph_load chậm gấp 3, RAM overhead ~250MB. (Giữ NNAPI là optional experiment trong manifest/release notes.)
7. ~~iOS provider mặc định là CPU hay CoreML sau benchmark?~~ **ĐÃ CHỐT (§0.11): CPU (duy nhất).** CoreML loại do lỗi kỹ thuật cứng (Metal texture size limit trên iPhone 11 — `vector_estimator.onnx` sequence-length 29184 > 16384). Không có provider nào khác trên iOS.
8. Có cho phép telemetry opt-in không?
9. Mobile artifact registry đặt ở đâu và ai quản lý signing/checksum?
10. Chính sách merge generic fixes từ `develop` ngược về `main` là gì?
11. ~~Triển khai theo hướng nào: verify hoàn toàn trước hay MVP trước?~~ **ĐÃ CHỐT (2026-09-29): MVP trước, validation nhúng** — xem căn cứ chi phí và backlog mapping tại §0.13.

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

## 15. Trạng thái triển khai plan 001-mvp (2026-09-29)

> Cập nhật sau Sprint 2b + 3 + 4 core + 5 host-path. **Không** phải biên bản validation vòng 10 — đây là map DoD `02-lo-trinh-sprint.md` so với code trên `develop`.

### 15.1. Bảng hoàn thiện theo sprint

| Giai đoạn | Trạng thái | Ghi chú |
|---|---|---|
| Gđ0 Spike | **PARTIAL** | S1 host/iOS FP16 có số; **S1-Android / S2 soak / S3 Tier B = NOT RUN** (thiếu device) |
| Sprint 1 | **DONE** | corpus-mvp-v1, golden, contract tests, CI pytest |
| Sprint 2a | **DONE** | `com.vieneu.mobile.core` + golden JVM |
| Sprint 2b | **PARTIAL** | `TextSegmenter` structural + golden 40 case; **sea-g2p normalize/G2P chưa on-device** |
| Sprint 3 | **PARTIAL** | API/engine/pipeline + `OrtSynthesisEngine`; sample UI/device; iOS bridge body = stub |
| Sprint 4 | **PARTIAL** | Queue/Thermal/Cache/Metrics/Worker unit-tested; **chưa AudioTrack + chưa 30' device** |
| Sprint 5 | **PARTIAL** | CI job + release template; **registry/signing/HF bundle chưa có** |
| Sprint 6–8 | **NOT STARTED** | device matrix, listening, telemetry, public release |

### 15.2. Known limitations (chính thức — integrator phải biết)

1. **G2P + normalize đầy đủ (sea-g2p, Rust) chưa chạy trên mobile.**  
   `Reader.enqueue(text)` / `synthesizeText` chỉ chắc chắn với text đã normalize/phonemize hoặc khi inject `phonemizer`. Dùng `synthesizePhones` / `enqueuePhones` cho production path hiện tại. `TextNormalizer.mini` chỉ phục vụ demo.

2. **iOS `OrtEngineBridge` chưa bind ORT C++** — bản inference đã PASS nằm ở `device-validation/.../NanoEngineTests.mm`; production iOS cần port body (BOOL `ctx_mask` bắt buộc qua C++ API).

3. **Playback**: `AudioPlayer` là interface; chưa có `AudioTrack`/`AVAudioEngine` impl wired vào Reader. `GenerationQueue` cancel-at-boundary đã test JVM.

4. **Mã lỗi** thống nhất theo `08` §8.4 (`E_CANCELED` — chính tả Mỹ); Flutter map 1:1.

5. **Device-gated**: mọi RTF/RAM/thermal/soak/Tier B chưa có số mới → release note ghi `NOT RUN` + lý do (không bịa số).

### 15.3. Freeze checklist v0.1 (chốt “đắt-để-sửa” — 00 §0.1)

| Hạng mục | Trạng thái freeze | File/contract |
|---|---|---|
| generation_defaults | **FROZEN** | steps=8, sway=-1, cfg=3.0, max_chars=140, treble on (D1) |
| default_voice_id | **FROZEN** | `duc_tri` (D8) |
| package shape P1 FP16 | **FROZEN** | 4 graph, ≤150 MB gzip (D4/D5) |
| provider ladder | **FROZEN** | Android CPU→XNNPACK; iOS CPU (D2/D3) |
| chunk/gap/sway/treble contract | **FROZEN** | `eval/golden/*` + JVM tests |
| error code table | **FROZEN** | `08` §8.4 + `api/EngineError.kt` |
| manifest schema v1 | **OPEN** | `build_mobile_package.py` / `03-model-package.md` — cần ký chính thức |
| voice asset schema v1 | **OPEN** | `voices_v3_nano_mobile.json` |
| public API Kotlin/Swift/Dart | **OPEN** | đổi được trước tag `sdk-0.1.0`; sau đó chỉ migration note |
| RNG semantics | **FROZEN** | `kotlin.random.Random`+Box-Muller; intra-platform determinism (quyết định b) |

### 15.4. Gate phát hành MVP còn thiếu (§0.6)

- [ ] S1-Android FP16 + S2 soak 30' + S3 Tier B (khi có device)
- [ ] Benchmark ≥1 flagship + ≥1 mid-range mỗi platform
- [ ] Playback 30 phút không underrun (AudioTrack + queue)
- [ ] Listening review ≥2 người ký
- [ ] License / attribution inventory
- [ ] Registry + signing + rollback artifact thật
- [ ] iOS ORT bridge body + SPM binary pin

Xem thêm checklist release: `v3nano-mobile/eval/RELEASE_NOTE_TEMPLATE.md`.
