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
        Sized("voices/af_heart.bin", 1 * MB),
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
        assertEquals(5, ModelDownloadWorker.progressPercent(1, 19, 0, 132 * MB))
        assertEquals(7, ModelDownloadWorker.progressPercent(1, 19, 66 * MB, 132 * MB))
        assertEquals(42, ModelDownloadWorker.progressPercent(8, 19, 0, 325 * MB))
        assertEquals(100, ModelDownloadWorker.progressPercent(19, 19, 0, 0))
    }

    @Test
    fun `unknown file size falls back to file count`() {
        // When the server doesn't advertise a length, the in-flight file adds
        // no fraction — whole-file granularity for that file only.
        assertEquals(5, ModelDownloadWorker.progressPercent(1, 19, 12345, 0))
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
            distinct.size >= 6,
        )
        // One file's worth of the 19-file bar: ~42% -> ~47%.
        assertEquals(42, largeFile.first().percent)
        assertEquals(47, largeFile.last().percent)
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
            "halfway through the large file the bar should read >= 44, was ${midpoint.percent}",
            midpoint.percent >= 44,
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

    // -----------------------------------------------------------------------
    // Byte-weighted bar. The file-count form above still advances within a
    // file, but weights a 2 KB config.json the same as the 325 MB weights
    // blob — so it spends ~60% of the bar on ~2% of the bytes. These cover the
    // byte-weighted overload that replaces it.
    // -----------------------------------------------------------------------

    /** Real published sizes, pipeline set then the LLM bundle. */
    private val pipelineBytes = manifest.sumOf { it.bytes }
    private val llmBytes = 297 * MB

    private fun byteSamples(): List<Int> {
        val out = ArrayList<Int>()
        var done = 0L
        val total = pipelineBytes + llmBytes
        for (m in manifest + Sized("model.litertlm", llmBytes)) {
            var fileBytes = 0L
            while (fileBytes < m.bytes) {
                fileBytes = minOf(m.bytes, fileBytes + CHUNK)
                out += ModelDownloadWorker.progressPercent(done + fileBytes, total)
            }
            done += m.bytes
        }
        return out
    }

    @Test
    fun `byte weighted percent tracks the fraction of bytes transferred`() {
        assertEquals(0, ModelDownloadWorker.progressPercent(0, 800 * MB))
        assertEquals(25, ModelDownloadWorker.progressPercent(200 * MB, 800 * MB))
        assertEquals(50, ModelDownloadWorker.progressPercent(400 * MB, 800 * MB))
        assertEquals(100, ModelDownloadWorker.progressPercent(800 * MB, 800 * MB))
    }

    @Test
    fun `byte weighted percent clamps and tolerates an unknown total`() {
        assertEquals(0, ModelDownloadWorker.progressPercent(1, 0))
        // A refined Content-Length can briefly exceed the seeded estimate.
        assertEquals(100, ModelDownloadWorker.progressPercent(900 * MB, 789 * MB))
    }

    @Test
    fun `dominant file gets bar time proportional to its bytes`() {
        // kokoro-e2e.onnx.data is 325 of 789 MB. Under file-count weighting it
        // was worth 5 of 100 points while taking ~40% of the wall clock.
        val total = pipelineBytes + llmBytes
        val before = manifest.takeWhile { it.name != "kokoro-e2e.onnx.data" }.sumOf { it.bytes }
        val start = ModelDownloadWorker.progressPercent(before, total)
        val end = ModelDownloadWorker.progressPercent(before + 325 * MB, total)
        assertTrue(
            "the 325 MB blob should own ~40 points of the bar, got ${end - start}",
            (end - start) in 38..43,
        )
    }

    @Test
    fun `byte weighted run is monotonic, ends at 100, and never resets`() {
        val samples = byteSamples()
        assertTrue(
            "byte-weighted percent must never decrease",
            samples.zipWithNext().all { (a, b) -> b >= a },
        )
        assertEquals(100, samples.last())
        // The LLM phase used to restart its own 0→100 sweep. Once the pipeline
        // set is done the bar must already be well past zero and stay there.
        val afterPipeline = ModelDownloadWorker.progressPercent(
            pipelineBytes, pipelineBytes + llmBytes,
        )
        assertTrue("bar should be ~62% when the LLM phase starts, was $afterPipeline",
            afterPipeline in 58..66)
    }

    @Test
    fun `detail line reports bytes, rate and eta`() {
        assertEquals(
            "412 / 789 MB · 3.6 MB/s · 2 min left",
            ModelDownloadWorker.detailLine(412_000_000, 789_000_000, 3_600_000, 105),
        )
    }

    @Test
    fun `detail line drops rate and eta until they settle`() {
        assertEquals(
            "12 / 789 MB",
            ModelDownloadWorker.detailLine(12_000_000, 789_000_000, 0, -1),
        )
    }
}
