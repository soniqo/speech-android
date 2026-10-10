package audio.soniqo.speech

internal object NativeBridge {

    external fun nativeCreateKeywordSpotter(modelDir: String, phrases: Array<String>, tokens: Array<IntArray>,
        thresholds: FloatArray, boosts: FloatArray, beamSize: Int): Long
    external fun nativeDestroyKeywordSpotter(handle: Long)
    external fun nativeKeywordPush(handle: Long, samples: FloatArray, count: Int): Array<KeywordDetection>
    external fun nativeKeywordEnd(handle: Long): Array<KeywordDetection>
    external fun nativeKeywordReset(handle: Long)

    init {
        System.loadLibrary("speech_android")
    }

    external fun nativeCreate(
        modelDir: String,
        useNnapi: Boolean,
        useInt8: Boolean,
        sttModel: Int,    // SttModel.ordinal: 0=PARAKEET, 1=NEMOTRON_MULTILINGUAL,
                          //                  2=PARAKEET_EOU, 3=CANARY
        sttBackend: Int,  // SttBackend.ordinal: 0=ONNX, 1=LITERT
        ttsModel: Int,    // 0=KOKORO, 1=SUPERTONIC, 2=KOKORO_SHORT_TURN, 3=POCKET
        pipelineMode: Int, // PipelineMode.ordinal: 0=ECHO, 1=TRANSCRIBE_ONLY
        language: String, // single language hint ("auto", "en-US", ...) — Nemotron prompt + TTS voice
        languageHints: Array<String>, // reserved; no current backend consumes hints
        callback: EventCallback,
        emitPartialTranscriptions: Boolean,
        partialTranscriptionInterval: Float,
        endOfSpeechSilenceSec: Float,  // seconds of silence ending an utterance
        beamSize: Int,                 // Parakeet-EOU RNN-T beam width; <=1 = greedy
        enableSmartTurn: Boolean,      // attach Smart Turn v3.2 after VAD pauses
        turnCompletionThreshold: Float,
        turnCompletionMaxSilenceSec: Float,
        enableEnhancer: Boolean,
    ): Long

    external fun nativeNnapiFallbackReason(): String?
    external fun nativeDestroy(handle: Long)
    external fun nativeStart(handle: Long)
    external fun nativeStop(handle: Long)
    external fun nativeCancelTurn(handle: Long)
    external fun nativePushAudio(handle: Long, samples: FloatArray, count: Int)
    external fun nativeResumeListen(handle: Long)
    external fun nativeGetState(handle: Long): Int

    // Contextual biasing for the Parakeet-EOU streaming STT (no-op for other
    // models or when beamSize <= 1). maxBonus caps each phrase's boost; 0 = off.
    external fun nativeSetContextPhrases(handle: Long, phrases: Array<String>, maxBonus: Float)

    // Direct synthesis with the pipeline's already-loaded TTS model — lets a
    // TRANSCRIBE_ONLY agent loop speak responses without a second TTS copy.
    external fun nativePipelineTtsSampleRate(handle: Long): Int
    // `voice` is a per-call preset ("" = engine default); the bridge restores
    // the default after the call so it never leaks into later synthesis.
    external fun nativePipelineSynthesize(
        handle: Long,
        text: String,
        language: String,
        voice: String,
    ): ByteArray
    external fun nativePipelineSynthesizeStreaming(
        handle: Long,
        text: String,
        language: String,
        voice: String,
        callback: SynthesisCallback,
    )
    external fun nativePipelineCancelSynthesis(handle: Long)

    external fun nativeCreateSynthesizer(
        modelDir: String,
        useNnapi: Boolean,
        ttsModel: Int,    // 0=KOKORO, 1=SUPERTONIC, 2=KOKORO_SHORT_TURN, 3=POCKET
    ): Long
    external fun nativeDestroySynthesizer(handle: Long)
    external fun nativeStopSynthesizer(handle: Long)
    external fun nativeSynthesizerSampleRate(handle: Long): Int
    external fun nativeSynthesize(
        handle: Long,
        text: String,
        language: String,
        voice: String,
    ): ByteArray

