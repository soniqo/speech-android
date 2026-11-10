package audio.soniqo.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native LiteRT integration; uses only the public English fixture and model bundle. */
@RunWith(AndroidJUnit4::class)
class LanguageGuideDeviceTest {
    @Test fun guideValidationAndClearPreserveAutomaticTextAndWordTimes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = ModelManager.ensureTranscriberModels(context)
        val audio = MeetingFixtures.read("transcriber_en.wav")
        StreamingTranscriber(TranscriberConfig(dir)).use { model ->
            fun decode(): StreamingTranscript {
                model.beginStream()
                for (offset in audio.indices step 1600) {
                    model.pushAudio(audio.copyOfRange(offset, minOf(offset+1600,audio.size)))
                }
                return model.endStream()
            }
            val baseline = decode()
            assertTrue(baseline.text.isNotBlank())
            model.setLanguageGuide(LanguageGuideConfig(emptyList()))
            assertEquals(baseline, decode())
            model.setLanguageGuide(LanguageGuideConfig(listOf("en-US"), 0f))
            assertEquals(baseline, decode())
            model.setLanguageGuide(LanguageGuideConfig(listOf("en-US", "ru-RU", "zh-CN")))
            val guided = decode()
            assertTrue(guided.text.isNotBlank())
            assertEquals(guided, decode()) // New stream, same immutable guide, fresh caches.
            assertThrows(IllegalArgumentException::class.java) {
                model.setLanguageGuide(LanguageGuideConfig(listOf("en-US", "xx-XX")))
            }
            assertEquals(guided, decode()) // Failed setup did not partially replace the mask.
            model.beginStream()
            assertThrows(IllegalArgumentException::class.java) {
                model.setLanguageGuide(LanguageGuideConfig(listOf("zh-CN")))
            }
            model.cancelStream()
            model.setLanguage("auto") // Selecting a prompt clears the guide.
            assertEquals(baseline, decode())
        }
    }
}
