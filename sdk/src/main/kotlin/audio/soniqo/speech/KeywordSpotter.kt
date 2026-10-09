package audio.soniqo.speech

/** A caller-tokenized acoustic phrase. Token IDs must match the supplied model. */
data class KeywordPhrase(
    val phrase: String,
    val tokens: IntArray,
    val acousticThreshold: Float = 0.25f,
    val contextBoost: Float = 0.1f,
)

data class KeywordSpotterConfig(val modelDir: String, val phrases: List<KeywordPhrase>, val beamSize: Int = 4)

/** Acoustic hit with monotonic frame positions (40 ms) and accepted PCM time. */
data class KeywordDetection(
    val phrase: String,
    val tokens: IntArray,
    val tokenFrames: LongArray,
    val streamFrame: Long,
    val audioEndSeconds: Double,
)

/**
 * Streaming Zipformer keyword spotting over local, verified ONNX assets.
 * No microphone, download, transcript parsing or command execution. Feed
 * normalized mono 16 kHz PCM; calls are serialized. Backend failures throw
 * and require [reset] or a fresh instance, never an ASR fallback.
 */
interface KeywordSpotter : AutoCloseable {
    fun pushAudio(samples: FloatArray, count: Int = samples.size): List<KeywordDetection>
    fun endStream(): List<KeywordDetection>
    fun reset()

    companion object {
        operator fun invoke(config: KeywordSpotterConfig): KeywordSpotter {
            config.requireValidConfiguration()
            return KeywordSpotterImpl(config)
        }
    }
}

internal fun KeywordSpotterConfig.requireValidConfiguration() {
    require(modelDir.isNotBlank()) { "KeywordSpotter requires a local model directory" }
    require(beamSize in 1..32 && phrases.size in 1..128) { "Invalid keyword search configuration" }
    phrases.forEach {
        require(it.phrase.isNotBlank() && it.tokens.size in 1..128 && it.tokens.all { token -> token >= 0 })
        require(it.acousticThreshold.isFinite() && it.acousticThreshold > 0 && it.acousticThreshold <= 1)
        require(it.contextBoost.isFinite() && it.contextBoost in 0f..10f)
    }
    require(phrases.map { it.tokens.toList() }.distinct().size == phrases.size) { "Duplicate keyword token sequence" }
}

internal class KeywordSpotterImpl(config: KeywordSpotterConfig) : KeywordSpotter {
    private var handle = NativeBridge.nativeCreateKeywordSpotter(config.modelDir,
        config.phrases.map { it.phrase }.toTypedArray(), config.phrases.map { it.tokens.copyOf() }.toTypedArray(),
        config.phrases.map { it.acousticThreshold }.toFloatArray(), config.phrases.map { it.contextBoost }.toFloatArray(),
        config.beamSize).also { check(it != 0L) { "Keyword spotter could not be created" } }

    @Synchronized override fun pushAudio(samples: FloatArray, count: Int): List<KeywordDetection> {
        requireSampleCount(samples, count)
        require(count <= 32000) { "Keyword audio blocks must be at most two seconds" }
        return NativeBridge.nativeKeywordPush(open(), samples, count).toList()
    }
    @Synchronized override fun endStream() = NativeBridge.nativeKeywordEnd(open()).toList()
    @Synchronized override fun reset() = NativeBridge.nativeKeywordReset(open())
    @Synchronized override fun close() {
        if (handle != 0L) { NativeBridge.nativeDestroyKeywordSpotter(handle); handle = 0 }
    }
    private fun open(): Long { check(handle != 0L) { "KeywordSpotter is closed" }; return handle }
}
