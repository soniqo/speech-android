#include "parakeet_stt.h"
#include "onnx_engine.h"
#include "../audio/mel.h"
#include "../util/json.h"
#include <algorithm>
#include <cmath>
#include <cstring>

// ---------------------------------------------------------------------------
// SentencePiece U+2581 → space, then trim
// ---------------------------------------------------------------------------

static void replace_sp_marker(std::string& s) {
    const std::string marker = "\xE2\x96\x81";
    size_t pos = 0;
    while ((pos = s.find(marker, pos)) != std::string::npos) {
        s.replace(pos, marker.size(), " ");
        pos += 1;
    }
}

// ---------------------------------------------------------------------------
// Construction
// ---------------------------------------------------------------------------

ParakeetStt::ParakeetStt(
    const std::string& encoder_path,
    const std::string& decoder_joint_path,
    const std::string& vocab_path,
    bool nnapi)
{
    auto& engine = OnnxEngine::get();
    api_ = engine.api();
    encoder_       = engine.load(encoder_path, nnapi);
    decoder_joint_ = engine.load(decoder_joint_path, false);

    load_vocab(vocab_path);
}

ParakeetStt::~ParakeetStt() {
    if (decoder_joint_) api_->ReleaseSession(decoder_joint_);
    if (encoder_)       api_->ReleaseSession(encoder_);
}

// ---------------------------------------------------------------------------
// Vocabulary
// ---------------------------------------------------------------------------

bool ParakeetStt::load_vocab(const std::string& path) {
    auto text = json::read_file(path);
    if (text.empty()) return false;

    auto flat = json::parse_flat_object(text);
    for (auto& [key, val] : flat) {
        try {
            int id = std::stoi(key);
            vocab_[id] = val;
        } catch (...) {}
    }

    // Update config based on actual vocab size
    if (!vocab_.empty()) {
        cfg_.vocab_size = static_cast<int>(vocab_.size());
        cfg_.blank_id = cfg_.vocab_size;
        cfg_.total_logits = cfg_.vocab_size + 1 + cfg_.num_dur_bins;
    }

    LOGI("Parakeet vocab: %zu tokens, blank=%d", vocab_.size(), cfg_.blank_id);
    return !vocab_.empty();
}

std::string ParakeetStt::decode_tokens(const std::vector<int>& token_ids) {
    std::string pieces;
    for (int id : token_ids) {
        auto it = vocab_.find(id);
        if (it != vocab_.end()) pieces += it->second;
    }
    replace_sp_marker(pieces);

    size_t start = pieces.find_first_not_of(' ');
    if (start == std::string::npos) return "";
    size_t end = pieces.find_last_not_of(' ');
    return pieces.substr(start, end - start + 1);
}

// ---------------------------------------------------------------------------
// Mel spectrogram
// ---------------------------------------------------------------------------

std::vector<float> ParakeetStt::compute_mel(const float* audio, size_t length) {
    std::vector<float> emphasized(length);
    emphasized[0] = audio[0];
    for (size_t i = 1; i < length; i++) {
        emphasized[i] = audio[i] - cfg_.pre_emphasis * audio[i - 1];
    }

    auto mel = mel_spectrogram(
        emphasized.data(), emphasized.size(),
        cfg_.sample_rate, cfg_.n_fft, cfg_.hop_length,
        cfg_.win_length, cfg_.num_mel_bins);

    // Per-feature normalization (NeMo AudioToMelSpectrogramPreprocessor)
    // mel layout: [num_mel_bins * num_frames], mel[m * num_frames + t]
    int num_frames = static_cast<int>(mel.size() / cfg_.num_mel_bins);
    if (num_frames > 1) {
        for (int m = 0; m < cfg_.num_mel_bins; m++) {
            float sum = 0, sq_sum = 0;
            for (int t = 0; t < num_frames; t++) {
                float v = mel[m * num_frames + t];
                sum += v;
                sq_sum += v * v;
            }
            float mean = sum / num_frames;
            float var = sq_sum / num_frames - mean * mean;
            float std = (var > 0) ? std::sqrt(var) : 1.0f;
            for (int t = 0; t < num_frames; t++) {
                mel[m * num_frames + t] = (mel[m * num_frames + t] - mean) / std;
            }
        }
    }

    return mel;
}

