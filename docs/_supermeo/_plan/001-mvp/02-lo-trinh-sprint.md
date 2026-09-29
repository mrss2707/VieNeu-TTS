# 02 — Lộ trình sprint (MVP trước, validation nhúng)

Tổng ngân sách dự kiến: **Spike 2 tuần + Sprint 1–8 (~12–16 tuần)**. Mỗi sprint có Definition of Done (DoD) **bao gồm luôn** các hạng mục validation còn lại từ protocol 002 — đây là bản chất chiến lược đã chốt (§0.13). Sprint có thể chạy song song một phần (Android ∥ iOS), nhưng gate giữa các sprint là tuần tự.

Đọc kèm: `00-tong-quan.md` (dữ liệu nền), `07-kiem-thu.md` (cách thực thi từng mục validation), `09-rui-ro-va-quyet-dinh-mo.md` (bảng quyết định mở).

---

## Giai đoạn 0 — Spike de-risk (2 tuần) ⚠️ LÀM ĐẦU TIÊN

Trả lời 3 câu hỏi khóa từ §0.13 trước khi đóng băng public API/package format. **Nếu bất kỳ mục nào fail → quay lại quyết định tương ứng trước khi code SDK** (chưa tốn rework lớn).

| # | Spike | Pass = | Fail → hành động |
|---|---|---|---|
| S1 | **FP16 trên thiết bị thật** (RAM/RTF/quality) — build P1-FP16, chạy smoke corpus trên OnePlus + iPhone 11 bằng harness hiện có, đo RSS + RTF + WER ngắn | P1-FP16 ≤150 MB; peak RSS giảm ≥40% so FP32; WER không tệ hơn baseline steps=8 (9.86/7.12) quá ±1 điểm | Quay lại D4: chọn mixed FP16/FP32, hoặc INT8 thử nghiệm sớm; đo lại gói |
| S2 | **Soak 30 phút** — kéo dài kịch bản §0.11 (MainActivity + adb polling) lên 30 phút | Không CRITICAL; RTF cuối ≤0.5; thermal policy tầng 3 giữ app chạy | Thermal policy thành hard requirement + thay đổi pacing mạnh hơn |
| S3 | **Một máy Android Tier B thật** (Snapdragon 4/6-series 4GB hoặc tương đương) chạy smoke | RTF ≤0.5 ở steps=8 (CPU) | Trả lời §12#1: device floor cao hơn dự kiến; xem lại target/quantize |

**DoD Giai đoạn 0:**
- 3 báo cáo machine-readable trong `v3nano-mobile/eval/reports/` + kết luận từng spike.
- Chốt chính thức: giọng mặc định SDK (D8), device floor (D-tier), package shape, thermal policy cấp độ.
- Public API/package format/voice schema **đóng băng v0.1** (chỉ thay đổi qua migration note từ đây).

---

## Sprint 1 — Baseline & hợp đồng (1–2 tuần)

**Mục tiêu:** khóa baseline có thể tái lập; định nghĩa contract mọi thứ khác tuân theo.

**Việc làm:**
1. Chốt `main_base_commit` (`c1390ab`) + `develop` revision vào manifest mẫu; snapshot checksum artifact v3 Nano hiện tại.
2. Regression: `uv run --no-sync pytest -q` → phải **111 passed** (baseline G0).
3. Đóng băng manifest schema v0.1 (xem `03-model-package.md`), voice asset schema v0.1, compatibility matrix.
4. Corpus: đóng băng `smoke/stress/voice_matrix` hiện có làm "corpus MVP v1" + kế hoạch mở rộng 300–1000 câu (H9 — giai đoạn sau).
5. Viết contract test cho chunk pipeline (input→chunks→gaps) bằng Python reference + snapshot expected.
6. Khai báo device matrix v0 (Android Tier A đã có; ghi thiếu Tier B/C, iOS gen thứ hai).

**Nhúng validation:** G0 đạt; H11 tiền đề (111 tests) được ghi vào CI.

**DoD:** manifest/checksum mẫu + corpus v1 có SHA-256; contract test chunk pass; CI chạy regression 111 tests mỗi PR.

---

## Sprint 2 — Engine core native (port 1:1) (2–3 tuần)

**Mục tiêu:** port đúng engine pipeline sang core tách-platform (Kotlin multiplatform-style core + C++ core dùng chung nếu cần), chạy trên macOS/JVM trước.

**Việc làm:**
1. Port đúng thứ tự: normalize→chunks (`max_chars=140`) → G2P/phone encode → duration → vector-flow (`steps=8, sway=-1, cfg=3.0`, công thức `tg = u + sway*(cos(π/2·u) − 1 + u)`) → codec decode → join gaps → treble filter (Butterworth bậc 2, 6 kHz, mix 50%).
2. Seed determinism: `seed` → RNG nội bộ tái lập; test seed=42 hai lần max_diff=0.00.
3. So sánh output core vs `V3NanoVieNeuTTS.infer()` Python: tolerance theo contract (xem `07-kiem-thu.md` §2).
4. Voice conditioning: load 11 preset từ `voices_v3_nano.json` (speaker_emb 192, style 50×256); chọn voice không tạo thêm session.

