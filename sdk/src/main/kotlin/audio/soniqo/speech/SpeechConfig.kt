package audio.soniqo.speech

enum class ModelPrecision { FP32, INT8 }

/** On-device STT model. PARAKEET_EOU is the low-memory streaming default.
 *  PARAKEET is the larger TDT v3 model with language-token detection;
 *  NEMOTRON_MULTILINGUAL is prompt-conditioned and uses [SpeechConfig.language]. */
enum class SttModel { PARAKEET, NEMOTRON_MULTILINGUAL, PARAKEET_EOU }

/** Native inference backend for the STT model. Only Nemotron multilingual
 *  ships both; Parakeet is ONNX-only. */
enum class SttBackend { ONNX, LITERT }

/** On-device TTS model. KOKORO (ONNX, 24 kHz) is the default; SUPERTONIC is a
 *  LiteRT non-autoregressive flow-matching model (Supertonic-3, 44.1 kHz, 31
 *  languages, G2P-free) — requires the LiteRT backend to be built into the SDK. */
enum class TtsModel { KOKORO, SUPERTONIC }

data class SpeechConfig(
    /** Path to directory containing ONNX model files. */
    val modelDir: String = "",

    /** Enable NNAPI acceleration (Qualcomm Hexagon NPU / Samsung NPU). */
    val useNnapi: Boolean = true,

    /** Which STT model to load. */
    val sttModel: SttModel = SttModel.PARAKEET_EOU,

    /** STT inference backend (Nemotron multilingual supports both). */
    val sttBackend: SttBackend = SttBackend.ONNX,

    /** Which TTS model to load. SUPERTONIC requires the LiteRT backend. */
    val ttsModel: TtsModel = TtsModel.KOKORO,

    /** Language/locale prompt for prompt-conditioned models and single-language
     *  Parakeet TDT guidance: e.g. "en-US", "fr", "ja-JP". "auto" lets the
     *  model decide, optionally constrained by [languageHints]. */
    val language: String = "auto",

    /** Enable noise cancellation (DeepFilterNet3). */
    val enableEnhancer: Boolean = true,

    /** Model quantization — INT8 recommended for mobile. */
    val precision: ModelPrecision = ModelPrecision.INT8,

    /** Emit partial transcription events during speech (words appear as you speak). */
    val emitPartialTranscriptions: Boolean = false,

    /** Interval between partial transcriptions in seconds. */
    val partialTranscriptionInterval: Float = 0.5f,

    /** Optional language shortlist for Parakeet TDT language-token guidance.
     *  Entries may be ISO codes or BCP-47 tags, e.g. listOf("en-US", "fr").
     *  Ignored when [language] selects a concrete non-auto language. */
    val languageHints: List<String> = emptyList(),
)
