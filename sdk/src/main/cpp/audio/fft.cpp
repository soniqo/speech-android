#include "fft.h"
#include <cmath>
#include <vector>

static void fft_complex(float* re, float* im, size_t n, bool inverse) {
    // Bit-reversal permutation
    for (size_t i = 1, j = 0; i < n; i++) {
        size_t bit = n >> 1;
        while (j & bit) { j ^= bit; bit >>= 1; }
        j ^= bit;
        if (i < j) {
            std::swap(re[i], re[j]);
            std::swap(im[i], im[j]);
        }
    }

    // Cooley-Tukey
    float sign = inverse ? 1.0f : -1.0f;
    for (size_t len = 2; len <= n; len <<= 1) {
        float ang = sign * 2.0f * static_cast<float>(M_PI) / static_cast<float>(len);
        float wr = std::cos(ang), wi = std::sin(ang);

        for (size_t i = 0; i < n; i += len) {
            float cur_r = 1.0f, cur_i = 0.0f;
            for (size_t j = 0; j < len / 2; j++) {
                size_t u = i + j, v = i + j + len / 2;
                float tr = re[v] * cur_r - im[v] * cur_i;
                float ti = re[v] * cur_i + im[v] * cur_r;
                re[v] = re[u] - tr;
                im[v] = im[u] - ti;
                re[u] += tr;
                im[u] += ti;
                float new_r = cur_r * wr - cur_i * wi;
                cur_i = cur_r * wi + cur_i * wr;
                cur_r = new_r;
            }
        }
    }

    if (inverse) {
        float inv_n = 1.0f / static_cast<float>(n);
        for (size_t i = 0; i < n; i++) {
            re[i] *= inv_n;
            im[i] *= inv_n;
        }
    }
}

// Zero-pad to next power of 2 for non-power-of-2 FFT sizes
static size_t next_pow2(size_t n) {
    size_t p = 1;
    while (p < n) p <<= 1;
    return p;
}

void fft_real(const float* input, size_t n,
              float* out_real, float* out_imag)
{
    size_t N = next_pow2(n);
    std::vector<float> re(N, 0.0f), im(N, 0.0f);
    for (size_t i = 0; i < n; i++) re[i] = input[i];

    fft_complex(re.data(), im.data(), N, false);

    size_t bins = n / 2 + 1;
    for (size_t i = 0; i < bins; i++) {
        out_real[i] = re[i];
        out_imag[i] = im[i];
    }
}

void ifft_real(const float* in_real, const float* in_imag, size_t n,
               float* output)
{
    size_t N = next_pow2(n);
    std::vector<float> re(N, 0.0f), im(N, 0.0f);

    size_t bins = n / 2 + 1;
    for (size_t i = 0; i < bins; i++) {
        re[i] = in_real[i];
        im[i] = in_imag[i];
    }
    // Conjugate symmetry
    for (size_t i = bins; i < N; i++) {
        re[i] =  re[N - i];
        im[i] = -im[N - i];
    }

    fft_complex(re.data(), im.data(), N, true);

    for (size_t i = 0; i < n; i++) output[i] = re[i];
}
