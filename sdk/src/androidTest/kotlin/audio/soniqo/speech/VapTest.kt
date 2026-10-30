package audio.soniqo.speech

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class VapTest {
    private lateinit var modelDir: String

    @Before
    fun setup() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        modelDir = InstrumentationRegistry.getArguments().getString("vapModelDir")
            ?: ModelManager.ensureVapModels(instrumentation.targetContext)
    }

    @Test
    fun originalCheckpointReferencesPreserveBothChannels() {
        val started = SystemClock.elapsedRealtime()
        VoiceActivityProjection(VoiceActivityProjectionConfig(modelDir)).use { model ->
            val loadMs = SystemClock.elapsedRealtime() - started
            val zero = FloatArray(320000)
            val inferenceStarted = SystemClock.elapsedRealtime()
            val silence = model.forecast(zero, zero)
            val forecastMs = SystemClock.elapsedRealtime() - inferenceStarted
            assertEquals(0.44378302f, silence.userNearTerm, 0.0005f)
            assertEquals(0.55563056f, silence.agentNearTerm, 0.0005f)
            assertEquals(0.34941691f, silence.userLater, 0.0005f)
            assertEquals(0.65039903f, silence.agentLater, 0.0005f)
            val signal = FloatArray(320000) { index ->
                if ((index / 2400) % 3 == 0) 0f else {
                    val time = index / 16000.0
                    (0.05 * sin(2 * PI * 173 * time) + 0.02 * sin(2 * PI * 587 * time)).toFloat()
                }
            }
            val user = model.forecast(signal, zero)
            val agent = model.forecast(zero, signal)
            assertEquals(0.98009735f, user.userNearTerm, 0.0005f)
            assertEquals(0.01986181f, user.agentNearTerm, 0.0005f)
            assertEquals(0.01861823f, agent.userNearTerm, 0.0005f)
            assertEquals(0.98133320f, agent.agentNearTerm, 0.0005f)
            Log.i("VapValidation", "loadMs=$loadMs forecastMs=$forecastMs contextSeconds=20")
        }
    }

    @Test
    fun invalidInputsAndClosedInstancesAreRejected() {
        val model = VoiceActivityProjection(VoiceActivityProjectionConfig(modelDir))
        try {
            assertThrows(IllegalArgumentException::class.java) {
                model.forecast(FloatArray(1), FloatArray(0))
            }
            assertThrows(IllegalArgumentException::class.java) {
                model.forecast(FloatArray(1), FloatArray(1), sampleRate = 48000)
            }
            assertThrows(IllegalArgumentException::class.java) {
                model.forecast(floatArrayOf(Float.NaN), FloatArray(1))
            }
        } finally {
            model.close()
        }
        model.close()
        assertThrows(IllegalStateException::class.java) { model.forecast(FloatArray(1), FloatArray(1)) }
    }
}
