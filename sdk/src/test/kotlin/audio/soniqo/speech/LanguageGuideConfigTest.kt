package audio.soniqo.speech

import org.junit.Assert.*
import org.junit.Test

class LanguageGuideConfigTest {
    @Test fun guideCopiesCallerSelectionAndDefaultIsAutomatic() {
        val supplied = mutableListOf("en-US", "ru-RU", "zh-CN")
        val guide = LanguageGuideConfig(supplied)
        supplied.clear()
        assertEquals(listOf("en-US", "ru-RU", "zh-CN"), guide.expectedLanguages)
        assertEquals(0.1f, guide.expectedLanguageWeight)
        assertNull(TranscriberConfig("models").languageGuide)
        TranscriberConfig("models", languageGuide = guide).requireValidConfiguration()
    }

    @Test fun fixedLanguageCannotSilentlyConsumeGuide() {
        assertThrows(IllegalArgumentException::class.java) {
            TranscriberConfig("models", language = "en-US",
                languageGuide = LanguageGuideConfig(listOf("en-US"))).requireValidConfiguration()
        }
        TranscriberConfig("models", language = "en-US",
            languageGuide = LanguageGuideConfig(emptyList())).requireValidConfiguration()
    }

    @Test fun invalidWeightsAndAliasesFailBeforeNativeLoading() {
        for (weight in listOf(-0.1f, 0.1001f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { LanguageGuideConfig(emptyList(), weight) }
        }
        for (locale in listOf("en", "auto", " en-US", "en_US", "EN-US")) {
            assertThrows(IllegalArgumentException::class.java) { LanguageGuideConfig(listOf(locale)) }
        }
    }
}
