#pragma once

#include <onnxruntime_c_api.h>
#include <string>
#include <vector>
#include "kokoro_phonemizer.h"

/// Kokoro 82M — lightweight text-to-speech via ONNX Runtime.
/// Non-autoregressive, single-pass synthesis.
/// Output: 24 kHz PCM Float32.
class KokoroTts {
public:
    KokoroTts(const std::string& model_path,
              const std::string& voices_dir,
              const std::string& data_dir,
              bool nnapi = true);
    ~KokoroTts();

    using ChunkCallback = void(*)(const float* samples, size_t length,
                                  bool is_final, void* ctx);

    void synthesize(const char* text, const char* language,
                    ChunkCallback on_chunk, void* ctx);
    void cancel();
    int output_sample_rate() const { return 24000; }

    void set_voice(const std::string& name);

private:
    std::vector<float> load_voice_embedding(const std::string& name);
    void auto_switch_voice(const std::string& language);

    const OrtApi* api_;
    OrtSession* session_ = nullptr;

    KokoroPhonemizer phonemizer_;
    std::vector<float> voice_embedding_;
    std::string voices_dir_;
    std::string current_lang_;
    bool cancelled_ = false;
};
