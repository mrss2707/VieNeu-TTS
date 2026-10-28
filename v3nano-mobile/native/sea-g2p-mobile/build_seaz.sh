#!/bin/zsh
# Variant B: capi + seaz (zstd block-compressed vi-only dictionary).
set -e
export PATH="$HOME/.cargo/bin:/opt/homebrew/bin:$PATH"
NDK=$(ls -d /opt/homebrew/share/android-commandlinetools/ndk/27.0.12077973/toolchains/llvm/prebuilt/*/bin | head -1)
cd "$(dirname "$0")/src-upstream"
export CARGO_PROFILE_RELEASE_LTO=true CARGO_PROFILE_RELEASE_STRIP=true CARGO_PROFILE_RELEASE_CODEGEN_UNITS=1
F=(--release --lib --no-default-features --features "capi seaz")
echo "== android arm64"
CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$NDK/aarch64-linux-android24-clang \
CC_aarch64_linux_android=$NDK/aarch64-linux-android24-clang AR_aarch64_linux_android=$NDK/llvm-ar \
  cargo build "${F[@]}" --target aarch64-linux-android
echo "== ios device";  cargo rustc "${F[@]}" --target aarch64-apple-ios --crate-type staticlib
echo "== ios sim";     cargo rustc "${F[@]}" --target aarch64-apple-ios-sim --crate-type staticlib
ls -l target/aarch64-linux-android/release/libsea_g2p_rs.so target/aarch64-apple-ios/release/libsea_g2p_rs.a | awk '{printf "%8.2f MB  %s\n",$5/1e6,$9}'
