package audio.soniqo.speech

/** Final result of one bounded Parakeet TDT v3 utterance. Word positions are
 * decoder emission times, in seconds from the first submitted sample. */
data class ParakeetTranscript(val text: String, val words: List<TimedToken>, val confidence: Float)

/** Standalone multilingual Parakeet TDT v3 INT8, with no capture, VAD or TTS.
 *
 * This is a batch API: call once per utterance, off the UI thread. It does not
 * repeatedly decode a growing microphone buffer. The caller owns segmentation;
 * 16 kHz mono Float32 input is bounded to 30 seconds per call. The 25 supported
 * languages are detected by the model; there is no language-forcing prompt.
 * [ModelManager.ensureParakeetTranscriberModels] downloads the pinned bundle.
 */
interface ParakeetTranscriber : AutoCloseable {
    fun transcribe(samples: FloatArray, count: Int = samples.size): ParakeetTranscript

    companion object {
        const val MAXIMUM_SAMPLES = 30 * 16_000

        operator fun invoke(modelDir: String): ParakeetTranscriber {
            require(modelDir.isNotBlank()) { "Parakeet modelDir must not be blank" }
            return ParakeetTranscriberImpl(modelDir)
        }
    }
}

internal fun requireParakeetSamples(samples: FloatArray, count: Int) {
    requireSampleCount(samples, count)
    require(count <= ParakeetTranscriber.MAXIMUM_SAMPLES) { "Parakeet utterances are limited to 30 seconds" }
    for (index in 0 until count) require(samples[index].isFinite()) { "Audio samples must be finite" }
}

internal class ParakeetTranscriberImpl(modelDir: String) : ParakeetTranscriber {
    private var handle = NativeBridge.nativeCreateParakeetTranscriber(modelDir)

    init { check(handle != 0L) { "Failed to create Parakeet transcriber" } }

    @Synchronized
    override fun transcribe(samples: FloatArray, count: Int): ParakeetTranscript {
        check(handle != 0L) { "ParakeetTranscriber is closed" }
        requireParakeetSamples(samples, count)
        val text = NativeBridge.nativeParakeetTranscribe(handle, samples, count).orEmpty().trim()
        val texts = NativeBridge.nativeTranscriberWordTexts(handle).orEmpty()
        val times = NativeBridge.nativeTranscriberWordTimes(handle) ?: FloatArray(0)
        val words = texts.indices.mapNotNull { index ->
            val word = texts[index].trim()
            if (word.isEmpty() || times.size < (index + 1) * 2) null
            else TimedToken(word, times[index * 2], times[index * 2 + 1])
        }
        return ParakeetTranscript(text, words, NativeBridge.nativeTranscriberLastConfidence(handle))
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            NativeBridge.nativeDestroyTranscriber(handle)
            handle = 0L
        }
    }
}
