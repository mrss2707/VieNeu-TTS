# 03 — Model package, manifest và voice asset

Quyết định ở tài liệu này thuộc nhóm **đắt-để-sửa** → chốt bằng Spike Giai đoạn 0, sau đó đóng băng v0.1. Mọi thay đổi schema sau khi đóng băng phải có **schema version + migration note** (xem cuối tài liệu).

## 3.1. Hình dạng gói: P1 preset-only, FP16

Bốn graph core (đứng ánh sáng đo: FP32 268.6 MB → FP16 134.6 MB, không hồi quy quality):

```text
v3-nano-mobile-0.1.0/
├── manifest.json                    # root manifest — xem §3.2
├── graphs/
│   ├── text_encoder.onnx            # FP16 (chuyển bằng onnxruntime.transformers.float16)
│   ├── duration_predictor.onnx
│   ├── vector_estimator.onnx
│   └── codec_decoder.onnx
├── assets/
│   ├── tokenizer/vo_codes.bin       # tokenizer/G2P metadata (version-locked)
│   ├── constants.npz                # nếu cần (giữ tương thích engine port)
│   └── voices/
│       ├── voices_v3_nano_mobile.json   # index 11 preset (xem §3.3)
│       └── <voice_id>/{speaker_emb.bin, style.npy}   # mỗi voice: 192 floats + 50×256
├── checksums.txt                    # sha256 mọi file nội bộ
└── LICENSE                         # license/attribution inventory §3.5
```

**Chưa đóng gói trong P1 (optional package sau):** speaker encoder, codec encoder, denoiser, cloning graphs (giảm package + attack surface — §3.3 summary).

**Kích thước mục tiêu:** tổng nén ≤150 MB (Spike S1 phải xác nhận cả kích thước thật sau nén + kiểm tra gzip/asset-compression của store — iOS App Store và Google Play nén khác nhau, đo cả hai).

**Quy trình export (documented, chạy được từ CI):**
1. Kéo model từ HF `pnnbao-ump/VieNeu-TTS-v3-Nano@aba295eb96a6fa6003ebe417cc1f2802a7adc1dc` (pin revision, không bao giờ dùng `latest`).
2. Chọn 4 graph core → FP16 bằng `onnxruntime.transformers.float16` (đã chứng minh load OK cả 4 graph).
3. `onnx.checker.check_model` + shape inference PASS từng graph.
4. Đóng gói + `checksums.txt` + sign (xem `08-ci-cd-phat-hanh.md`).
5. Chạy smoke corpus trên macOS (fast gate) + ghi report.

## 3.2. Root manifest (`manifest.json`)

```json
{
  "schema_version": "1",
  "model_id": "v3-nano-mobile",
  "model_family": "vieuneu-tts",
  "model_revision": "0.1.0",
  "base_model_revision": "aba295eb96a6fa6003ebe417cc1f2802a7adc1dc",
  "main_base_commit": "c1390abbdb2eedcdf58eafb546966c06ce27af71",
  "precision": "fp16",
  "opset": 17,
  "export_toolchain": "onnx 1.23.0 / onnxruntime.transformers.float16 / ort 1.24.4",
  "supported_abi": ["arm64-v8a", "arm64e"],
  "min_sdk": { "android": 26, "ios": "15.0" },
  "sample_rate": 24000,
  "graphs": {
    "text_encoder":       { "file": "graphs/text_encoder.onnx",       "sha256": "<...>", "size_bytes": 0 },
    "duration_predictor": { "file": "graphs/duration_predictor.onnx", "sha256": "<...>", "size_bytes": 0 },
    "vector_estimator":   { "file": "graphs/vector_estimator.onnx",   "sha256": "<...>", "size_bytes": 0 },
    "codec_decoder":      { "file": "graphs/codec_decoder.onnx",      "sha256": "<...>", "size_bytes": 0 }
  },
  "input_contract": {
    "ctx_mask_dtype": "bool",
    "voice_speaker_emb_dim": 192,
    "voice_style_shape": [50, 256]
  },
  "generation_defaults": { "steps": 8, "sway": -1.0, "cfg": 3.0, "max_chars_per_chunk": 140 },
  "compatibility": {
    "runtime_revision_min": "ort-mobile-1.22",
    "sdk_revision_min": "0.1.0"
  },
  "release": {
    "released_at": "2026-00-00T00:00:00Z",
    "channel": "beta",
    "rollback_to": "0.0.9",
    "known_limitations": [
      "NNAPI p95 vượt target trên thiết bị đã đo — không chọn làm mặc định",
      "CoreML không tương thích vector_estimator (Metal texture limit) — chờ static-shape graph"
    ]
  }
}
```

Quy tắc:
- **Pin** `base_model_revision` + `main_base_commit` — hai trường này gắn gói với lịch sử git/HF (điều kiện G0, H11).
- `compatibility` là hợp đồng **từ chối sớm**: app không thỏa → lỗi `E_COMPAT` trước khi load bất kỳ trọng số nào.
- Mọi graph/asset đều có `sha256` riêng; file `checksums.txt` bên ngoài để tự kiểm integer.