**Nhúng validation:** H14 (determinism), H13 subset (load 11 voice không tăng session), H18 subset (switch không lẫn state) — tất cả thành test tự động.

**DoD:** core test suite xanh trên CI host; report diff Python-vs-native cho 40 case corpus; không NaN/Inf.

---

## Sprint 3 — Adapter nền tảng + sample apps (2–3 tuần)

**Mục tiêu:** Android/iOS chạy engine trên thiết bị thật qua SDK skeleton + UI sample có nút play/pause/cancel.

**Việc làm:**
1. Android: ORT Mobile ARM64 + CPU/XNNPACK EP (config flag); copy graph ra file cache → `createSession(path)`; AudioTrack streaming; MainActivity **foreground** cho benchmark (bài học OplusHansManager).
2. iOS: ORT C++ API qua bridging (giải quyết BOOL `ctx_mask`); AVAudioEngine; `xcodebuild test` cho sample.
3. Binding tối thiểu cho Flutter (Pigeon) — play/pause/cancel/voice list — để Flutter sample app có tiếng sớm.
4. Báo cáo `graphsLoadAndInferenceRuns` per platform, RTF/RAM per provider.

**Nhúng validation:** G1 đầy đủ (không silent fallback; không NaN/Inf; không unsupported op); H1 nâng lên từ PARTIAL (thêm device thật đã có).

**DoD:** sample chạy trên 2 thiết bị thật (OnePlus, iPhone 11); fallback được log; report median/p95; voice switch bằng UI không crash.

---

## Sprint 4 — Player + queue + thermal policy + cache (2 tuần)

**Mục tiêu:** biến "chạy được" thành "đọc được lâu" — đây là sprint phân biệt product với demo.

**Việc làm:**
1. `GenerationQueue` bounded + priority (chunk gần) + cancel-at-boundary; prefetch depth config.
2. `PlayerBridge`: buffer ≥2 chunk; không underrun; audio focus (Android) / session (iOS); pause/resume/background.
3. `AudioCache`: disk LRU + quota + path theo model revision; dọn khi cancel/đổi voice.
4. `ThermalPolicy` 3 tầng (§1.7) nối tín hiệu thật (dumpsys/NSProcessInfo).
5. `MetricsCollector`: RTF, peak RAM bucket, thermal bucket, cache hit/miss, error code — xuất JSON machine-readable.

**Nhúng validation:** H5 (chunk prefetch/underrun/cancel — target 30 phút không underrun trên Tier B), H12 subset (graceful failure trên SDK), H8 nâng cấp (soak 30 phút có thermal policy).

**DoD:** playback 30 phút không underrun trên 2 thiết bị; cancel giữa mọi phase không crash/leak; thermal tầng 3 giữ app không CRITICAL; report JSON đầy đủ.

---

## Sprint 5 — CI/CD + registry + chữ ký + kênh phát hành (1–2 tuần)

**Mục tiêu:** mọi artifact có nguồn gốc, checksum, và đường rollback — không ai "copy tay" model.

