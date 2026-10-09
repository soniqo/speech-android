package audio.soniqo.speech

import org.junit.Test
import org.junit.Assert.assertThrows

class KeywordSpotterConfigTest {
    @Test fun validLocalConfigurationNeedsNoNativeRuntime() {
        KeywordSpotterConfig("/local/models", listOf(KeywordPhrase("alpha beta", intArrayOf(1, 2)))).requireValidConfiguration()
    }
    @Test fun malformedConfigurationsAreRejectedBeforeNativeLoad() {
        val phrase = KeywordPhrase("alpha", intArrayOf(1))
        listOf(
            KeywordSpotterConfig("", listOf(phrase)),
            KeywordSpotterConfig("/models", emptyList()),
            KeywordSpotterConfig("/models", listOf(phrase, phrase.copy(phrase = "duplicate"))),
            KeywordSpotterConfig("/models", listOf(phrase.copy(acousticThreshold = Float.NaN))),
            KeywordSpotterConfig("/models", listOf(phrase.copy(contextBoost = -1f))),
            KeywordSpotterConfig("/models", listOf(phrase.copy(tokens = intArrayOf(-1)))),
            KeywordSpotterConfig("/models", listOf(phrase), 33),
        ).forEach { assertThrows(IllegalArgumentException::class.java) { it.requireValidConfiguration() } }
    }
}
