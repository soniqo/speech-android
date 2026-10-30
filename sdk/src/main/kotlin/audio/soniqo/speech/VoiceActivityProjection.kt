package audio.soniqo.speech

data class VoiceActivityProjectionConfig(
    /** Directory returned by [ModelManager.ensureVapModels]. */
    val modelDir: String,
    /** Try hardware acceleration; CPU is the default. */
    val hardwareAcceleration: Boolean = false,
)

/** Probabilities in user/agent channel order, from the latest model frame. */
data class VapForecast(
    val userNearTerm: Float,
    val agentNearTerm: Float,
    val userLater: Float,
    val agentLater: Float,
    val userSpeaking: Float,
    val agentSpeaking: Float,
)

/**
 * Voice Activity Projection with the otoSpeech checkpoint.
 *
 * Supply synchronized user-microphone and agent-playback channels at 16 kHz.
 * Each request recomputes their latest 20 seconds, adding leading silence when
 * shorter. An inactive agent uses a zero channel. The application decides when
 * the forecasts should trigger a response or interruption. Calls are serialized.
 */
interface VoiceActivityProjection : AutoCloseable {
    fun forecast(
        userAudio: FloatArray,
        agentAudio: FloatArray,
        count: Int = userAudio.size,
        sampleRate: Int = 16000,
    ): VapForecast

    companion object {
        const val SAMPLE_RATE = 16000
        const val WINDOW_SAMPLES = 320000

        operator fun invoke(config: VoiceActivityProjectionConfig): VoiceActivityProjection {
            config.requireValidConfiguration()
            return VoiceActivityProjectionImpl(config)
        }
    }
}

internal fun VoiceActivityProjectionConfig.requireValidConfiguration() {
    require(modelDir.isNotBlank()) { "VoiceActivityProjectionConfig.modelDir must not be blank" }
}

internal fun requireVapAudio(user: FloatArray, agent: FloatArray, count: Int, sampleRate: Int) {
    requireSampleCount(user, count)
    requireSampleCount(agent, count)
    require(count > 0) { "VAP requires nonempty synchronized channels" }
    require(sampleRate == VoiceActivityProjection.SAMPLE_RATE) { "VAP requires synchronized 16000 Hz audio" }
    for (index in 0 until count) {
        require(user[index].isFinite() && agent[index].isFinite()) { "VAP audio contains a non-finite sample" }
    }
}

internal fun vapForecast(values: FloatArray): VapForecast {
    check(values.size == 6 && values.all { it.isFinite() && it in 0f..1f }) {
        "VAP returned invalid probabilities"
    }
    return VapForecast(values[0], values[1], values[2], values[3], values[4], values[5])
}

internal class VoiceActivityProjectionImpl(config: VoiceActivityProjectionConfig) : VoiceActivityProjection {
    private var handle = NativeBridge.nativeCreateVap(config.modelDir, config.hardwareAcceleration).also {
        check(it != 0L) { "Failed to create the native VAP model" }
    }

    @Synchronized
    override fun forecast(userAudio: FloatArray, agentAudio: FloatArray, count: Int, sampleRate: Int): VapForecast {
        check(handle != 0L) { "VoiceActivityProjection is closed" }
        requireVapAudio(userAudio, agentAudio, count, sampleRate)
        val values = NativeBridge.nativeVapForecast(handle, userAudio, agentAudio, count, sampleRate)
            ?: throw IllegalStateException("VAP returned no forecast")
        return vapForecast(values)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            NativeBridge.nativeDestroyVap(handle)
            handle = 0L
        }
    }
}