**Việc làm theo `08-ci-cd-phat-hanh.md`:**
1. Pipeline CI: host tests (JVM/Swift core) mỗi PR; device tests nightly (nếu máy test nối mạng có thiết bị).
2. Model registry: nơi đặt artifact + signing/checksum + quy trình tải/downsize (trả lời §12#9).
3. Atomic install + kiểm tra checksum + rollback (tình huống: download đứt, file hỏng, provider fail — H12).
4. Kênh phát hành: internal → closed beta (device matrix) → small production → public (preset-only).
5. Inventory license/attribution đầy đủ (G2 điều kiện, §11 summary).

**Nhúng validation:** H12 đầy đủ (rollback/interrupted download); H15 subset (install/verify/từ chối asset sai checksum).

**DoD:** file hỏng/checksum sai/ABI sai đều fail có kiểm soát với mã lỗi; rollback hoạt động; release note có đủ trường (revision/runtime/provider/precision/opset/ABI/checksum/limitation/rollback artifact).

---

## Sprint 6 — Beta + device matrix mở rộng + regression quality (2 tuần)

**Mục tiêu:** scale device/corpus sang mức tích lũy đủ tin cậy; thu thập listening test.

**Việc làm:**
1. Chạy `07-kiem-thu.md` §4 đầy đủ trên device matrix: thêm ≥1 Android non-MediaTek Tier B, 1 iPhone đời mới hơn (khi có ngân sách — nếu không có thiết bị, ghi rõ BLOCKED, không bịa số).
2. ASR quality regression: chạy `run_asr_quality_check.py` trên audio sinh từ **chính thiết bị thật** (không chỉ macOS) với candidate đã chốt.
3. Listening test có cấu trúc (ít nhất 2–3 người nghe; tái sử dụng `v3nano-mobile/eval/listening_test_steps8_vs_steps16/`), ghi quy trình để lặp lại.
4. Mở rộng corpus dần (100 → 300+ câu) theo thời gian trong sprint — không chờ đủ 1000 mới bắt đầu.
5. Kiểm thử Flutter integration test trên device thật (xem `07-kiem-thu.md` §5).

**Nhúng validation:** H6/h4 mở rộng số liệu thiết bị; H9 nâng từ MVP → mid-scale; H14 cross-platform determinism.

**DoD:** mỗi thiết bị trong matrix có report đủ metadata; WER candidate ≤ baseline+ngưỡng phê duyệt; danh sách known-issues theo voice.

---

## Sprint 7 — Hardening & telemetry & polish (1–2 tuần)

**Mục tiêu:** sửa các vết nứt phát hiện ở beta trước khi mở rộng cohort.

**Việc làm:**
1. Xử lý mọi "non-hard-fail nhưng ghi nhận" thành quyết định: fix hoặc documented limitation.
2. Telemetry opt-in (chỉ metadata theo §1.10) + dashboard nội bộ.
3. Low-storage/disk-full handling; process kill/resume; app-update giữa chương (cache versioned).
4. Tài liệu integration: cách app thứ ba dùng SDK (sample + API docs + troubleshooting).
5. Kiểm tra lại toàn bộ DoD MVP §11 summary còn thiếu mục nào.

**DoD:** checklist §11 summary đạt 100% hoặc từng mục còn lại có quyết định trì hoãn ghi rõ + lý do.

---

## Sprint 8 — Phát hành public (preset-only) (1 tuần)

- Public release preset-only (cloning = optional package sau, theo roadmap §14.5 summary).
- Đóng gói: voice pack download (5–8 giọng tốt nhất bundle; còn lại downloadable — quyết định ở `09`).
- Post-release: theo dõi crash/thermal report 2–4 tuần; kế hoạch phát hành cloning package riêng.

---

## Bảng theo dõi hai chiều: 18 giả thiết ↔ sprint phụ trách

| H | Nội dung gọn | Sprint chịu trách nhiệm | Trạng thái khi MVP xong (kỳ vọng) |
|---|---|---|---|
| H1 | Graph chạy đúng trên device | S3 + S6 | PASS (CPU, ≥2 thiết bị nếu có) |
| H2 | Package/RAM | Gđ0-S1 + S4 | G2 đạt nếu FP16 pass spike |
| H3 | Quantization FP16/INT8 | Gđ0-S1 | FP16 xác nhận trên device (INT8 để sau) |
| H4 | steps cấu hình | đã chốt D1 | Không đổi trong MVP (config cho phép thử) |
| H5 | Prefetch/underrun/cancel | S4 | PASS (30 phút) |
| H6 | Provider default | đã chốt D2/D3 + S6 mở rộng | PASS đủ điều kiện nếu ≥2 thiết bị |
| H7 | ORT opt/buffer reuse | S2–S3 | Ghi nhận (RAM do weights) |
| H8 | Thermal/battery | Gđ0-S2 + S4 | Soak 30 phút với policy, không CRITICAL |
| H9 | Quality regression | S6 + S7 | Mid-scale (100–300 câu) + listening có cấu trúc |
| H10 | Distill/train | sau MVP (chỉ khi Tier B fail RTF) | Không mở trong MVP |
| H11 | Sync main→develop | S1 + mỗi lần sync | Thủ tục + test trước/sau |
| H12 | Rollback/graceful failure | S4 + S5 | PASS trên SDK thật |
| H13 | Voice memory/switch | S2 + S6 | PASS |
| H14 | Determinism | S2 + S6 | PASS incl. cross-platform |
| H15 | Voice pack/registry | S5 + S8 | Bundle + download với checksum |
| H16 | Local enrollment | sau MVP | Không mở trong MVP |
| H17 | Adapter/LoRA | sau MVP | Không mở trong MVP |
| H18 | Voice switch lifecycle | S2 + S4 | PASS |

---

## Quy tắc sprint chung

1. **Một biến mỗi lần** khi so sánh candidate; ghi đủ metadata baseline (mẫu JSON §8 summary).
2. Không suy luận simulator/emulator → device thật cho RTF/RAM/thermal/battery.
3. Quality và hiệu năng là **hai gate độc lập** — candidate nhanh mà đọc sai là fail.
4. Fallback ladder: INT8 → FP16 → FP32/reference; NNAPI/CoreML → XNNPACK/CPU.
5. Hard fail = crash/ANR/deadlock, NaN/Inf, checksum sai, underrun lặp, không rollback được, leak tăng liên tục.
6. Báo cáo median/p95/variance; artifact machine-readable + reviewer ký.
7. Sprint bị block (ví dụ thiếu thiết bị) → ghi rõ BLOCKED + ngày, chuyển sang công việc không phụ thuộc — **không bịa số để xanh gate**.