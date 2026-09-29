# Plan 001-MVP — VieNeu-TTS v3 Nano Mobile (Kế hoạch xây dựng)

- **Trạng thái:** 📋 Kế hoạch chi tiết — sẵn sàng thực thi
- **Chế độ:** MVP trước, validation nhúng vào từng sprint (theo §0.13 của `../_summary/001-v3-nano-mobile.md`)
- **Nguồn sự thật:** `docs/_supermeo/_summary/001-v3-nano-mobile.md` (trạng thái, quyết định đã chốt, dữ liệu đo canonical)
- **Đối tượng:** team Mobile/Edge — kỹ sư Android, iOS, Flutter, MLOps
- **Thư mục này:** kế hoạch hành động chi tiết. Không thay thế summary — summary giữ vai trò ghi nhận *lịch sử và quyết định*, thư mục `_plan/001-mvp/` giữ vai trò *cầm tay thực thi*.

## Bộ tài liệu và thứ tự đọc

| # | File | Nội dung | Đọc khi nào |
|---|---|---|---|
| 0 | [`00-tong-quan.md`](00-tong-quan.md) | Chiến lược MVP-first, quyết định đã chốt, dữ liệu đo canonical, mục tiêu và phạm vi | **Đọc đầu tiên** |
| 1 | [`01-kien-truc-runtime.md`](01-kien-truc-runtime.md) | Kiến trúc tổng thể: 3 lớp version, sơ đồ module SDK, chunk streaming, threading/memory/thermal | Trước khi code bất kỳ module nào |
| 2 | [`02-lo-trinh-sprint.md`](02-lo-trinh-sprint.md) | Roadmap: spike de-risk 2 tuần + Sprint 1–8, Definition of Done từng sprint (validation nhúng) | Lập kế hoạch sprint |
| 3 | [`03-model-package.md`](03-model-package.md) | Model bundle FP16 preset-only, manifest + checksum, voice asset schema, install/rollback | Làm Phase model/asset |
| 4 | [`04-sdk-android.md`](04-sdk-android.md) | Thiết kế SDK Kotlin: cấu trúc code, ORT Mobile + XNNPACK, AudioTrack, coroutine, thermal policy, testing | Phát triển Android |
| 5 | [`05-sdk-ios.md`](05-sdk-ios.md) | Thiết kế SDK Swift: ORT C++ bridging (vấn đề BOOL tensor), AVAudioEngine, lifecycle | Phát triển iOS |
| 6 | [`06-sdk-flutter.md`](06-sdk-flutter.md) | Plugin Flutter: kiến trúc bao bọc SDK native (Pigeon), API Dart, đóng gói, test | Phát triển Flutter |
| 7 | [`07-kiem-thu.md`](07-kiem-thu.md) | Chiến lược kiểm thử toàn diện + ba project kiểm thử (Android / iOS / Flutter) chi tiết | Mọi sprint (DoD bắt buộc) |
| 8 | [`08-ci-cd-phat-hanh.md`](08-ci-cd-phat-hanh.md) | CI/CD, model registry, signing, kênh phát hành, telemetry | Trước Sprint 5 |
| 9 | [`09-rui-ro-va-quyet-dinh-mo.md`](09-rui-ro-va-quyet-dinh-mo.md) | Bảng rủi ro + các quyết định còn mở (câu hỏi §12 chưa chốt) | Định kỳ mỗi sprint |

## Quy ước xuyên suốt bộ tài liệu

- **Model ID mới:** `v3-nano-mobile` — namespace riêng, **không** đổi tên/artifact của `v3 Nano` hiện tại (xem §3.1 summary).
- **Cấu hình mobile đã chốt:** `steps=8, sway=-1` + treble filter (Butterworth bậc 2, cutoff 6 kHz, mix 50%) — §0.10 summary.
- **Provider đã chốt:** Android → CPU (ưu tiên) / XNNPACK (fallback); iOS → CPU. NNAPI/CoreML không dùng làm mặc định — §0.11 summary.
- **Precision ban đầu:** FP16 cả 4 graph (giảm ~50% dung lượng, quality không xấu đi) — §0.12 summary.
- **Không commit model weights vào git** — tải từ HF/pip theo hướng dẫn; đúng nguyên tắc §10 summary.
- **Pipeline sinh âm chuẩn:** `normalize_to_chunks_v3_with_gaps(text, max_chars=140)` → sinh từng chunk → `join_audio_chunks(..., silence_ps=...)` (**bắt buộc**, xem bug chunking — vòng 8 summary).
- **Không suy luận simulator → thiết bị thật** cho RTF/RAM/thermal/battery.
- Số liệu báo cáo: **median, p95, variance** — không lấy lần chạy nhanh nhất.