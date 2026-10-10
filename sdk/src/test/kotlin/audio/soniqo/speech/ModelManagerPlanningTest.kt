package audio.soniqo.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

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
        // Published INT8 Parakeet-EOU + Kokoro + DeepFilter set is ~502 MB.
        assertTrue("expected ~502 MB, got $pipeline", pipeline in 480_000_000..505_000_000)
    }

    @Test
    fun `Smart Turn adds its pinned int8 graph to the plan`() {
        val without = ModelManager.plannedModelBytes(context)
        val with = ModelManager.plannedModelBytes(context, enableSmartTurn = true)
        assertEquals(11_123_370L, with - without)
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
        cacheDefaultSet()
        writeValid(modelDir, "deepfilter.onnx", 8_608_859)

        assertEquals(0L, ModelManager.plannedModelBytes(context))
        assertTrue(ModelManager.areModelsReady(context))
    }

    @Test
    fun `existing cache only needs the streaming enhancer weights`() {
        cacheDefaultSet()
        writeValid(modelDir, "deepfilter-auxiliary.bin", 126_976)

        assertEquals(8_608_859L, ModelManager.plannedModelBytes(context))
        assertFalse(ModelManager.areModelsReady(context))
        assertEquals(0L, ModelManager.plannedModelBytes(context, enableEnhancer = false))
        assertTrue(ModelManager.areModelsReady(context, enableEnhancer = false))
    }

    @Test
    fun `upgrading an existing cache downloads only the pinned enhancer`() = runBlocking {
        cacheDefaultSet()
        val cachedEncoder = File(modelDir, "parakeet-eou-encoder.onnx")
        val modified = cachedEncoder.lastModified()
        val endpoint = ModelManager.endpoint
        val server = MockWebServer()
        server.start()
        try {
            // Validity, not inference, is exercised here. Sparse cache files
            // and a protobuf header keep this test independent of real models.
            val body = ByteArray(8_608_859).apply { this[0] = 0x08 }
            server.enqueue(MockResponse().setBody(Buffer().write(body)))
            ModelManager.endpoint = server.url("/").toString().trimEnd('/')

            assertEquals(modelDir.path, ModelManager.ensureModels(context))
            assertTrue(ModelManager.areModelsReady(context))
            assertEquals(1, server.requestCount)
            assertEquals(
                "/soniqo/DeepFilterNet3-ONNX/resolve/" +
                    "63d8ba442ba900143c468b798e94a04009b2f0c9/deepfilter.onnx",
                server.takeRequest(1, TimeUnit.SECONDS)!!.path,
            )
            assertEquals(131_741_896L, cachedEncoder.length())
            assertEquals(modified, cachedEncoder.lastModified())
        } finally {
            ModelManager.endpoint = endpoint
            server.shutdown()
        }
    }

    private fun cacheDefaultSet() {
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
        listOf(
            "af_heart", "ff_siwis", "ef_dora", "if_sara",
            "pf_dora", "hf_alpha", "jf_alpha", "zf_xiaobei",
        ).forEach { writeValid(modelDir, "voices/$it.bin", 1_024) }
        writeCurrentMarkers(modelDir)
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
