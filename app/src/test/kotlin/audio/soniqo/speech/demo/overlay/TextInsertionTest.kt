package audio.soniqo.speech.demo.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class TextInsertionTest {

    @Test
    fun insertsIntoEmptyField() {
        val result = TextInsertion.insert("", 0, 0, "hello world")
        assertEquals("hello world", result.text)
        assertEquals(11, result.selection)
    }

    @Test
    fun insertsAtCursorInsideExistingText() {
        val result = TextInsertion.insert("hello world", 5, 5, "there")
        assertEquals("hello there world", result.text)
        assertEquals(11, result.selection)
    }

    @Test
    fun replacesSelection() {
        val result = TextInsertion.insert("hello world", 6, 11, "there")
        assertEquals("hello there", result.text)
        assertEquals(11, result.selection)
    }

    @Test
    fun unknownSelectionAppendsAtEnd() {
        val result = TextInsertion.insert("hello", -1, -1, "world")
        assertEquals("hello world", result.text)
        assertEquals(11, result.selection)
    }

    @Test
    fun outOfRangeSelectionAppendsAtEnd() {
        val result = TextInsertion.insert("hi", 99, 120, "there")
        assertEquals("hi there", result.text)
    }

    @Test
    fun doesNotDoubleSpace() {
        val result = TextInsertion.insert("hello ", 6, 6, "world")
        assertEquals("hello world", result.text)
    }

    @Test
    fun clingingPunctuationGetsNoSpace() {
        val result = TextInsertion.insert("hello", 5, 5, "world.")
        assertEquals("hello world.", result.text)
        assertEquals("done.", TextInsertion.insert("done", 4, 4, ".").text)
    }

    @Test
    fun blankTranscriptLeavesTextUnchanged() {
        val result = TextInsertion.insert("hello", 2, 2, "   ")
        assertEquals("hello", result.text)
        assertEquals(2, result.selection)
    }

    @Test
    fun trimsTranscriptWhitespace() {
        val result = TextInsertion.insert("", 0, 0, "  spoken words  ")
        assertEquals("spoken words", result.text)
    }

    @Test
    fun endSelectionBeforeStartIsTreatedAsCaret() {
        val result = TextInsertion.insert("hello world", 5, 2, "x")
        assertEquals("hello x world", result.text)
    }
}
