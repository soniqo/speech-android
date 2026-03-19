#pragma once

#include <cstddef>

/// Number of STFT frames for a given signal length.
int stft_num_frames(size_t signal_length, int fft_size, int hop_size);

/// Forward STFT with overlap-add windowing.
/// @param audio       Input signal
/// @param length      Number of samples
/// @param fft_size    FFT size (e.g. 960 for DeepFilterNet3)
/// @param hop_size    Hop size (e.g. 480)
/// @param window      Analysis window [fft_size]
/// @param out_real    Output real spectrum [num_frames * freq_bins]
/// @param out_imag    Output imaginary spectrum [num_frames * freq_bins]
void stft_forward(const float* audio, size_t length,
                  int fft_size, int hop_size,
                  const float* window,
                  float* out_real, float* out_imag);

/// Inverse STFT via overlap-add.
/// @param spec_real   Real spectrum [num_frames * freq_bins]
/// @param spec_imag   Imaginary spectrum [num_frames * freq_bins]
/// @param num_frames  Number of STFT frames
/// @param fft_size    FFT size
/// @param hop_size    Hop size
/// @param window      Synthesis window [fft_size]
/// @param output      Output signal buffer
/// @param out_length  Expected output length (samples)
void stft_inverse(const float* spec_real, const float* spec_imag,
                  int num_frames, int fft_size, int hop_size,
                  const float* window,
                  float* output, size_t out_length);
