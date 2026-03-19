#!/bin/bash
set -euo pipefail

# Setup script for speech-android development environment.
# Downloads ONNX Runtime and initializes the speech-core submodule.

ORT_VERSION="1.19.0"
ORT_URL="https://github.com/microsoft/onnxruntime/releases/download/v${ORT_VERSION}/onnxruntime-android-${ORT_VERSION}.aar"

ROOT="$(cd "$(dirname "$0")" && pwd)"
ORT_DIR="${ROOT}/ort"

echo "=== speech-android setup ==="

# --- speech-core submodule ---

if [ ! -f "${ROOT}/speech-core/CMakeLists.txt" ]; then
    echo "Adding speech-core submodule..."
    cd "$ROOT"
    git submodule add https://github.com/soniqo/speech-core.git speech-core 2>/dev/null || true
    git submodule update --init --recursive
fi

# --- ONNX Runtime ---

if [ ! -f "${ORT_DIR}/include/onnxruntime_c_api.h" ]; then
    echo "Downloading ONNX Runtime ${ORT_VERSION}..."

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

    # If headers aren't in AAR, download them separately
    if [ ! -f "${ORT_DIR}/include/onnxruntime_c_api.h" ]; then
        mkdir -p "${ORT_DIR}/include"
        HEADER_URL="https://raw.githubusercontent.com/microsoft/onnxruntime/v${ORT_VERSION}/include/onnxruntime/core/session/onnxruntime_c_api.h"
        curl -L -o "${ORT_DIR}/include/onnxruntime_c_api.h" "$HEADER_URL"
    fi

    # Native shared libraries
    mkdir -p "${ORT_DIR}/lib"
    for abi in arm64-v8a armeabi-v7a x86 x86_64; do
        if [ -d "jni/${abi}" ]; then
            mkdir -p "${ORT_DIR}/lib/${abi}"
            cp jni/${abi}/*.so "${ORT_DIR}/lib/${abi}/"
        fi
    done

    rm -rf "$TMP_DIR"
    echo "ONNX Runtime installed to ${ORT_DIR}"
else
    echo "ONNX Runtime already installed"
fi

# --- .gitignore ---

cat > "${ROOT}/.gitignore" << 'GITIGNORE'
# Build
.gradle/
build/
*.iml
.idea/
local.properties

# ONNX Runtime (downloaded by setup.sh)
/ort/

# Native build artifacts
.cxx/
.externalNativeBuild/
GITIGNORE

echo ""
echo "Done. Open the project in Android Studio or run:"
echo "  ./gradlew :app:assembleDebug"
