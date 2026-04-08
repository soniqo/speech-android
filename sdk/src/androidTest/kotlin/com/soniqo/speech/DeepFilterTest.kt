package audio.soniqo.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E test: DeepFilterNet3 noise cancellation.
 *
 * DFN is currently disabled in the pipeline (sample rate mismatch, see #12).
 * These tests verify the pipeline works correctly without DFN and that
 * the enableEnhancer config flag doesn't cause crashes.
 */
@RunWith(AndroidJUnit4::class)
class DeepFilterTest {

    private lateinit var modelDir: String

    @Before
    fun setup() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        modelDir = ModelManager.ensureModels(ctx)
    }

    @Test
    fun pipelineCreatesWithEnhancerFlag() {
        // enableEnhancer is accepted but DFN is not attached (disabled in jni_bridge)
        val config = SpeechConfig(
            modelDir = modelDir,
            useNnapi = false,
            enableEnhancer = true,
        )
        val pipeline = SpeechPipeline(config)
        assertEquals(PipelineState.Idle, pipeline.state)
        pipeline.close()
    }

    @Test
    fun noisyInputProcessedWithoutDfn() {
        val config = SpeechConfig(
            modelDir = modelDir,
            useNnapi = false,
            enableEnhancer = true,
        )
        val pipeline = SpeechPipeline(config)
        pipeline.start()

        // Noisy signal — should process fine even without DFN
        val sr = 16000
        val noisy = FloatArray(sr * 2) { i ->
            val t = i.toFloat() / sr
            val speech = (0.3f * Math.sin(2.0 * Math.PI * 200.0 * t)).toFloat()
            val noise = (Math.random().toFloat() - 0.5f) * 0.2f
            speech + noise
        }

        for (offset in noisy.indices step 512) {
            val end = minOf(offset + 512, noisy.size)
            val chunk = noisy.sliceArray(offset until end)
            if (chunk.size == 512) pipeline.pushAudio(chunk)
        }

        pipeline.stop()
        pipeline.close()
    }

    @Test
    fun pipelineWithoutEnhancerAlsoWorks() {
        val config = SpeechConfig(
            modelDir = modelDir,
            useNnapi = false,
            enableEnhancer = false,
        )
        val pipeline = SpeechPipeline(config)
        pipeline.start()
        pipeline.pushAudio(FloatArray(512))
        pipeline.stop()
        pipeline.close()
    }
}
