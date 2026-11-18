#!/usr/bin/env bash
# Usage: ANDROID_HOME=/path/to/sdk build_runtime.sh /empty/build/directory
set -eu
tools_dir=$(cd "$(dirname "$0")" && pwd -P)
mkdir -p "$1"
cd "$1"
work_dir=$(pwd -P)
ndk="$ANDROID_HOME/ndk/29.0.14206865"
export PATH="$ANDROID_HOME/cmake/3.31.6/bin:$PATH"
git clone -n --filter=blob:none https://github.com/microsoft/onnxruntime.git ort
git -C ort checkout --detach f217402897f40ebba457e2421bc0a4702771968e
git clone -n --filter=blob:none https://gitlab.com/libeigen/eigen.git eigen
git -C eigen checkout --detach 1d8b82b0740839c0de7f1242a3585e3390ff5f33
# The arm64 FP16 convolution still needs this upstream helper with contrib kernels disabled.
sed -i '/^#include "contrib_ops\/cpu\/fused_activation.h"/a #ifdef DISABLE_CONTRIB_OPS\n#include "contrib_ops/cpu/fused_activation.cc"\n#endif' ort/onnxruntime/core/providers/cpu/fp16/fp16_conv.cc
# Keep the build host's prefix-map arguments out of the public runtime identification string.
sed -i '/string(APPEND ORT_BUILD_INFO ", cmake cxx flags:/d' ort/cmake/CMakeLists.txt
maps="-ffile-prefix-map=$work_dir=/src -ffile-prefix-map=$ANDROID_HOME=/android-sdk"
python3 ort/tools/ci_build/build.py --build_dir "$work_dir/build" --config MinSizeRel \
    --android --android_abi arm64-v8a --android_api 34 --android_sdk_path "$ANDROID_HOME" \
    --android_ndk_path "$ndk" --build_shared_lib --target onnxruntime \
    --include_ops_by_config "$tools_dir/operators.config" --disable_contrib_ops --disable_ml_ops \
    --skip_tests --skip_submodule_sync --parallel "${JOBS:-4}" --cmake_generator Ninja --update --build \
    --cmake_extra_defines CMAKE_POLICY_VERSION_MINIMUM=3.5 FETCHCONTENT_SOURCE_DIR_EIGEN3="$work_dir/eigen" \
    "CMAKE_C_FLAGS=$maps" "CMAKE_CXX_FLAGS=$maps"
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded \
    -o libonnxruntime.so build/MinSizeRel/libonnxruntime.so
"$tools_dir/build_jni.sh" ort build/MinSizeRel "$work_dir"
curl -fsSLo stock.aar https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.22.0/onnxruntime-android-1.22.0.aar
python3 "$tools_dir/package_runtime.py" stock.aar "$work_dir"
