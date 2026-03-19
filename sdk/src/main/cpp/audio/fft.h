#pragma once

#include <cstddef>
#include <vector>

/// Minimal radix-2 FFT for Android NDK (no external dependencies).
/// Operates on real signals — returns complex spectrum [0..N/2].

void fft_real(const float* input, size_t n,
              float* out_real, float* out_imag);

void ifft_real(const float* in_real, const float* in_imag, size_t n,
               float* output);
