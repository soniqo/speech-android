#!/bin/bash
set -euo pipefail

ORT_VERSION="1.19.0"
ARCH="${1:-$(uname -m)}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ORT_DIR="${ROOT}/ort-linux"

echo "=== speech-linux setup (${ARCH}) ==="

if [ ! -f "${ORT_DIR}/include/onnxruntime_c_api.h" ]; then
    echo "Downloading ONNX Runtime ${ORT_VERSION} for Linux ${ARCH}..."

    case "${ARCH}" in
        aarch64|arm64)
            ORT_URL="https://github.com/microsoft/onnxruntime/releases/download/v${ORT_VERSION}/onnxruntime-linux-aarch64-${ORT_VERSION}.tgz"
            ;;
        x86_64|amd64)
            ORT_URL="https://github.com/microsoft/onnxruntime/releases/download/v${ORT_VERSION}/onnxruntime-linux-x64-${ORT_VERSION}.tgz"
            ;;
        *)
            echo "Unsupported architecture: ${ARCH}"
            exit 1
            ;;
    esac

    TMP_DIR=$(mktemp -d)
    curl -L -o "${TMP_DIR}/ort.tgz" "${ORT_URL}"

    mkdir -p "${ORT_DIR}"
    tar xf "${TMP_DIR}/ort.tgz" -C "${TMP_DIR}"

    # Find extracted dir
    ORT_EXTRACTED=$(find "${TMP_DIR}" -maxdepth 1 -name "onnxruntime-*" -type d | head -1)

    mkdir -p "${ORT_DIR}/include" "${ORT_DIR}/lib"
    cp "${ORT_EXTRACTED}"/include/*.h "${ORT_DIR}/include/"
    cp "${ORT_EXTRACTED}"/lib/libonnxruntime.so* "${ORT_DIR}/lib/"

    rm -rf "${TMP_DIR}"
    echo "ONNX Runtime installed to ${ORT_DIR}"
else
    echo "ONNX Runtime already installed"
fi

echo ""
echo "Build with:"
echo "  cd linux && cmake -B build -DORT_DIR=${ORT_DIR} && cmake --build build"
