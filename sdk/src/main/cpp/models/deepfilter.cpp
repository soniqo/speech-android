#include "deepfilter.h"
#include "onnx_engine.h"
#include "../audio/stft.h"
#include <cmath>
#include <cstring>
#include <fstream>

DeepFilterEnhancer::DeepFilterEnhancer(
    const std::string& model_path,
    const std::string& auxiliary_path,
    bool nnapi)
{
    auto& engine = OnnxEngine::get();
    api_ = engine.api();
    session_ = engine.load(model_path, nnapi);
    load_auxiliary(auxiliary_path);
}

DeepFilterEnhancer::~DeepFilterEnhancer() {
    if (session_) api_->ReleaseSession(session_);
}

void DeepFilterEnhancer::load_auxiliary(const std::string& path) {
    // Load precomputed ERB filterbanks and window from binary file.
    // Format: erb_fb [481*32] | erb_inv_fb [32*481] | window [960]  (float32)
    std::ifstream file(path, std::ios::binary);
    if (!file.is_open()) {
        LOGE("Auxiliary file not found: %s", path.c_str());
        return;
    }

    erb_fb_.resize(cfg_.freq_bins * cfg_.erb_bands);
    erb_inv_fb_.resize(cfg_.erb_bands * cfg_.freq_bins);
    window_.resize(cfg_.fft_size);

    file.read(reinterpret_cast<char*>(erb_fb_.data()),
              erb_fb_.size() * sizeof(float));
    file.read(reinterpret_cast<char*>(erb_inv_fb_.data()),
              erb_inv_fb_.size() * sizeof(float));
    file.read(reinterpret_cast<char*>(window_.data()),
              window_.size() * sizeof(float));
}

void DeepFilterEnhancer::compute_erb_features(
    const float* spec_real, const float* spec_imag, int num_frames,
    std::vector<float>& feat_erb, std::vector<float>& feat_spec)
{
    feat_erb.resize(num_frames * cfg_.erb_bands);
    feat_spec.resize(num_frames * 2 * cfg_.df_bins);

    for (int t = 0; t < num_frames; t++) {
        // Power spectrum → ERB bands
        for (int b = 0; b < cfg_.erb_bands; b++) {
            float sum = 0.0f;
            for (int f = 0; f < cfg_.freq_bins; f++) {
                float re = spec_real[t * cfg_.freq_bins + f];
                float im = spec_imag[t * cfg_.freq_bins + f];
                sum += (re * re + im * im) * erb_fb_[f * cfg_.erb_bands + b];
            }
            feat_erb[t * cfg_.erb_bands + b] = 10.0f * std::log10(sum + 1e-10f);
        }

        // Complex spectrum for deep-filtered bins
        for (int f = 0; f < cfg_.df_bins; f++) {
            feat_spec[t * 2 * cfg_.df_bins + f] =
                spec_real[t * cfg_.freq_bins + f];
            feat_spec[t * 2 * cfg_.df_bins + cfg_.df_bins + f] =
                spec_imag[t * cfg_.freq_bins + f];
        }
    }
}

void DeepFilterEnhancer::apply_erb_mask(
    float* spec_real, float* spec_imag,
    const float* mask, int num_frames)
{
    for (int t = 0; t < num_frames; t++) {
        for (int f = 0; f < cfg_.freq_bins; f++) {
            // Expand ERB mask to full spectrum
            float gain = 0.0f;
            for (int b = 0; b < cfg_.erb_bands; b++) {
                gain += mask[t * cfg_.erb_bands + b]
                        * erb_inv_fb_[b * cfg_.freq_bins + f];
            }
            spec_real[t * cfg_.freq_bins + f] *= gain;
            spec_imag[t * cfg_.freq_bins + f] *= gain;
        }
    }
}

