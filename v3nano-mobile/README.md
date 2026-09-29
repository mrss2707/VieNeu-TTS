# v3nano-mobile — VieNeu-TTS v3 Nano Mobile

Toàn bộ code + dữ liệu của **v3 Nano Mobile** nằm dưới một root này.
Mục đích: gọn, dễ tìm, và chỉ chạm một cổng khi merge `main` → `develop`.

Nguồn sự thật quyết định: `docs/_supermeo/_summary/001-v3-nano-mobile.md`.

## Cây thư mục

```text
v3nano-mobile/
  android/
    sdk/                  # SẢN PHẨM — Kotlin core thuần JVM (com.vieneu.mobile)
    device-validation/    # APP kiểm chứng engine trên device/emulator (không phải SDK)
  ios/
    device-validation/    # APP kiểm chứng engine (ORT C++ API)
  host/                   # Python phía host: export, convert FP16, build/validate package, ASR
  package/                # Model package P1 (manifest/build output; *.onnx → gitignore)
  eval/                   # TẤT CẢ dữ liệu đánh giá
    corpus/               #   corpus-mvp-v1 đã freeze (40 case + sha256)
    golden/               #   contract golden (nguồn sự thật cho port Kotlin/C++)
    reports/              #   report hiện hành (dùng ra quyết định)
    reports-archive/      #   report superseded (lịch sử, không dùng ra quyết định)
    listening-tests/      #   html + manifest; payload base64 WAV không commit
  tests/                  # Contract tests Python (pytest, xem pyproject testpaths)
```

## Vai trò từng phần

| Phần | Là gì | Không phải |
|---|---|---|
| `android/sdk`, (sau này `ios/sdk`) | SDK mobile sản phẩm | không phải app |
| `*/device-validation` | App + XCTest/JUnit validate 4 graph ONNX trên máy | không phải SDK, không ship |
| `host/` | Tool CLI chạy trên macOS/Linux | không chạy trên device |
| `package/` | Artifact model P1 (manifest, checksums) | không commit weights |
| `eval/` | Corpus, golden, report | không phải source code |

## Quy ước

- **Không commit model weights** (`*.onnx`, `constants.npz`, voice `.bin`) — xem `.gitignore` root.
- **Report hiện hành** = `eval/reports/`; file superseded để `eval/reports-archive/`.
- Namespace Kotlin: SDK `com.vieneu.mobile`; device-validation `com.vieneu.mobile.validation`.
- Regenerate assets cho harness: xem README trong `android/device-validation/` và `ios/device-validation/`.
