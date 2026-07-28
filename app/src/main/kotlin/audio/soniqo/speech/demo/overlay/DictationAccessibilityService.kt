package audio.soniqo.speech.demo.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
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
            // Pasting is the last resort, not the default: it costs the user
            // their clipboard (see [paste]). A field whose contents we can
            // reconstruct is written directly instead.
            //
            // Note the fallbacks are not symmetric, and deliberately so.
            // SET_TEXT cannot simply lead with PASTE as an on-failure backstop:
            // against a placeholder it *succeeds* and writes the placeholder
            // into the field, so there is no failure to catch.
            if (contentsAreKnown(node)) {
                if (setText(node, text)) return InsertResult.INSERTED
                if (paste(node, text)) return InsertResult.INSERTED_VIA_CLIPBOARD
            } else {
                if (paste(node, text)) return InsertResult.INSERTED_VIA_CLIPBOARD
                if (setText(node, text)) return InsertResult.INSERTED
            }
            Log.w(TAG, "Focused field rejected both PASTE and SET_TEXT")
            InsertResult.NO_FOCUSED_FIELD
        } finally {
            node.recycle()
        }
    }

    /** [TextInsertion.contentsAreKnown] over the node's reported state. */
    private fun contentsAreKnown(node: AccessibilityNodeInfo): Boolean =
        TextInsertion.contentsAreKnown(
            text = node.text?.toString(),
            hint = node.hintText?.toString(),
            showingHint = node.isShowingHintText,
            contentDescription = node.contentDescription?.toString(),
            selStart = node.textSelectionStart,
            selEnd = node.textSelectionEnd,
        )

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
     * Insert via the clipboard: the app decides where the text lands, so an
     * empty field stays empty underneath and no placeholder can leak in.
     *
     * **This destroys whatever the user had on the clipboard, unrecoverably.**
     * An earlier version saved the previous clip and put it back, which never
     * worked: from API 29 on, [ClipboardManager.getPrimaryClip] returns null to
     * any app that does not own the focused window, and an accessibility
     * service never does. So the read always came back null and the restore
     * always fell through to clearing — the save/restore only ever *looked*
     * like it protected the clipboard.
     *
     * Nothing here can fix that. Writing the clip is what loses the old one,
     * and it is unavoidable for `ACTION_PASTE`. All that is left to choose is
     * what to leave behind, and an empty clipboard beats one holding the
     * user's dictation. The real mitigation lives in [insertText], which takes
     * this path only when `ACTION_SET_TEXT` would corrupt the field.
     */
    private fun paste(node: AccessibilityNodeInfo, text: String): Boolean {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false

        val clip = ClipData.newPlainText("Dictation", text).apply {
            // Android 13+ previews clipboard content in the chip; dictation
            // can be as private as anything else typed.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
        }
        cm.setPrimaryClip(clip)
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)

        // The dictation must not linger on the clipboard once pasted — it can
        // be as sensitive as anything else typed. Delayed because clearing
        // synchronously races the target app reading the clip.
        Handler(Looper.getMainLooper()).postDelayed({
            try { clearClipboard(cm) } catch (_: Exception) {}
        }, CLIPBOARD_CLEAR_DELAY_MS)
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
    enum class InsertResult {
        INSERTED,

        /**
         * Inserted, but only by going through the clipboard — so the user's
         * previous clip is gone. Surfaced separately from [INSERTED] so the
         * cost is visible at the call site rather than buried in [paste].
         */
        INSERTED_VIA_CLIPBOARD,

        NO_FOCUSED_FIELD,
        SERVICE_DISABLED,
    }

    companion object {
        private const val TAG = "DictationA11y"
        private const val MAX_TREE_DEPTH = 40
        private const val CLIPBOARD_CLEAR_DELAY_MS = 1500L

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
