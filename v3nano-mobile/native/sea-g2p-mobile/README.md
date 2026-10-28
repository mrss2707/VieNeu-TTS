# sea-g2p on mobile — spike (2026-10-03)

Upstream: pnnbao97/sea-g2p v0.10.0 (Apache-2.0), C ABI (`--features capi`) in `src-upstream/`.
Local changes: feature `seaz` (+ `zstd`), `src/core/seaz.rs`, `PhonemeDict` dispatch, lookups return `Cow<str>`.

## Results
| | SEAP (original .bin) | SEAZ (vi-only, zstd blocks=64) |
|---|---|---|
| Dictionary file | 62.83 MB | 9.41 MB (128 blocks: 8.78 MB) |
| Native lib android arm64 `.so` | 3.87 MB | 3.96 MB |
| iOS staticlib `.a` (device) | 15.15 MB | 15.76 MB (links dead-stripped in app) |
| Parity vs Python, 42 corpus lines | identical | identical |
| Parity, 160k random dict words (host) | n/a | 0 mismatches |
| OnePlus PHP110: open / first call / 42 lines | 0.3 ms / 113 ms / 158 ms | 3.2 ms / 93 ms / 154 ms |
| OnePlus peak RSS after run | 60 MB | 31 MB |

Dropped: Thai/Indonesian sections only (unused by the vi pipeline). All vi+en entries kept (lossless).

## Reproduce
`./build_baseline.sh`, `./build_seaz.sh`, `tools/seap_to_seaz.py in.bin out.seaz 64`,
`harness/parity.py <cli> <dict>`, `harness/parity_dense.py <cli> <orig.bin> <dict> 20000`.

## Not done
- Redmi M2006C3LG is 32-bit (armeabi-v7a): not tested; would need `armv7-linux-androideabi`.
- No JNI/Swift wrapper, no APK/IPA size measurement, no Flutter wiring.
- Thread-safety: handle is not thread-safe; SEAZ block cache is behind a Mutex.
