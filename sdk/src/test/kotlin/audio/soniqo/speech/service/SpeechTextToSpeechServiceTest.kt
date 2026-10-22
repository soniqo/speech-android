package audio.soniqo.speech.service

import android.media.AudioFormat
import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import audio.soniqo.speech.KokoroPack
import audio.soniqo.speech.SpeechSynthesisResult
import audio.soniqo.speech.SpeechSynthesizer
import audio.soniqo.speech.SpeechSynthesizerConfig
import audio.soniqo.speech.TtsModel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SpeechTextToSpeechServiceTest {

    private lateinit var fakeSynthesizer: FakeSynthesizer
    private lateinit var controller: ServiceController<TestableService>
    private lateinit var service: TestableService

    @Before
    fun setUp() {
        fakeSynthesizer = FakeSynthesizer(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9),
        )
        controller = Robolectric.buildService(TestableService::class.java)
        service = controller.create().get()
        service.install(fakeSynthesizer)
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    @Test
    fun languageContract_exposesEmbeddedEnglishVoice() {
        assertArrayEquals(arrayOf("eng", "USA", ""), service.getLanguage())
        assertEquals(
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            service.isLanguageAvailable("eng", "USA", ""),
        )
        assertEquals(
            TextToSpeech.LANG_NOT_SUPPORTED,
            service.isLanguageAvailable("fra", "FRA", ""),
        )

        val voice = service.getVoices().first { it.name == SpeechTextToSpeechService.DEFAULT_VOICE_NAME }
        assertEquals(SpeechTextToSpeechService.DEFAULT_LOCALE, voice.locale)
        assertTrue(voice.features.contains(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS))
        assertEquals(
            SpeechTextToSpeechService.DEFAULT_VOICE_NAME,
            service.defaultVoiceNameFor("eng", "USA", ""),
        )
    }

    @Test
    fun languageContract_exposesEmbeddedGermanVoice() {
        assertEquals(
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            service.isLanguageAvailable("deu", "DEU", ""),
        )
        assertEquals(TextToSpeech.LANG_AVAILABLE, service.isLanguageAvailable("de", "", ""))
        assertEquals(TextToSpeech.LANG_AVAILABLE, service.isLanguageAvailable("deu", "AUT", ""))
        assertEquals(
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE,
            service.isLanguageAvailable("deu", "DEU", "x-kokoro"),
        )

        val voice = service.getVoices().first { it.name == SpeechTextToSpeechService.GERMAN_VOICE_NAME }
        assertEquals(SpeechTextToSpeechService.GERMAN_LOCALE, voice.locale)
        assertTrue(voice.features.contains(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS))
        assertEquals(2, service.getVoices().size)
        assertEquals(
            SpeechTextToSpeechService.GERMAN_VOICE_NAME,
            service.defaultVoiceNameFor("deu", "DEU", ""),
        )
        assertEquals("de", SpeechTextToSpeechService.normalizeLanguage("ger"))
        assertEquals("en", SpeechTextToSpeechService.normalizeLanguage("fra"))
    }

    @Test
    fun synthesizeText_germanRequestUsesTheGermanPack() {
        // The framework hands every request its own callback.
        val callbacks = List(3) { RecordingSynthesisCallback() }

        service.synthesize(SynthesisRequest("Guten Tag", Bundle.EMPTY), callbacks[0])
        service.synthesize(germanRequest("Guten Tag"), callbacks[1])
        service.synthesize(germanRequest("Hallo"), callbacks[2])

        assertEquals(listOf(null, null, null), callbacks.map { it.errorCode })
        assertEquals(listOf(1, 1, 1), callbacks.map { it.doneCount })
        assertEquals(listOf("en", "de", "de"), fakeSynthesizer.languages)
        assertEquals(listOf(KokoroPack.MULTILINGUAL, KokoroPack.GERMAN_MARTIN), service.resolvedPacks)
        // One synthesizer per pack, created on first use and reused.
        assertEquals(
            listOf("/fake/models_tts", "/fake/models_tts-kokoro_de_martin"),
            service.createdConfigs.map { it.modelDir },
        )
    }

    /**
     * The framework resolves the request locale and stores it on the request
     * through the package-private `setLanguage` before calling
     * `onSynthesizeText`; mirror that here.
     */
    private fun germanRequest(text: String): SynthesisRequest {
        val request = SynthesisRequest(text, Bundle.EMPTY)
        SynthesisRequest::class.java
            .getDeclaredMethod("setLanguage", String::class.java, String::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(request, "deu", "DEU", "")
        return request
    }

    @Test
    fun synthesizeText_streamsPcm16ChunksAndCompletes() {
        val callback = RecordingSynthesisCallback(maxBufferSize = 4)

        service.synthesize(SynthesisRequest("hello", Bundle.EMPTY), callback)

        assertEquals("/fake/models_tts", service.createdConfig?.modelDir)
        assertEquals(false, service.createdConfig?.useNnapi)
        assertEquals(TtsModel.KOKORO_SHORT_TURN, service.createdConfig?.ttsModel)
        assertEquals("hello", fakeSynthesizer.lastText)
        assertEquals("en", fakeSynthesizer.lastLanguage)
        assertEquals(24_000, callback.sampleRate)
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, callback.audioFormat)
        assertEquals(1, callback.channelCount)
        assertEquals(listOf(4, 4, 1), callback.chunks.map { it.size })
        assertEquals(1, callback.doneCount)
        assertEquals(null, callback.errorCode)
    }

    @Test
    fun synthesizeText_emptyInputReportsInvalidRequest() {
        val callback = RecordingSynthesisCallback()

        service.synthesize(SynthesisRequest("  ", Bundle.EMPTY), callback)

        assertEquals(TextToSpeech.ERROR_INVALID_REQUEST, callback.errorCode)
        assertEquals(0, fakeSynthesizer.synthesizeCalls)
    }

    @Test
    fun stop_cancelsSynthesizer() {
        service.synthesize(SynthesisRequest("hello", Bundle.EMPTY), RecordingSynthesisCallback())

        service.stop()

        assertTrue(fakeSynthesizer.stopped)
    }

    class TestableService : SpeechTextToSpeechService() {
        private lateinit var fakeSynthesizer: FakeSynthesizer
        var createdConfig: SpeechSynthesizerConfig? = null
            private set
        val createdConfigs = mutableListOf<SpeechSynthesizerConfig>()
        val resolvedPacks = mutableListOf<KokoroPack>()

        fun install(synthesizer: FakeSynthesizer) {
            fakeSynthesizer = synthesizer
        }

        fun getLanguage(): Array<String> = onGetLanguage()

        fun isLanguageAvailable(lang: String, country: String, variant: String): Int =
            onIsLanguageAvailable(lang, country, variant)

        fun getVoices() = onGetVoices()

        fun defaultVoiceNameFor(lang: String, country: String, variant: String): String? =
            onGetDefaultVoiceNameFor(lang, country, variant)

        fun synthesize(request: SynthesisRequest, callback: SynthesisCallback) =
            onSynthesizeText(request, callback)

        fun stop() = onStop()

        override suspend fun resolveModelDir(): String {
            ApplicationProvider.getApplicationContext<android.content.Context>()
            return "/fake/models_tts"
        }

        override suspend fun resolveModelDir(pack: KokoroPack): String {
            resolvedPacks += pack
            return if (pack == KokoroPack.MULTILINGUAL) resolveModelDir() else "/fake/models_tts-kokoro_de_martin"
        }

        override fun createSynthesizer(config: SpeechSynthesizerConfig): SpeechSynthesizer {
            createdConfig = config
            createdConfigs += config
            return fakeSynthesizer
        }
    }

    class FakeSynthesizer(
        private val output: ByteArray,
    ) : SpeechSynthesizer {
        override val sampleRate: Int = 24_000
        var lastText: String? = null
        var lastLanguage: String? = null
        val languages = mutableListOf<String>()
        var synthesizeCalls = 0
        var stopped = false
        var closed = false

        override fun synthesize(text: String, language: String): SpeechSynthesisResult {
            synthesizeCalls++
            lastText = text
            lastLanguage = language
            languages += language
            return SpeechSynthesisResult(sampleRate, output)
        }

        override fun stop() {
            stopped = true
        }

        override fun close() {
            closed = true
        }
    }

    class RecordingSynthesisCallback(
        private val maxBufferSize: Int = 8192,
    ) : SynthesisCallback {
        var sampleRate: Int? = null
        var audioFormat: Int? = null
        var channelCount: Int? = null
        val chunks = mutableListOf<ByteArray>()
        var doneCount = 0
        var errorCode: Int? = null
        private var started = false
        private var finished = false

        override fun getMaxBufferSize(): Int = maxBufferSize

        override fun start(sampleRateInHz: Int, audioFormat: Int, channelCount: Int): Int {
            started = true
            sampleRate = sampleRateInHz
            this.audioFormat = audioFormat
            this.channelCount = channelCount
            return TextToSpeech.SUCCESS
        }

        override fun audioAvailable(buffer: ByteArray, offset: Int, length: Int): Int {
            chunks += buffer.copyOfRange(offset, offset + length)
            return TextToSpeech.SUCCESS
        }

        override fun done(): Int {
            finished = true
            doneCount++
            return TextToSpeech.SUCCESS
        }

        override fun error() {
            finished = true
            errorCode = TextToSpeech.ERROR
        }

        override fun error(errorCode: Int) {
            finished = true
            this.errorCode = errorCode
        }

        override fun hasStarted(): Boolean = started

        override fun hasFinished(): Boolean = finished
    }
}
