#!/usr/bin/env bash
# Build the CPU-only Java native bridge against the same core and generated configuration.
set -eu
src=$(realpath "$1")
build=$(realpath "$2")
output=$(realpath "$3")
bin="$ANDROID_HOME/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64/bin"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/headers" "$tmp/classes"
printf '%s\n' '{ global: JNI_OnLoad; Java_*; local: *; };' > "$tmp/exports.map"
find "$src/java/src/main/java" "$src/java/src/main/android" -name '*.java' -print0 |
    xargs -0 javac --release 11 -h "$tmp/headers" -d "$tmp/classes"
"$bin/aarch64-linux-android34-clang" -std=c11 -Oz -DNDEBUG -fPIC -shared \
    -I"$build" -I"$src/include" -I"$src/include/onnxruntime/core/session" \
    -I"$src/orttraining/orttraining/training_api/include" -I"$tmp/headers" \
    "$src"/java/src/main/native/*.c -L"$output" -lonnxruntime -ldl -lm \
    -Wl,--version-script="$tmp/exports.map",-z,defs,-z,max-page-size=16384,-soname,libonnxruntime4j_jni.so \
    -o "$output/libonnxruntime4j_jni.so"
"$bin/llvm-strip" --strip-unneeded "$output/libonnxruntime4j_jni.so"
