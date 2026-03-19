#include "stft.h"
#include "fft.h"
#include <cstring>
#include <vector>
#include <algorithm>

int stft_num_frames(size_t signal_length, int fft_size, int hop_size) {
    if (static_cast<int>(signal_length) < fft_size) return 0;
    return static_cast<int>((signal_length - fft_size) / hop_size) + 1;
}

void stft_forward(const float* audio, size_t length,
                  int fft_size, int hop_size,
                  const float* window,
                  float* out_real, float* out_imag)
{
    int num_frames = stft_num_frames(length, fft_size, hop_size);
    int freq_bins = fft_size / 2 + 1;
    std::vector<float> frame(fft_size);

    for (int t = 0; t < num_frames; t++) {
        // Apply window
        for (int i = 0; i < fft_size; i++) {
            frame[i] = audio[t * hop_size + i] * window[i];
        }

        fft_real(frame.data(), fft_size,
                 out_real + t * freq_bins,
                 out_imag + t * freq_bins);
    }
}

void stft_inverse(const float* spec_real, const float* spec_imag,
                  int num_frames, int fft_size, int hop_size,
                  const float* window,
                  float* output, size_t out_length)
{
    int freq_bins = fft_size / 2 + 1;
    std::vector<float> frame(fft_size);
    std::vector<float> win_sum(out_length, 0.0f);

    std::memset(output, 0, out_length * sizeof(float));

    for (int t = 0; t < num_frames; t++) {
        ifft_real(spec_real + t * freq_bins,
                  spec_imag + t * freq_bins,
                  fft_size, frame.data());

        // Overlap-add with synthesis window
        for (int i = 0; i < fft_size; i++) {
            size_t idx = t * hop_size + i;
            if (idx >= out_length) break;
            output[idx] += frame[i] * window[i];
            win_sum[idx] += window[i] * window[i];
        }
    }

    // Normalize by window sum
    for (size_t i = 0; i < out_length; i++) {
        if (win_sum[i] > 1e-8f) {
            output[i] /= win_sum[i];
        }
    }
}
