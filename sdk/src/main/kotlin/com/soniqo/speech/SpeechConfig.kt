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

    /** Anthropic API key. If set, enables full pipeline mode (STT → LLM → TTS). */
    val llmApiKey: String? = null,

    /** System prompt for the LLM agent. */
    val systemPrompt: String = "You are a helpful voice assistant. Keep responses concise.",

    /** Claude model to use for LLM inference. */
    val llmModel: String = "claude-sonnet-4-6",
)
