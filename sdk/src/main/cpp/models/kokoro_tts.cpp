#include "kokoro_tts.h"
#include "onnx_engine.h"
#include <cstring>
#include <cstdlib>
#include <fstream>

static constexpr int MAX_PHONEMES = 128;

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
    auto raw_tokens = phonemizer_.tokenize(text, MAX_PHONEMES);
    if (raw_tokens.empty() || cancelled_) return;

    size_t token_count = raw_tokens.size();

    LOGI("TTS: text='%.60s' tokens=%zu", text, token_count);

    // Pad to fixed MAX_PHONEMES with attention mask
    std::vector<int64_t> input_ids(MAX_PHONEMES, 0);
    std::vector<int64_t> attention_mask(MAX_PHONEMES, 0);
    for (size_t i = 0; i < token_count && i < MAX_PHONEMES; i++) {
        input_ids[i] = raw_tokens[i];
        attention_mask[i] = 1;
    }

    // --- input tensors ---

    const int64_t ids_shape[] = {1, MAX_PHONEMES};

    // input_ids [1, 128]
    OrtValue* t_ids = nullptr;
    ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
        mem, input_ids.data(), input_ids.size() * sizeof(int64_t),
        ids_shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &t_ids));

    // attention_mask [1, 128]
    OrtValue* t_mask = nullptr;
    ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
        mem, attention_mask.data(), attention_mask.size() * sizeof(int64_t),
        ids_shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &t_mask));

    // ref_s / voice embedding [1, 256]
    const int64_t style_shape[] = {1, 256};
    OrtValue* t_style = nullptr;
    ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
        mem, voice_embedding_.data(), voice_embedding_.size() * sizeof(float),
        style_shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &t_style));

    // speed [1]
    float speed = 0.85f;
    const int64_t speed_shape[] = {1};
    OrtValue* t_speed = nullptr;
    ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
        mem, &speed, sizeof(float),
        speed_shape, 1, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &t_speed));

    // random_phases [1, 9]
    float phases[9];
    for (int i = 0; i < 9; i++)
        phases[i] = static_cast<float>(rand()) / static_cast<float>(RAND_MAX);
    const int64_t phases_shape[] = {1, 9};
    OrtValue* t_phases = nullptr;
    ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
        mem, phases, sizeof(phases),
        phases_shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &t_phases));

    // --- run ---

    const char* in_names[]  = {"input_ids", "attention_mask", "ref_s", "speed", "random_phases"};
    const char* out_names[] = {"audio", "audio_length_samples", "pred_dur"};
    OrtValue* inputs[]  = {t_ids, t_mask, t_style, t_speed, t_phases};
    OrtValue* outputs[] = {nullptr, nullptr, nullptr};

    ort_check(api_, api_->Run(
        session_, nullptr,
        in_names, inputs, 5,
        out_names, 3, outputs));

    if (!cancelled_) {
        float* audio = nullptr;
        ort_check(api_, api_->GetTensorMutableData(outputs[0], (void**)&audio));

        // Get valid sample count from model
        int64_t* len_ptr = nullptr;
        ort_check(api_, api_->GetTensorMutableData(outputs[1], (void**)&len_ptr));
        size_t valid_samples = static_cast<size_t>(len_ptr[0]);

        // Fade in/out (5ms) to prevent pop/click
        const size_t fade = std::min(static_cast<size_t>(120), valid_samples);
        for (size_t i = 0; i < fade; i++) {
            audio[i] *= static_cast<float>(i) / static_cast<float>(fade);
            audio[valid_samples - fade + i] *= static_cast<float>(fade - i) / static_cast<float>(fade);
        }

        // Normalize to 0.9 peak (handles both quiet and clipping outputs)
        float peak = 0.0f;
        for (size_t i = 0; i < valid_samples; i++) {
            float a = std::abs(audio[i]);
            if (a > peak) peak = a;
        }
        if (peak > 0.01f) {
            float gain = 0.9f / peak;
            for (size_t i = 0; i < valid_samples; i++)
                audio[i] *= gain;
        }

        LOGI("TTS: valid=%zu peak=%.4f", valid_samples, peak);

        on_chunk(audio, valid_samples, true, ctx);
    }

    // --- cleanup ---

    for (int i = 2; i >= 0; i--) api_->ReleaseValue(outputs[i]);
    api_->ReleaseValue(t_phases);
    api_->ReleaseValue(t_speed);
    api_->ReleaseValue(t_style);
    api_->ReleaseValue(t_mask);
    api_->ReleaseValue(t_ids);
}

void KokoroTts::cancel() {
    cancelled_ = true;
}
