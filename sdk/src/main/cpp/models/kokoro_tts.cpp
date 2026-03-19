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

        on_chunk(audio, num_elements, true, ctx);
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
