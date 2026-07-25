package audio.soniqo.speech.demo.overlay

/**
 * Pure text-splicing logic for the dictation overlay.
 *
 * Kept free of Android types so the tricky parts — cursor clamping, replacing a
 * selection, deciding whether a joining space is needed — are unit-testable
 * without an accessibility node.
 */
object TextInsertion {

    data class Result(val text: String, val selection: Int)

    /** Punctuation that attaches to the preceding word, so no space is added. */
    private const val CLINGING = ",.!?;:)]}'’”%"

    /**
     * Splice [insert] into [existing] at the cursor / selection given by
     * [selStart]..[selEnd]. Out-of-range or unknown (-1) offsets mean "append
     * at the end", which is what accessibility nodes report when a field has
     * never been focused for editing.
     */
    fun insert(existing: String, selStart: Int, selEnd: Int, insert: String): Result {
        val addition = insert.trim()
        if (addition.isEmpty()) {
            val cursor = normalize(existing, selStart, selEnd).second
            return Result(existing, cursor)
        }

        val (start, end) = normalize(existing, selStart, selEnd)
        val prefix = existing.substring(0, start)
        val suffix = existing.substring(end)

        val spaced = if (needsSpace(prefix, addition)) " $addition" else addition
        return Result(prefix + spaced + suffix, start + spaced.length)
    }

    /** Clamped, ordered (start, end) pair; -1 or out-of-range means end-of-text. */
    private fun normalize(existing: String, selStart: Int, selEnd: Int): Pair<Int, Int> {
        val len = existing.length
        if (selStart !in 0..len) return len to len
        val end = if (selEnd in selStart..len) selEnd else selStart
        return selStart to end
    }

    private fun needsSpace(prefix: String, addition: String): Boolean {
        val last = prefix.lastOrNull() ?: return false
        if (last.isWhitespace()) return false
        val first = addition.first()
        if (first.isWhitespace() || first in CLINGING) return false
        return true
    }
}
