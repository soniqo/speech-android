package audio.soniqo.speech

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ParakeetTranscriberTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rejectsOversizedNonFiniteAndInvalidCountsBeforeNativeInference() {
        requireParakeetSamples(FloatArray(0), 0)
        requireParakeetSamples(floatArrayOf(0f, Float.NaN), 1)
        assertThrows(IllegalArgumentException::class.java) { requireParakeetSamples(floatArrayOf(0f), 2) }
        assertThrows(IllegalArgumentException::class.java) { requireParakeetSamples(floatArrayOf(0f), -1) }
        assertThrows(IllegalArgumentException::class.java) { requireParakeetSamples(floatArrayOf(Float.NaN), 1) }
        assertThrows(IllegalArgumentException::class.java) { requireParakeetSamples(floatArrayOf(Float.POSITIVE_INFINITY), 1) }
        assertThrows(IllegalArgumentException::class.java) {
            requireParakeetSamples(FloatArray(ParakeetTranscriber.MAXIMUM_SAMPLES + 1), ParakeetTranscriber.MAXIMUM_SAMPLES + 1)
        }
    }

    @Test fun theMultilingualBundleHasAnImmutableIdentitySeparateFromNemotron() {
        val files = ModelManager.parakeetTranscriberModels()
        assertEquals(setOf("Parakeet-TDT-v3-ONNX"), files.map { it.repo }.toSet())
        assertEquals(setOf("519efaaa53afb1ee4c8fa7f6e611908f030f1eba"), files.map { it.revision }.toSet())
        assertEquals(ModelManager.PARAKEET_TRANSCRIBER_HASHES.keys, files.map { it.filename }.toSet())
        assertEquals(3, files.size)
        assertTrue(ModelManager.PARAKEET_TRANSCRIBER_HASHES.values.all { it.matches(Regex("[0-9a-f]{64}")) })
        val ordinary = ModelDownloadWorker.uniqueName(includePipeline = false, includeTranscriber = true)
        val experiment = ModelDownloadWorker.uniqueName(includePipeline = false, includeTranscriber = true,
            includeParakeetTranscriber = true)
        assertNotEquals(ordinary, experiment)
        assertTrue(experiment.endsWith(".parakeetTdtV3"))
    }

    @Test fun checksumFailureRemovesOnlyTheInvalidModelForAResumableRetry() {
        val dir = temporary.newFolder()
        val model = File(dir, "model.onnx").apply { writeText("abc") }
        val unrelated = File(dir, "other").apply { writeText("keep") }
        val hashes = mapOf("model.onnx" to "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        ModelManager.verifyModelChecksums(dir, hashes)
        assertTrue(model.isFile)
        model.writeText("abd")
        assertThrows(IOException::class.java) { ModelManager.verifyModelChecksums(dir, hashes) }
        assertFalse(model.exists())
        assertEquals("keep", unrelated.readText())
    }
}
