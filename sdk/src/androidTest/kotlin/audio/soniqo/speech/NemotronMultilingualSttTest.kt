package audio.soniqo.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device E2E test for the Nemotron-3.5 ASR streaming multilingual STT
 * (ONNX or LiteRT backend). Unlike ParakeetSttTest this does NOT download from
 * Hugging Face — the bundle is expected to be pre-pushed (via adb + run-as)
 * into the target app's internal files dir under `nemo-models/`, so the test
 * runs offline:
 *
 *   ./gradlew :sdk:installDebugAndroidTest
 *   adb shell run-as audio.soniqo.speech.test mkdir -p files/nemo-models
 *   # Stream each model/fixture with:
 *   adb exec-in run-as audio.soniqo.speech.test sh -c \
 *     'cat > files/nemo-models/<filename>' < <local-file>
 *
 * Pass `-e nemotronBackend LITERT` to exercise LiteRT (the default is ONNX).
 * Tests are reported as skipped when the provisioned bundle is absent.
 */
@RunWith(AndroidJUnit4::class)
class NemotronMultilingualSttTest {

    private val backend: SttBackend
        get() {
            val value = InstrumentationRegistry.getArguments()
                .getString("nemotronBackend")
                ?.uppercase()
            return if (value == SttBackend.LITERT.name) {
                SttBackend.LITERT
            } else {
                SttBackend.ONNX
            }
        }

    private fun modelDir(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.filesDir, "nemo-models")
        android.util.Log.i("NemoTest", "modelDir=$dir exists=${dir.exists()} bundle=${hasBundle(dir)}")
        return dir
    }

    private fun hasBundle(dir: File): Boolean {
        val sttFiles = when (backend) {
            SttBackend.ONNX -> listOf("encoder.onnx", "decoder.onnx", "joint.onnx")
            SttBackend.LITERT -> listOf(
                "nemotron-multilingual-encoder.tflite",
                "nemotron-multilingual-decoder.tflite",
                "nemotron-multilingual-joint.tflite",
            )
        }
        val pipelineFiles = sttFiles + listOf(
            "vocab.json",
            "languages.json",
            "silero-vad.onnx",
            "kokoro-e2e-realtime.onnx",
            "kokoro-e2e.onnx.data",
        )
        return pipelineFiles.all { File(dir, it).isFile }
    }

    private fun nemotronConfig(dir: File) = SpeechConfig(
        modelDir = dir.absolutePath,
        useNnapi = false,
        sttModel = SttModel.NEMOTRON_MULTILINGUAL,
        sttBackend = backend,
        ttsModel = TtsModel.KOKORO_SHORT_TURN,
        pipelineMode = PipelineMode.TRANSCRIBE_ONLY,
        language = "en-US",
    )

    /** The pipeline (VAD + Nemotron STT + TTS) constructs and tears down. */
    @Test
    fun pipelineConstructsWithNemotron() {
        val dir = modelDir()
        assumeTrue("provision the $backend Nemotron test bundle in $dir", hasBundle(dir))
        val pipeline = SpeechPipeline(nemotronConfig(dir))
        try {
            assertEquals(PipelineState.Idle, pipeline.state)
            pipeline.start()
            assertTrue(
                pipeline.state == PipelineState.Idle ||
                    pipeline.state == PipelineState.Listening,
            )
        } finally {
            pipeline.stop()
            pipeline.close()
        }
    }

    /** Real English audio → a non-empty transcription mentioning expected words. */
    @Test
    fun transcribesEnglishAudio() = runBlocking {
        val dir = modelDir()
        assumeTrue("provision the $backend Nemotron test bundle in $dir", hasBundle(dir))
        val raw = File(dir, "nemo_en.raw")
        assumeTrue("provision nemo_en.raw in $dir", raw.isFile)

        val pipeline = SpeechPipeline(nemotronConfig(dir))
        try {
            pipeline.start()
            val eventDeferred = async {
                withTimeout(180_000) {
                    pipeline.events.first { it is SpeechEvent.TranscriptionCompleted }
                }
            }

            val bytes = raw.readBytes()
            val samples = FloatArray(bytes.size / 4)
            java.nio.ByteBuffer.wrap(bytes)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer().get(samples)

            for (offset in samples.indices step 512) {
                val end = minOf(offset + 512, samples.size)
                pipeline.pushAudio(samples.sliceArray(offset until end))
                delay(8)
            }
            // Trailing silence to trigger end-of-speech.
            val silence = FloatArray(16000)
            for (offset in silence.indices step 512) {
                pipeline.pushAudio(silence.sliceArray(offset until minOf(offset + 512, silence.size)))
                delay(8)
            }

            val tc = eventDeferred.await() as SpeechEvent.TranscriptionCompleted
            assertTrue("transcription should not be empty", tc.text.isNotBlank())
            val text = tc.text.lowercase()
            val expected = InstrumentationRegistry.getArguments()
                .getString("nemotronExpectedWords")
                ?.split(",")
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?: listOf("alloy", "metal", "mixture", "element")
            val matched = expected.count { it.lowercase() in text }
            assertTrue("expected >=1 of $expected in '$text'", matched >= 1)
        } finally {
            pipeline.stop()
            pipeline.close()
        }
    }
}
