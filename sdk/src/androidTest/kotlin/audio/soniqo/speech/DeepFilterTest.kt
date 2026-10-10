package audio.soniqo.speech

import android.system.Os
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/** Exercises the real CPU streaming enhancer through the Android JNI pipeline. */
@RunWith(AndroidJUnit4::class)
class DeepFilterTest {
    private lateinit var modelDir: String

    @Before
    fun setup() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        modelDir = ModelManager.ensureModels(ctx)
    }

    @Test
    fun missingEnhancerFailsBeforeCaptureStarts() = withTemporaryModels { dir ->
        val error = assertThrows(RuntimeException::class.java) {
            SpeechPipeline(SpeechConfig(modelDir = dir.path, enableEnhancer = true)).close()
        }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("deepfilter.onnx"))
    }

    @Test
    fun unsupportedEnhancerExportFailsBeforeCaptureStarts() = withTemporaryModels { dir ->
        val model = File(dir, "deepfilter.onnx")
        File(modelDir, model.name).copyTo(model)
        // A valid unknown protobuf field keeps the batch graph loadable, but
        // changes its bytes so it cannot be used by the stateful graph.
        model.appendBytes(byteArrayOf(0xa0.toByte(), 0x06, 0x01))
        val error = assertThrows(RuntimeException::class.java) {
            SpeechPipeline(SpeechConfig(modelDir = dir.path, enableEnhancer = true)).close()
        }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("published v0.5.6 FP32"))
    }

    @Test
    fun disabledEnhancerDoesNotRequireItsWeights() = withTemporaryModels { dir ->
        val source = File(modelDir)
        // Link files individually, with real directories, so cleanup never
        // follows a directory symlink into the shared model cache.
        source.walkTopDown().filter { it.isFile && it.name != "deepfilter.onnx" }.forEach { file ->
            val link = File(dir, file.relativeTo(source).path)
            link.parentFile!!.mkdirs()
            Os.symlink(file.absolutePath, link.absolutePath)
        }
        SpeechPipeline(SpeechConfig(modelDir = dir.path, enableEnhancer = false)).use { pipeline ->
            pipeline.start()
            pipeline.pushAudio(FloatArray(512))
            assertEquals(PipelineState.Idle, pipeline.state)
        }
    }

    @Test
    fun noisySpeechSurvivesPacketChangesRestartAndCancellation() = runBlocking {
        val speech = MeetingFixtures.read("transcriber_en.wav")
        val random = Random(95)
        val noisy = FloatArray(speech.size) { speech[it] + (random.nextFloat() - 0.5f) * 0.03f }
        SpeechPipeline(SpeechConfig(
            modelDir = modelDir,
            useNnapi = false,
            enableEnhancer = true,
            pipelineMode = PipelineMode.TRANSCRIBE_ONLY,
            endOfSpeechSilenceSec = 1.2f,
        )).use { pipeline ->
            pipeline.start()
            val regular = transcribe(pipeline, noisy, intArrayOf(512))
            assertRecognizable(regular)

            pipeline.stop()
            pipeline.start()
            val irregular = transcribe(pipeline, noisy, intArrayOf(1, 97, 160, 513, 2048, 31))
            assertEquals("restart and packet sizes must preserve the words",
                MeetingFixtures.words(regular), MeetingFixtures.words(irregular))

            pipeline.pushAudio(noisy.copyOfRange(0, minOf(8_000, noisy.size)))
            pipeline.cancelCurrentTurn()
            val afterCancel = transcribe(pipeline, noisy, intArrayOf(160))
            assertEquals("cancel must discard the previous stream's history",
                MeetingFixtures.words(regular), MeetingFixtures.words(afterCancel))
        }
    }

    private suspend fun transcribe(
        pipeline: SpeechPipeline, audio: FloatArray, packets: IntArray,
    ): String = coroutineScope {
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(60_000) {
                pipeline.events.first {
                    it is SpeechEvent.TranscriptionCompleted || it is SpeechEvent.Error
                }
            }
        }
        val input = FloatArray(16_000) + audio + FloatArray(32_000)
        val started = System.nanoTime()
        var offset = 0
        var packet = 0
        while (offset < input.size) {
            val end = minOf(offset + packets[packet++ % packets.size], input.size)
            pipeline.pushAudio(input.copyOfRange(offset, end))
            offset = end
        }
        val captureMs = (System.nanoTime() - started) / 1_000_000.0
        Log.i("DeepFilterTest", "packets=${packets.toList()} captureMs=$captureMs " +
            "audioSeconds=${MeetingFixtures.seconds(input.size)}")
        when (val event = result.await()) {
            is SpeechEvent.TranscriptionCompleted -> event.text.also {
                Log.i("DeepFilterTest", "transcript=$it sttMs=${event.sttMs}")
            }
            is SpeechEvent.Error -> error(event.message)
            else -> error("unexpected event: $event")
        }
    }

    private fun assertRecognizable(text: String) {
        val expected = MeetingFixtures.words(MeetingFixtures.TRANSCRIBER_SENTENCE)
        val actual = MeetingFixtures.words(text)
        val recognized = expected.count { it in actual }
        assertTrue("noisy speech must remain recognizable: $text",
            recognized >= expected.size * 0.8)
    }

    private fun withTemporaryModels(block: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "deepfilter-test-${System.nanoTime()}")
        check(dir.mkdirs())
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
