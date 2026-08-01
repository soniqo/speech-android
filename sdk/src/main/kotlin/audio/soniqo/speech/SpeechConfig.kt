package audio.soniqo.speech

enum class ModelPrecision { FP32, INT8 }

/** On-device STT model. PARAKEET_EOU is the low-memory streaming default.
 *  PARAKEET is the larger TDT v3 model with language-token detection;
 *  NEMOTRON_MULTILINGUAL is prompt-conditioned and uses [SpeechConfig.language]. */
enum class SttModel { PARAKEET, NEMOTRON_MULTILINGUAL, PARAKEET_EOU, CANARY }

/** Native inference backend for the STT model. Only Nemotron multilingual
 *  ships both; Parakeet is ONNX-only. */
enum class SttBackend { ONNX, LITERT }

/** On-device TTS model. [KOKORO_SHORT_TURN] uses the same Kokoro weights with
 *  a shorter unrolled graph for bounded voice-agent replies. [KOKORO] keeps
 *  the full-capacity graph; [SUPERTONIC] is a LiteRT flow-matching model
 *  (44.1 kHz, 31 languages, G2P-free). [POCKET] is the true-streaming ONNX
 *  profile: English-only, fixed Alba voice, with 80 ms audio frames. */
enum class TtsModel(internal val nativeId: Int) {
    KOKORO(0),
    SUPERTONIC(1),
    KOKORO_SHORT_TURN(2),
    POCKET(3),
}

/** Optional on-device language-model bundle. [FUNCTIONGEMMA] is the original
 *  standalone export. [FUNCTIONGEMMA_CONTROL_LORA] is the reusable
 *  LoRA-enabled Android base plus the separately loaded Control adapter. */
enum class LlmModel {
    FUNCTIONGEMMA,
    FUNCTIONGEMMA_CONTROL_LORA,
}

internal val TtsModel.isKokoro: Boolean
    get() = this == TtsModel.KOKORO || this == TtsModel.KOKORO_SHORT_TURN

/** What the pipeline does after a completed transcription. ECHO speaks the
 *  transcript back via TTS (demo/testing); TRANSCRIBE_ONLY emits
 *  TranscriptionCompleted and returns to idle — for dictation or an
 *  app-side agent loop that decides the response itself. */
enum class PipelineMode { ECHO, TRANSCRIBE_ONLY }

data class SpeechConfig(
    /** Path to directory containing ONNX model files. */
    val modelDir: String = "",

    /** Try the deprecated NNAPI execution provider. CPU is the measured,
     *  portable default; hardware-provider experiments are opt-in. */
    val useNnapi: Boolean = false,

    /** Which STT model to load. */
    val sttModel: SttModel = SttModel.PARAKEET_EOU,

    /** STT inference backend (Nemotron multilingual supports both). */
    val sttBackend: SttBackend = SttBackend.ONNX,

    /** Which TTS model/profile to load. The short-turn Kokoro graph is the
     *  Android default because measured in-profile replies stay faster than
     *  real time on CPU; oversized replies are split and retried safely. */
    val ttsModel: TtsModel = TtsModel.KOKORO_SHORT_TURN,

    /** Pipeline behavior after transcription. See [PipelineMode]. */
    val pipelineMode: PipelineMode = PipelineMode.ECHO,

    /** Language/locale for prompt-conditioned STT models (Nemotron) and TTS
     *  voice selection: e.g. "en-US", "fr", "ja-JP". "auto" lets the model
     *  decide. Parakeet TDT always autodetects — the transducer has no
     *  forcing mechanism, matching every other Parakeet runtime. */
    val language: String = "auto",

    /** Enable noise cancellation (DeepFilterNet3). */
    val enableEnhancer: Boolean = true,

    /** Model quantization — INT8 recommended for mobile. */
    val precision: ModelPrecision = ModelPrecision.INT8,

    /** Emit partial transcription events during speech (words appear as you speak). */
    val emitPartialTranscriptions: Boolean = false,

    /** Interval between partial transcriptions in seconds. */
    val partialTranscriptionInterval: Float = 0.5f,

    /** Reserved language shortlist for future prompt-conditioned backends.
     *  No current STT backend consumes it: Nemotron takes a single
     *  [language], and Parakeet TDT always autodetects. */
    val languageHints: List<String> = emptyList(),

    /** RNN-T beam width for the Parakeet-EOU streaming STT. `<= 1` keeps the
     *  greedy default; `> 1` enables modified beam search, which contextual
     *  biasing ([SpeechPipeline.setContextPhrases]) rides on. Ignored by other
     *  STT models. */
    val beamSize: Int = 0,

    /** Seconds of detected silence that end an utterance. The 0.5 s default
     *  favors snappy short commands; raise it (0.8–1.2 s) when users pause
     *  mid-utterance — dictating digit sequences, thinking through a
     *  sentence — and get cut off early. */
    val endOfSpeechSilenceSec: Float = 0.5f,
)
