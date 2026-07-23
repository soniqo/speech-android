package audio.soniqo.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Precision A/B for the Kokoro weights, run once per candidate model directory.
 *
 * Point it at the published fp32 bundle, then at a candidate, and diff the
 * dumps. Everything it emits is deterministic for a fixed model: synthesis is
 * seeded only by the text and the voice embedding here, so any difference
 * between two runs is the weights.
 *
 * The long phrases are the point. Kokoro's vocoder sums over the time axis, so
 * a reduced-precision build can measure clean on a one-second reply and still
 * collapse past two seconds — the failure scales with utterance length, not
 * with the weights alone. A regression check that only synthesizes "playing
 * music" will not see it.
 *
 * ```
 * ./gradlew :sdk:connectedAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=audio.soniqo.speech.KokoroPrecisionRegressionTest \
 *   -Pandroid.testInstrumentationRunnerArguments.kokoroModelDir=/data/.../kok32 \
 *   -Pandroid.testInstrumentationRunnerArguments.kokoroDumpDir=/data/.../dump32
 * ```
 */
@RunWith(AndroidJUnit4::class)
class KokoroPrecisionRegressionTest {

    private val phrases = listOf(
        "playing music",
        "I stopped the music",
        "sorry, I can't do that",
        // Past the ~2 s mark, where time-axis accumulation shows up.
        "I can call a contact, play music, or set the volume",
        "I can call a contact, play music, or set the volume, " +
            "and I can also stop the music or dial a number for you",
    )

    @Test
    fun synthesizesEveryPhraseAndDumpsPcm() {
        val args = InstrumentationRegistry.getArguments()
        val modelDir = args.getString("kokoroModelDir")
        assumeTrue("set -e kokoroModelDir to run the Kokoro precision test", !modelDir.isNullOrBlank())
        assumeTrue(File(modelDir!!, "kokoro-e2e-realtime.onnx").isFile)
        assumeTrue(File(modelDir, "kokoro-e2e.onnx.data").isFile)

        // Resolved under the app's own files directory: the test process can
        // read a model dir staged in /data/local/tmp but cannot create anything
        // there, so an absolute path would fail with ENOENT on the first write.
        val dumpDir = args.getString("kokoroDumpDir")?.let { name ->
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                File(name).name,
            ).apply { mkdirs() }
        }

        SpeechSynthesizer(
            SpeechSynthesizerConfig(
                modelDir = modelDir,
                useNnapi = false,
                ttsModel = TtsModel.KOKORO_SHORT_TURN,
            )
        ).use { synthesizer ->
            assertEquals(24_000, synthesizer.sampleRate)

            phrases.forEachIndexed { index, text ->
                val result = synthesizer.synthesize(text, "en")
                assertEquals(24_000, result.sampleRate)
                assertTrue("[$index] PCM must not be empty", result.pcm16.isNotEmpty())
                assertTrue("[$index] PCM must contain audio", result.pcm16.any { it != 0.toByte() })

                // A collapsed build still emits bytes, so check the signal has
                // real amplitude rather than just being non-zero.
                val samples = ShortArray(result.pcm16.size / 2) { i ->
                    ((result.pcm16[i * 2].toInt() and 0xFF) or
                        (result.pcm16[i * 2 + 1].toInt() shl 8)).toShort()
                }
                var sumSquares = 0.0
                var peak = 0
                for (s in samples) {
                    val v = s.toInt()
                    sumSquares += v.toDouble() * v
                    if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
                }
                val rms = kotlin.math.sqrt(sumSquares / samples.size.coerceAtLeast(1))
                val seconds = samples.size / 24_000.0

                // Half-second RMS profile, not just a single figure. A build
                // that degrades with utterance length looks fine on the
                // aggregate — the earlier fp16 attempt held level for two
                // seconds and then fell to a quarter of it — so the shape over
                // time is what actually has to match between two runs.
                val window = 12_000
                val profile = (0 until (samples.size + window - 1) / window).map { w ->
                    val from = w * window
                    val to = minOf(from + window, samples.size)
                    var acc = 0.0
                    for (i in from until to) acc += samples[i].toDouble() * samples[i]
                    kotlin.math.sqrt(acc / (to - from).coerceAtLeast(1))
                }

                // Machine-readable so two runs can be diffed straight from logcat.
                android.util.Log.i(
                    "KOKORO_AB",
                    "idx=$index samples=${samples.size} sec=%.3f rms=%.1f peak=%d profile=%s text=%s"
                        .format(
                            seconds, rms, peak,
                            profile.joinToString(",") { "%.0f".format(it) },
                            text,
                        )
                )

                assertTrue("[$index] audio suspiciously quiet (rms=$rms)", rms > 50.0)
                assertTrue("[$index] audio suspiciously short (${seconds}s)", seconds > 0.2)

                dumpDir?.let { File(it, "phrase_$index.pcm").writeBytes(result.pcm16) }
            }
        }
    }
}
