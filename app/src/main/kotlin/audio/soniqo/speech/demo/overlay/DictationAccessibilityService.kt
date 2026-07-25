package audio.soniqo.speech.demo.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
            recordNode(node)
            // Paste first. ACTION_PASTE lets the app insert at its own cursor,
            // so it never has to be told what the field already contains —
            // which is the whole placeholder problem. ACTION_SET_TEXT has to
            // rebuild the full value, and any app whose empty field reports a
            // placeholder as its text (Telegram's composer) gets that
            // placeholder baked into the result.
            if (paste(node, text) || setText(node, text)) {
                InsertResult.INSERTED
            } else {
                Log.w(TAG, "Focused field rejected both PASTE and SET_TEXT")
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

    /** Snapshot of the target field, surfaced by the setup screen. */
    private fun recordNode(node: AccessibilityNodeInfo) {
        lastNodeReport = buildString {
            appendLine("class: ${node.className}")
            appendLine("pkg: ${node.packageName}")
            appendLine("text: ${quote(node.text)}")
            appendLine("hintText: ${quote(node.hintText)}")
            appendLine("isShowingHintText: ${node.isShowingHintText}")
            appendLine("contentDescription: ${quote(node.contentDescription)}")
            append("selection: ${node.textSelectionStart}..${node.textSelectionEnd}")
        }
    }

    private fun quote(value: CharSequence?): String =
        if (value == null) "null" else "\"$value\""

    /**
     * Insert via the clipboard, restoring whatever the user had afterwards.
     *
     * The app decides where the text lands, so an empty field stays empty
     * underneath and no placeholder can leak into the result.
     */
    private fun paste(node: AccessibilityNodeInfo, text: String): Boolean {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val previous = try { cm.primaryClip } catch (_: Exception) { null }

        cm.setPrimaryClip(ClipData.newPlainText("Dictation", text))
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)

        // The dictation must not linger on the clipboard once pasted — it can
        // be as sensitive as anything else typed. Restore what the user had if
        // there was something, otherwise clear it outright. Delayed because
        // doing this synchronously races the target app reading the clip.
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (previous != null) cm.setPrimaryClip(previous) else clearClipboard(cm)
            } catch (_: Exception) {
            }
        }, CLIPBOARD_RESTORE_DELAY_MS)
        return pasted
    }

    private fun clearClipboard(cm: ClipboardManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cm.clearPrimaryClip()
        } else {
            // No clear() before API 28 — an empty clip is the closest thing.
            cm.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    /** Outcome of an insert attempt — each case needs a different fallback. */
    enum class InsertResult { INSERTED, NO_FOCUSED_FIELD, SERVICE_DISABLED }

    companion object {
        private const val TAG = "DictationA11y"
        private const val MAX_TREE_DEPTH = 40
        private const val CLIPBOARD_RESTORE_DELAY_MS = 1500L

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
