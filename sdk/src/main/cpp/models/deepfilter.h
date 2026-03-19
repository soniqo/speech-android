#pragma once

#include <onnxruntime_c_api.h>
#include <string>
#include <vector>

/// DeepFilterNet3 — real-time speech enhancement / noise cancellation.
/// Processes audio at 48 kHz using STFT + ERB filterbank + neural network.
/// Model size: ~2.1M parameters (~8 MB FP16).
class DeepFilterEnhancer {
public:
    struct Config {
        int fft_size    = 960;
        int hop_size    = 480;
        int erb_bands   = 32;
        int df_bins     = 96;   // deep-filtered frequency bins
        int df_order    = 5;    // filter taps
        int df_lookahead = 2;
        int freq_bins   = 481;  // fft_size / 2 + 1
        int sample_rate = 48000;
    };

    DeepFilterEnhancer(const std::string& model_path,
                       const std::string& auxiliary_path,
                       bool nnapi = true);
    ~DeepFilterEnhancer();

    /// Enhance audio by removing noise.
    /// @param audio       Input PCM Float32 at 48 kHz
    /// @param length      Number of samples
    /// @param sample_rate Input sample rate (must be 48000)
    /// @param output      Pre-allocated output buffer (same length)
    void enhance(const float* audio, size_t length, int sample_rate,
                 float* output);

    int input_sample_rate() const { return cfg_.sample_rate; }

private:
    void load_auxiliary(const std::string& path);
    void compute_erb_features(const float* spectrum_real,
                              const float* spectrum_imag,
                              int num_frames,
                              std::vector<float>& feat_erb,
                              std::vector<float>& feat_spec);
    void apply_erb_mask(float* spectrum_real, float* spectrum_imag,
                        const float* mask, int num_frames);
    void apply_deep_filter(float* spectrum_real, float* spectrum_imag,
                           const float* coefs, int num_frames);

    const OrtApi* api_;
    OrtSession* session_ = nullptr;
    Config cfg_;

    // ERB filterbanks
    std::vector<float> erb_fb_;       // [freq_bins, erb_bands]
    std::vector<float> erb_inv_fb_;   // [erb_bands, freq_bins]
    std::vector<float> window_;       // Vorbis window [fft_size]
};
