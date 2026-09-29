# 08 — CI/CD, model registry, phát hành & telemetry

## 8.1. CI pipeline (GitHub Actions hoặc tương đương — repo dùng git, chọn runner macOS arm64 cho host test)

```text
PR pipeline (mỗi PR):
  1. uv sync --group dev            →  pytest -q  (111 passed baseline — G0/H11 tiền đề)
  2. JVM unit tests SDK Android (gradlew test:  core + contract vs golden)
  3. Swift core XCTest (xcodebuild test -destination 'platform=macOS' hoặc iOS Simulator)  [hard-fail only]
  4. flutter analyze + flutter test (mock host)
  5. Lint + schema validator manifest/voice assets (công cụ trong v3nano-mobile/host/)
  6. Package size check: dựng P1 FP16 → so ngưỡng 150MB → cảnh báo sớm

Nightly (nếu máy test có device nối mạng):
  - connectedDebugAndroidTest (emulator ARM64) — hard-fail multi-provider
  - xcodebuild test (simulator) — hard-fail
  - integration_test Flutter trên device sẵn có
  - nếu có device thật cắm máy: L3 benchmark + báo cáo JSON tự commit vào v3nano-mobile/eval/reports/

Release pipeline:
  - build AAR / SPM / pub package + AAB/IPA sample
  - ký (keystore riêng, CI secrets — không commit)
  - tải/sanction gói model từ registry → verify checksum + sign → đính kèm vào release notes
  - Tag git: v3-nano-mobile-<model_revision> và sdk-<sdk_revision>
```

**Quy tắc:** CI không commit model weights; model tải từ HF với **revision pin** khi cần ở job export/benchmark; artifact benchmark là máy-đọc-được (`v3nano-mobile/eval/reports/*.json` hiện hữu là tiền lệ).

## 8.2. Model registry (trả lời §12 câu #9)

| Việc | Quyết định đề xuất |
|---|---|
| Vị trí | HF repo riêng `pnnbao-ump/VieNeu-TTS-v3-Nano-Mobile` + CDN; bản gốc model ở HF `pnnbao-ump/VieNeu-TTS-v3-Nano` |
| Ai quản lý | Team MLOps (sign key riêng, không phải key dev) |
| Artifact | `v3-nano-mobile-<revision>.bundle` (đóng gói theo §3.1) + `bundle.json` + `checksums.txt` + signature file + changelog/limitations |
| Signing | Ký file `bundle.json` (chứa sha256 các thành phần) bằng key chỉ MLOps giữ; app verify chữ ký + checksum trước khi dùng |
| Voice pack sau MVP | `voices/<voice_id>-<asset_revision>.vpack` — manifest theo §3.3, ký y hệt |
| Download | Atomic (temp → verify → commit) — chu trình §3.4; resumable download; timeout/retry rõ ràng |

**Quy trình cập nhật model:**
1. Export/pin revision mới + chạy đủ L0–L2.
2. Đăng internal channel → closed beta devices (nhóm thiết bị matrix).
3. Promote → public với `rollback_to` ghi revision cũ; SDK giữ cả hai bản trên máy tới khi app chạy ổn (pointer `active_revision`).
4. Không bao giờ xóa revision cũ khỏi registry trong 2 release liên tiếp.

## 8.3. Kênh phát hành (theo §Phase 7 summary)

```text
Internal harness → Internal app → Closed beta (device matrix) → Small production (telemetry opt-in)
→ Public release preset-only → Optional cloning package (sau MVP)
```

Mỗi release note bắt buộc đủ trường: model revision · SDK revision · runtime/provider · precision · opset/export tool · supported ABI/OS · checksum · known limitations · rollback artifact.

## 8.4. Bảng mã lỗi SDK (thống nhất 3 platform — đừng để mỗi platform một style)

| Mã | Ý nghĩa | Hành vi chuẩn |
|---|---|---|
| `E_COMPAT` | ABI/model/runtime/sdk không tương thích | Từ chối trước khi load, kèm revision cần |
| `E_MODEL_CHECKSUM` | checksum/signature sai | Không kích hoạt; giữ bản cũ; gợi ý tải lại |
| `E_MODEL_CORRUPT` | file hỏng/không phải ONNX (`InvalidProtobuf`) | Rollback pointer về revision cũ |
| `E_MODEL_MISSING` | không tìm thấy file (`NoSuchFile`) | Hướng dẫn install; không tự download khi offline-mode |
| `E_PROVIDER_INIT` | provider khởi tạo fail | Tự fallback CPU + log lý do vào metrics |
| `E_CACHE_FULL` / `E_DISK_FULL` | không đủ chỗ disk | Dọn LRU → nếu vẫn thiếu → lỗi rõ ràng, không treo |
| `E_CANCELED` | job bị hủy (bình thường) | Không tính là lỗi; dọn queue/cache |
| `E_UNDERRUN` | buffer cạn | Warning + tăng ưu tiên chunk gần; đếm để đánh giá H5 |
| `E_THERMAL_T3` | thermal SEVERE | Đã chuyển tầng 3; chỉ báo cáo, không crash |

Mỗi lỗi: mã + mô tả tiếng Việt/Anh + hành động gợi ý cho integrator. Dart/`EngineError` map 1:1 bảng này.

## 8.5. Telemetry (mặc định OFF; đúng §9 summary)

- Opt-in mới bật. Chỉ gửi metadata: model/SDK/provider revision · device tier/model · latency/RTF · peak memory bucket · thermal bucket · error code · cache hit/miss · lý do cancel.
- **Cấm tuyệt đối:** raw text · reference audio · speaker embedding · waveform · voice asset — trừ khi diagnostic upload có consent rõ ràng riêng.
- Lưu trữ: server nội bộ, retention ngắn, không bán/share.

## 8.6. Quy trình sync `main` → `develop` (H11 — thủ tục bắt buộc mỗi lần sync)

1. Snapshot benchmark mobile trước sync (report JSON + 111 pytest + L1 port-compare).
2. Fetch `main` mới → merge vào branch sync riêng (không merge thẳng develop).
3. Chạy regression cả hai phía; diff các vùng nhạy cảm: text normalization, G2P/tokenizer, asset schema, opset, dependency, public API.
4. Port-compare 40 case — **đây là lưới bắt drift** giữa Python và hai bản native.
5. Migration note + commit ghi `main` SHA đã tích hợp; merge develop sau khi gate quality pass.

## 8.7. Checklist hạ tầng trước Sprint 5

- [ ] Runner macOS arm64 (host test + export) hoạt động; pytest 111 xanh trong CI
- [ ] Model registry: repo + quy trình ký + bundle.json mẫu
- [ ] Keystore/ký iOS trong CI secrets (không trong git)
- [ ] Nightly device job (kể cả khi chỉ có emulator/simulator — ghi nhãn "ảo hóa")
- [ ] Telemetry endpoint + tài liệu chính sách dữ liệu