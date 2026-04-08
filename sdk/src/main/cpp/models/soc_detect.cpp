#include "inference_engine.h"
#include "onnx_backend.h"

#ifdef __ANDROID__
#include <sys/system_properties.h>
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "Speech", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) do { fprintf(stderr, "[speech] "); fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while(0)
#endif

#include <string>

enum class SocVendor { GOOGLE_TENSOR, QUALCOMM, SAMSUNG, MEDIATEK, UNKNOWN };

static SocVendor detect_soc() {
#ifdef __ANDROID__
    char value[92] = {};

    // Google Tensor: ro.hardware.chipname starts with "gs" or "zuma"
    __system_property_get("ro.hardware.chipname", value);
    std::string chipname(value);
    if (chipname.find("gs") == 0 || chipname.find("zuma") == 0) {
        LOGI("SoC: Google Tensor (%s)", chipname.c_str());
        return SocVendor::GOOGLE_TENSOR;
    }

    // Qualcomm: ro.board.platform starts with "msm", "sm", "sdm"
    __system_property_get("ro.board.platform", value);
    std::string platform(value);
    if (platform.find("msm") == 0 || platform.find("sm") == 0 ||
        platform.find("sdm") == 0 || platform.find("lahaina") != std::string::npos ||
        platform.find("taro") != std::string::npos || platform.find("kalama") != std::string::npos ||
        platform.find("pineapple") != std::string::npos || platform.find("sun") != std::string::npos) {
        LOGI("SoC: Qualcomm (%s)", platform.c_str());
        return SocVendor::QUALCOMM;
    }

    // Samsung Exynos
    __system_property_get("ro.hardware", value);
    std::string hardware(value);
    if (hardware.find("exynos") != std::string::npos) {
        LOGI("SoC: Samsung Exynos (%s)", hardware.c_str());
        return SocVendor::SAMSUNG;
    }

    LOGI("SoC: Unknown (chipname=%s, platform=%s, hardware=%s)",
         chipname.c_str(), platform.c_str(), hardware.c_str());
#endif
    return SocVendor::UNKNOWN;
}

Backend detect_optimal_backend() {
    SocVendor soc = detect_soc();
    switch (soc) {
#ifdef SPEECH_LITERT
        case SocVendor::GOOGLE_TENSOR:
            return Backend::LITERT;
#endif
        default:
            return Backend::ONNX;
    }
}

std::unique_ptr<InferenceBackend> create_backend(Backend preference) {
    Backend actual = preference;
    if (actual == Backend::AUTO) {
        actual = detect_optimal_backend();
    }

#ifdef SPEECH_LITERT
    if (actual == Backend::LITERT) {
        // LiteRT backend will be implemented in litert_backend.cpp
        // For now, fall back to ONNX
        LOGI("LiteRT backend not yet available, using ONNX");
        actual = Backend::ONNX;
    }
#endif

    if (actual == Backend::LITERT) {
        LOGI("LiteRT requested but not compiled in, using ONNX");
        actual = Backend::ONNX;
    }

    LOGI("Inference backend: ONNX Runtime");
    return std::make_unique<OnnxBackend>();
}
