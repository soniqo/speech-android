package audio.soniqo.speech

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * On-device speech pipeline — VAD → STT → (LLM) → TTS with optional noise cancellation.
 *
 * Wraps speech-core (C++) via JNI. All local inference runs via ONNX Runtime with
 * NNAPI acceleration on Qualcomm Snapdragon / Samsung Exynos.
 *
 * Set [SpeechConfig.llmApiKey] to enable full agent mode (STT → Claude → TTS).
 * Without it the pipeline runs in echo mode (STT → TTS).
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
class SpeechPipeline(private val config: SpeechConfig) : AutoCloseable {

    private val _events = MutableSharedFlow<SpeechEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Stream of pipeline events (speech start/end, transcriptions, audio). */
    val events: SharedFlow<SpeechEvent> = _events.asSharedFlow()

    private val nativeCallback = object : NativeBridge.EventCallback {
        override fun onEvent(
            type: Int, text: String?, audio: ByteArray?,
            confidence: Float, sttMs: Float, ttsMs: Float,
        ) {
            val event = when (type) {
                0  -> SpeechEvent.SessionCreated
                1  -> SpeechEvent.SpeechStarted
                2  -> SpeechEvent.SpeechEnded
                3  -> SpeechEvent.PartialTranscription(text ?: "", confidence)
                4  -> SpeechEvent.TranscriptionCompleted(text ?: "", confidence, sttMs)
                5  -> SpeechEvent.ResponseCreated
                6  -> SpeechEvent.ResponseInterrupted
                7  -> audio?.let { SpeechEvent.ResponseAudioDelta(it, ttsMs) }
                8  -> SpeechEvent.ResponseDone
                11 -> SpeechEvent.Error(text ?: "unknown error")
                else -> null
            } ?: return

            _events.tryEmit(event)
        }
    }

    private val llmBridge: LlmBridge? = config.llmApiKey?.let { key ->
        LlmBridge(key, config.llmModel, config.systemPrompt)
    }

    private val llmCallback: NativeBridge.LlmCallback? = llmBridge?.let { bridge ->
        object : NativeBridge.LlmCallback {
            override fun chat(
                roles: Array<String>, contents: Array<String>,
                onTokenFnPtr: Long, tokenCtxPtr: Long,
            ) = bridge.chat(roles, contents, onTokenFnPtr, tokenCtxPtr)

            override fun cancel() = bridge.cancel()
        }
    }

    private var handle: Long = NativeBridge.nativeCreate(
        config.modelDir,
        config.useNnapi,
        config.precision == ModelPrecision.INT8,
        nativeCallback,
        llmCallback,
        config.emitPartialTranscriptions,
        config.partialTranscriptionInterval,
    ).also { h ->
        if (h == 0L) throw IllegalStateException(
            "Failed to create native pipeline. Models may be corrupt — " +
            "try clearing app data and reinstalling."
        )
    }

    val state: PipelineState
        get() = PipelineState.from(NativeBridge.nativeGetState(handle))

    /**
     * Non-null if NNAPI failed during model loading and the engine fell back to CPU.
     * Contains the NNAPI error message. Useful for diagnostics — ask users to report this.
     */
    val nnapiFallbackReason: String?
        get() = NativeBridge.nativeNnapiFallbackReason()

    fun start() {
        NativeBridge.nativeStart(handle)
    }

    fun stop() {
        NativeBridge.nativeStop(handle)
    }

    /** Feed PCM Float32 microphone samples at 16 kHz. */
    fun pushAudio(samples: FloatArray) {
        NativeBridge.nativePushAudio(handle, samples, samples.size)
    }

    /** Signal that response playback finished — resume listening. */
    fun resumeListening() {
        NativeBridge.nativeResumeListen(handle)
    }

    override fun close() {
        if (handle != 0L) {
            NativeBridge.nativeDestroy(handle)
            handle = 0
        }
    }
}
