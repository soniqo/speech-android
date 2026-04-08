#pragma once

#include "inference_engine.h"
#include "onnx_engine.h"
#include <memory>
#include <string>
#include <vector>

/// ONNX Runtime output tensor — wraps OrtValue*.
class OnnxOutputTensor : public OutputTensor {
public:
    OnnxOutputTensor(const OrtApi* api, OrtValue* value) : api_(api), value_(value) {}

    ~OnnxOutputTensor() override {
        if (value_) api_->ReleaseValue(value_);
    }

    float* data_float() override {
        float* data = nullptr;
        ort_check(api_, api_->GetTensorMutableData(value_, (void**)&data));
        return data;
    }

    int64_t* data_int64() override {
        int64_t* data = nullptr;
        ort_check(api_, api_->GetTensorMutableData(value_, (void**)&data));
        return data;
    }

    std::vector<int64_t> shape() override {
        OrtTensorTypeAndShapeInfo* info = nullptr;
        ort_check(api_, api_->GetTensorTypeAndShape(value_, &info));
        size_t dim_count = 0;
        api_->GetDimensionsCount(info, &dim_count);
        std::vector<int64_t> dims(dim_count);
        api_->GetDimensions(info, dims.data(), dim_count);
        api_->ReleaseTensorTypeAndShapeInfo(info);
        return dims;
    }

    size_t element_count() override {
        auto s = shape();
        size_t n = 1;
        for (auto d : s) n *= static_cast<size_t>(d);
        return n;
    }

private:
    const OrtApi* api_;
    OrtValue* value_;
};

/// ONNX Runtime session — wraps OrtSession*.
class OnnxSession : public InferenceSession {
public:
    OnnxSession(const OrtApi* api, OrtSession* session)
        : api_(api), session_(session) {}

    ~OnnxSession() override {
        if (session_) api_->ReleaseSession(session_);
    }

    std::vector<std::unique_ptr<OutputTensor>> run(
        const std::vector<const char*>& input_names,
        const std::vector<TensorInfo>& inputs,
        const std::vector<const char*>& output_names) override
    {
        auto* mem = OnnxEngine::get().cpu_memory();
        size_t num_in = inputs.size();
        size_t num_out = output_names.size();

        // Create input OrtValues
        std::vector<OrtValue*> ort_inputs(num_in, nullptr);
        for (size_t i = 0; i < num_in; i++) {
            auto& t = inputs[i];
            ONNXTensorElementDataType ort_dtype;
            switch (t.dtype) {
                case DType::FLOAT32: ort_dtype = ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT; break;
                case DType::INT64:   ort_dtype = ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64; break;
                case DType::INT32:   ort_dtype = ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32; break;
                case DType::INT8:    ort_dtype = ONNX_TENSOR_ELEMENT_DATA_TYPE_INT8; break;
            }
            ort_check(api_, api_->CreateTensorWithDataAsOrtValue(
                mem,
                const_cast<void*>(t.data),
                t.byte_size(),
                t.shape.data(),
                t.shape.size(),
                ort_dtype,
                &ort_inputs[i]));
        }

        // Prepare output array
        std::vector<OrtValue*> ort_outputs(num_out, nullptr);

        // Run
        ort_check(api_, api_->Run(
            session_, nullptr,
            input_names.data(), ort_inputs.data(), num_in,
            output_names.data(), num_out, ort_outputs.data()));

        // Release inputs
        for (auto* v : ort_inputs) api_->ReleaseValue(v);

        // Wrap outputs
        std::vector<std::unique_ptr<OutputTensor>> results;
        results.reserve(num_out);
        for (auto* v : ort_outputs) {
            results.push_back(std::make_unique<OnnxOutputTensor>(api_, v));
        }
        return results;
    }

private:
    const OrtApi* api_;
    OrtSession* session_;
};

/// ONNX Runtime backend — delegates to OnnxEngine singleton.
class OnnxBackend : public InferenceBackend {
public:
    std::unique_ptr<InferenceSession> load(
        const std::string& path, bool hw_accel = true) override
    {
        auto& engine = OnnxEngine::get();
        OrtSession* session = engine.load(path, hw_accel);
        return std::make_unique<OnnxSession>(engine.api(), session);
    }

    Backend type() const override { return Backend::ONNX; }
};
