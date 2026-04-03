#include "kokoro_tts.h"
#include "onnx_engine.h"
#include <cstring>
#include <fstream>

KokoroTts::KokoroTts(
    const std::string& model_path,
    const std::string& voices_dir,
    const std::string& data_dir,
    bool nnapi)
    : voices_dir_(voices_dir)
{
    auto& engine = OnnxEngine::get();
    api_ = engine.api();
    session_ = engine.load(model_path, nnapi);

    // Load phonemizer vocabulary and dictionaries
    phonemizer_.load_vocab(data_dir + "/vocab_index.json");
    phonemizer_.load_dictionaries(data_dir);

    // Load default voice
    set_voice("af_heart");
}

KokoroTts::~KokoroTts() {
    if (session_) api_->ReleaseSession(session_);
}

void KokoroTts::set_voice(const std::string& name) {
    voice_embedding_ = load_voice_embedding(name);
}

std::vector<float> KokoroTts::load_voice_embedding(const std::string& name) {
    std::string path = voices_dir_ + "/" + name + ".bin";
    std::ifstream file(path, std::ios::binary);
    if (!file.is_open()) {
        LOGE("Voice file not found: %s", path.c_str());
        return std::vector<float>(256, 0.0f);
    }

    std::vector<float> embedding(256);
    file.read(reinterpret_cast<char*>(embedding.data()), 256 * sizeof(float));
    return embedding;
}

void KokoroTts::synthesize(
    const char* text, const char* /*language*/,
    ChunkCallback on_chunk, void* ctx)
{
    cancelled_ = false;
    auto* mem = OnnxEngine::get().cpu_memory();

    // Text → phoneme token IDs
    auto tokens = phonemizer_.tokenize(text);
    if (tokens.empty() || cancelled_) return;

    auto phonemes = phonemizer_.text_to_phonemes(text);
    LOGI("TTS: text='%.60s' phonemes='%.80s' tokens=%zu",
         text, phonemes.c_str(), tokens.size());

    int64_t num_tokens = static_cast<int64_t>(tokens.size());

    // --- input tensors ---

    // tokens [1, N]
    const int64_t tok_shape[] = {1, num_tokens};
    OrtValue* t_tokens = nullptr;
    ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
        mem, tokens.data(), tokens.size() * sizeof(int64_t),
        tok_shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &t_tokens));

    // style / voice embedding [1, 256]
    const int64_t style_shape[] = {1, 256};
    OrtValue* t_style = nullptr;
    ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
        mem, voice_embedding_.data(), voice_embedding_.size() * sizeof(float),
        style_shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &t_style));

    // speed [1]
    float speed = 1.0f;
    const int64_t speed_shape[] = {1};
    OrtValue* t_speed = nullptr;
    ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
        mem, &speed, sizeof(float),
        speed_shape, 1, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &t_speed));

    // --- run ---

    const char* in_names[]  = {"tokens", "style", "speed"};
    const char* out_names[] = {"audio"};
    OrtValue* inputs[]  = {t_tokens, t_style, t_speed};
    OrtValue* outputs[] = {nullptr};

    ort_check(api_, api_->Run(
        session_, nullptr,
        in_names, inputs, 3,
        out_names, 1, outputs));

    if (!cancelled_) {
        float* audio = nullptr;
        ort_check(api_, api_->GetTensorMutableData(outputs[0], (void**)&audio));

        OrtTensorTypeAndShapeInfo* info = nullptr;
        ort_check(api_, api_->GetTensorTypeAndShape(outputs[0], &info));
        size_t num_elements = 0;
        api_->GetTensorShapeElementCount(info, &num_elements);
        api_->ReleaseTensorTypeAndShapeInfo(info);

        // Trim trailing silence (padding from fixed-size model output)
        const int frame_size = 240;  // 10ms at 24kHz
        size_t valid_end = num_elements;
        for (int i = static_cast<int>(num_elements) - frame_size; i > 0; i -= frame_size) {
            float rms = 0;
            for (int j = 0; j < frame_size && (i + j) < (int)num_elements; j++)
                rms += audio[i + j] * audio[i + j];
            rms = std::sqrt(rms / frame_size);
            if (rms > 0.005f) {
                valid_end = std::min(static_cast<size_t>(i + frame_size), num_elements);
                break;
            }
        }

        // Fade out last 5ms to prevent click
        const size_t fade = std::min(static_cast<size_t>(120), valid_end);
        for (size_t i = 0; i < fade; i++) {
            audio[valid_end - fade + i] *= static_cast<float>(fade - i) / static_cast<float>(fade);
        }

        // Fade in first 5ms to prevent pop
        const size_t fade_in = std::min(static_cast<size_t>(120), valid_end);
        for (size_t i = 0; i < fade_in; i++) {
            audio[i] *= static_cast<float>(i) / static_cast<float>(fade_in);
        }

        // Normalize
        float peak = 0.0f;
        for (size_t i = 0; i < valid_end; i++) {
            float a = std::abs(audio[i]);
            if (a > peak) peak = a;
        }
        float gain = (peak > 0.01f) ? (0.9f / peak) : 1.0f;
        if (gain > 2.0f) gain = 2.0f;
        if (gain > 1.01f) {
            for (size_t i = 0; i < valid_end; i++)
                audio[i] *= gain;
        }

        LOGI("TTS: samples=%zu valid=%zu peak=%.4f gain=%.1fx",
             num_elements, valid_end, peak, gain);

        on_chunk(audio, valid_end, true, ctx);
    }

    // --- cleanup ---

    api_->ReleaseValue(outputs[0]);
    api_->ReleaseValue(t_speed);
    api_->ReleaseValue(t_style);
    api_->ReleaseValue(t_tokens);
}

void KokoroTts::cancel() {
    cancelled_ = true;
}