// ---------------------------------------------------------------------------
// Transcribe
// ---------------------------------------------------------------------------

ParakeetStt::Result ParakeetStt::transcribe(
    const float* audio, size_t length, int /*sample_rate*/)
{
    auto* mem = OnnxEngine::get().cpu_memory();

    // --- mel spectrogram [B, 128, T] ---

    auto mel = compute_mel(audio, length);
    int64_t num_frames = static_cast<int64_t>(mel.size() / cfg_.num_mel_bins);
    const int64_t mel_shape[] = {1, static_cast<int64_t>(cfg_.num_mel_bins), num_frames};

    OrtValue* t_mel = nullptr;
    ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
        mem, mel.data(), mel.size() * sizeof(float),
        mel_shape, 3, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &t_mel));

    int64_t mel_len = num_frames;
    const int64_t len_shape[] = {1};
    OrtValue* t_len = nullptr;
    ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
        mem, &mel_len, sizeof(int64_t),
        len_shape, 1, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &t_len));

    // --- encoder: audio_signal, length → outputs, encoded_lengths ---

    const char* enc_in[]  = {"audio_signal", "length"};
    const char* enc_out[] = {"outputs", "encoded_lengths"};
    OrtValue* enc_inputs[]  = {t_mel, t_len};
    OrtValue* enc_outputs[] = {nullptr, nullptr};

    ort_check(api_, api_->Run(
        encoder_, nullptr, enc_in, enc_inputs, 2, enc_out, 2, enc_outputs));

    // Get encoded shape [B, 1024, T']
    OrtTensorTypeAndShapeInfo* info = nullptr;
    ort_check(api_, api_->GetTensorTypeAndShape(enc_outputs[0], &info));
    size_t dim_count = 0;
    api_->GetDimensionsCount(info, &dim_count);
    std::vector<int64_t> enc_shape(dim_count);
    api_->GetDimensions(info, enc_shape.data(), dim_count);
    api_->ReleaseTensorTypeAndShapeInfo(info);

    float* encoded = nullptr;
    ort_check(api_, api_->GetTensorMutableData(enc_outputs[0], (void**)&encoded));

    int64_t* enc_len_ptr = nullptr;
    ort_check(api_, api_->GetTensorMutableData(enc_outputs[1], (void**)&enc_len_ptr));
    int64_t enc_len = enc_len_ptr[0];
    int64_t hidden  = (dim_count >= 3) ? enc_shape[1] : cfg_.encoder_hidden;

    // --- TDT greedy decode ---

    auto result = tdt_decode(encoded, enc_len, hidden);

    // --- cleanup ---

    api_->ReleaseValue(enc_outputs[1]);
    api_->ReleaseValue(enc_outputs[0]);
    api_->ReleaseValue(t_len);
    api_->ReleaseValue(t_mel);

    return result;
}

// ---------------------------------------------------------------------------
// TDT greedy decoding with fused decoder_joint model
// ---------------------------------------------------------------------------

