package audio.soniqo.speech.service

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import audio.soniqo.speech.KokoroPack
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
 *
 * Two voices are offered: English (`en-US`, the multilingual Kokoro bundle)
 * and German (`de-DE`, the [GERMAN_PACK] single-voice bundle). Each language
 * loads its own synthesizer the first time it is requested, downloading its
 * bundle through [ModelManager.ensureTtsModels] into a separate cache, so
 * installing German never evicts English and vice versa.
 */
open class SpeechTextToSpeechService : TextToSpeechService() {

    private val synthesizerLock = Any()

    @Volatile
    private var stopped = false

    private val synthesizers = mutableMapOf<KokoroPack, SpeechSynthesizer>()

    override fun onGetLanguage(): Array<String> =
        arrayOf(DEFAULT_LANGUAGE_ISO3, DEFAULT_COUNTRY_ISO3, "")

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        languageAvailability(lang, country, variant)

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        languageAvailability(lang, country, variant)

    override fun onGetVoices(): MutableList<Voice> =
        mutableListOf(DEFAULT_VOICE, GERMAN_VOICE)

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? =
        if (languageAvailability(lang, country, variant) == TextToSpeech.LANG_NOT_SUPPORTED) {
            null
        } else {
            voiceNameFor(normalizeLanguage(lang))
        }

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (voiceName == DEFAULT_VOICE_NAME || voiceName == GERMAN_VOICE_NAME) {
            TextToSpeech.SUCCESS
        } else {
            TextToSpeech.ERROR
        }

    override fun onLoadVoice(voiceName: String?): Int =
        onIsValidVoiceName(voiceName)

    override fun onStop() {
        stopped = true
        synchronized(synthesizerLock) { synthesizers.values.forEach { it.stop() } }
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val text = request.charSequenceText?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            return
        }

        stopped = false
        try {
            val language = normalizeLanguage(request.language)
            val synth = getOrCreateSynthesizer(packFor(language))
            val result = synth.synthesize(text, language)
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
            synthesizers.values.forEach { it.close() }
            synthesizers.clear()
        }
        super.onDestroy()
    }

    /** Model directory of the English (multilingual) bundle. */
    protected open suspend fun resolveModelDir(): String =
        ModelManager.ensureTtsModels(
            applicationContext,
            TtsModel.KOKORO_SHORT_TURN,
        )

    /**
     * Model directory of [pack]. The multilingual pack goes through
     * [resolveModelDir] so an existing override keeps serving English.
     */
    protected open suspend fun resolveModelDir(pack: KokoroPack): String =
        if (pack == KokoroPack.MULTILINGUAL) {
            resolveModelDir()
        } else {
            ModelManager.ensureTtsModels(
                applicationContext,
                TtsModel.KOKORO_SHORT_TURN,
                kokoroPack = pack,
            )
        }

    protected open fun createSynthesizer(config: SpeechSynthesizerConfig): SpeechSynthesizer =
        SpeechSynthesizer(config)

    private fun getOrCreateSynthesizer(pack: KokoroPack): SpeechSynthesizer {
        synchronized(synthesizerLock) {
            synthesizers[pack]?.let { return it }
        }

        val modelDir = runBlocking { resolveModelDir(pack) }
        return synchronized(synthesizerLock) {
            synthesizers.getOrPut(pack) {
                createSynthesizer(
                    SpeechSynthesizerConfig(
                        modelDir = modelDir,
                        useNnapi = false,
                        ttsModel = TtsModel.KOKORO_SHORT_TURN,
                    )
                )
            }
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

        /** The German voice: Martin from the [GERMAN_PACK] bundle. */
        const val GERMAN_VOICE_NAME = "soniqo-kokoro-dm-martin"
        val GERMAN_LOCALE: Locale = Locale.GERMANY
        val GERMAN_PACK: KokoroPack = KokoroPack.GERMAN_MARTIN

        private const val TAG = "SpeechTtsService"
        private const val CHANNEL_COUNT_MONO = 1
        private const val DEFAULT_CHUNK_BYTES = 8192
        private const val DEFAULT_LANGUAGE_ISO2 = "en"
        private const val DEFAULT_LANGUAGE_ISO3 = "eng"
        private const val DEFAULT_COUNTRY_ISO2 = "US"
        private const val DEFAULT_COUNTRY_ISO3 = "USA"
        private const val GERMAN_LANGUAGE_ISO2 = "de"
        private const val GERMAN_LANGUAGE_ISO3 = "deu"
        private const val GERMAN_COUNTRY_ISO2 = "DE"
        private const val GERMAN_COUNTRY_ISO3 = "DEU"

        private val VOICE_FEATURES = setOf(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)
        private val DEFAULT_VOICE = Voice(
            DEFAULT_VOICE_NAME,
            DEFAULT_LOCALE,
            Voice.QUALITY_NORMAL,
            Voice.LATENCY_NORMAL,
            false,
            VOICE_FEATURES,
        )
        private val GERMAN_VOICE = Voice(
            GERMAN_VOICE_NAME,
            GERMAN_LOCALE,
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
            val expectedCountry = when {
                isEnglishLanguage(lang) -> setOf(DEFAULT_COUNTRY_ISO2, DEFAULT_COUNTRY_ISO3)
                isGermanLanguage(lang) -> setOf(GERMAN_COUNTRY_ISO2, GERMAN_COUNTRY_ISO3)
                else -> return TextToSpeech.LANG_NOT_SUPPORTED
            }
            if (country.isNullOrBlank()) return TextToSpeech.LANG_AVAILABLE
            if (expectedCountry.none { it.equals(country, ignoreCase = true) }) {
                return TextToSpeech.LANG_AVAILABLE
            }
            return if (variant.isNullOrBlank()) {
                TextToSpeech.LANG_COUNTRY_AVAILABLE
            } else {
                TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            }
        }

        /** The engine language code for a framework language tag; English for anything unsupported. */
        internal fun normalizeLanguage(lang: String?): String =
            if (isGermanLanguage(lang)) GERMAN_LANGUAGE_ISO2 else DEFAULT_LANGUAGE_ISO2

        internal fun packFor(language: String): KokoroPack =
            if (language == GERMAN_LANGUAGE_ISO2) GERMAN_PACK else KokoroPack.MULTILINGUAL

        internal fun voiceNameFor(language: String): String =
            if (language == GERMAN_LANGUAGE_ISO2) GERMAN_VOICE_NAME else DEFAULT_VOICE_NAME

        private fun isEnglishLanguage(lang: String?): Boolean =
            lang.isNullOrBlank() ||
                lang.equals(DEFAULT_LANGUAGE_ISO2, ignoreCase = true) ||
                lang.equals(DEFAULT_LANGUAGE_ISO3, ignoreCase = true)

        private fun isGermanLanguage(lang: String?): Boolean =
            lang.equals(GERMAN_LANGUAGE_ISO2, ignoreCase = true) ||
                lang.equals(GERMAN_LANGUAGE_ISO3, ignoreCase = true) ||
                lang.equals("ger", ignoreCase = true)
    }
}
