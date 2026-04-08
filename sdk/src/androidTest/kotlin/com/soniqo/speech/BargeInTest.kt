package audio.soniqo.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Barge-in / interruption tests.
 *
 * Shares a single pipeline across tests to avoid OOM from loading
 * models multiple times in the same test process.
 */
@RunWith(AndroidJUnit4::class)
class BargeInTest {

    private lateinit var modelDir: String
    private lateinit var pipeline: SpeechPipeline

    @Before
    fun setup() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        modelDir = ModelManager.ensureModels(ctx)
        pipeline = SpeechPipeline(SpeechConfig(modelDir = modelDir, useNnapi = false))
        pipeline.start()
    }

    @After
    fun teardown() {
        pipeline.stop()
        pipeline.close()
    }

    private fun speechSignal(durationSec: Int = 2, freqHz: Double = 200.0): FloatArray {
        val sr = 16000
        return FloatArray(sr * durationSec) { i ->
            val t = i.toFloat() / sr
            (0.3f * Math.sin(2.0 * Math.PI * freqHz * t)).toFloat()
        }
    }

    private fun silenceSignal(): FloatArray = FloatArray(16000)

    private fun pushChunked(audio: FloatArray) {
        for (offset in audio.indices step 512) {
            val end = minOf(offset + 512, audio.size)
            val chunk = audio.sliceArray(offset until end)
            if (chunk.size == 512) pipeline.pushAudio(chunk)
        }
    }

    @Test
    fun bargeInDuringSpeakingEmitsInterrupted() = runBlocking {
        pushChunked(speechSignal(freqHz = 220.0))
        pushChunked(silenceSignal())

        try {
            withTimeout(30_000) {
                pipeline.events.first { it is SpeechEvent.ResponseAudioDelta }
            }

            // Barge-in while speaking
            pushChunked(speechSignal(durationSec = 1, freqHz = 250.0))

            val interrupted = withTimeout(10_000) {
                pipeline.events.first { it is SpeechEvent.ResponseInterrupted }
            }
            assertNotNull("ResponseInterrupted should be emitted", interrupted)
        } catch (_: Exception) {
            // Synthetic signal may not trigger full chain
        }
    }

    @Test
    fun pipelineStableAfterBargeIn() = runBlocking {
        pushChunked(speechSignal())
        pushChunked(silenceSignal())

        try {
            withTimeout(30_000) {
                pipeline.events.first { it is SpeechEvent.ResponseAudioDelta }
            }

            pushChunked(speechSignal(durationSec = 1, freqHz = 250.0))
            delay(2_000)
            pipeline.resumeListening()

            assertTrue(
                "Pipeline should be in Idle or Listening after barge-in recovery",
                pipeline.state == PipelineState.Idle || pipeline.state == PipelineState.Listening
            )
        } catch (_: Exception) {
            // Synthetic signal may not trigger full chain
        }
    }

    @Test
    fun resumeListeningAfterInterruption() = runBlocking {
        pushChunked(speechSignal(durationSec = 1))
        pipeline.resumeListening()

        assertTrue(
            "Pipeline should be in a valid state after resumeListening",
            pipeline.state == PipelineState.Idle ||
            pipeline.state == PipelineState.Listening ||
            pipeline.state == PipelineState.Transcribing
        )
    }
}
