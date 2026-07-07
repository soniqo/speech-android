package audio.soniqo.speech.service

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioRecord
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import audio.soniqo.speech.PipelineState
import audio.soniqo.speech.SpeechConfig
import audio.soniqo.speech.SpeechEvent
import audio.soniqo.speech.SpeechPipeline
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [SpeechRecognitionService].
 *
 * Uses the protected seams [SpeechRecognitionService.createPipeline],
 * [SpeechRecognitionService.resolveModelDir], and
 * [SpeechRecognitionService.newAudioRecord] to inject a fake pipeline and a
 * mocked AudioRecord — no native library load, no real microphone, no model
 * download. Each test runs in well under a second.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SpeechRecognitionServiceTest {

    private lateinit var fakePipeline: FakeSpeechPipeline
    private lateinit var fakeRecord: AudioRecord
    private lateinit var controller: ServiceController<TestableService>
    private lateinit var service: TestableService
    private lateinit var listener: RecognitionService.Callback

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

        fakePipeline = FakeSpeechPipeline()
        fakeRecord = mockk(relaxed = true) {
            every { state } returns AudioRecord.STATE_INITIALIZED
            // Returning -1 makes the mic loop exit immediately so it doesn't
            // hot-spin during the test. We're not exercising the mic path here.
            every { read(any<FloatArray>(), any(), any(), any()) } returns -1
        }

        controller = Robolectric.buildService(TestableService::class.java)
        service = controller.create().get()
        service.install(fakePipeline, fakeRecord)
        listener = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        controller.destroy()
        unmockkAll()
    }

    @Test
    fun startListening_setsUpPipelineAndSignalsReady() {
        service.startListening(Intent(), listener)

        verify(timeout = 1500) { listener.readyForSpeech(any()) }
    }

    @Test
    fun startListening_concurrentCallReturnsBusy() {
        // Regression test for the race fix: the first call must claim the
        // `starting` flag synchronously so a second call hits the busy branch
        // before the suspending setup of the first one completes.
        service.startListening(Intent(), listener)

        val second = mockk<RecognitionService.Callback>(relaxed = true)
        service.startListening(Intent(), second)

        verify { second.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
    }

    @Test
    fun startListening_requestedRegionalLanguageIsAccepted() {
        val intent = Intent().putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")

        service.startListening(intent, listener)

        verify(timeout = 1500) { listener.readyForSpeech(any()) }
        assertEquals("fr", service.lastConfig?.language)
    }

    @Test
    fun startListening_requestedLanguagePreferenceIsAccepted() {
        val intent = Intent().putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")

        service.startListening(intent, listener)

        verify(timeout = 1500) { listener.readyForSpeech(any()) }
        assertEquals("en", service.lastConfig?.language)
    }

    @Test
    fun startListening_unsupportedLanguageReportsLanguageNotSupported() {
        val intent = Intent().putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")

        service.startListening(intent, listener)

        verify { listener.error(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) }
        assertEquals(0, fakePipeline.startCalls)
    }

    @Test
    fun startListening_modelsNotReadyReportsLanguageUnavailableAndSchedulesDownload() {
        service.modelsReady = false

        service.startListening(Intent(), listener)

        verify { listener.error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) }
        assertEquals(1, service.downloadRequests)
        assertEquals(0, fakePipeline.startCalls)
    }

    @Test
    fun stopListening_flushesPipelineWithSilence() {
        // Regression test for the stop-hang fix: VAD detects end-of-utterance
        // from silence in the audio stream, and nativeStop does not flush. We
        // expect ~32 chunks × 32 ms ≈ 1 s of zero frames pushed after the mic
        // is cut so the pipeline emits its final TranscriptionCompleted.
        service.startListening(Intent(), listener)
        verify(timeout = 1500) { listener.readyForSpeech(any()) }

        service.stopListening(listener)

        waitFor(2_000) { fakePipeline.silencePushCount >= 30 }
    }

    @Test
    fun startListening_withoutPermission_reportsInsufficient() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)

        service.startListening(Intent(), listener)

        verify { listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
    }

    @Test
    fun transcriptionCompleted_emitsResultsAndTearsDownSession() {
        service.startListening(Intent(), listener)
        verify(timeout = 1500) { listener.readyForSpeech(any()) }

        kotlinx.coroutines.runBlocking {
            fakePipeline.emit(SpeechEvent.TranscriptionCompleted("hello world", 0.9f, 12.5f))
        }

        verify(timeout = 1500) { listener.results(any()) }
        // Pipeline closed → a fresh start is allowed again.
        waitFor(1_000) { fakePipeline.closeCalls > 0 }
    }

    @Test
    fun startListening_doesNotRequestAudioFocusFromCallingApp() {
        service.startListening(Intent(), listener)
        verify(timeout = 1500) { listener.readyForSpeech(any()) }

        val app = ApplicationProvider.getApplicationContext<Application>()
        val am = app.getSystemService(AudioManager::class.java)
        assertNull(shadowOf(am).lastAudioFocusRequest)
    }

    @Test
    fun onCheckRecognitionSupport_modelsNotReady_marksLanguagesSupportedForDownload() {
        service.modelsReady = false
        val callback = mockk<RecognitionService.SupportCallback>(relaxed = true)
        service.checkRecognitionSupport(Intent(), callback)

        val supportSlot = slot<RecognitionSupport>()
        verify(timeout = 1500) { callback.onSupportResult(capture(supportSlot)) }
        val support = supportSlot.captured

        // Models aren't on disk in the test, so all advertised languages
        // should be supported for download — never installed.
        assertTrue("installed should be empty", support.installedOnDeviceLanguages.isEmpty())
        assertTrue(
            "supported should include 'en'",
            support.supportedOnDeviceLanguages.contains("en"),
        )
        assertEquals(
            "supported should match SUPPORTED_LANGUAGES",
            SpeechRecognitionService.SUPPORTED_LANGUAGES,
            support.supportedOnDeviceLanguages,
        )
    }

    @Test
    fun onCheckRecognitionSupport_requestedRegionalLanguageReturnsExactLocale() {
        val callback = mockk<RecognitionService.SupportCallback>(relaxed = true)
        val intent = Intent().putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")

        service.checkRecognitionSupport(intent, callback)

        val supportSlot = slot<RecognitionSupport>()
        verify(timeout = 1500) { callback.onSupportResult(capture(supportSlot)) }
        assertEquals(
            listOf("en-US", "en"),
            supportSlot.captured.installedOnDeviceLanguages,
        )
    }

    @Test
    fun onCheckRecognitionSupport_unsupportedRequestedLanguageReportsError() {
        val callback = mockk<RecognitionService.SupportCallback>(relaxed = true)
        val intent = Intent().putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")

        service.checkRecognitionSupport(intent, callback)

        verify { callback.onError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) }
    }

    @Test
    fun triggerModelDownload_supportedLanguageEnqueuesDownload() {
        val intent = Intent().putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")

        service.triggerModelDownload(intent)

        assertEquals(1, service.downloadRequests)
    }

    @Test
    fun triggerModelDownload_unsupportedLanguageDoesNotEnqueueDownload() {
        val intent = Intent().putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")

        service.triggerModelDownload(intent)

        assertEquals(0, service.downloadRequests)
    }

    private fun waitFor(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(20)
        }
        assertTrue("predicate did not become true within ${timeoutMs}ms", predicate())
    }

    /**
     * Subclass that exposes the protected seams plus public helpers for the
     * protected RecognitionService callbacks (onStartListening / onStopListening
     * are protected on the SDK class, so tests can't call them directly).
     */
    class TestableService : SpeechRecognitionService() {
        private lateinit var pipelineToInject: SpeechPipeline
        private lateinit var recordToInject: AudioRecord
        var modelsReady = true
        var downloadRequests = 0
        var lastConfig: SpeechConfig? = null

        fun install(pipeline: SpeechPipeline, record: AudioRecord) {
            pipelineToInject = pipeline
            recordToInject = record
        }

        fun startListening(intent: Intent, listener: Callback) =
            onStartListening(intent, listener)

        fun stopListening(listener: Callback) = onStopListening(listener)

        fun checkRecognitionSupport(intent: Intent, callback: SupportCallback) =
            onCheckRecognitionSupport(intent, callback)

        fun triggerModelDownload(intent: Intent) = onTriggerModelDownload(intent)

        override fun createPipeline(config: SpeechConfig): SpeechPipeline {
            lastConfig = config
            return pipelineToInject
        }

        override fun areRecognitionModelsReady(): Boolean = modelsReady

        override fun enqueueRecognitionModelDownload(recognizerIntent: Intent?): java.util.UUID {
            downloadRequests++
            return java.util.UUID.randomUUID()
        }

        override suspend fun resolveModelDir(): String = "/fake/models"

        override fun newAudioRecordContext(listener: Callback): Context = this

        override fun newAudioRecord(context: Context): AudioRecord = recordToInject
    }

    /** Minimal SpeechPipeline that records calls and lets the test push events. */
    class FakeSpeechPipeline : SpeechPipeline {
        private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 64)
        override val events: SharedFlow<SpeechEvent> = _events.asSharedFlow()
        override val state: PipelineState = PipelineState.Idle
        override val nnapiFallbackReason: String? = null

        @Volatile var silencePushCount = 0
        @Volatile var totalPushCount = 0
        @Volatile var startCalls = 0
        @Volatile var stopCalls = 0
        @Volatile var closeCalls = 0

        override fun start() { startCalls++ }
        override fun stop() { stopCalls++ }
        override fun pushAudio(samples: FloatArray) {
            totalPushCount++
            if (samples.size == 512 && samples.all { it == 0f }) silencePushCount++
        }
        override fun resumeListening() {}
        override fun close() { closeCalls++ }

        suspend fun emit(event: SpeechEvent) = _events.emit(event)
    }
}
