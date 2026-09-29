# DeviceValidation (iOS)

Validation harness for `docs/_supermeo/_summary/002-v3-nano-mobile-validation.md` — proves
the four v3 Nano core ONNX graphs (`text_encoder`, `duration_predictor`, `vector_estimator`,
`codec_decoder`) load and run real inference (16-step Euler flow + CFG=3.0 + codec decode)
through ONNX Runtime's C++ API on an iOS Simulator (arm64 slice), across CPU/CoreML EP, on
the full `v3nano-mobile/eval/corpus/{smoke,stress}.jsonl` corpus. Not the mobile SDK.

Latest run (2026-09-28, multi-provider, full corpus): `v3nano-mobile/eval/reports/2026-09-28-ios-simulator-multi-provider.json`,
evidence in `evidence/`. Earlier single-case run: `2026-09-28-ios-simulator-fp32.json`.

Uses the ORT **C++ API directly** (not the Objective-C wrapper shipped by
`microsoft/onnxruntime-swift-package-manager`): that wrapper does not expose a BOOL
tensor element type as of 1.24.2 (see its GitHub issues #31, #41), and `ctx_mask` here is
BOOL. CoreML EP is added the same way, via `OrtSessionOptionsAppendExecutionProvider_CoreML`
(`coreml_provider_factory.h`, vendored in the same pod archive).

## Regenerate the vendored files (not committed — see .gitignore)

```bash
# ORT xcframework (~81MB, simulator slice only)
curl -L -o /tmp/ort_pod.zip https://download.onnxruntime.ai/pod-archive-onnxruntime-c-1.24.2.zip
unzip -q /tmp/ort_pod.zip -d /tmp/ort_pod_extracted
mkdir -p Frameworks
cp -R /tmp/ort_pod_extracted/onnxruntime.xcframework Frameworks/
rm -rf Frameworks/onnxruntime.xcframework/macos-arm64_x86_64 Frameworks/onnxruntime.xcframework/ios-arm64
# then edit Frameworks/onnxruntime.xcframework/Info.plist to list only the
# ios-arm64_x86_64-simulator AvailableLibraries entry (see git history for the exact plist)

# model graphs — same four .onnx files as v3nano-mobile/android/device-validation, copied to
# DeviceValidation/nano_graphs/

# corpus input tensors (shared with the Android harness). Each case exports
# chunks_meta.json + one chunk<k>/{ids.bin,meta.json} per chunk (sentences over
# max_chars=140 — s07, st17 in this corpus — split into multiple chunks, same as
# V3NanoVieNeuTTS.infer() does; runInferenceMultiChunkWithCaseDir:... in
# NanoEngineTests.mm joins them back with EdgeSilence/PausePadSamples/JoinAudioChunks,
# ported from src/vieneu_utils/core_utils.py — see docs/_supermeo/_summary/001-v3-nano-mobile.md §0.8).
cd /path/to/repo/root
uv run --no-sync python v3nano-mobile/host/export_corpus_tensors.py
rm -rf v3nano-mobile/ios/device-validation/DeviceValidation/nano_test_input/corpus/cases
cp -R v3nano-mobile/host/_exported_corpus_tensors/* v3nano-mobile/ios/device-validation/DeviceValidation/nano_test_input/corpus/
```

## Build & run

```bash
brew install xcodegen  # if not already installed
xcodegen generate
xcrun simctl boot <device-udid>   # headless, no Simulator.app needed; xcrun simctl list devices
xcodebuild -project DeviceValidation.xcodeproj -scheme DeviceValidation \
  -destination 'id=<device-udid>' test
xcrun simctl shutdown <device-udid>
```

The single test method `testRunFullCorpusAcrossProviders` loads all 4 graphs twice (once per
provider: CPU, CoreML), runs all 20 smoke cases x3 repeats + all 20 stress cases x1, and logs
the full result as `RESULT_JSON_CHUNK_N` lines (NSLog truncates long lines — results are
chunked and must be reassembled, see `v3nano-mobile/host/` session history or write a small parser
matching the `RESULT_JSON_CHUNK_(\d+)=` prefix). Takes ~3.5 minutes total.
