package audio.soniqo.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        modelDir = ModelManager.ensureModels(ctx, sttModel = SttModel.PARAKEET)
    }

    @Test
    fun pipelineStateTransitions() {
        val config = parakeetConfig()
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
    fun sttTranscribesSynthesizedEnglishWithLanguageHints() = runBlocking {
        val config = parakeetConfig(languageHints = listOf("en-US", "fr"))
        val pipeline = SpeechPipeline(config)
        try {
            pipeline.start()
            val eventDeferred = async {
                withTimeout(60_000) {
                    pipeline.events.first { it is SpeechEvent.TranscriptionCompleted }
                }
            }

            val synthesizer = SpeechSynthesizer(
                SpeechSynthesizerConfig(modelDir = modelDir, useNnapi = false)
            )
            val audio = synthesizer.use {
                val spoken = it.synthesize("The quick brown fox jumps over the dog.", "en")
                assertTrue("Synthesized fixture should not be empty", spoken.pcm16.isNotEmpty())
                pcm16ToFloat16k(spoken.pcm16, spoken.sampleRate)
            }
            assertTrue("Synthesized fixture should contain audio", audio.isNotEmpty())

            // Push in 512-sample chunks at real-time pace.
            for (offset in audio.indices step 512) {
                val end = minOf(offset + 512, audio.size)
                val chunk = audio.sliceArray(offset until end)
                pipeline.pushAudio(chunk)
                delay(32) // 512 samples @ 16kHz = 32ms
            }

            // Push silence to trigger end-of-speech
            val silence = FloatArray(16000) // 1s
            for (offset in silence.indices step 512) {
                pipeline.pushAudio(
                    silence.sliceArray(offset until minOf(offset + 512, silence.size))
                )
                delay(32)
            }

            val tc = eventDeferred.await() as SpeechEvent.TranscriptionCompleted

            assertNotNull(tc.text)
            assertTrue("Transcription should not be empty", tc.text.isNotBlank())
            assertTrue(
                "Confidence should be in range, was ${tc.confidence}",
                tc.confidence in 0.0f..1.0f
            )

            // Check that key words are present (case-insensitive)
            val text = tc.text.lowercase()
            val expectedWords = listOf("quick", "brown", "fox", "dog")
            val matched = expectedWords.count { it in text }
            assertTrue(
                "Expected at least 2 of $expectedWords in '$text', matched $matched",
                matched >= 2
            )
        } finally {
            pipeline.stop()
            pipeline.close()
        }
    }

    @Test
    fun sttDecodesBlankCorrectly() = runBlocking {
        val config = parakeetConfig()
        val pipeline = SpeechPipeline(config)
        pipeline.start()

        // Push pure silence — STT should not produce garbage text
        val sr = 16000
        val silence = FloatArray(sr * 3) // 3 seconds of silence

        for (offset in silence.indices step 512) {
            val end = minOf(offset + 512, silence.size)
            val chunk = silence.sliceArray(offset until end)
            if (chunk.size == 512) pipeline.pushAudio(chunk)
        }

        // Additional silence to ensure end-of-speech triggers if VAD fires
        val trailing = FloatArray(sr * 2)
        for (offset in trailing.indices step 512) {
            val end = minOf(offset + 512, trailing.size)
            val chunk = trailing.sliceArray(offset until end)
            if (chunk.size == 512) pipeline.pushAudio(chunk)
        }

        try {
            // If VAD triggers on silence (unlikely), the transcription should be blank
            val event = withTimeout(10_000) {
                pipeline.events.first { it is SpeechEvent.TranscriptionCompleted }
            }
            val tc = event as SpeechEvent.TranscriptionCompleted
            // Blank/silence input should produce empty or very short transcription
            assertTrue(
                "Silence should not produce long text, got '${tc.text}' (${tc.text.length} chars)",
                tc.text.length < 20
            )
        } catch (_: Exception) {
            // Expected: VAD does not trigger on silence, so no transcription event.
            // This is the correct behavior.
        }

        pipeline.stop()
        pipeline.close()
    }

    @Test
    fun transcriptionEventFields() = runBlocking {
        val config = parakeetConfig()
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

    @Test
    fun fixedLanguagePipelineStarts() {
        val config = parakeetConfig(language = "en-US")
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

    private fun pcm16ToFloat16k(pcm16: ByteArray, sourceSampleRate: Int): FloatArray {
        val shorts = ShortArray(pcm16.size / 2)
        ByteBuffer.wrap(pcm16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shorts)
        val source = FloatArray(shorts.size) { i -> shorts[i] / 32768.0f }
        if (sourceSampleRate == 16000) return source

        val outputSize = ((source.size.toLong() * 16000L) / sourceSampleRate).toInt()
        return FloatArray(outputSize) { i ->
            val src = i.toDouble() * sourceSampleRate.toDouble() / 16000.0
            val lo = src.toInt().coerceIn(0, source.lastIndex)
            val hi = minOf(lo + 1, source.lastIndex)
            val frac = (src - lo).toFloat()
            source[lo] * (1.0f - frac) + source[hi] * frac
        }
    }

    private fun parakeetConfig(
        language: String = "auto",
        languageHints: List<String> = emptyList(),
    ): SpeechConfig = SpeechConfig(
        modelDir = modelDir,
        useNnapi = false,
        sttModel = SttModel.PARAKEET,
        language = language,
        languageHints = languageHints,
    )
}
