package audio.soniqo.speech.service

import android.Manifest
import android.app.Application
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.speech.RecognitionService
import android.speech.RecognitionSupport
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
    fun startListening_requestsAudioFocus() {
        service.startListening(Intent(), listener)
        verify(timeout = 1500) { listener.readyForSpeech(any()) }

        val app = ApplicationProvider.getApplicationContext<Application>()
        val am = app.getSystemService(AudioManager::class.java)
        val granted = shadowOf(am).lastAudioFocusRequest
        assertTrue(
            "expected an AudioFocusRequest after startListening, got $granted",
            granted != null,
        )
    }

    @Test
    fun audioFocusLoss_tearsDownSession() {
        service.startListening(Intent(), listener)
        verify(timeout = 1500) { listener.readyForSpeech(any()) }

        // Simulate the system handing audio focus to a phone call: invoke
        // the listener that was registered at startListening time.
        val app = ApplicationProvider.getApplicationContext<Application>()
        val am = app.getSystemService(AudioManager::class.java)
        val recordedReq = shadowOf(am).lastAudioFocusRequest!!
        recordedReq.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        // tearDownSession() runs synchronously on the main thread; the
        // pipeline's close() is part of it, so we can assert on closeCalls
        // without polling for long.
        waitFor(1_000) { fakePipeline.closeCalls > 0 }
    }

    @Test
    fun onCheckRecognitionSupport_modelsNotReady_marksLanguagesPending() {
        val callback = mockk<RecognitionService.SupportCallback>(relaxed = true)
        service.checkRecognitionSupport(Intent(), callback)

        val supportSlot = slot<RecognitionSupport>()
        verify(timeout = 1500) { callback.onSupportResult(capture(supportSlot)) }
        val support = supportSlot.captured

        // Models aren't on disk in the test, so all advertised languages
        // should be pending — never installed.
        assertTrue("installed should be empty", support.installedOnDeviceLanguages.isEmpty())
        assertTrue(
            "pending should include 'en'",
            support.pendingOnDeviceLanguages.contains("en"),
        )
        assertEquals(
            "pending should match SUPPORTED_LANGUAGES",
            SpeechRecognitionService.SUPPORTED_LANGUAGES,
            support.pendingOnDeviceLanguages,
        )
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

        fun install(pipeline: SpeechPipeline, record: AudioRecord) {
            pipelineToInject = pipeline
            recordToInject = record
        }

        fun startListening(intent: Intent, listener: Callback) =
            onStartListening(intent, listener)

        fun stopListening(listener: Callback) = onStopListening(listener)

        fun checkRecognitionSupport(intent: Intent, callback: SupportCallback) =
            onCheckRecognitionSupport(intent, callback)

        override fun createPipeline(config: SpeechConfig): SpeechPipeline = pipelineToInject

        override suspend fun resolveModelDir(): String = "/fake/models"

        override fun newAudioRecord(): AudioRecord = recordToInject
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
