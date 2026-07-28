package audio.soniqo.speech

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * On-device speech pipeline — VAD → STT → TTS with optional noise cancellation.
 *
 * Wraps speech-core (C++) via JNI. All inference runs locally via ONNX Runtime
 * with NNAPI acceleration on Qualcomm Snapdragon / Samsung Exynos.
 *
 * Construct via `SpeechPipeline(config)`. Tests can supply their own
 * implementation of this interface to avoid loading the native library.
 *
 * Usage:
 * ```
 * val pipeline = SpeechPipeline(config)
 * pipeline.events.collect { event -> ... }
 * pipeline.start()
 * pipeline.pushAudio(micSamples)
 * pipeline.stop()
 * pipeline.close()
 * ```
 */
interface SpeechPipeline : AutoCloseable {

    /** Stream of pipeline events (speech start/end, transcriptions, audio). */
    val events: SharedFlow<SpeechEvent>

    val state: PipelineState

    /**
     * Non-null if NNAPI failed during model loading and the engine fell back to CPU.
     * Contains the NNAPI error message. Useful for diagnostics — ask users to report this.
     */
    val nnapiFallbackReason: String?

    fun start()
    fun stop()

    /**
     * Throw away the turn in flight without tearing down the models or worker
     * threads: buffered and queued audio is dropped, in-progress STT/LLM/TTS
     * results are invalidated, and turn detection resets for an independent
     * stream. What a long-lived listener wants when the user abandons an
     * utterance — [stop] plus [start] would do the same but costs a restart.
     *
     * The default is a no-op so custom implementations keep compiling; they
     * inherit no isolation guarantee and should override it.
     */
    fun cancelCurrentTurn() {}

    /** Feed PCM Float32 microphone samples at 16 kHz. */
    fun pushAudio(samples: FloatArray)

    /** Signal that response playback finished — resume listening. */
    fun resumeListening()

    /**
     * Install contextual-biasing phrases for the Parakeet-EOU streaming STT
     * (no-op for other models or when [SpeechConfig.beamSize] `<= 1`). Nudges
     * recognition toward these surface phrases — command words, a brand name,
     * the contact / track names currently on the device. Call between
     * utterances and rebuild per turn to reflect live device state. [maxBonus]
     * caps each phrase's boost so a long list can't override clear audio; an
     * empty list clears biasing.
     */
    fun setContextPhrases(phrases: List<String>, maxBonus: Float = 6f) {}

    /** Sample rate of [synthesize] output, or 0 if unsupported. */
    val ttsSampleRate: Int get() = 0

    /**
     * Synthesize [text] with the pipeline's own TTS model — no second model
     * instance. Intended for [PipelineMode.TRANSCRIBE_ONLY] agent loops that
     * compose their response app-side; in ECHO mode the pipeline synthesizes
     * on its own and this must not be called concurrently with a response.
     */
    fun synthesize(text: String, language: String = "en"): SpeechSynthesisResult =
        throw UnsupportedOperationException("This SpeechPipeline does not support direct synthesis")

    /**
     * Synthesize [text] and deliver each safe native model chunk as soon as it
     * is available. The call is blocking; invoke it off the main thread. The
     * default preserves compatibility with custom pipelines by emitting one
     * buffered result.
     */
    fun synthesizeStreaming(
        text: String,
        language: String = "en",
        onChunk: (result: SpeechSynthesisResult, isFinal: Boolean) -> Unit,
    ) {
        onChunk(synthesize(text, language), true)
    }

    /** Cancel an in-progress [synthesize]. */
    fun cancelSynthesis() {}

    companion object {
        operator fun invoke(config: SpeechConfig): SpeechPipeline = SpeechPipelineImpl(config)
    }
}

internal class SpeechPipelineImpl(config: SpeechConfig) : SpeechPipeline {

    private val _events = MutableSharedFlow<SpeechEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: SharedFlow<SpeechEvent> = _events.asSharedFlow()

