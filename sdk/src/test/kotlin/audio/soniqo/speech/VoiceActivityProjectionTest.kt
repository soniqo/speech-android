package audio.soniqo.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class VoiceActivityProjectionTest {
    @Test
    fun `blank model directories are rejected before native loading`() {
        assertThrows(IllegalArgumentException::class.java) {
            VoiceActivityProjectionConfig(" ").requireValidConfiguration()
        }
        assertFalse(VoiceActivityProjectionConfig("/models").hardwareAcceleration)
    }

    @Test
    fun `channels require the same requested sample count and 16 kHz`() {
        requireVapAudio(floatArrayOf(0f), floatArrayOf(0f), 1, 16000)
        assertThrows(IllegalArgumentException::class.java) {
            requireVapAudio(floatArrayOf(0f), floatArrayOf(), 1, 16000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireVapAudio(floatArrayOf(), floatArrayOf(), 0, 16000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireVapAudio(floatArrayOf(0f), floatArrayOf(0f), 1, 48000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireVapAudio(floatArrayOf(Float.NaN), floatArrayOf(0f), 1, 16000)
        }
    }

    @Test
    fun `native probability order preserves both speakers and horizons`() {
        val result = vapForecast(floatArrayOf(0.1f, 0.9f, 0.2f, 0.8f, 0.3f, 0.7f))
        assertEquals(0.1f, result.userNearTerm, 0f)
        assertEquals(0.9f, result.agentNearTerm, 0f)
        assertEquals(0.2f, result.userLater, 0f)
        assertEquals(0.7f, result.agentSpeaking, 0f)
        assertThrows(IllegalStateException::class.java) { vapForecast(FloatArray(5)) }
        assertThrows(IllegalStateException::class.java) { vapForecast(FloatArray(6) { Float.NaN }) }
    }
}
