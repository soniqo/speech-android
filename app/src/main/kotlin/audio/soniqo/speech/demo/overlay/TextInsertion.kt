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
     * The field's real content, treating a displayed hint as empty.
     *
     * An empty field often reports its placeholder through `text` — Telegram's
     * composer returns "Message" — so inserting naively appends the dictation
     * to the placeholder. [showingHint] is the node's own flag; the [hint]
     * comparison catches apps that populate `hintText` but never set it.
     */
    fun existingText(
        text: String?,
        hint: String?,
        showingHint: Boolean,
        contentDescription: String? = null,
        selStart: Int = -1,
        selEnd: Int = -1,
    ): String {
        if (showingHint) return ""
        val actual = text.orEmpty()
        if (actual.isEmpty()) return ""
        if (!hint.isNullOrEmpty() && actual == hint) return ""

        // Custom editors (Telegram's composer among them) can surface the
        // placeholder as text with no hintText set, describing it only through
        // contentDescription. An unset caret alongside that match means the
        // field is empty and we are looking at the placeholder, not content
        // the user typed.
        if (!contentDescription.isNullOrEmpty() &&
            actual == contentDescription &&
            selStart <= 0 && selEnd <= 0
        ) {
            return ""
        }
        return actual
    }

    /**
     * True when the field's real contents can be reconstructed from what the
     * node reports.
     *
     * `ACTION_SET_TEXT` rewrites the whole value, so it is only safe when we
     * know that value; guessing wrong bakes a placeholder into the field
     * permanently. Two things establish it. Either [existingText] recognises
     * the field as empty — every placeholder signal we know of resolves
     * there — or the caret sits past the start, which a displayed placeholder
     * never does, so the text is content the user actually has.
     *
     * When neither holds, the caller has to fall back to pasting, which costs
     * the user their clipboard. Widening this predicate is how that gets rarer.
     */
    fun contentsAreKnown(
        text: String?,
        hint: String?,
        showingHint: Boolean,
        contentDescription: String? = null,
        selStart: Int = -1,
        selEnd: Int = -1,
    ): Boolean {
        val existing = existingText(text, hint, showingHint, contentDescription, selStart, selEnd)
        if (existing.isEmpty()) return true
        return selStart > 0 || selEnd > 0
    }

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
