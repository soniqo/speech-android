package audio.soniqo.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

/**
 * Coverage for [ModelManager.plannedModelBytes] / [ModelManager.plannedLlmBytes],
 * which supply the denominator of the byte-weighted download bar. Getting these
 * wrong doesn't fail a download — it silently makes the bar lie — so the cache
 * states they have to distinguish are pinned here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelManagerPlanningTest {

    private lateinit var context: Context
    private lateinit var modelDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        modelDir = File(ModelManager.modelDir(context))
        modelDir.deleteRecursively()
        File(ModelManager.llmModelDir(context)).deleteRecursively()
    }

    /**
     * A file that passes [ModelManager]'s validity check without costing disk:
     * ONNX protobuf magic up front, then a sparse extent to clear the
     * per-file size floor.
     */
    private fun writeValid(dir: File, name: String, size: Long) {
        val f = File(dir, name)
        f.parentFile?.mkdirs()
        RandomAccessFile(f, "rw").use { raf ->
            raf.write(byteArrayOf(0x08, 0x00))
            if (size > 2) raf.setLength(size)
        }
    }

    @Test
    fun `fresh install plans the whole default manifest`() {
        val pipeline = ModelManager.plannedModelBytes(context)
        // Published INT8 Parakeet-EOU + Kokoro set is ~493 MB.
        assertTrue("expected ~493 MB, got $pipeline", pipeline in 480_000_000..505_000_000)
    }

    @Test
    fun `fresh install plans the exact FunctionGemma bundle size`() {
        assertEquals(297_212_528L, ModelManager.plannedLlmBytes(context))
    }

    /** Markers that make an existing cache current rather than stale. */
    private fun writeCurrentMarkers(dir: File) {
        File(dir, "version.txt").writeText("6")
        File(dir, "model-set.txt").writeText(
            ModelManager.modelSetKey(
                ModelPrecision.INT8, SttModel.PARAKEET_EOU, SttBackend.ONNX,
                TtsModel.KOKORO_SHORT_TURN,
            )
        )
    }

    @Test
    fun `cached valid files drop out of the plan`() {
        // Markers only: nothing cached yet, but the cache is current so
        // ensureModels will keep whatever lands in it.
        modelDir.mkdirs()
        writeCurrentMarkers(modelDir)
        val before = ModelManager.plannedModelBytes(context)

        // Two files whose published sizes are in the estimate table. Neither
        // carries a size floor, so any non-empty content is valid.
        writeValid(modelDir, "us_gold.json", 3_000_469)
        writeValid(modelDir, "us_silver.json", 3_099_517)

        val after = ModelManager.plannedModelBytes(context)
        assertEquals(
            "plan should shrink by exactly the two cached files",
            3_000_469L + 3_099_517L, before - after,
        )
    }

    @Test
    fun `a fully cached model set plans nothing`() {
        modelDir.mkdirs()
        // Sparse extents keep the 325 MB and 132 MB blobs off the disk.
        writeValid(modelDir, "silero-vad.onnx", 2_243_022)
        writeValid(modelDir, "parakeet-eou-encoder.onnx", 131_741_896)
        writeValid(modelDir, "parakeet-eou-decoder.onnx", 15_757_826)
        writeValid(modelDir, "parakeet-eou-joint.onnx", 5_589_132)
        writeValid(modelDir, "vocab.json", 17_437)
        writeValid(modelDir, "config.json", 524)
        writeValid(modelDir, "kokoro-e2e.onnx", 3_047_254)
        writeValid(modelDir, "kokoro-e2e-realtime.onnx", 2_413_312)
        writeValid(modelDir, "kokoro-e2e.onnx.data", 324_564_624)
        writeValid(modelDir, "vocab_index.json", 2_501)
        writeValid(modelDir, "us_gold.json", 3_000_469)
        writeValid(modelDir, "us_silver.json", 3_099_517)
        listOf("fr", "es", "it", "pt", "hi").forEach { writeValid(modelDir, "dict_$it.json", 5_000) }
        writeValid(modelDir, "voices/af_heart.bin", 1_024)
        writeValid(modelDir, "deepfilter-auxiliary.bin", 126_976)
        writeCurrentMarkers(modelDir)

        assertEquals(0L, ModelManager.plannedModelBytes(context))
        assertTrue(ModelManager.areModelsReady(context))
    }

    @Test
    fun `a stale model version re-plans the whole manifest`() {
        modelDir.mkdirs()
        writeValid(modelDir, "us_gold.json", 3_000_469)
        writeCurrentMarkers(modelDir)
        File(modelDir, "version.txt").writeText("1")   // below MODEL_VERSION

        // ensureModels wipes this cache before downloading, so the cached file
        // must not be discounted from the plan.
        val planned = ModelManager.plannedModelBytes(context)
        assertTrue("stale cache should plan the full set, got $planned",
            planned in 480_000_000..505_000_000)
    }

    @Test
    fun `an in-progress LLM download is not treated as a stale cache`() {
        // No markers on disk = a download that never finished. ensureLlmModels
        // deliberately keeps its .tmp in that state, so the plan must stay the
        // full bundle rather than collapsing to zero.
        File(ModelManager.llmModelDir(context)).mkdirs()
        assertEquals(297_212_528L, ModelManager.plannedLlmBytes(context))
    }
}
