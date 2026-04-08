#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

/// Supported inference backends.
enum class Backend { ONNX, LITERT, AUTO };

/// Tensor element data types.
enum class DType { FLOAT32, INT64, INT32, INT8 };

/// Describes a tensor's data, shape, and type for passing to inference.
struct TensorInfo {
    const void* data;
    std::vector<int64_t> shape;
    DType dtype;

    size_t byte_size() const {
        size_t elems = 1;
        for (auto d : shape) elems *= static_cast<size_t>(d);
        switch (dtype) {
            case DType::FLOAT32: return elems * 4;
            case DType::INT64:   return elems * 8;
            case DType::INT32:   return elems * 4;
            case DType::INT8:    return elems * 1;
        }
        return elems * 4;
    }
};

/// Wraps a single output tensor from an inference call.
/// Owns the backend-specific memory — valid until destroyed or next run().
class OutputTensor {
public:
    virtual ~OutputTensor() = default;

    virtual float* data_float() = 0;
    virtual int64_t* data_int64() = 0;
    virtual std::vector<int64_t> shape() = 0;
    virtual size_t element_count() = 0;
};

/// A loaded model session — run inference with named inputs/outputs.
class InferenceSession {
public:
    virtual ~InferenceSession() = default;

    /// Run inference. Outputs are returned as owned OutputTensor objects.
    virtual std::vector<std::unique_ptr<OutputTensor>> run(
        const std::vector<const char*>& input_names,
        const std::vector<TensorInfo>& inputs,
        const std::vector<const char*>& output_names) = 0;
};

/// Factory for loading models. Each backend implements this.
class InferenceBackend {
public:
    virtual ~InferenceBackend() = default;

    virtual std::unique_ptr<InferenceSession> load(
        const std::string& path, bool hw_accel = true) = 0;

    virtual Backend type() const = 0;
};

/// Detect the optimal backend for the current device's SoC.
Backend detect_optimal_backend();

/// Create a backend instance. AUTO resolves via detect_optimal_backend().
std::unique_ptr<InferenceBackend> create_backend(Backend preference);
