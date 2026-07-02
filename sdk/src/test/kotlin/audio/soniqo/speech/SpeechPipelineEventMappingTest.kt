package audio.soniqo.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPipelineEventMappingTest {

    @Test
    fun `response done preserves native tts duration`() {
        val event = nativeEventToSpeechEvent(
            type = 8,
            text = null,
            audio = null,
            confidence = 0f,
            sttMs = 0f,
            ttsMs = 2035f,
        )

        assertTrue(event is SpeechEvent.ResponseDone)
        assertEquals(2035f, (event as SpeechEvent.ResponseDone).ttsMs, 0.001f)
    }
}
