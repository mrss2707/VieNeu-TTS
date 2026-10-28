#!/bin/zsh
# Build the JNI library (arm64-v8a) + host dylib for JVM tests, convert the dictionary,
# and stage everything for android/sdk-g2p. Outputs are git-ignored build artifacts.
#
# usage: build_android.sh [path/to/sea_g2p.bin]   (default: .venv sea_g2p package)
set -e
export PATH="$HOME/.cargo/bin:/opt/homebrew/bin:$PATH"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
SDK_G2P="$HERE/../../android/sdk-g2p"
NDK=$(ls -d /opt/homebrew/share/android-commandlinetools/ndk/*/toolchains/llvm/prebuilt/*/bin | tail -1)
BIN="${1:-$(ls "$ROOT"/.venv/lib/python*/site-packages/sea_g2p/sea_g2p.bin | head -1)}"

cd "$HERE/src-upstream"
# No panic=abort: capi.rs relies on catch_unwind so a Rust panic becomes an error, not a crash.
export CARGO_PROFILE_RELEASE_LTO=true CARGO_PROFILE_RELEASE_STRIP=true CARGO_PROFILE_RELEASE_CODEGEN_UNITS=1
F=(--release --lib --no-default-features --features "jni seaz")

echo "== android arm64-v8a"
CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$NDK/aarch64-linux-android24-clang \
CC_aarch64_linux_android=$NDK/aarch64-linux-android24-clang AR_aarch64_linux_android=$NDK/llvm-ar \
  cargo build "${F[@]}" --target aarch64-linux-android
mkdir -p "$SDK_G2P/src/main/jniLibs/arm64-v8a"
cp target/aarch64-linux-android/release/libsea_g2p_rs.so "$SDK_G2P/src/main/jniLibs/arm64-v8a/"

echo "== host (macOS arm64) for JVM parity tests"
cargo build "${F[@]}" --target aarch64-apple-darwin
mkdir -p "$HERE/out/host"
cp target/aarch64-apple-darwin/release/libsea_g2p_rs.dylib "$HERE/out/host/"

echo "== dictionary -> SEAZ"
[ -x /tmp/seaz-venv/bin/python ] || { python3 -m venv /tmp/seaz-venv && /tmp/seaz-venv/bin/pip install -q zstandard; }
mkdir -p "$SDK_G2P/src/main/assets/sea_g2p"
/tmp/seaz-venv/bin/python "$HERE/tools/seap_to_seaz.py" "$BIN" "$SDK_G2P/src/main/assets/sea_g2p/vi.seaz" 64
cp "$SDK_G2P/src/main/assets/sea_g2p/vi.seaz" "$HERE/out/host/vi.seaz"
ls -l "$SDK_G2P/src/main/jniLibs/arm64-v8a/" "$SDK_G2P/src/main/assets/sea_g2p/"
