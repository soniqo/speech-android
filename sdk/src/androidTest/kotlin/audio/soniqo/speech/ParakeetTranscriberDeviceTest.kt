package audio.soniqo.speech

import android.os.Debug
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Standalone TDT v3 over authored synthetic speech. No capture or playback. */
@RunWith(AndroidJUnit4::class)
class ParakeetTranscriberDeviceTest {
    private lateinit var modelDir: String

    @Before fun setup() = runBlocking {
        modelDir = ModelManager.ensureParakeetTranscriberModels(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
    }

    @Test fun repeatedUtterancesKeepTheirWordsAndBoundedEmissionTimes() {
        val samples = MeetingFixtures.read("transcriber_en.wav")
        val duration = MeetingFixtures.seconds(samples.size)
        val model = ParakeetTranscriber(modelDir)
        try {
            val first = model.transcribe(samples)
            val repeated = model.transcribe(samples)
            assertEquals(first.text, repeated.text)
            assertEquals(first.words, repeated.words)
            val expected = MeetingFixtures.words(MeetingFixtures.TRANSCRIBER_SENTENCE)
                .map { if (it == "nine") "9" else it }.toSet()
            val actual = MeetingFixtures.words(first.text).map { if (it == "nine") "9" else it }.toSet()
            assertTrue("the synthetic sentence retains its words", expected.intersect(actual).size >= expected.size * .85)
            assertEquals(first.text, first.words.joinToString(" ") { it.text })
            var previous = 0f
            for (word in first.words) {
                assertTrue(word.startSec >= previous && word.endSec > word.startSec)
                assertTrue(word.endSec <= duration + .001)
                previous = word.startSec
            }
            assertTrue(model.transcribe(FloatArray(0)).text.isEmpty())
        } finally { model.close() }
        model.close()
        assertThrows(IllegalStateException::class.java) { model.transcribe(samples) }
    }

    @Test fun incompleteBundlesReleaseSessionsAndAllowAHealthyRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (missing in listOf("parakeet-decoder-joint-int8.onnx", "vocab.json")) {
            val folder = File(context.cacheDir, "parakeet-incomplete-${System.nanoTime()}").apply { mkdirs() }
            try {
                for (name in listOf("parakeet-encoder-int8.onnx", "parakeet-decoder-joint-int8.onnx", "vocab.json")) {
                    if (name != missing) Os.symlink(File(modelDir, name).path, File(folder, name).path)
                }
                val before = Debug.getNativeHeapAllocatedSize()
                assertThrows(RuntimeException::class.java) { ParakeetTranscriber(folder.path).close() }
                val retained = Debug.getNativeHeapAllocatedSize() - before
                // A stranded encoder is about 900 MiB. Leave room for ORT
                // initialization without permitting a leaked model session.
                assertTrue("failed $missing load retained $retained bytes", retained < 128L * 1024 * 1024)
            } finally {
                folder.listFiles()?.forEach { it.delete() }
                folder.delete()
            }
        }
        ParakeetTranscriber(modelDir).use {
            assertTrue(it.transcribe(MeetingFixtures.read("transcriber_en.wav")).text.isNotBlank())
        }
    }
}