    private val nativeCallback = object : NativeBridge.EventCallback {
        override fun onEvent(
            type: Int, text: String?, audio: ByteArray?,
            confidence: Float, sttMs: Float, ttsMs: Float,
        ) {
            val event = nativeEventToSpeechEvent(
                type = type,
                text = text,
                audio = audio,
                confidence = confidence,
                sttMs = sttMs,
                ttsMs = ttsMs,
            ) ?: return

            _events.tryEmit(event)
        }
    }

    private var handle: Long = NativeBridge.nativeCreate(
        config.modelDir,
        config.useNnapi,
        config.precision == ModelPrecision.INT8,
        config.sttModel.ordinal,
        config.sttBackend.ordinal,
        config.ttsModel.nativeId,
        config.pipelineMode.ordinal,
        config.language,
        config.languageHints.toTypedArray(),
        nativeCallback,
        config.emitPartialTranscriptions,
        config.partialTranscriptionInterval,
        config.endOfSpeechSilenceSec,
        config.beamSize,
    ).also { h ->
        if (h == 0L) throw IllegalStateException(
            "Failed to create native pipeline. Models may be corrupt — " +
            "try clearing app data and reinstalling."
        )
    }

    override val state: PipelineState
        get() = PipelineState.from(NativeBridge.nativeGetState(handle))

    override val nnapiFallbackReason: String?
        get() = NativeBridge.nativeNnapiFallbackReason()

    override fun start() {
        NativeBridge.nativeStart(handle)
    }

    override fun stop() {
        NativeBridge.nativeStop(handle)
    }

    override fun cancelCurrentTurn() {
        NativeBridge.nativeCancelTurn(handle)
    }

    override fun pushAudio(samples: FloatArray) {
        NativeBridge.nativePushAudio(handle, samples, samples.size)
    }

    override fun resumeListening() {
        NativeBridge.nativeResumeListen(handle)
    }

    override fun setContextPhrases(phrases: List<String>, maxBonus: Float) {
        NativeBridge.nativeSetContextPhrases(handle, phrases.toTypedArray(), maxBonus)
    }

    override val ttsSampleRate: Int
        get() = NativeBridge.nativePipelineTtsSampleRate(handle)

    override fun synthesize(text: String, language: String): SpeechSynthesisResult {
        check(handle != 0L) { "SpeechPipeline is closed" }
        return SpeechSynthesisResult(
            sampleRate = ttsSampleRate,
            pcm16 = NativeBridge.nativePipelineSynthesize(handle, text, language),
        )
    }

    override fun synthesizeStreaming(
        text: String,
        language: String,
        onChunk: (result: SpeechSynthesisResult, isFinal: Boolean) -> Unit,
    ) {
        check(handle != 0L) { "SpeechPipeline is closed" }
        val sampleRate = ttsSampleRate
        NativeBridge.nativePipelineSynthesizeStreaming(
            handle,
            text,
            language,
            NativeBridge.SynthesisCallback { audio, isFinal ->
                onChunk(SpeechSynthesisResult(sampleRate, audio), isFinal)
            },
        )
    }

    override fun cancelSynthesis() {
        if (handle != 0L) NativeBridge.nativePipelineCancelSynthesis(handle)
    }

    override fun close() {
        if (handle != 0L) {
            NativeBridge.nativeDestroy(handle)
            handle = 0
        }
    }
}

internal fun nativeEventToSpeechEvent(
    type: Int,
    text: String?,
    audio: ByteArray?,
    confidence: Float,
    sttMs: Float,
    ttsMs: Float,
): SpeechEvent? = when (type) {
    0  -> SpeechEvent.SessionCreated
    1  -> SpeechEvent.SpeechStarted
    2  -> SpeechEvent.SpeechEnded
    3  -> SpeechEvent.PartialTranscription(text ?: "", confidence)
    4  -> SpeechEvent.TranscriptionCompleted(text ?: "", confidence, sttMs)
    5  -> SpeechEvent.ResponseCreated
    6  -> SpeechEvent.ResponseInterrupted
    7  -> audio?.let { SpeechEvent.ResponseAudioDelta(it, ttsMs) }
    8  -> SpeechEvent.ResponseDone(ttsMs)
    11 -> SpeechEvent.Error(text ?: "unknown error")
    else -> null
}
