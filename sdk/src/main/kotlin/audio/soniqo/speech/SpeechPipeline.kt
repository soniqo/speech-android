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

    /** Feed PCM Float32 microphone samples at 16 kHz. */
    fun pushAudio(samples: FloatArray)

    /** Signal that response playback finished — resume listening. */
    fun resumeListening()

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
        config.ttsModel.ordinal,
        config.language,
        config.languageHints.toTypedArray(),
        nativeCallback,
        config.emitPartialTranscriptions,
        config.partialTranscriptionInterval,
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

    override fun pushAudio(samples: FloatArray) {
        NativeBridge.nativePushAudio(handle, samples, samples.size)
    }

    override fun resumeListening() {
        NativeBridge.nativeResumeListen(handle)
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