void DeepFilterEnhancer::apply_deep_filter(
    float* spec_real, float* spec_imag,
    const float* coefs, int num_frames)
{
    int pad_before = cfg_.df_order - 1 - cfg_.df_lookahead;

    for (int t = 0; t < num_frames; t++) {
        for (int f = 0; f < cfg_.df_bins; f++) {
            float out_re = 0.0f, out_im = 0.0f;

            for (int n = 0; n < cfg_.df_order; n++) {
                int src_t = t + n - pad_before;
                if (src_t < 0 || src_t >= num_frames) continue;

                float x_re = spec_real[src_t * cfg_.freq_bins + f];
                float x_im = spec_imag[src_t * cfg_.freq_bins + f];

                // coefs layout: [1, df_order, T, df_bins, 2]
                int idx = (n * num_frames * cfg_.df_bins + t * cfg_.df_bins + f) * 2;
                float w_re = coefs[idx];
                float w_im = coefs[idx + 1];

                // Complex multiply
                out_re += x_re * w_re - x_im * w_im;
                out_im += x_re * w_im + x_im * w_re;
            }

            spec_real[t * cfg_.freq_bins + f] = out_re;
            spec_imag[t * cfg_.freq_bins + f] = out_im;
        }
    }
}

void DeepFilterEnhancer::enhance(
    const float* audio, size_t length, int /*sample_rate*/, float* output)
{
    auto* mem = OnnxEngine::get().cpu_memory();

    // --- STFT ---

    int num_frames = stft_num_frames(length, cfg_.fft_size, cfg_.hop_size);
    std::vector<float> spec_real(num_frames * cfg_.freq_bins);
    std::vector<float> spec_imag(num_frames * cfg_.freq_bins);

    stft_forward(audio, length, cfg_.fft_size, cfg_.hop_size,
                 window_.data(), spec_real.data(), spec_imag.data());

    // --- features ---

    std::vector<float> feat_erb, feat_spec;
    compute_erb_features(spec_real.data(), spec_imag.data(),
                         num_frames, feat_erb, feat_spec);

    // --- ONNX inference ---

    int64_t T = num_frames;
    const int64_t erb_shape[]  = {1, 1, T, cfg_.erb_bands};
    const int64_t spec_shape[] = {1, 2, T, cfg_.df_bins};

    OrtValue* t_erb = nullptr;
    ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
        mem, feat_erb.data(), feat_erb.size() * sizeof(float),
        erb_shape, 4, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &t_erb));

    OrtValue* t_spec = nullptr;
    ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
        mem, feat_spec.data(), feat_spec.size() * sizeof(float),
        spec_shape, 4, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &t_spec));

    const char* in_names[]  = {"feat_erb", "feat_spec"};
    const char* out_names[] = {"erb_mask", "df_coefs"};
    OrtValue* inputs[]  = {t_erb, t_spec};
    OrtValue* outputs[] = {nullptr, nullptr};

    ort_check(api_, api_->Run(
        session_, nullptr,
        in_names, inputs, 2,
        out_names, 2, outputs));

    float* erb_mask = nullptr;
    ort_check(api_, api_->GetTensorMutableData(outputs[0], (void**)&erb_mask));
    float* df_coefs = nullptr;
    ort_check(api_, api_->GetTensorMutableData(outputs[1], (void**)&df_coefs));

    // --- apply mask + deep filter ---

    apply_erb_mask(spec_real.data(), spec_imag.data(), erb_mask, num_frames);
    apply_deep_filter(spec_real.data(), spec_imag.data(), df_coefs, num_frames);

    // --- inverse STFT ---

    stft_inverse(spec_real.data(), spec_imag.data(), num_frames,
                 cfg_.fft_size, cfg_.hop_size,
                 window_.data(), output, length);

    // --- cleanup ---

    api_->ReleaseValue(outputs[1]);
    api_->ReleaseValue(outputs[0]);
    api_->ReleaseValue(t_spec);
    api_->ReleaseValue(t_erb);
}
