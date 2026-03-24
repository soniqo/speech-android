#pragma once

#include <onnxruntime_c_api.h>
#include <stdexcept>
#include <string>

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "Speech"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) do { fprintf(stderr, "[speech] "); fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while(0)
#define LOGE(...) do { fprintf(stderr, "[speech ERROR] "); fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while(0)
#endif

inline void ort_check(const OrtApi* api, OrtStatus* status) {
    if (status != nullptr) {
        const char* msg = api->GetErrorMessage(status);
        std::string err(msg);
        api->ReleaseStatus(status);
        throw std::runtime_error("ORT: " + err);
    }
}

/// Singleton ONNX Runtime environment shared across all models.
class OnnxEngine {
public:
    static OnnxEngine& get() {
        static OnnxEngine instance;
        return instance;
    }

    const OrtApi* api() const { return api_; }
    OrtEnv* env() const { return env_; }

    OrtSession* load(const std::string& path, bool nnapi = true) {
        OrtSessionOptions* opts = nullptr;
        ort_check(api_, api_->CreateSessionOptions(&opts));
        api_->SetSessionGraphOptimizationLevel(opts, ORT_ENABLE_ALL);
        api_->SetIntraOpNumThreads(opts, 2);

        if (nnapi) {
#ifdef __ANDROID__
            // NNAPI accelerates on Qualcomm Hexagon DSP / Samsung NPU
            const char* keys[] = {"nnapi_flags"};
            const char* values[] = {"0"};
            OrtStatus* s = api_->SessionOptionsAppendExecutionProvider(
                opts, "NNAPI", keys, values, 1);
#else
            // QNN EP accelerates on Qualcomm Hexagon DSP (automotive/embedded)
            const char* keys[] = {"backend_path"};
            const char* values[] = {"libQnnHtp.so"};
            OrtStatus* s = api_->SessionOptionsAppendExecutionProvider(
                opts, "QNN", keys, values, 1);
#endif
            if (s != nullptr) {
                LOGI("Hardware EP unavailable, using CPU");
                api_->ReleaseStatus(s);
            }
        }

        OrtSession* session = nullptr;
        ort_check(api_, api_->CreateSession(env_, path.c_str(), opts, &session));
        api_->ReleaseSessionOptions(opts);
        return session;
    }

    OrtMemoryInfo* cpu_memory() const { return mem_; }

    ~OnnxEngine() {
        if (mem_) api_->ReleaseMemoryInfo(mem_);
        if (env_) api_->ReleaseEnv(env_);
    }

private:
    OnnxEngine() {
        api_ = OrtGetApiBase()->GetApi(ORT_API_VERSION);
        ort_check(api_, api_->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "speech", &env_));
        ort_check(api_, api_->CreateCpuMemoryInfo(
            OrtArenaAllocator, OrtMemTypeDefault, &mem_));
    }

    OnnxEngine(const OnnxEngine&) = delete;
    OnnxEngine& operator=(const OnnxEngine&) = delete;

    const OrtApi* api_ = nullptr;
    OrtEnv* env_ = nullptr;
    OrtMemoryInfo* mem_ = nullptr;
};
