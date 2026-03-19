#pragma once

#include <cstddef>
#include <vector>

/// Compute log-mel spectrogram from raw audio.
/// Returns flattened [num_mel_bins, num_frames] in column-major order
/// (mel bin varies fastest) matching Parakeet's expected layout.
std::vector<float> mel_spectrogram(
    const float* audio, size_t length,
    int sample_rate, int n_fft, int hop_length,
    int win_length, int num_mel_bins);
