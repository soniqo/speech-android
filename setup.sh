#!/bin/bash
set -euo pipefail

# Setup script for speech-android development environment.
# Downloads ONNX Runtime and initializes the speech-core submodule.

ORT_VERSION="1.27.0"
ORT_URL="https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/${ORT_VERSION}/onnxruntime-android-${ORT_VERSION}.aar"

# LiteRT (TFLite) runtime — Google's libLiteRt C API, matching speech-core's
# third_party/litert/ headers. 2.1.5 ships libLiteRt.so per ABI on Google Maven.
LITERT_VERSION="2.1.5"
LITERT_URL="https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert/${LITERT_VERSION}/litert-${LITERT_VERSION}.aar"

ROOT="$(cd "$(dirname "$0")" && pwd)"
ORT_DIR="${ROOT}/ort"
LITERT_DIR="${ROOT}/litert"

echo "=== speech-android setup ==="

# --- speech-core submodule ---

if [ ! -f "${ROOT}/speech-core/CMakeLists.txt" ]; then
    echo "Adding speech-core submodule..."
    cd "$ROOT"
    git submodule add https://github.com/soniqo/speech-core.git speech-core 2>/dev/null || true
    git submodule update --init --recursive
fi

# --- ONNX Runtime ---

ORT_INSTALLED_VERSION=""
if [ -f "${ORT_DIR}/version.txt" ]; then
    ORT_INSTALLED_VERSION="$(cat "${ORT_DIR}/version.txt")"
fi

if [ ! -f "${ORT_DIR}/include/onnxruntime_c_api.h" ] || \
   [ ! -f "${ORT_DIR}/include/onnxruntime_ep_c_api.h" ] || \
   [ "${ORT_INSTALLED_VERSION}" != "${ORT_VERSION}" ]; then
    echo "Downloading ONNX Runtime ${ORT_VERSION}..."
    rm -rf "$ORT_DIR"

    TMP_DIR=$(mktemp -d)
    AAR_FILE="${TMP_DIR}/onnxruntime.aar"

    curl -L -o "$AAR_FILE" "$ORT_URL"

    echo "Extracting..."
    mkdir -p "$ORT_DIR"

    # AAR is a ZIP — extract native libs and headers
    cd "$TMP_DIR"
    unzip -q "$AAR_FILE"

    # Headers (from the AAR's headers/ directory or from GitHub release)
    # The AAR bundles headers under headers/
    if [ -d "headers" ]; then
        cp -r headers/* "${ORT_DIR}/include/" 2>/dev/null || true
    fi

    # If headers aren't in AAR, download them separately. Newer ORT C headers
    # include companion headers from the same directory.
    if [ ! -f "${ORT_DIR}/include/onnxruntime_c_api.h" ] || \
       [ ! -f "${ORT_DIR}/include/onnxruntime_ep_c_api.h" ]; then
        mkdir -p "${ORT_DIR}/include"
        for header in \
            onnxruntime_c_api.h \
            onnxruntime_ep_c_api.h \
            onnxruntime_float16.h; do
            HEADER_URL="https://raw.githubusercontent.com/microsoft/onnxruntime/v${ORT_VERSION}/include/onnxruntime/core/session/${header}"
            curl -L -o "${ORT_DIR}/include/${header}" "$HEADER_URL"
        done
    fi

    # Native shared libraries
    mkdir -p "${ORT_DIR}/lib"
    for abi in arm64-v8a armeabi-v7a x86 x86_64; do
        if [ -d "jni/${abi}" ]; then
            mkdir -p "${ORT_DIR}/lib/${abi}"
            cp jni/${abi}/*.so "${ORT_DIR}/lib/${abi}/"
        fi
    done

    echo "${ORT_VERSION}" > "${ORT_DIR}/version.txt"
    rm -rf "$TMP_DIR"
    echo "ONNX Runtime installed to ${ORT_DIR}"
else
    echo "ONNX Runtime ${ORT_VERSION} already installed"
fi

# --- LiteRT runtime (libLiteRt) ---

if [ ! -f "${LITERT_DIR}/arm64-v8a/libLiteRt.so" ]; then
    echo "Downloading LiteRT ${LITERT_VERSION}..."

    TMP_DIR=$(mktemp -d)
    AAR_FILE="${TMP_DIR}/litert.aar"
    curl -L -o "$AAR_FILE" "$LITERT_URL"

    cd "$TMP_DIR"
    unzip -q "$AAR_FILE"

    # libLiteRt.so per ABI (+ optional GPU accelerator, runtime-loaded).
    for abi in arm64-v8a armeabi-v7a x86 x86_64; do
        if [ -f "jni/${abi}/libLiteRt.so" ]; then
            mkdir -p "${LITERT_DIR}/${abi}"
            cp "jni/${abi}/libLiteRt.so" "${LITERT_DIR}/${abi}/"
            [ -f "jni/${abi}/libLiteRtClGlAccelerator.so" ] && \
                cp "jni/${abi}/libLiteRtClGlAccelerator.so" "${LITERT_DIR}/${abi}/"
        fi
    done

    rm -rf "$TMP_DIR"
    echo "LiteRT installed to ${LITERT_DIR}"
else
    echo "LiteRT already installed"
fi

echo ""
echo "Done. Open the project in Android Studio or run:"
echo "  ./gradlew :app:assembleDebug"
