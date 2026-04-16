#pragma once

#include <cstddef>
#include <vector>

/// Compute log-mel spectrogram from raw audio.
/// Returns flattened [num_mel_bins, num_frames] in channels-first layout
/// (row = mel bin, column = time frame).
///
/// Optional parameters (default to the original behaviour):
///   slaney_norm  — area-normalise each triangular filter by its bandwidth
///   log_floor    — additive floor before log: log(x + floor)
///   center       — pad signal by n_fft/2 on each side (reflect mode)
std::vector<float> mel_spectrogram(
    const float* audio, size_t length,
    int sample_rate, int n_fft, int hop_length,
    int win_length, int num_mel_bins,
    bool slaney_norm = false,
    float log_floor = 1e-10f,
    bool center = false);
