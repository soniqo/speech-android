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

ort_headers_ready() {
    grep -qs '^#define ORT_API_VERSION ' "${ORT_DIR}/include/onnxruntime_c_api.h" &&
        grep -qs '^struct OrtEpApi {' "${ORT_DIR}/include/onnxruntime_ep_c_api.h"
}

if [ "${ORT_INSTALLED_VERSION}" != "${ORT_VERSION}" ] || ! ort_headers_ready; then
    echo "Downloading ONNX Runtime ${ORT_VERSION}..."
    rm -rf "$ORT_DIR"

    TMP_DIR=$(mktemp -d)
    AAR_FILE="${TMP_DIR}/onnxruntime.aar"

    curl --fail --location --output "$AAR_FILE" "$ORT_URL"

    echo "Extracting..."
    mkdir -p "${ORT_DIR}/include"

    # AAR is a ZIP — extract native libs and headers
    cd "$TMP_DIR"
    unzip -q "$AAR_FILE"

    # Headers (from the AAR's headers/ directory or from GitHub release)
    # The AAR bundles headers under headers/
    if [ -d "headers" ]; then
        cp -R headers/. "${ORT_DIR}/include/"
    fi

    # If headers aren't in AAR, download them separately. Newer ORT C headers
    # include companion headers from the same directory.
    if ! ort_headers_ready; then
        for header in \
            onnxruntime_c_api.h \
            onnxruntime_ep_c_api.h \
            onnxruntime_float16.h; do
            HEADER_URL="https://raw.githubusercontent.com/microsoft/onnxruntime/v${ORT_VERSION}/include/onnxruntime/core/session/${header}"
            curl --fail --location --output "${ORT_DIR}/include/${header}" "$HEADER_URL"
        done
    fi
    if ! ort_headers_ready; then
        echo "ERROR: invalid ONNX Runtime ${ORT_VERSION} headers" >&2
        exit 1
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

# --- CMake ---

# The native build pins an exact CMake in sdk/build.gradle.kts. AGP downloads a
# missing platform or build-tools on its own, but never CMake: an absent version
# fails configuration with "[CXX1300] CMake '<version>' was not found in SDK,
# PATH, or by cmake.dir property". Install it here so a fresh checkout and CI
# both get it from the one place that already bootstraps this project.

CMAKE_VERSION="$(sed -n 's/^[[:space:]]*version = "\([0-9.]*\)".*/\1/p' \
    "${ROOT}/sdk/build.gradle.kts" | head -1)"

if [ -z "${CMAKE_VERSION}" ]; then
    echo "WARNING: could not read the CMake version from sdk/build.gradle.kts; skipping."
else
    SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -z "${SDK_ROOT}" ] && [ -f "${ROOT}/local.properties" ]; then
        SDK_ROOT="$(sed -n 's/^sdk\.dir=//p' "${ROOT}/local.properties" | head -1)"
    fi

    if [ -n "${SDK_ROOT}" ] && [ -d "${SDK_ROOT}/cmake/${CMAKE_VERSION}" ]; then
        echo "CMake ${CMAKE_VERSION} already installed"
    else
        SDKMANAGER=""
        for candidate in \
            "${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager" \
            "${SDK_ROOT}/cmdline-tools/bin/sdkmanager" \
            "${SDK_ROOT}/tools/bin/sdkmanager"; do
            if [ -n "${SDK_ROOT}" ] && [ -x "${candidate}" ]; then
                SDKMANAGER="${candidate}"
                break
            fi
        done
        if [ -z "${SDKMANAGER}" ] && command -v sdkmanager >/dev/null 2>&1; then
            SDKMANAGER="$(command -v sdkmanager)"
        fi

        if [ -z "${SDKMANAGER}" ]; then
            echo "WARNING: sdkmanager not found. Install CMake ${CMAKE_VERSION} yourself,"
            echo "         or the native build will fail with CXX1300."
        else
            echo "Installing CMake ${CMAKE_VERSION}..."
            # `yes` feeds the license prompt on a runner that has not accepted it.
            # It is killed by SIGPIPE once sdkmanager stops reading, so read the
            # installer's own status out of PIPESTATUS rather than the pipeline's.
            set +e
            if [ -n "${SDK_ROOT}" ]; then
                yes | "${SDKMANAGER}" --sdk_root="${SDK_ROOT}" "cmake;${CMAKE_VERSION}" >/dev/null
                CMAKE_STATUS=${PIPESTATUS[1]}
            else
                yes | "${SDKMANAGER}" "cmake;${CMAKE_VERSION}" >/dev/null
                CMAKE_STATUS=${PIPESTATUS[1]}
            fi
            set -e
            if [ "${CMAKE_STATUS}" -ne 0 ]; then
                echo "ERROR: sdkmanager could not install CMake ${CMAKE_VERSION} (exit ${CMAKE_STATUS})."
                exit 1
            fi
            echo "CMake ${CMAKE_VERSION} installed"
        fi
    fi
fi

echo ""
echo "Done. Open the project in Android Studio or run:"
echo "  ./gradlew :app:assembleDebug"
echo "  ./gradlew :control-demo:assembleDebug"
