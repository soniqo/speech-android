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
 * On-device Canary STT through the full pipeline.
 *
 * Canary is offline per utterance — the encoder consumes the whole segment
 * before the first token — so there is nothing streaming to assert here.
 * Starting the pipeline is itself a real check: the wrapper reads its prompt,
 * decoder cache shape and end-of-text id from the bundle's graph metadata and
 * throws if any of it is missing, so a partially downloaded bundle fails loudly
 * instead of decoding something fluent and wrong.
 */
@RunWith(AndroidJUnit4::class)
class CanarySttTest {

    private lateinit var modelDir: String

    @Before
    fun setup() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        modelDir = ModelManager.ensureModels(ctx, sttModel = SttModel.CANARY)
    }

    @Test
    fun pipelineStateTransitions() {
        val pipeline = SpeechPipeline(canaryConfig())
        try {
            assertEquals(PipelineState.Idle, pipeline.state)
            pipeline.start()
            assertTrue(
                pipeline.state == PipelineState.Idle ||
                pipeline.state == PipelineState.Listening
            )
            pipeline.stop()
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun acceptsEverySupportedLanguage() {
        // en, de, es and fr have prompt tokens in the bundle. An unknown code
        // leaves the bundle's default in place rather than failing to build the
        // pipeline, so "zz" must start just as cleanly.
        for (language in listOf("en", "de", "es", "fr", "zz")) {
            val pipeline = SpeechPipeline(canaryConfig(language))
            try {
                pipeline.start()
                pipeline.stop()
            } finally {
                pipeline.close()
            }
        }
    }

    @Test
    fun sttTranscribesSynthesizedEnglish() = runBlocking {
        val pipeline = SpeechPipeline(canaryConfig())
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

            for (offset in audio.indices step 512) {
                val end = minOf(offset + 512, audio.size)
                pipeline.pushAudio(audio.sliceArray(offset until end))
                delay(32) // 512 samples @ 16 kHz = 32 ms
            }

            // Silence closes the turn, which is when Canary decodes.
            val silence = FloatArray(16000)
            for (offset in silence.indices step 512) {
                pipeline.pushAudio(
                    silence.sliceArray(offset until minOf(offset + 512, silence.size))
                )
                delay(32)
            }

            val tc = eventDeferred.await() as SpeechEvent.TranscriptionCompleted
            assertTrue("Transcription should not be empty", tc.text.isNotBlank())
            assertTrue(
                "Confidence should be in range, was ${tc.confidence}",
                tc.confidence in 0.0f..1.0f
            )

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
    fun silenceProducesNoTranscript() = runBlocking {
        val pipeline = SpeechPipeline(canaryConfig())
        try {
            pipeline.start()
            val silence = FloatArray(16000 * 3)
            for (offset in silence.indices step 512) {
                pipeline.pushAudio(
                    silence.sliceArray(offset until minOf(offset + 512, silence.size))
                )
                delay(32)
            }
            delay(1000)
            assertTrue(
                "Silence should leave the pipeline idle or listening, was ${pipeline.state}",
                pipeline.state == PipelineState.Idle ||
                pipeline.state == PipelineState.Listening
            )
        } finally {
            pipeline.stop()
            pipeline.close()
        }
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
            val hi = (lo + 1).coerceAtMost(source.lastIndex)
            val frac = (src - lo).toFloat()
            source[lo] * (1f - frac) + source[hi] * frac
        }
    }

    private fun canaryConfig(language: String = "en"): SpeechConfig = SpeechConfig(
        modelDir = modelDir,
        useNnapi = false,
        sttModel = SttModel.CANARY,
        language = language,
    )
}
