#include "speech.h"

#include <cassert>
#include <cstdio>
#include <cstring>
#include <cmath>
#include <string>
#include <vector>
#include <atomic>
#include <chrono>
#include <thread>

// ---------------------------------------------------------------------------
// Test helpers
// ---------------------------------------------------------------------------

static int tests_run = 0;
static int tests_passed = 0;

#define TEST(name) \
    static void test_##name(); \
    static struct Register_##name { \
        Register_##name() { test_funcs.push_back({#name, test_##name}); } \
    } reg_##name; \
    static void test_##name()

#define ASSERT(cond) do { \
    if (!(cond)) { \
        fprintf(stderr, "  FAIL: %s (line %d)\n", #cond, __LINE__); \
        return; \
    } \
} while(0)

#define PASS() tests_passed++

struct TestFunc { const char* name; void (*fn)(); };
static std::vector<TestFunc> test_funcs;

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

TEST(config_default) {
    speech_config_t cfg = speech_config_default();
    ASSERT(cfg.use_int8 == true);
    ASSERT(cfg.use_qnn == false);
    ASSERT(cfg.min_silence_duration > 0.0f);
    ASSERT(cfg.model_dir == nullptr);
    PASS();
}

TEST(version) {
    const char* v = speech_version();
    ASSERT(v != nullptr);
    ASSERT(strlen(v) > 0);
    PASS();
}

TEST(create_null_dir_fails) {
    speech_config_t cfg = speech_config_default();
    cfg.model_dir = nullptr;
    speech_pipeline_t p = speech_create(cfg, nullptr, nullptr);
    ASSERT(p == nullptr);
    PASS();
}

TEST(create_bad_dir_fails) {
    speech_config_t cfg = speech_config_default();
    cfg.model_dir = "/nonexistent/path";
    speech_pipeline_t p = speech_create(cfg, nullptr, nullptr);
    ASSERT(p == nullptr);
    PASS();
}

TEST(destroy_null_safe) {
    speech_destroy(nullptr);
    PASS();
}

TEST(push_null_safe) {
    float buf[512] = {};
    speech_push_audio(nullptr, buf, 512);
    speech_start(nullptr);
    speech_resume_listening(nullptr);
    PASS();
}

// If models are available, test the full pipeline
static const char* find_model_dir() {
    const char* env = getenv("SPEECH_MODEL_DIR");
    if (env) return env;
    // Check common locations
    static const char* paths[] = {
        "./models",
        "../models",
        "../tests/models",
        "/opt/speech/models",
        nullptr
    };
    for (const char** p = paths; *p; p++) {
        char path[512];
        snprintf(path, sizeof(path), "%s/silero-vad.onnx", *p);
        FILE* f = fopen(path, "r");
        if (f) { fclose(f); return *p; }
    }
    return nullptr;
}

struct EventLog {
    std::atomic<int> transcriptions{0};
    std::atomic<int> speech_started{0};
    std::atomic<int> speech_ended{0};
    std::string last_text;
};

static void test_event_cb(const speech_event_t* event, void* ctx) {
    auto* log = static_cast<EventLog*>(ctx);
    switch (event->type) {
        case SPEECH_EVENT_SPEECH_STARTED: log->speech_started++; break;
        case SPEECH_EVENT_SPEECH_ENDED: log->speech_ended++; break;
        case SPEECH_EVENT_TRANSCRIPTION:
            log->transcriptions++;
            if (event->text) log->last_text = event->text;
            break;
        default: break;
    }
}

TEST(pipeline_lifecycle) {
    const char* dir = find_model_dir();
    if (!dir) { fprintf(stderr, "  SKIP (no models)\n"); PASS(); return; }

    speech_config_t cfg = speech_config_default();
    cfg.model_dir = dir;
    cfg.transcribe_only = true;

    EventLog log;
    speech_pipeline_t p = speech_create(cfg, test_event_cb, &log);
    ASSERT(p != nullptr);

    speech_start(p);

    // Push 2 seconds of silence
    float silence[512] = {};
    for (int i = 0; i < 62; i++) {
        speech_push_audio(p, silence, 512);
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }

    speech_destroy(p);
    // No crash = success
    PASS();
}

