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

    /** Language/locale for prompt-conditioned STT models: e.g. "en-US", "fr",
     *  "zh-CN". "auto" lets the model decide. Parakeet TDT and Parakeet-EOU
     *  always autodetect and reject a concrete value because neither model has
     *  a language-prompt input. */
    val language: String = "auto",

    /** Stream microphone audio through DeepFilterNet3 before VAD/STT. Uses
     *  CPU inference with persistent 16↔48 kHz resampling and adds 40 ms of
     *  model delay. [ModelManager.ensureModels] must use the same flag. */
    val enableEnhancer: Boolean = true,

    /** Model quantization — INT8 recommended for mobile. */
    val precision: ModelPrecision = ModelPrecision.INT8,

    /** Emit partial transcription events during speech (words appear as you speak). */
    val emitPartialTranscriptions: Boolean = false,

    /** Interval between partial transcriptions in seconds. */
    val partialTranscriptionInterval: Float = 0.5f,

    /** Reserved for a future backend that can constrain automatic detection.
     *  No current STT backend consumes a shortlist, so a non-empty value is
     *  rejected instead of being silently ignored. Nemotron takes one
     *  [language]; both Parakeet models always autodetect. */
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

    /** Use Pipecat Smart Turn v3.2 after VAD detects a pause. The classifier
     *  considers the last eight seconds of the current turn and keeps the turn
     *  open when the user's prosody sounds incomplete. Requires
     *  [ModelManager.ensureModels] or [ModelDownloadWorker] to be called with
     *  their matching `enableSmartTurn = true` option. */
    val enableSmartTurn: Boolean = false,

    /** Smart Turn probability that confirms the user has finished speaking. */
    val turnCompletionThreshold: Float = 0.5f,

    /** Maximum silence after Smart Turn vetoes an endpoint. Measured from the
     *  start of the pause; `0` disables the silence cap. */
    val turnCompletionMaxSilenceSec: Float = 2.0f,
)

internal fun SpeechConfig.requireValidConfiguration() {
    requireValidLanguageConfiguration()
    require(turnCompletionThreshold in 0f..1f) {
        "SpeechConfig.turnCompletionThreshold must be between 0 and 1"
    }
    require(turnCompletionMaxSilenceSec.isFinite() && turnCompletionMaxSilenceSec >= 0f) {
        "SpeechConfig.turnCompletionMaxSilenceSec must be finite and non-negative"
    }
}

/** Validate language settings before JNI loads models or starts native work. */
internal fun SpeechConfig.requireValidLanguageConfiguration() {
    val requested = language.trim()
    val base = requested.substringBefore('-').substringBefore('_').lowercase()

    require(base != "cn") {
        "SpeechConfig.language='$language' uses the country code 'cn', not a " +
            "Chinese language tag. Use 'zh-CN' for Simplified Chinese or " +
            "'zh-TW' for Traditional Chinese."
    }
    require(languageHints.isEmpty()) {
        "SpeechConfig.languageHints is not supported by any current STT backend. " +
            "Parakeet models always auto-detect; for one fixed language select " +
            "SttModel.NEMOTRON_MULTILINGUAL and set SpeechConfig.language."
    }

    val automatic = requested.isEmpty() || requested == "auto"
    if (sttModel == SttModel.PARAKEET || sttModel == SttModel.PARAKEET_EOU) {
        require(automatic) {
            "${sttModel.name} always auto-detects language and cannot honor " +
                "SpeechConfig.language='$language'. For a fixed language select " +
                "SttModel.NEMOTRON_MULTILINGUAL (for Chinese, use 'zh-CN' or 'zh-TW')."
        }
    }
}
