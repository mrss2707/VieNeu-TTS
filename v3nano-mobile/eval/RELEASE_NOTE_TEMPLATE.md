# Release note template — v3-nano-mobile

Điều kiện phát hành MVP (07 §7.8 / 02): L0+L1 100% xanh · L2 hard-fail xanh ·
L3 tối thiểu trên devices khai báo · quality pipeline · listening review ký ·
license inventory. Mục không chạy được → **NOT RUN + lý do** (không bịa số).

## 1. Revisions (bắt buộc đủ 3 lớp — 01 §1.1)

| Lớp | Giá trị |
|---|---|
| model_revision | `vnm-0.1.0` |
| base_model_revision | `pnnbao-ump/VieNeu-TTS-v3-Nano@aba295eb…` |
| runtime_revision | `ort-1.22.0-android` / `ort-1.24.x-ios` |
| sdk_revision | `vieneu-mobile-sdk-0.x.y` |
| main_base_commit | `c1390ab` |
| develop_revision | `<git rev-parse HEAD>` |

## 2. Package

| Hạng mục | Giá trị |
|---|---|
| shape | P1 preset-only, 4 graph |
| precision | FP16 (keep_io_types) |
| raw / gzip | … MB / … MB (gate ≤150 MB gzip) |
| checksums.txt | sha256 tất cả file nội bộ |
| default_voice_id | `duc_tri` |
| generation_defaults | steps=8, sway=-1, cfg=3.0, max_chars=140, treble on |

## 3. Devices (L3)

| Platform | Device | Tier | RTF median / p95 | Peak RSS | Thermal | Status |
|---|---|---|---|---|---|---|
| Android | … | A/B | … | … | … | PASS / NOT RUN |
| iOS | … | A/B | … | … | … | PASS / NOT RUN |

## 4. Quality

| Config | WER | CER | vs baseline 9.86 / 7.12 |
|---|---:|---:|---|
| candidate | | | ± … |

Listening review (≥2 người): ký + link artifact.

## 5. Known limitations

- NNAPI / CoreML không phải default (D2/D3).
- sea-g2p normalize/G2P chưa on-device — text pipeline dùng pre-phonemize / mini normalizer (2b).
- ORT iOS BOOL `ctx_mask` cần C++ bridge (không dùng ObjC wrapper).

## 6. Rollback

- Artifact rollback: `<url hoặc path>`
- Active revision pointer + `active_revision.prev` (ModelRegistry)

## 7. Checklist trước khi tag

- [ ] pytest baseline + v3nano-mobile/tests xanh
- [ ] SDK JVM unit tests xanh
- [ ] validate_mobile_package PASS (checksums + probe-load)
- [ ] Device reports JSON trong `eval/reports/` hoặc NOT RUN có lý do
- [ ] License / attribution inventory
- [ ] Không commit weights / ORT binary / `local.properties`
