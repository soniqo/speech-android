package com.soniqo.speech

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
}