ParakeetStt::Result ParakeetStt::tdt_decode(
    const float* encoded, int64_t enc_len, int64_t hidden)
{
    auto* mem = OnnxEngine::get().cpu_memory();

    std::vector<int> token_ids;
    float log_prob_sum = 0.0f;
    int log_prob_count = 0;

    // LSTM states: [2, 1, 640]
    int64_t state_size = cfg_.decoder_layers * 1 * cfg_.decoder_hidden;
    std::vector<float> h_state(state_size, 0.0f);
    std::vector<float> c_state(state_size, 0.0f);
    const int64_t lstm_shape[] = {
        static_cast<int64_t>(cfg_.decoder_layers), 1,
        static_cast<int64_t>(cfg_.decoder_hidden)
    };

    int64_t prev_token = static_cast<int64_t>(cfg_.blank_id);
    int64_t t = 0;

    while (t < enc_len) {
        // Encoder frame at time t: [1, hidden, 1]
        // NeMo encoder output is [B, hidden, T'] so frame is at offset t
        // But decoder_joint expects encoder_outputs [B, hidden, T']
        // For greedy decode, we pass a single frame [1, hidden, 1]
        std::vector<float> enc_frame(hidden);
        for (int64_t h = 0; h < hidden; h++) {
            enc_frame[h] = encoded[h * enc_len + t];  // [B, H, T] layout
        }

        const int64_t enc_frame_shape[] = {1, hidden, 1};
        OrtValue* t_enc = nullptr;
        ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
            mem, enc_frame.data(), enc_frame.size() * sizeof(float),
            enc_frame_shape, 3, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &t_enc));

        // Target: previous token [1, 1]
        const int64_t tok_shape[] = {1, 1};
        OrtValue* t_tok = nullptr;
        ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
            mem, &prev_token, sizeof(int64_t),
            tok_shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &t_tok));

        // Target length: [1] = 1
        int64_t tgt_len = 1;
        const int64_t tgt_len_shape[] = {1};
        OrtValue* t_tgt_len = nullptr;
        ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
            mem, &tgt_len, sizeof(int64_t),
            tgt_len_shape, 1, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &t_tgt_len));

        // LSTM states
        OrtValue* t_h = nullptr;
        ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
            mem, h_state.data(), h_state.size() * sizeof(float),
            lstm_shape, 3, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &t_h));

        OrtValue* t_c = nullptr;
        ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
            mem, c_state.data(), c_state.size() * sizeof(float),
            lstm_shape, 3, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &t_c));

        // Run decoder_joint (v3 uses "prednet_lengths_orig" instead of "target_length")
        const char* in_names[]  = {"encoder_outputs", "targets", "prednet_lengths_orig",
                                   "input_states_1", "input_states_2"};
        const char* out_names[] = {"outputs", "prednet_lengths",
                                   "output_states_1", "output_states_2"};
        OrtValue* inputs[]  = {t_enc, t_tok, t_tgt_len, t_h, t_c};
        OrtValue* outputs[] = {nullptr, nullptr, nullptr, nullptr};

        ort_check(api_, api_->Run(
            decoder_joint_, nullptr,
            in_names, inputs, 5,
            out_names, 4, outputs));

        // Logits: [1, 1, 1, total_logits] — token logits + duration logits
        float* logits = nullptr;
        ort_check(api_, api_->GetTensorMutableData(outputs[0], (void**)&logits));

        int token_end = cfg_.vocab_size + 1;  // includes blank

        // Greedy argmax: token
        int best_token = 0;
        float best_score = logits[0];
        for (int i = 1; i < token_end; i++) {
            if (logits[i] > best_score) {
                best_score = logits[i];
                best_token = i;
            }
        }

        if (best_token == cfg_.blank_id) {
            t += 1;
        } else {
            if (best_token >= cfg_.first_text_token && best_token < cfg_.vocab_size) {
                token_ids.push_back(best_token);
                log_prob_sum += best_score;
                log_prob_count++;
            }

            // Duration logits start after token logits
            float* dur_logits = logits + token_end;
            int dur_idx = 0;
            float best_dur = dur_logits[0];
            for (int d = 1; d < cfg_.num_dur_bins; d++) {
                if (dur_logits[d] > best_dur) {
                    best_dur = dur_logits[d];
                    dur_idx = d;
                }
            }
            t += std::max(cfg_.duration_bins[dur_idx], 1);

            prev_token = best_token;
        }

        // Update LSTM states
        float* h_out = nullptr;
        ort_check(api_, api_->GetTensorMutableData(outputs[2], (void**)&h_out));
        std::memcpy(h_state.data(), h_out, state_size * sizeof(float));

        float* c_out = nullptr;
        ort_check(api_, api_->GetTensorMutableData(outputs[3], (void**)&c_out));
        std::memcpy(c_state.data(), c_out, state_size * sizeof(float));

        // Cleanup
        for (int i = 3; i >= 0; i--) api_->ReleaseValue(outputs[i]);
        api_->ReleaseValue(t_c);
        api_->ReleaseValue(t_h);
        api_->ReleaseValue(t_tgt_len);
        api_->ReleaseValue(t_tok);
        api_->ReleaseValue(t_enc);
    }

    // --- build result ---

    Result result;
    result.text = decode_tokens(token_ids);

    if (log_prob_count > 0) {
        float mean_logit = log_prob_sum / static_cast<float>(log_prob_count);
        result.confidence = 1.0f / (1.0f + std::exp(-mean_logit * 0.1f));
    }

    return result;
}
