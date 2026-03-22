#pragma once

#include <onnxruntime_c_api.h>
#include <string>
#include <unordered_map>
#include <vector>

/// Parakeet TDT v3 (0.6B) — speech recognition via ONNX Runtime.
/// FastConformer encoder + fused LSTM decoder/joint network.
/// Exported via NeMo as 2 ONNX models: encoder + decoder_joint.
/// Input: PCM Float32 audio at 16 kHz.
/// Output: transcribed text with language detection.
class ParakeetStt {
public:
    struct Config {
        int num_mel_bins    = 128;
        int sample_rate     = 16000;
        int n_fft           = 512;
        int hop_length      = 160;
        int win_length      = 400;
        float pre_emphasis  = 0.97f;
        int encoder_hidden  = 1024;
        int decoder_hidden  = 640;
        int decoder_layers  = 2;
        int vocab_size      = 1024;   // SentencePiece BPE
        int blank_id        = 1024;   // vocab_size
        int num_dur_bins    = 5;
        int duration_bins[5] = {0, 1, 2, 3, 4};
        int total_logits    = 1030;   // vocab_size+1 + num_dur_bins
        int first_text_token = 0;     // Only token 0 (<unk>) is special
    };

    struct Result {
        std::string text;
        std::string language;
        float confidence = 0.0f;
    };

    /// Load encoder + decoder_joint ONNX models and vocabulary.
    ParakeetStt(const std::string& encoder_path,
                const std::string& decoder_joint_path,
                const std::string& vocab_path,
                bool nnapi = true);
    ~ParakeetStt();

    Result transcribe(const float* audio, size_t length, int sample_rate);
    int input_sample_rate() const { return cfg_.sample_rate; }

private:
    bool load_vocab(const std::string& path);
    std::vector<float> compute_mel(const float* audio, size_t length);
    Result tdt_decode(const float* encoded, int64_t enc_len, int64_t hidden);
    std::string decode_tokens(const std::vector<int>& token_ids);

    const OrtApi* api_;
    OrtSession* encoder_       = nullptr;
    OrtSession* decoder_joint_ = nullptr;
    Config cfg_;

    // SentencePiece vocabulary: token ID → token string
    std::unordered_map<int, std::string> vocab_;
};
