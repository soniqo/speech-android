package audio.soniqo.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for issue #30 — "download bar appears frozen / hung".
 *
 * Pure-JVM (no Robolectric / device / network): it drives the REAL production
 * percent function [ModelDownloadWorker.progressPercent] over the default
 * low-memory INT8 size distribution, replaying the byte-stream contract of
 * [ModelManager.ensureModels] (per-file `bytesDownloaded`, `completed`
 * increments only AFTER each file lands).
 *
 * The old behaviour computed `pct = completed * 100 / totalFiles`, which pinned
 * the bar to a single value for an entire large model file — indistinguishable
 * from a hang. The fix makes the percent byte-aware, so it advances
 * continuously through the dominant file. These assertions lock that in: the
 * bar must MOVE during the large file and never go backwards.
 *
 * Run: `./gradlew :sdk:test --tests '*DownloadProgressTest*'`
 */
class DownloadProgressTest {

    private data class Sized(val name: String, val bytes: Long)

    private val MB = 1_000_000L
    private val totalFiles = 18

    /** Default low-memory INT8 manifest order/sizes. */
    private val manifest = listOf(
        Sized("silero-vad.onnx", 2 * MB),                   // #1
        Sized("parakeet-eou-encoder.onnx", 132 * MB),       // #2
        Sized("parakeet-eou-decoder.onnx", 16 * MB),        // #3
        Sized("parakeet-eou-joint.onnx", 6 * MB),           // #4
        Sized("vocab.json", 1 * MB),                        // #5
        Sized("config.json", 1 * MB),                       // #6
        Sized("kokoro-e2e.onnx", 3 * MB),                   // #7
        Sized("kokoro-e2e.onnx.data", 325 * MB),            // #8  <-- the giant
        Sized("vocab_index.json", 1 * MB),                  // #9
        Sized("us_gold.json", 2 * MB),                      // #10
        Sized("us_silver.json", 4 * MB),                    // #11
        Sized("dict_fr.json", 1 * MB),                      // #12
        Sized("dict_es.json", 1 * MB),                      // #13
        Sized("dict_it.json", 1 * MB),                      // #14
        Sized("dict_pt.json", 1 * MB),                      // #15
        Sized("dict_hi.json", 1 * MB),                      // #16
        Sized("voices/af_heart.bin", 1 * MB),               // #17
        Sized("deepfilter-auxiliary.bin", 2 * MB),          // #18
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
        assertEquals(5, ModelDownloadWorker.progressPercent(1, 18, 0, 132 * MB))        // start of EOU encoder
        assertEquals(8, ModelDownloadWorker.progressPercent(1, 18, 66 * MB, 132 * MB))  // half the EOU encoder
        assertEquals(38, ModelDownloadWorker.progressPercent(7, 18, 0, 325 * MB))       // start of Kokoro weights
        assertEquals(100, ModelDownloadWorker.progressPercent(18, 18, 0, 0))            // all files done
    }

    @Test
    fun progressPercent_unknownFileSize_fallsBackToFileCount() {
        // When the server doesn't advertise a length, the in-flight file adds
        // no fraction — degrades to the old whole-file value for that file only.
        assertEquals(5, ModelDownloadWorker.progressPercent(1, 18, 12345, 0))
    }

    @Test
    fun progressPercent_guardsZeroTotal() {
        assertEquals(0, ModelDownloadWorker.progressPercent(3, 0, 1, 2))
    }

    @Test
    fun fixed_barMovesContinuouslyThroughTheEncoder() {
        val largeFile = simulate().filter { it.file == "kokoro-e2e.onnx.data" }

        // The whole point of the fix: the bar is NO LONGER frozen on the
        // dominant file — it advances through several distinct values.
        val distinct = largeFile.map { it.percent }.toSet()
        assertTrue(
            "large file percent should advance through several values, got $distinct",
            distinct.size >= 6,
        )
        // It spans roughly 38% -> 44% (one file's worth of the 18-file bar).
        assertEquals("large file starts at ~38%", 38, largeFile.first().percent)
        assertEquals("large file ends at ~44%", 44, largeFile.last().percent)
        // ...and is monotonic non-decreasing within the file.
        assertTrue(
            "large file percent must never go backwards",
            largeFile.zipWithNext().all { (a, b) -> b.percent >= a.percent },
        )
    }

    @Test
    fun fixed_byHalfTheLargeFileBarHasMovedPastTheOldFrozenCeiling() {
        // Before the fix the bar was pinned at the whole-file count for the
        // entire large file. Now, halfway through the bytes it has climbed.
        val largeFile = simulate().filter { it.file == "kokoro-e2e.onnx.data" }
        val midpoint = largeFile.first { it.fileBytes >= it.fileTotal / 2 }
        assertTrue(
            "halfway through the large file the bar should read >= 41 (was frozen at 38), " +
                "was ${midpoint.percent}",
            midpoint.percent >= 41,
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
