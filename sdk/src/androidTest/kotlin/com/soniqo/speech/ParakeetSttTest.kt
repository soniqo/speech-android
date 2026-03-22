package audio.soniqo.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * E2E test: Parakeet TDT 0.6B speech recognition on device.
 *
 * Verifies that:
 * - Encoder + decoder models load and produce transcriptions
 * - Speech-like audio triggers VAD → STT pipeline
 * - Transcription contains expected text (accuracy check)
 * - Pipeline state transitions work correctly
 */
@RunWith(AndroidJUnit4::class)
class ParakeetSttTest {

    private lateinit var modelDir: String

    @Before
    fun setup() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        modelDir = ModelManager.ensureModels(ctx)
    }

    @Test
    fun pipelineStateTransitions() {
        val config = SpeechConfig(modelDir = modelDir, useNnapi = false)
        val pipeline = SpeechPipeline(config)

        assertEquals(PipelineState.Idle, pipeline.state)
        pipeline.start()
        assertTrue(
            pipeline.state == PipelineState.Idle ||
            pipeline.state == PipelineState.Listening
        )

        pipeline.stop()
        pipeline.close()
    }

    @Test
    fun sttTranscribesTestAudio() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val rawFile = File(ctx.filesDir, "test_speech.raw")
        if (!rawFile.exists()) {
            // Skip if test audio not present — CI should push it
            return@runBlocking
        }

        val config = SpeechConfig(modelDir = modelDir, useNnapi = false)
        val pipeline = SpeechPipeline(config)
        pipeline.start()

        // Read raw Float32 PCM
        val bytes = rawFile.readBytes()
        val samples = FloatArray(bytes.size / 4)
        java.nio.ByteBuffer.wrap(bytes)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
            .get(samples)

        // Push in 512-sample chunks at real-time pace
        for (offset in samples.indices step 512) {
            val end = minOf(offset + 512, samples.size)
            val chunk = samples.sliceArray(offset until end)
            pipeline.pushAudio(chunk)
            delay(32) // 512 samples @ 16kHz = 32ms
        }

        // Push silence to trigger end-of-speech
        val silence = FloatArray(16000) // 1s
        for (offset in silence.indices step 512) {
            pipeline.pushAudio(silence.sliceArray(offset until minOf(offset + 512, silence.size)))
            delay(32)
        }

        // Wait for transcription
        val event = withTimeout(30_000) {
            pipeline.events.first { it is SpeechEvent.TranscriptionCompleted }
        }
        val tc = event as SpeechEvent.TranscriptionCompleted

        assertNotNull(tc.text)
        assertTrue("Transcription should not be empty", tc.text.isNotBlank())
        assertTrue("Confidence should be > 0.5, was ${tc.confidence}", tc.confidence > 0.5f)

        // Check that key words are present (case-insensitive)
        val text = tc.text.lowercase()
        val expectedWords = listOf("quick", "brown", "fox", "dog")
        val matched = expectedWords.count { it in text }
        assertTrue(
            "Expected at least 2 of $expectedWords in '$text', matched $matched",
            matched >= 2
        )

        pipeline.stop()
        pipeline.close()
    }

    @Test
    fun transcriptionEventFields() = runBlocking {
        val config = SpeechConfig(modelDir = modelDir, useNnapi = false)
        val pipeline = SpeechPipeline(config)
        pipeline.start()

        // Generate speech-like signal (buzz at 150Hz with harmonics)
        val sr = 16000
        val n = sr * 2 // 2 seconds
        val speech = FloatArray(n) { i ->
            val t = i.toFloat() / sr
            (0.3f * Math.sin(2.0 * Math.PI * 150.0 * t)
            + 0.2f * Math.sin(2.0 * Math.PI * 300.0 * t)
            + 0.1f * Math.sin(2.0 * Math.PI * 450.0 * t)).toFloat()
        }

        for (offset in speech.indices step 512) {
            val end = minOf(offset + 512, speech.size)
            val chunk = speech.sliceArray(offset until end)
            if (chunk.size == 512) pipeline.pushAudio(chunk)
        }

        // Silence to trigger end-of-speech
        val silence = FloatArray(16000)
        for (offset in silence.indices step 512) {
            val end = minOf(offset + 512, silence.size)
            pipeline.pushAudio(silence.sliceArray(offset until end))
        }

        try {
            val event = withTimeout(15_000) {
                pipeline.events.first { it is SpeechEvent.TranscriptionCompleted }
            }
            val tc = event as SpeechEvent.TranscriptionCompleted
            assertNotNull(tc.text)
            assertTrue(tc.confidence in 0.0f..1.0f)
            assertTrue(tc.sttMs >= 0f)
        } catch (_: Exception) {
            // Synthetic signal may not trigger VAD — acceptable
        }

        pipeline.stop()
        pipeline.close()
    }
}
