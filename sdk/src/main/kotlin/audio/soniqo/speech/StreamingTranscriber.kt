package audio.soniqo.speech

/**
 * Configuration for [StreamingTranscriber]: the Nemotron-3.5 multilingual
 * recognizer on its own, with no VAD, TTS or pipeline around it.
 */
data class TranscriberConfig(
    /** Directory returned by [ModelManager.ensureTranscriberModels]. */
    val modelDir: String,

    /** LiteRT runs the INT8 bundle, or FP16 for [ModelPrecision.FP32]. ONNX
     *  always runs the FP16 export on Android: the mobile ONNX Runtime has no
     *  ConvInteger kernel for the INT8 encoder. */
    val backend: SttBackend = SttBackend.LITERT,

    /** The precision the models were downloaded with. Only the LiteRT bundle
     *  differs by precision, and it has to match [modelDir]. */
    val precision: ModelPrecision = ModelPrecision.INT8,

    /** A locale key from the bundle's `languages.json` ("en-US", "pt-BR",
     *  "zh-CN"), or "auto" for the model's automatic-language prompt. */
    val language: String = "auto",

    /** Try a hardware delegate for the encoder. CPU is the measured default. */
    val hardwareAcceleration: Boolean = false,

    /** Optional experimental soft guide, used only with the automatic prompt. */
    val languageGuide: LanguageGuideConfig? = null,
)

/** Fractional prompt conditioning: Auto keeps 90% at the default weight.
 * Other languages remain possible. This is not calibrated language confidence
 * or a vocabulary restriction. The list is copied at construction. */
class LanguageGuideConfig(
    expectedLanguages: List<String>,
    val expectedLanguageWeight: Float = 0.1f,
) {
    val expectedLanguages: List<String> = expectedLanguages.toList()

    init {
        require(expectedLanguageWeight.isFinite() && expectedLanguageWeight in 0f..0.1f) {
            "Language-guide weight must be finite and between 0 and 0.1"
        }
        require(this.expectedLanguages.all { it.matches(Regex("[a-z]{2,3}-[A-Z]{2}")) }) {
            "Language guide requires exact locale tags, such as en-US, ru-RU or zh-CN"
        }
    }
}

/** One recognised word and its position in the stream, in seconds. */
data class TimedToken(val text: String, val startSec: Float, val endSec: Float)

/**
 * Text of a transcription stream, and its words.
 *
 * [words] covers the whole stream so far, and joined by single spaces they
 * read as [text]. A word's times are seconds from
 * [StreamingTranscriber.beginStream]: it starts on the encoder frame its first
 * token was emitted on and ends where its last token's frame ends. Frames are
 * 80 ms for the published bundles, read from their config. That is emission
 * time, so it trails the speech by up to the model's lookahead, and no time
 * passes the end of the audio pushed. [confidence] is the wrapper's own
 * value, which for Nemotron is not a calibrated score.
 */
data class StreamingTranscript(
    val text: String,
    val words: List<TimedToken>,
    val confidence: Float,
)

/**
 * Nemotron-3.5 ASR Streaming Multilingual 0.6B, without a pipeline.
 *
 * For apps that run their own capture and segmentation: push 16 kHz mono
 * float32 audio, read the text so far, and end the stream where the app
 * decides an utterance ends. The model decodes fixed 320 ms windows, so
 * [pushAudio] returns the same text until another window completes.
 *
 * Features are computed as audio arrives and only the samples the next frame
 * reads are kept, so a window costs the same however long the stream has
 * run. Its text and words still grow with the stream, so a stream is still
 * best kept to an utterance or paragraph.
 *
 * Calls are serialized; [close] releases the model.
 *
 * ```
 * val transcriber = StreamingTranscriber(
 *     TranscriberConfig(modelDir = ModelManager.ensureTranscriberModels(context))
 * )
 * transcriber.beginStream()
 * transcriber.pushAudio(samples).text   // text so far
 * transcriber.endStream().text          // final text
 * transcriber.close()
 * ```
 */
interface StreamingTranscriber : AutoCloseable {

    /** The rate [pushAudio] expects, in Hz. */
    val sampleRate: Int

    /**
     * Select the language prompt for windows decoded after this call.
     *
     * Resolves as speech-swift does: the tag as written, `_` read as `-`, then
     * the bare language ("fr" for "fr-XX"). "auto" selects the automatic
     * prompt. Every call clears the experimental language guide.
     * Returns false when nothing matches, and the model is then left
     * on the automatic prompt.
     */
    fun setLanguage(locale: String): Boolean

    /** Set before [beginStream]. Null/empty clears the guide. Exact locales
     * must exist in the loaded vocabulary and prompt table. [setLanguage]
     * clears any previous guide, including when selecting Auto again. */
    fun setLanguageGuide(guide: LanguageGuideConfig?)

