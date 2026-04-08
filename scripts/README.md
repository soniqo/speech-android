# Model Conversion Scripts

Convert ONNX models to TFLite format for the LiteRT inference backend.

## Prerequisites

- Windows 10/11 with Python 3.10+
- ~16GB RAM for large model conversion (Parakeet encoder)
- CUDA optional (speeds up conversion)

## Setup

```batch
cd scripts
setup_convert_env.bat
```

## Usage

1. Download ONNX models from HuggingFace:
   - [Silero-VAD-v5-ONNX](https://huggingface.co/aufklarer/Silero-VAD-v5-ONNX)
   - [Parakeet-TDT-v3-ONNX](https://huggingface.co/aufklarer/Parakeet-TDT-v3-ONNX)
   - [Kokoro-82M-ONNX](https://huggingface.co/aufklarer/Kokoro-82M-ONNX)
   - [DeepFilterNet3-ONNX](https://huggingface.co/aufklarer/DeepFilterNet3-ONNX)

2. Run conversion:
   ```batch
   .venv\Scripts\activate.bat
   convert_models.bat C:\models\onnx C:\models\tflite
   ```

3. Upload `.tflite` files to HuggingFace repos.

## Single model conversion

```batch
python convert_single.py --input model.onnx --output model.tflite --quantize int8
```

Options:
- `--quantize none` — FP32 (default)
- `--quantize int8` — INT8 post-training quantization
- `--quantize fp16` — FP16 half precision
- `--external_data path.onnx.data` — for models with external weights (Kokoro)
- `--skip_validation` — skip TFLite loading test after conversion
