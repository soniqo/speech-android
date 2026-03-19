#include "silero_vad.h"
#include "onnx_engine.h"
#include <cstring>

SileroVad::SileroVad(const std::string& model_path, bool nnapi) {
    auto& engine = OnnxEngine::get();
    api_ = engine.api();
    session_ = engine.load(model_path, nnapi);
    reset();
}

SileroVad::~SileroVad() {
    if (session_) api_->ReleaseSession(session_);
}

void SileroVad::reset() {
    state_.fill(0.0f);
}

float SileroVad::process_chunk(const float* samples, size_t length) {
    auto* mem = OnnxEngine::get().cpu_memory();

    // --- input tensors ---

    const int64_t input_shape[] = {1, static_cast<int64_t>(length)};
    OrtValue* t_input = nullptr;
    ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
        mem, const_cast<float*>(samples), length * sizeof(float),
        input_shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &t_input));

    // sr is a scalar (no shape dimensions)
    OrtValue* t_sr = nullptr;
    ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
        mem, &sr_, sizeof(int64_t),
        nullptr, 0, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &t_sr));

    const int64_t state_shape[] = {2, 1, 128};
    OrtValue* t_state = nullptr;
    ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
        mem, state_.data(), state_.size() * sizeof(float),
        state_shape, 3, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &t_state));

    // --- run ---

    const char* in_names[]  = {"input", "state", "sr"};
    const char* out_names[] = {"output", "stateN"};
    OrtValue* inputs[]  = {t_input, t_state, t_sr};
    OrtValue* outputs[] = {nullptr, nullptr};

    ort_check(api_, api_->Run(
        session_, nullptr,
        in_names, inputs, 3,
        out_names, 2, outputs));

    // --- extract ---

    float* out_data = nullptr;
    ort_check(api_, api_->GetTensorMutableData(outputs[0], (void**)&out_data));
    float prob = out_data[0];

    float* new_state = nullptr;
    ort_check(api_, api_->GetTensorMutableData(outputs[1], (void**)&new_state));
    std::memcpy(state_.data(), new_state, state_.size() * sizeof(float));

    // --- cleanup ---

    api_->ReleaseValue(outputs[1]);
    api_->ReleaseValue(outputs[0]);
    api_->ReleaseValue(t_state);
    api_->ReleaseValue(t_sr);
    api_->ReleaseValue(t_input);

    return prob;
}