    // VAD-only detector: Silero + speech_core::TurnDetector, no STT/TTS model
    // and no VoicePipeline. Must stay in lockstep with the VadHandle section
    // of jni_bridge.cpp.
    external fun nativeCreateVad(
        modelDir: String,
        onsetThreshold: Float,
        offsetThreshold: Float,
        minSpeechDurationSec: Float,
        endOfSpeechSilenceSec: Float,
        preSpeechBufferSec: Float,
        maxUtteranceDurationSec: Float,
        emitUtteranceAudio: Boolean,
        callback: VadCallback,
    ): Long

    external fun nativeDestroyVad(handle: Long)
    external fun nativePushVadAudio(handle: Long, samples: FloatArray, count: Int)
    external fun nativeFlushVad(handle: Long)
    external fun nativeResetVad(handle: Long)
    external fun nativeVadInSpeech(handle: Long): Boolean

    // Standalone meeting-transcription models: a Nemotron multilingual
    // stream, a streaming Sortformer diarizer and a ReDimNet speaker encoder,
    // with no VoicePipeline. Must stay in lockstep with the matching sections
    // of jni_bridge.cpp.
    external fun nativeCreateTranscriber(
        modelDir: String,
        sttBackend: Int, // SttBackend.ordinal: 0=ONNX, 1=LITERT
        hardwareAcceleration: Boolean,
        language: String, // "auto" or a languages.json locale
    ): Long
    external fun nativeDestroyTranscriber(handle: Long)
    external fun nativeTranscriberSetLanguage(handle: Long, locale: String): Boolean
    external fun nativeTranscriberBegin(handle: Long)
    // Returns the open stream's text so far, not only this chunk's.
    external fun nativeTranscriberPush(handle: Long, samples: FloatArray, count: Int): String?
    external fun nativeTranscriberEnd(handle: Long): String?
    external fun nativeTranscriberCancel(handle: Long)
    external fun nativeTranscriberLastConfidence(handle: Long): Float
    // Words of the last push or end: decoded text with its leading space, and
    // [start, end] seconds per word from the start of the stream.
    external fun nativeTranscriberWordTexts(handle: Long): Array<String>?
    external fun nativeTranscriberWordTimes(handle: Long): FloatArray?

    external fun nativeCreateDiarizer(modelDir: String, hardwareAcceleration: Boolean): Long
    external fun nativeDestroyDiarizer(handle: Long)
    // [frames x speakers] probabilities finalised by this call, row by row.
    external fun nativeDiarizerPush(handle: Long, samples: FloatArray, count: Int): FloatArray?
    external fun nativeDiarizerEnd(handle: Long): FloatArray?
    external fun nativeDiarizerReset(handle: Long)
    external fun nativeDiarizerSpeakers(handle: Long): Int
    external fun nativeDiarizerFrameSeconds(handle: Long): Float
    external fun nativeDiarizerFramesEmitted(handle: Long): Long

    external fun nativeCreateEmbedder(modelDir: String, hardwareAcceleration: Boolean): Long
    external fun nativeDestroyEmbedder(handle: Long)
    external fun nativeEmbedderDimension(handle: Long): Int
    external fun nativeEmbedderMinimumSamples(): Int
    external fun nativeEmbed(
        handle: Long,
        samples: FloatArray,
        count: Int,
        sampleRate: Int,
    ): FloatArray?

    /** Called from native code on the thread that pushed the audio. */
    fun interface VadCallback {
        // type: 0=SpeechStarted, 1=SpeechEnded. `audio` is the utterance
        // (with its pre-speech head) on SpeechEnded, null when the detector
        // was created with emitUtteranceAudio = false.
        fun onTurn(type: Int, timeSec: Float, audio: FloatArray?)
    }

    /** Called from native code on the pipeline worker thread. */
    interface EventCallback {
        fun onEvent(
            type: Int,
            text: String?,
            audio: ByteArray?,
            confidence: Float,
            sttMs: Float,
            ttsMs: Float,
        )
    }

    /** Called synchronously from native code after each safe TTS model run. */
    fun interface SynthesisCallback {
        fun onChunk(audio: ByteArray, isFinal: Boolean)
    }
}