## 3.3. Voice asset schema v0.1

`voices_v3_nano_mobile.json` — **index** (metadata nhẹ, giữ luôn trong RAM):

```json
{
  "schema_version": "1",
  "default_voice_id": "duc_tri",
  "voices": [
    {
      "voice_id": "duc_tri",
      "display_name": "Đức Trí",
      "locale": "vi-VN",
      "gender": "male",
      "style_hint": "news",
      "quality_tier": 1,
      "files": { "speaker_emb": "voices/duc_tri/speaker_emb.bin", "style": "voices/duc_tri/style.npy" },
      "sha256": "<checksum speaker_emb+style>",
      "wer_reference": 0.0312,
      "license": "<license-id>",
      "provenance": "official"
    }
  ]
}
```

Nguyên tắc:

1. **Base model + voice asset, không nhân bản model** (§14.1 summary). Voice asset chỉ là conditioning (speaker_emb 192 + style 50×256). Đổi giọng = đổi conditioning input, **không tạo session mới**.
2. **Giọng mặc định (D8):** "Đức Trí" (WER 3.12%) — trừ khi spike thay đổi quyết định. "Minh Quân" giữ trong danh sách nhưng không làm mặc định (WER 19.63%).
3. **Quality tier** trong index (tier 1: WER < 6%, tier 2: < 10%, tier 3: còn lại) — không dùng một ngưỡng trung bình cho 11 giọng; package 5–8 giọng tier 1–2 làm bundled, còn lại downloadable (quyết định boxing ở `09`).
4. `asset_revision` theo từng voice (bản voice riêng có version + `base_model_revision` nó tương thích). Asset sai revision → **từ chối rõ ràng**, không tự dùng model sai.
5. Manifest **không chứa raw reference audio** (H16 sau này mới có local enrollment — luồng riêng, có consent).

## 3.4. Install, verify, rollback (Yêu cầu H12/H15)

Chu trình mọi thay đổi package (cài, cập nhật, tải voice pack):

```text
1. Download/copy vào TEMP dir (không bao giờ ghi đè bản đang dùng)
2. Verify checksums.txt + signature
3. Kiểm tra compatibility (model_revision × runtime × sdk × abi)
4. Probe-load: tạo session thử cho 4 graph (bắt lỗi sớm trước khi "install xong")
5. Atomic rename/commit vào model dir versioned: models/v3-nano-mobile/<revision>/
6. Cập nhật pointer "active_revision" (1 file nhỏ, ghi atomic)
7. Nếu crash ở lần chạy đầu → rollback pointer về revision cũ
```

- Dir versioned theo `model_revision` + cache path có revision → app-update giữa chương không dính asset cũ (H12).
- Tình huống đã chứng minh ORT có exception catchable: file truncate/corrupt (`InvalidProtobuf`), missing (`NoSuchFile`), provider invalid (tự fallback CPU) — SDK wrap thành lỗi có mã (bảng mã đầy đủ ở `08-ci-cd-phat-hanh.md`), **không bao giờ** để exception thô rơi tới app layer.
- Disk full / interrupt download: phần tạm bị dọn tự động; trạng thái registry nhất quán vì commit chỉ xảy ra ở bước 5.

## 3.5. License/attribution inventory

Trước khi phân phối **bất kỳ** artifact nào (kể cả beta), phải có inventory:

| Artifact | Nguồn | License | Được phân phối? |
|---|---|---|---|
| 4 graph ONNX (FP16 từ v3 Nano) | HF `pnnbao-ump/VieNeu-TTS-v3-Nano` | ghi theo mô hình gốc | xác nhận lại legal |
| `vo_codes.bin` / tokenizer | repo VieNeu | theo repo | cần xác nhận |
| 11 voice asset | `src/vieneu/assets/voices_v3_nano.json` | theo repo | cần xác nhận |
| ONNX Runtime Mobile | download.onnxruntime.ai | MIT | ✅ |
| Corpus smoke/stress | nội bộ CC0 + tái dùng repo | ghi trong `v3nano-mobile/eval/corpus/manifest.json` | nội bộ |

Mọi file phân phối phải kèm attribution text tương ứng. Đây là điều kiện DoD MVP (§11 summary), không phải việc cuối sprint.

## 3.6. Thay đổi schema sau này (migration contract)

- Mọi thay đổi manifest/schema phải bump `schema_version`/`asset_revision` → không âm thầm thay field cũ.
- Trường cũ deprecated: giữ 1 revision đọc ngược, ghi warning → xóa ở revision +1.
- Voice asset mới (pack download sau MVP) dùng **cùng schema** này + `provenance` + `license`, không fork schema.
- Chunk-pipeline contract (`max_chars=140`, gap, filter) ghi trong manifest `input_contract`/`generation_defaults` → thay đổi = bump model revision (vì đổi audio contract).