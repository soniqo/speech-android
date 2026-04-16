#include "mel.h"
#include "fft.h"
#include <cmath>
#include <algorithm>

static float hz_to_mel(float hz) {
    return 2595.0f * std::log10(1.0f + hz / 700.0f);
}

static float mel_to_hz(float mel) {
    return 700.0f * (std::pow(10.0f, mel / 2595.0f) - 1.0f);
}

static std::vector<float> mel_filterbank(
    int num_mel_bins, int n_fft, int sample_rate, bool slaney_norm)
{
    int num_bins = n_fft / 2 + 1;
    float mel_low = hz_to_mel(0.0f);
    float mel_high = hz_to_mel(static_cast<float>(sample_rate) / 2.0f);

    std::vector<float> mel_points(num_mel_bins + 2);
    // Hz centres of each mel point (for Slaney norm later).
    std::vector<float> hz_points(num_mel_bins + 2);
    for (int i = 0; i < num_mel_bins + 2; i++) {
        float mel = mel_low + (mel_high - mel_low) * i / (num_mel_bins + 1);
        hz_points[i] = mel_to_hz(mel);
    }

    // Convert to FFT bin indices
    std::vector<float> bin_freqs(num_mel_bins + 2);
    for (int i = 0; i < num_mel_bins + 2; i++) {
        bin_freqs[i] = hz_points[i] * n_fft / sample_rate;
    }

    // Triangular filters [num_mel_bins * num_bins]
    std::vector<float> fb(num_mel_bins * num_bins, 0.0f);
    for (int m = 0; m < num_mel_bins; m++) {
        float left = bin_freqs[m];
        float center = bin_freqs[m + 1];
        float right = bin_freqs[m + 2];

        for (int f = 0; f < num_bins; f++) {
            float ff = static_cast<float>(f);
            if (ff >= left && ff <= center && center > left) {
                fb[m * num_bins + f] = (ff - left) / (center - left);
            } else if (ff > center && ff <= right && right > center) {
                fb[m * num_bins + f] = (right - ff) / (right - center);
            }
        }

        // Slaney normalization: divide each filter by its bandwidth in Hz
        // so the filter has unit area. Matches torchaudio norm="slaney".
        if (slaney_norm) {
            float bandwidth = hz_points[m + 2] - hz_points[m];
            if (bandwidth > 0.0f) {
                float enorm = 2.0f / bandwidth;
                for (int f = 0; f < num_bins; f++) {
                    fb[m * num_bins + f] *= enorm;
                }
            }
        }
    }
    return fb;
}

std::vector<float> mel_spectrogram(
    const float* audio, size_t length,
    int sample_rate, int n_fft, int hop_length,
    int win_length, int num_mel_bins,
    bool slaney_norm, float log_floor, bool center)
{
    // Optional center padding: pad signal by n_fft/2 on each side using
    // reflect mode (matches torchaudio / NeMo center=True).
    std::vector<float> padded;
    const float* sig = audio;
    size_t sig_len = length;

    if (center) {
        int pad = n_fft / 2;
        sig_len = length + 2 * static_cast<size_t>(pad);
        padded.resize(sig_len);

        // Left reflect padding: padded[pad-1-i] = audio[i+1] for i in [0, pad-1)
        for (int i = 0; i < pad; ++i) {
            int src = std::min(i + 1, static_cast<int>(length) - 1);
            padded[pad - 1 - i] = audio[src];
        }
        // Copy original signal
        std::copy(audio, audio + length, padded.begin() + pad);
        // Right reflect padding
        for (int i = 0; i < pad; ++i) {
            int src = std::max(static_cast<int>(length) - 2 - i, 0);
            padded[pad + static_cast<int>(length) + i] = audio[src];
        }
        sig = padded.data();
    }

    int num_bins = n_fft / 2 + 1;
    int num_frames = static_cast<int>((sig_len - static_cast<size_t>(win_length))
                                      / hop_length) + 1;
    if (num_frames <= 0) return {};

    auto fb = mel_filterbank(num_mel_bins, n_fft, sample_rate, slaney_norm);

    // Hann window
    std::vector<float> window(win_length);
    for (int i = 0; i < win_length; i++) {
        window[i] = 0.5f * (1.0f - std::cos(2.0f * static_cast<float>(M_PI)
                    * i / (win_length - 1)));
    }

    // STFT + mel
    std::vector<float> mel(num_mel_bins * num_frames);
    std::vector<float> frame(n_fft, 0.0f);
    std::vector<float> spec_re(num_bins), spec_im(num_bins);

    for (int t = 0; t < num_frames; t++) {
        // Windowed frame (zero-padded if win_length < n_fft)
        std::fill(frame.begin(), frame.end(), 0.0f);
        for (int i = 0; i < win_length; i++) {
            frame[i] = sig[t * hop_length + i] * window[i];
        }

        fft_real(frame.data(), n_fft, spec_re.data(), spec_im.data());

        // Power spectrum → mel → log
        for (int m = 0; m < num_mel_bins; m++) {
            float sum = 0.0f;
            for (int f = 0; f < num_bins; f++) {
                float power = spec_re[f] * spec_re[f]
                            + spec_im[f] * spec_im[f];
                sum += power * fb[m * num_bins + f];
            }
            mel[m * num_frames + t] = std::log(sum + log_floor);
        }
    }

    return mel;
}
