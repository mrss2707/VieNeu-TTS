#!/bin/zsh
# Baseline: upstream sea-g2p v0.10.0 C ABI (no python), size-optimised, stripped.
set -e
export PATH="$HOME/.cargo/bin:/opt/homebrew/bin:$PATH"
NDK=/opt/homebrew/share/android-commandlinetools/ndk/27.0.12077973/toolchains/llvm/prebuilt/darwin-x86_64/bin
[ -d "$NDK" ] || NDK=$(ls -d /opt/homebrew/share/android-commandlinetools/ndk/27.0.12077973/toolchains/llvm/prebuilt/*/bin | head -1)
cd "$(dirname "$0")/src-upstream"
export CARGO_PROFILE_RELEASE_LTO=true CARGO_PROFILE_RELEASE_STRIP=true CARGO_PROFILE_RELEASE_CODEGEN_UNITS=1
F=(--release --lib --no-default-features --features capi)
echo "== host macOS arm64 (cdylib)"; cargo build "${F[@]}" --target aarch64-apple-darwin
echo "== android arm64 (cdylib)"
CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$NDK/aarch64-linux-android24-clang CC_aarch64_linux_android=$NDK/aarch64-linux-android24-clang \
  cargo build "${F[@]}" --target aarch64-linux-android
echo "== ios device (staticlib)"; cargo rustc "${F[@]}" --target aarch64-apple-ios --crate-type staticlib
echo "== ios sim (staticlib)"; cargo rustc "${F[@]}" --target aarch64-apple-ios-sim --crate-type staticlib
echo "== sizes"
ls -l target/aarch64-apple-darwin/release/libsea_g2p_rs.dylib target/aarch64-linux-android/release/libsea_g2p_rs.so target/aarch64-apple-ios/release/libsea_g2p_rs.a target/aarch64-apple-ios-sim/release/libsea_g2p_rs.a | awk '{printf "%8.2f MB  %s\n",$5/1e6,$9}'