TEST(pipeline_speech_detection) {
    const char* dir = find_model_dir();
    if (!dir) { fprintf(stderr, "  SKIP (no models)\n"); PASS(); return; }

    speech_config_t cfg = speech_config_default();
    cfg.model_dir = dir;
    cfg.transcribe_only = true;

    EventLog log;
    speech_pipeline_t p = speech_create(cfg, test_event_cb, &log);
    ASSERT(p != nullptr);

    speech_start(p);

    // Push speech-like signal (150Hz buzz) for 1.5s
    float speech[512];
    for (int chunk = 0; chunk < 47; chunk++) {
        for (int i = 0; i < 512; i++) {
            float t = (float)(chunk * 512 + i) / 16000.0f;
            speech[i] = 0.3f * sinf(2.0f * 3.14159f * 150.0f * t)
                       + 0.2f * sinf(2.0f * 3.14159f * 300.0f * t);
        }
        speech_push_audio(p, speech, 512);
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }

    // Push 1.5s silence to trigger end-of-speech
    float silence[512] = {};
    for (int i = 0; i < 47; i++) {
        speech_push_audio(p, silence, 512);
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }

    // Wait for processing
    std::this_thread::sleep_for(std::chrono::seconds(3));

    speech_destroy(p);

    // VAD should have detected speech
    ASSERT(log.speech_started > 0);
    PASS();
}

TEST(resume_listening_null_safe) {
    speech_resume_listening(nullptr);
    PASS();
}

TEST(pipeline_multiple_sessions) {
    const char* dir = find_model_dir();
    if (!dir) { fprintf(stderr, "  SKIP (no models)\n"); PASS(); return; }

    for (int session = 0; session < 3; session++) {
        speech_config_t cfg = speech_config_default();
        cfg.model_dir = dir;
        cfg.transcribe_only = true;

        EventLog log;
        speech_pipeline_t p = speech_create(cfg, test_event_cb, &log);
        ASSERT(p != nullptr);

        speech_start(p);

        // Push 1 second of silence
        float silence[512] = {};
        for (int i = 0; i < 31; i++) {
            speech_push_audio(p, silence, 512);
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }

        speech_destroy(p);
    }
    // No crash or leak after 3 create/destroy cycles
    PASS();
}

TEST(pipeline_concurrent_push) {
    const char* dir = find_model_dir();
    if (!dir) { fprintf(stderr, "  SKIP (no models)\n"); PASS(); return; }

    speech_config_t cfg = speech_config_default();
    cfg.model_dir = dir;
    cfg.transcribe_only = true;

    EventLog log;
    speech_pipeline_t p = speech_create(cfg, test_event_cb, &log);
    ASSERT(p != nullptr);

    speech_start(p);

    // Push audio from 4 threads concurrently
    std::vector<std::thread> threads;
    for (int t = 0; t < 4; t++) {
        threads.emplace_back([p]() {
            float buf[512] = {};
            for (int i = 0; i < 50; i++) {
                speech_push_audio(p, buf, 512);
                std::this_thread::sleep_for(std::chrono::milliseconds(2));
            }
        });
    }

    for (auto& th : threads) {
        th.join();
    }

    speech_destroy(p);
    // No crash under concurrent push
    PASS();
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

int main() {
    fprintf(stderr, "speech-linux tests (%s)\n\n", speech_version());

    for (auto& t : test_funcs) {
        tests_run++;
        fprintf(stderr, "  %s... ", t.name);
        t.fn();
        if (tests_passed == tests_run) {
            fprintf(stderr, "ok\n");
        }
    }

    fprintf(stderr, "\n%d/%d passed\n", tests_passed, tests_run);
    return tests_passed == tests_run ? 0 : 1;
}
