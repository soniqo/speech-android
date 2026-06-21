package audio.soniqo.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for issue #30 — "download bar appears frozen / hung".
 *
 * Pure-JVM (no Robolectric / device / network): it drives the REAL production
 * percent function [ModelDownloadWorker.progressPercent] over the actual 16-file
 * INT8 size distribution, replaying the byte-stream contract of
 * [ModelManager.ensureModels] (per-file `bytesDownloaded`, `completed`
 * increments only AFTER each file lands).
 *
 * The old behaviour computed `pct = completed * 100 / totalFiles`, which pinned
 * the bar to a single value for the entire ~840 MB `parakeet-encoder-int8.onnx`
 * download (file #2) — indistinguishable from a hang. The fix makes the percent
 * byte-aware, so it advances continuously through the dominant file. These
 * assertions lock that in: the bar must MOVE during the encoder and never go
 * backwards.
 *
 * Run: `./gradlew :sdk:test --tests '*DownloadProgressTest*'`
 */
class DownloadProgressTest {

    private data class Sized(val name: String, val bytes: Long)

    private val MB = 1_000_000L
    private val totalFiles = 16

    /** Real INT8 manifest order/sizes; file #2 dwarfs the rest (~85% of bytes). */
    private val manifest = listOf(
        Sized("silero-vad.onnx", 2 * MB),                   // #1
        Sized("parakeet-encoder-int8.onnx", 840 * MB),      // #2  <-- the giant
        Sized("parakeet-decoder-joint-int8.onnx", 51 * MB), // #3
        Sized("vocab.json", 1 * MB),                        // #4
        Sized("kokoro-e2e.onnx", 1 * MB),                   // #5
        Sized("kokoro-e2e.onnx.data", 89 * MB),             // #6
        Sized("vocab_index.json", 1 * MB),                  // #7
        Sized("us_gold.json", 2 * MB),                      // #8
        Sized("us_silver.json", 2 * MB),                    // #9
        Sized("dict_fr.json", 1 * MB),                      // #10
        Sized("dict_es.json", 1 * MB),                      // #11
        Sized("dict_it.json", 1 * MB),                      // #12
        Sized("dict_pt.json", 1 * MB),                      // #13
        Sized("dict_hi.json", 1 * MB),                      // #14
        Sized("voices/af_heart.bin", 1 * MB),               // #15
        Sized("deepfilter-auxiliary.bin", 2 * MB),          // #16
    )

    private val CHUNK = 65536L

    private data class Sample(
        val file: String,
        val completed: Int,
        val fileBytes: Long,
        val fileTotal: Long,
        val percent: Int,
    )

    /** Replays ensureModels: `completed` lags until a file fully lands; each */
    /** tick's percent comes from the production [ModelDownloadWorker.progressPercent]. */
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
    fun progressPercent_isByteAware_unitValues() {
        // Whole files done plus the fraction of the file in flight.
        assertEquals(6, ModelDownloadWorker.progressPercent(1, 16, 0, 840 * MB))        // start of encoder
        assertEquals(9, ModelDownloadWorker.progressPercent(1, 16, 420 * MB, 840 * MB)) // half the encoder
        assertEquals(12, ModelDownloadWorker.progressPercent(2, 16, 0, 89 * MB))        // after encoder lands
        assertEquals(100, ModelDownloadWorker.progressPercent(16, 16, 0, 0))            // all files done
    }

    @Test
    fun progressPercent_unknownFileSize_fallsBackToFileCount() {
        // When the server doesn't advertise a length, the in-flight file adds
        // no fraction — degrades to the old whole-file value for that file only.
        assertEquals(6, ModelDownloadWorker.progressPercent(1, 16, 12345, 0))
    }

    @Test
    fun progressPercent_guardsZeroTotal() {
        assertEquals(0, ModelDownloadWorker.progressPercent(3, 0, 1, 2))
    }

    @Test
    fun fixed_barMovesContinuouslyThroughTheEncoder() {
        val encoder = simulate().filter { it.file == "parakeet-encoder-int8.onnx" }

        // The whole point of the fix: the bar is NO LONGER frozen on the
        // dominant file — it advances through several distinct values.
        val distinct = encoder.map { it.percent }.toSet()
        assertTrue(
            "encoder percent should advance through several values, got $distinct",
            distinct.size >= 6,
        )
        // It spans roughly 6% -> 12% (one file's worth of the 16-file bar).
        assertEquals("encoder starts at ~6%", 6, encoder.first().percent)
        assertEquals("encoder ends at ~12%", 12, encoder.last().percent)
        // ...and is monotonic non-decreasing within the file.
        assertTrue(
            "encoder percent must never go backwards",
            encoder.zipWithNext().all { (a, b) -> b.percent >= a.percent },
        )
    }

    @Test
    fun fixed_byHalfTheEncoderBarHasMovedPastTheOldFrozenCeiling() {
        // Before the fix the bar was pinned at 6 for the entire encoder. Now,
        // halfway through the encoder bytes it has already climbed past 6.
        val encoder = simulate().filter { it.file == "parakeet-encoder-int8.onnx" }
        val midpoint = encoder.first { it.fileBytes >= it.fileTotal / 2 }
        assertTrue(
            "halfway through the encoder the bar should read >= 9 (was frozen at 6), " +
                "was ${midpoint.percent}",
            midpoint.percent >= 9,
        )
    }

    @Test
    fun fixed_wholeRunPercentIsMonotonic() {
        val samples = simulate()
        assertTrue(
            "reported percent must never decrease across the whole download",
            samples.zipWithNext().all { (a, b) -> b.percent >= a.percent },
        )
        assertEquals("download ends at 100%", 100, samples.last().percent)
    }
}
