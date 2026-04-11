package audio.soniqo.speech

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

    /** Emit partial transcription events during speech (words appear as you speak). */
    val emitPartialTranscriptions: Boolean = false,

    /** Interval between partial transcriptions in seconds. */
    val partialTranscriptionInterval: Float = 0.5f,
)
