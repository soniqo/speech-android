package com.soniqo.speech

enum class ModelPrecision { FP32, INT8 }

data class SpeechConfig(
    /** Path to directory containing ONNX model files. */
    val modelDir: String = "",

    /** Enable NNAPI acceleration (Qualcomm Hexagon NPU / Samsung NPU). */
    val useNnapi: Boolean = true,

    /** Enable noise cancellation (DeepFilterNet3). */
    val enableEnhancer: Boolean = true,

    /** Model quantization — INT8 recommended for mobile. */
    val precision: ModelPrecision = ModelPrecision.INT8,
)
