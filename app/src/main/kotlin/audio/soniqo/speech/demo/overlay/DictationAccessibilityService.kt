package audio.soniqo.speech.demo.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Types dictated text into whatever text field currently holds input focus —
 * in any app, which is why this has to be an accessibility service. The overlay
 * bubble itself cannot reach another app's views.
 *
 * The overlay window is created non-focusable, so the target field keeps input
 * focus while the user taps Stop / Cancel, and [FOCUS_INPUT] still resolves to
 * it here.
 */
class DictationAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // The overlay drives everything; we never react to events on our own.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /**
     * Insert [text] at the cursor of the focused editable field.
     * The result distinguishes "no field focused" from "insert rejected" so the
     * caller can fall back sensibly instead of silently dropping the words.
     */
    fun insertText(text: String): InsertResult {
        val node = focusedEditable() ?: run {
            Log.w(TAG, "No focused editable field to insert into")
            return InsertResult.NO_FOCUSED_FIELD
        }
        return try {
            if (setText(node, text) || paste(node, text)) {
                InsertResult.INSERTED
            } else {
                Log.w(TAG, "Focused field rejected both SET_TEXT and PASTE")
                InsertResult.NO_FOCUSED_FIELD
            }
        } finally {
            node.recycle()
        }
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focus ->
            if (focus.isEditable) return focus
            focus.recycle()
        }

        rootInActiveWindow?.let { root ->
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focus ->
                if (focus.isEditable) return focus
                focus.recycle()
            }
            // Some apps never report input focus through findFocus (custom
            // views, Compose text fields, web views). Walking the tree for a
            // focused editable catches those.
            findEditable(root)?.let { return it }
        }

        // Split-screen / floating windows: the target may not be the active one.
        for (window in windows) {
            val root = window.root ?: continue
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focus ->
                if (focus.isEditable) return focus
                focus.recycle()
            }
            findEditable(root)?.let { return it }
        }
        return null
    }

    /** Depth-first hunt for a focused editable node, bounded to keep it cheap. */
    private fun findEditable(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > MAX_TREE_DEPTH) return null
        if (node.isEditable && (node.isFocused || node.isAccessibilityFocused)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findEditable(child, depth + 1)
            if (hit != null) return hit
            child.recycle()
        }
        return null
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val existing = TextInsertion.existingText(
            text = node.text?.toString(),
            hint = node.hintText?.toString(),
            showingHint = node.isShowingHintText,
            contentDescription = node.contentDescription?.toString(),
            selStart = node.textSelectionStart,
            selEnd = node.textSelectionEnd,
        )

        // Placeholder handling has to be guessed per app, so record what the
        // node actually reported — the setup screen surfaces it.
        lastNodeReport = buildString {
            appendLine("class: ${node.className}")
            appendLine("pkg: ${node.packageName}")
            appendLine("text: ${quote(node.text)}")
            appendLine("hintText: ${quote(node.hintText)}")
            appendLine("isShowingHintText: ${node.isShowingHintText}")
            appendLine("contentDescription: ${quote(node.contentDescription)}")
            appendLine("selection: ${node.textSelectionStart}..${node.textSelectionEnd}")
            append("treated as existing: ${quote(existing)}")
        }
        // Selection offsets describe the hint when one is displayed, so they
        // are meaningless once it is treated as empty.
        val hasContent = existing.isNotEmpty()
        val result = TextInsertion.insert(
            existing = existing,
            selStart = if (hasContent) node.textSelectionStart else 0,
            selEnd = if (hasContent) node.textSelectionEnd else 0,
            insert = text,
        )

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                result.text,
            )
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false

        // Best-effort: leave the caret after the words we just typed.
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, result.selection)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, result.selection)
            },
        )
        return true
    }

    private fun quote(value: CharSequence?): String =
        if (value == null) "null" else "\"$value\""

    /** Fallback for fields that reject ACTION_SET_TEXT (some web views). */
    private fun paste(node: AccessibilityNodeInfo, text: String): Boolean {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("Dictation", text))
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    /** Outcome of an insert attempt — each case needs a different fallback. */
    enum class InsertResult { INSERTED, NO_FOCUSED_FIELD, SERVICE_DISABLED }

    companion object {
        private const val TAG = "DictationA11y"
        private const val MAX_TREE_DEPTH = 40

        @Volatile
        private var instance: DictationAccessibilityService? = null

        /** What the last targeted field reported, for diagnosing placeholders. */
        @Volatile
        var lastNodeReport: String? = null
            private set

        val isRunning: Boolean get() = instance != null

        /** Insert [text] into the focused field. See [InsertResult]. */
        fun insertIntoFocusedField(text: String): InsertResult =
            instance?.insertText(text) ?: InsertResult.SERVICE_DISABLED
    }
}
