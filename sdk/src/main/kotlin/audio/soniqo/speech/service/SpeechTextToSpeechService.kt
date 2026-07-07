package audio.soniqo.speech.service

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import audio.soniqo.speech.ModelManager
import audio.soniqo.speech.SpeechSynthesizer
import audio.soniqo.speech.SpeechSynthesizerConfig
import audio.soniqo.speech.TtsModel
import kotlinx.coroutines.runBlocking
import java.util.Locale

/**
 * Android framework TextToSpeech engine backed by the on-device Kokoro model.
 *
 * Apps expose this service from their manifest with
 * `android.intent.action.TTS_SERVICE` and `android.speech.tts` metadata.
 */
open class SpeechTextToSpeechService : TextToSpeechService() {

    private val synthesizerLock = Any()

    @Volatile
    private var stopped = false

    private var synthesizer: SpeechSynthesizer? = null

    override fun onGetLanguage(): Array<String> =
        arrayOf(DEFAULT_LANGUAGE_ISO3, DEFAULT_COUNTRY_ISO3, "")

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        languageAvailability(lang, country, variant)

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        languageAvailability(lang, country, variant)

    override fun onGetVoices(): MutableList<Voice> =
        mutableListOf(DEFAULT_VOICE)

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? =
        if (languageAvailability(lang, country, variant) == TextToSpeech.LANG_NOT_SUPPORTED) {
            null
        } else {
            DEFAULT_VOICE_NAME
        }

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (voiceName == DEFAULT_VOICE_NAME) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int =
        onIsValidVoiceName(voiceName)

    override fun onStop() {
        stopped = true
        synthesizer?.stop()
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val text = request.charSequenceText?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            return
        }

        stopped = false
        try {
            val synth = getOrCreateSynthesizer()
            val result = synth.synthesize(text, normalizeLanguage(request.language))
            if (stopped) {
                callback.error()
                return
            }
            if (result.pcm16.isEmpty()) {
                callback.error(TextToSpeech.ERROR_SYNTHESIS)
                return
            }

            val started = callback.start(
                result.sampleRate,
                AudioFormat.ENCODING_PCM_16BIT,
                CHANNEL_COUNT_MONO,
            )
            if (started != TextToSpeech.SUCCESS) return

            if (writeAudio(callback, result.pcm16) && !callback.hasFinished()) {
                callback.done()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Text-to-speech synthesis failed", t)
            if (!callback.hasFinished()) callback.error(TextToSpeech.ERROR_SYNTHESIS)
        }
    }

    override fun onDestroy() {
        synchronized(synthesizerLock) {
            synthesizer?.close()
            synthesizer = null
        }
        super.onDestroy()
    }

    protected open suspend fun resolveModelDir(): String =
        ModelManager.ensureTtsModels(applicationContext, TtsModel.KOKORO)

    protected open fun createSynthesizer(config: SpeechSynthesizerConfig): SpeechSynthesizer =
        SpeechSynthesizer(config)

    private fun getOrCreateSynthesizer(): SpeechSynthesizer {
        synchronized(synthesizerLock) {
            synthesizer?.let { return it }
        }

        val modelDir = runBlocking { resolveModelDir() }
        return synchronized(synthesizerLock) {
            synthesizer ?: createSynthesizer(
                SpeechSynthesizerConfig(
                    modelDir = modelDir,
                    useNnapi = true,
                    ttsModel = TtsModel.KOKORO,
                )
            ).also { synthesizer = it }
        }
    }

    private fun writeAudio(callback: SynthesisCallback, pcm16: ByteArray): Boolean {
        val maxBuffer = callback.maxBufferSize.takeIf { it > 0 } ?: DEFAULT_CHUNK_BYTES
        var offset = 0
        while (offset < pcm16.size) {
            if (stopped) {
                callback.error()
                return false
            }
            val count = minOf(maxBuffer, pcm16.size - offset)
            val status = callback.audioAvailable(pcm16, offset, count)
            if (status != TextToSpeech.SUCCESS) return false
            offset += count
        }
        return true
    }

    companion object {
        const val DEFAULT_VOICE_NAME = "soniqo-kokoro-af-heart"
        val DEFAULT_LOCALE: Locale = Locale.US

        private const val TAG = "SpeechTtsService"
        private const val CHANNEL_COUNT_MONO = 1
        private const val DEFAULT_CHUNK_BYTES = 8192
        private const val DEFAULT_LANGUAGE_ISO2 = "en"
        private const val DEFAULT_LANGUAGE_ISO3 = "eng"
        private const val DEFAULT_COUNTRY_ISO2 = "US"
        private const val DEFAULT_COUNTRY_ISO3 = "USA"

        private val VOICE_FEATURES = setOf(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)
        private val DEFAULT_VOICE = Voice(
            DEFAULT_VOICE_NAME,
            DEFAULT_LOCALE,
            Voice.QUALITY_NORMAL,
            Voice.LATENCY_NORMAL,
            false,
            VOICE_FEATURES,
        )

        internal fun languageAvailability(
            lang: String?,
            country: String?,
            variant: String?,
        ): Int {
            if (!isEnglishLanguage(lang)) return TextToSpeech.LANG_NOT_SUPPORTED
            if (country.isNullOrBlank()) return TextToSpeech.LANG_AVAILABLE
            if (!isUsCountry(country)) return TextToSpeech.LANG_AVAILABLE
            return if (variant.isNullOrBlank()) {
                TextToSpeech.LANG_COUNTRY_AVAILABLE
            } else {
                TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            }
        }

        internal fun normalizeLanguage(lang: String?): String =
            if (isEnglishLanguage(lang)) DEFAULT_LANGUAGE_ISO2 else DEFAULT_LANGUAGE_ISO2

        private fun isEnglishLanguage(lang: String?): Boolean =
            lang.isNullOrBlank() ||
                lang.equals(DEFAULT_LANGUAGE_ISO2, ignoreCase = true) ||
                lang.equals(DEFAULT_LANGUAGE_ISO3, ignoreCase = true)

        private fun isUsCountry(country: String?): Boolean =
            country.equals(DEFAULT_COUNTRY_ISO2, ignoreCase = true) ||
                country.equals(DEFAULT_COUNTRY_ISO3, ignoreCase = true)
    }
}
