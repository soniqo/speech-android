package audio.soniqo.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for issue #30 ("download bar appears frozen"): percent was
 * once computed per whole file, pinning the bar for the duration of a large
 * model file. [ModelDownloadWorker.progressPercent] is byte-aware, so the bar
 * advances within a file and never decreases. Replays the callback contract of
 * [ModelManager.ensureModels] over the default INT8 manifest sizes.
 */
class DownloadProgressTest {

    private data class Sized(val name: String, val bytes: Long)

    private val MB = 1_000_000L
    /** Default low-memory INT8 manifest order/sizes. */
    private val manifest = listOf(
        Sized("silero-vad.onnx", 2 * MB),
        Sized("parakeet-eou-encoder.onnx", 132 * MB),
        Sized("parakeet-eou-decoder.onnx", 16 * MB),
        Sized("parakeet-eou-joint.onnx", 6 * MB),
        Sized("vocab.json", 1 * MB),
        Sized("config.json", 1 * MB),
        Sized("kokoro-e2e.onnx", 3 * MB),
        Sized("kokoro-e2e-realtime.onnx", 3 * MB),
        Sized("kokoro-e2e.onnx.data", 325 * MB),
        Sized("vocab_index.json", 1 * MB),
        Sized("us_gold.json", 2 * MB),
        Sized("us_silver.json", 4 * MB),
        Sized("dict_fr.json", 1 * MB),
        Sized("dict_es.json", 1 * MB),
        Sized("dict_it.json", 1 * MB),
        Sized("dict_pt.json", 1 * MB),
        Sized("dict_hi.json", 1 * MB),
        Sized("voices/af_heart.bin", 1_024),
        Sized("voices/ff_siwis.bin", 1_024),
        Sized("voices/ef_dora.bin", 1_024),
        Sized("voices/if_sara.bin", 1_024),
        Sized("voices/pf_dora.bin", 1_024),
        Sized("voices/hf_alpha.bin", 1_024),
        Sized("voices/jf_alpha.bin", 1_024),
        Sized("voices/zf_xiaobei.bin", 1_024),
        Sized("deepfilter-auxiliary.bin", 2 * MB),
    )
    private val totalFiles = manifest.size

    private val CHUNK = 65536L

    private data class Sample(
        val file: String,
        val completed: Int,
        val fileBytes: Long,
        val fileTotal: Long,
        val percent: Int,
    )

    /** Replays ensureModels: `completed` lags until a file fully lands. */
    private fun simulate(): List<Sample> {
        val samples = ArrayList<Sample>()
        var completed = 0
        for (m in manifest) {
            var fileBytes = 0L
            while (fileBytes < m.bytes) {
                fileBytes = minOf(m.bytes, fileBytes + CHUNK)
                samples += Sample(
                    file = m.name,
                    completed = completed,
                    fileBytes = fileBytes,
                    fileTotal = m.bytes,
                    percent = ModelDownloadWorker.progressPercent(
                        completed, totalFiles, fileBytes, m.bytes,
                    ),
                )
            }
            completed++
        }
        return samples
    }

    @Test
    fun `percent adds completed files plus fraction of the file in flight`() {
        assertEquals(3, ModelDownloadWorker.progressPercent(1, totalFiles, 0, 132 * MB))
        assertEquals(5, ModelDownloadWorker.progressPercent(1, totalFiles, 66 * MB, 132 * MB))
        assertEquals(30, ModelDownloadWorker.progressPercent(8, totalFiles, 0, 325 * MB))
        assertEquals(100, ModelDownloadWorker.progressPercent(totalFiles, totalFiles, 0, 0))
    }

    @Test
    fun `unknown file size falls back to file count`() {
        // When the server doesn't advertise a length, the in-flight file adds
        // no fraction — whole-file granularity for that file only.
        assertEquals(3, ModelDownloadWorker.progressPercent(1, totalFiles, 12345, 0))
    }

    @Test
    fun `zero total files yields zero percent`() {
        assertEquals(0, ModelDownloadWorker.progressPercent(3, 0, 1, 2))
    }

    @Test
    fun `bar advances within the dominant file`() {
        val largeFile = simulate().filter { it.file == "kokoro-e2e.onnx.data" }

        val distinct = largeFile.map { it.percent }.toSet()
        assertTrue(
            "large file percent should advance through several values, got $distinct",
            distinct.size >= 4,
        )
        // One file's worth of the 26-file bar: ~30% -> ~34%.
        assertEquals(30, largeFile.first().percent)
        assertEquals(34, largeFile.last().percent)
        assertTrue(
            "large file percent must never go backwards",
            largeFile.zipWithNext().all { (a, b) -> b.percent >= a.percent },
        )
    }

    @Test
    fun `bar has moved by the middle of the dominant file`() {
        val largeFile = simulate().filter { it.file == "kokoro-e2e.onnx.data" }
        val midpoint = largeFile.first { it.fileBytes >= it.fileTotal / 2 }
        assertTrue(
            "halfway through the large file the bar should read >= 32, was ${midpoint.percent}",
            midpoint.percent >= 32,
        )
    }

    @Test
    fun `fixture matches the default model manifest`() {
        assertEquals(
            ModelManager.models(ModelPrecision.INT8).map { it.localFilename },
            manifest.map { it.name },
        )
    }

    @Test
    fun `whole run percent is monotonic and ends at 100`() {
        val samples = simulate()
        assertTrue(
            "reported percent must never decrease across the whole download",
            samples.zipWithNext().all { (a, b) -> b.percent >= a.percent },
        )
        assertEquals(100, samples.last().percent)
    }
}