    /** Open a new stream, discarding any open one. [pushAudio] opens one by
     *  itself when none is open. */
    fun beginStream()

    /** Feed [count] samples of 16 kHz mono float32 audio. Returns the text of
     *  the open stream so far. */
    fun pushAudio(samples: FloatArray, count: Int = samples.size): StreamingTranscript

    /** Decode the audio left in the stream, close it and return its text. A
     *  closed stream returns empty text. */
    fun endStream(): StreamingTranscript

    /** Close the open stream without decoding its remaining audio. */
    fun cancelStream()

    companion object {
        /**
         * Loads the model. Throws [IllegalArgumentException] for a language
         * the bundle has no prompt for, and [RuntimeException] naming the
         * cause when the model cannot be loaded.
         */
        operator fun invoke(config: TranscriberConfig): StreamingTranscriber {
            config.requireValidConfiguration()
            return StreamingTranscriberImpl(config)
        }
    }
}

/** Validate before JNI loads ~700 MB of weights. */
internal fun TranscriberConfig.requireValidConfiguration() {
    require(modelDir.isNotBlank()) {
        "TranscriberConfig.modelDir must be the directory returned by " +
            "ModelManager.ensureTranscriberModels()"
    }
    val base = language.trim().substringBefore('-').substringBefore('_').lowercase()
    require(base != "cn") {
        "TranscriberConfig.language='$language' uses the country code 'cn', not a " +
            "Chinese language tag. Use 'zh-CN' for Simplified Chinese or " +
            "'zh-TW' for Traditional Chinese."
    }
    require(languageGuide?.expectedLanguages.isNullOrEmpty() || language.trim() in listOf("", "auto")) {
        "A language guide requires automatic-language recognition"
    }
}

/** JNI reads `count` elements without a bounds check of its own. */
internal fun requireSampleCount(samples: FloatArray, count: Int) {
    require(count in 0..samples.size) {
        "count must be between 0 and samples.size (${samples.size}), was $count"
    }
}

internal class StreamingTranscriberImpl(config: TranscriberConfig) : StreamingTranscriber {

    private var handle: Long = NativeBridge.nativeCreateTranscriber(
        config.modelDir,
        config.backend.ordinal,
        config.hardwareAcceleration,
        config.language.trim().ifEmpty { "auto" },
    ).also { h ->
        if (h == 0L) throw IllegalStateException("Failed to create the native transcriber")
    }

    init {
        try {
            config.languageGuide?.let(::setLanguageGuide)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override val sampleRate: Int = SAMPLE_RATE

    @Synchronized
    override fun setLanguage(locale: String): Boolean =
        NativeBridge.nativeTranscriberSetLanguage(open(), locale.trim())

    @Synchronized
    override fun setLanguageGuide(guide: LanguageGuideConfig?) =
        NativeBridge.nativeTranscriberSetLanguageGuide(
            open(), guide?.expectedLanguages.orEmpty().toTypedArray(),
            guide?.expectedLanguageWeight ?: 0.1f,
        )

    @Synchronized
    override fun beginStream() {
        NativeBridge.nativeTranscriberBegin(open())
    }

    @Synchronized
    override fun pushAudio(samples: FloatArray, count: Int): StreamingTranscript {
        requireSampleCount(samples, count)
        val h = open()
        return transcript(h, NativeBridge.nativeTranscriberPush(h, samples, count))
    }

    @Synchronized
    override fun endStream(): StreamingTranscript {
        val h = open()
        return transcript(h, NativeBridge.nativeTranscriberEnd(h))
    }

    @Synchronized
    override fun cancelStream() {
        NativeBridge.nativeTranscriberCancel(open())
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            NativeBridge.nativeDestroyTranscriber(handle)
            handle = 0L
        }
    }

    private fun open(): Long {
        check(handle != 0L) { "StreamingTranscriber is closed" }
        return handle
    }

    // Pieces carry their word boundary as a leading space, so the running
    // text and each word stay untrimmed natively and are trimmed once here.
    private fun transcript(h: Long, text: String?): StreamingTranscript {
        val texts = NativeBridge.nativeTranscriberWordTexts(h).orEmpty()
        val times = NativeBridge.nativeTranscriberWordTimes(h) ?: FloatArray(0)
        val words = texts.indices.mapNotNull { index ->
            val word = texts[index].trim()
            if (word.isEmpty() || times.size < (index + 1) * 2) {
                null
            } else {
                TimedToken(word, times[index * 2], times[index * 2 + 1])
            }
        }
        return StreamingTranscript(
            text.orEmpty().trim(),
            words,
            NativeBridge.nativeTranscriberLastConfidence(h),
        )
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
