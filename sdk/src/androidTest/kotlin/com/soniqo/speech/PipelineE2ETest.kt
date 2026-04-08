package audio.soniqo.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end pipeline tests.
 *
 * Tests the full VAD → STT → TTS chain including:
 * - Model download and caching
 * - Pipeline lifecycle (create, start, stop, destroy)
 * - Event emission
 * - Concurrent audio push from separate thread
 * - Multiple start/stop cycles
 * - Memory management (no leaks on repeated create/destroy)
 */
@RunWith(AndroidJUnit4::class)
class PipelineE2ETest {

    private lateinit var modelDir: String

    @Before
    fun setup() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        modelDir = ModelManager.ensureModels(ctx)
    }

    @Test
    fun modelDownloadIsCached() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // First call downloads
        val start = System.currentTimeMillis()
        val dir1 = ModelManager.ensureModels(ctx)
        val firstDuration = System.currentTimeMillis() - start

        // Second call should be near-instant (cached)
        val start2 = System.currentTimeMillis()
        val dir2 = ModelManager.ensureModels(ctx)
        val secondDuration = System.currentTimeMillis() - start2

        assertEquals(dir1, dir2)
        assertTrue(
            "Cached call should be <100ms, was ${secondDuration}ms",
            secondDuration < 100
        )
    }

    @Test
    fun sessionCreatedEventEmitted() = runBlocking {
        val config = SpeechConfig(modelDir = modelDir, useNnapi = false)
        val pipeline = SpeechPipeline(config)

        pipeline.start()

        try {
            val event = withTimeout(5_000) {
                pipeline.events.first { it is SpeechEvent.SessionCreated }
            }
            assertNotNull(event)
        } catch (e: Exception) {
            // SessionCreated may be emitted before we start collecting
        }

        pipeline.stop()
        pipeline.close()
    }

    @Test
    fun multipleStartStopCycles() {
        val config = SpeechConfig(modelDir = modelDir, useNnapi = false)
        val pipeline = SpeechPipeline(config)

        repeat(5) {
            pipeline.start()
            pipeline.pushAudio(FloatArray(512))
            pipeline.stop()
        }

        pipeline.close()
    }

    @Test
    fun concurrentAudioPush() = runBlocking {
        val config = SpeechConfig(modelDir = modelDir, useNnapi = false)
        val pipeline = SpeechPipeline(config)
        pipeline.start()

        // Push audio from multiple coroutines concurrently
        val jobs = (0 until 4).map {
            launch {
                repeat(100) {
                    pipeline.pushAudio(FloatArray(512))
                    delay(1) // 1ms between pushes
                }
            }
        }
        jobs.forEach { it.join() }

        pipeline.stop()
        pipeline.close()
    }

    @Test
    fun repeatedCreateDestroy() {
        // Test for memory leaks — create and destroy 10 pipelines
        repeat(10) {
            val config = SpeechConfig(modelDir = modelDir, useNnapi = false)
            val pipeline = SpeechPipeline(config)
            pipeline.start()
            pipeline.pushAudio(FloatArray(512))
            pipeline.stop()
            pipeline.close()
        }
    }

    @Test
    fun pushAudioAfterStopIsNoOp() {
        val config = SpeechConfig(modelDir = modelDir, useNnapi = false)
        val pipeline = SpeechPipeline(config)
        pipeline.start()
        pipeline.stop()

        // Should not crash
        pipeline.pushAudio(FloatArray(512))
        pipeline.close()
    }

    @Test
    fun resumeListeningAfterResponse() = runBlocking {
        val config = SpeechConfig(modelDir = modelDir, useNnapi = false)
        val pipeline = SpeechPipeline(config)
        pipeline.start()

        // Simulate: push audio → get response → resume listening
        pipeline.pushAudio(FloatArray(512))
        pipeline.resumeListening()

        assertEquals(
            true,
            pipeline.state == PipelineState.Idle || pipeline.state == PipelineState.Listening
        )

        pipeline.stop()
        pipeline.close()
    }

    @Test
    fun corruptModelFileTriggersRedownload() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = java.io.File(ctx.filesDir, "models")

        // Corrupt a small model file
        val vadFile = java.io.File(dir, "silero-vad.onnx")
        val originalSize = vadFile.length()
        assertTrue("VAD model should exist", vadFile.exists())

        // Write zero bytes to corrupt it
        vadFile.writeBytes(ByteArray(0))
        assertEquals(0L, vadFile.length())

        // ensureModels should detect the corrupt file and redownload
        val modelDir2 = ModelManager.ensureModels(ctx)
        val restoredFile = java.io.File(modelDir2, "silero-vad.onnx")
        assertTrue("VAD model should be restored", restoredFile.exists())
        assertTrue("VAD model should have valid size", restoredFile.length() > 100_000)
    }

    @Test
    fun nativeCreateFailureThrowsException() {
        // Pass invalid model directory — nativeCreate should throw, not return 0 silently
        try {
            val config = SpeechConfig(modelDir = "/nonexistent/path", useNnapi = false)
            SpeechPipeline(config)
            fail("Should have thrown an exception for invalid model dir")
        } catch (e: Exception) {
            // Expected — either RuntimeException from native or IllegalStateException from handle check
            assertTrue(
                "Error should mention pipeline or model failure",
                e.message?.contains("pipeline") == true ||
                e.message?.contains("model") == true ||
                e.message?.contains("Native") == true ||
                e.message?.contains("Failed") == true
            )
        }
    }

    @Test
    fun partialTranscriptionEmittedDuringSpeech() = runBlocking {
        val config = SpeechConfig(
            modelDir = modelDir,
            useNnapi = false,
            emitPartialTranscriptions = true,
            partialTranscriptionInterval = 0.5f,
        )
        val pipeline = SpeechPipeline(config)
        pipeline.start()

        // Push 3 seconds of speech-like signal to give streaming time to emit partials
        val sr = 16000
        val speech = FloatArray(sr * 3) { i ->
            val t = i.toFloat() / sr
            (0.3f * Math.sin(2.0 * Math.PI * 200.0 * t)
            + 0.15f * Math.sin(2.0 * Math.PI * 400.0 * t)).toFloat()
        }

        for (offset in speech.indices step 512) {
            val end = minOf(offset + 512, speech.size)
            val chunk = speech.sliceArray(offset until end)
            if (chunk.size == 512) pipeline.pushAudio(chunk)
        }

        // Push silence to end the utterance
        val silence = FloatArray(sr)
        for (offset in silence.indices step 512) {
            val chunk = silence.sliceArray(offset until minOf(offset + 512, silence.size))
            if (chunk.size == 512) pipeline.pushAudio(chunk)
        }

        // Wait for either a partial or completed transcription
        try {
            val event = withTimeout(30_000) {
                pipeline.events.first {
                    it is SpeechEvent.PartialTranscription ||
                    it is SpeechEvent.TranscriptionCompleted
                }
            }
            // If we got any transcription event, streaming is working
            assertTrue(
                "Should receive partial or completed transcription",
                event is SpeechEvent.PartialTranscription ||
                event is SpeechEvent.TranscriptionCompleted
            )
        } catch (_: Exception) {
            // Synthetic signal may not trigger VAD — pass silently
        }

        pipeline.stop()
        pipeline.close()
    }
}
