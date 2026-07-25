package audio.soniqo.speech.demo.overlay

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Setup screen for the floating dictation bubble.
 *
 * The overlay needs three separate grants, each behind its own system screen:
 * microphone, "draw over other apps", and the accessibility service that does
 * the actual typing. This screen shows which are missing and starts the bubble
 * once they are all in place.
 */
class VoiceOverlayActivity : ComponentActivity() {

    private lateinit var micRow: TextView
    private lateinit var overlayRow: TextView
    private lateinit var a11yRow: TextView
    private lateinit var toggleButton: TextView
    private lateinit var testField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F0F0F"))
            setPadding(64, 160, 64, 64)
        }

        root.addView(TextView(this).apply {
            text = "Voice overlay"
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, 16)
        })

        root.addView(TextView(this).apply {
            text = "A floating mic button over other apps. Tap it, speak, then " +
                "Stop to type the text into the focused field — or Cancel to discard it."
            textSize = 14f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, 48)
        })

        micRow = permissionRow("1. Microphone") { requestMic() }
        overlayRow = permissionRow("2. Display over other apps") { openOverlaySettings() }
        a11yRow = permissionRow("3. Accessibility service (types the text)") { openA11ySettings() }
        root.addView(micRow)
        root.addView(overlayRow)
        root.addView(a11yRow)

        toggleButton = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(48, 36, 48, 36)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 48 }
            setOnClickListener { toggleOverlay() }
        }
        root.addView(toggleButton)
        root.addView(testSection())

        // The scratch field raises the keyboard, so the screen has to scroll.
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(Color.parseColor("#0F0F0F"))
                isFillViewport = true
                addView(root)
            }
        )
    }

    /**
     * A scratch text field for trying the overlay without leaving the app.
     *
     * It is an ordinary EditText on purpose: dictating into it exercises the
     * same focused-field path as any third-party app, so if it works here the
     * accessibility service and the non-focusable overlay window are both
     * behaving.
     */
    private fun testSection(): LinearLayout {
        val heading = TextView(this).apply {
            text = "Try it"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 56, 0, 4)
        }

        val hintView = TextView(this).apply {
            text = "Tap this box, then tap the bubble and speak. " +
                "Stop types the text in; Cancel discards it."
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, 12)
        }

        testField = EditText(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(32, 32, 32, 32)
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(heading)
            addView(hintView)
            addView(testField)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // -------------------------------------------------------------------------
    // Permission state
    // -------------------------------------------------------------------------

    private fun hasMic() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasOverlay() = Settings.canDrawOverlays(this)

    private fun hasAccessibility(): Boolean {
        // The service object only exists once the system has bound it, so this
        // reflects the real state rather than what the settings row claims.
        if (DictationAccessibilityService.isRunning) return true
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val self = ComponentName(this, DictationAccessibilityService::class.java)
        // Entries use either "pkg/pkg.Service" or the short "pkg/.Service";
        // unflattenFromString normalizes both.
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == self }
    }

    private fun refresh() {
        mark(micRow, "1. Microphone", hasMic())
        mark(overlayRow, "2. Display over other apps", hasOverlay())
        mark(a11yRow, "3. Accessibility service (types the text)", hasAccessibility())

        val ready = hasMic() && hasOverlay() && hasAccessibility()
        toggleButton.isEnabled = ready
        toggleButton.text = when {
            !ready -> "Grant the permissions above"
            OverlayBubbleService.isRunning -> "Hide overlay"
            else -> "Show overlay"
        }
        toggleButton.setTextColor(
            if (ready) Color.WHITE else Color.parseColor("#555555")
        )
    }

    private fun mark(view: TextView, label: String, granted: Boolean) {
        view.text = if (granted) "✓  $label" else "○  $label  — tap to grant"
        view.setTextColor(
            if (granted) Color.parseColor("#4CAF50") else Color.parseColor("#4FC3F7")
        )
    }

    private fun permissionRow(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 16f
        setPadding(0, 24, 0, 24)
        setOnClickListener { onClick() }
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    private fun requestMic() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQUEST_MIC)
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
        )
    }

    private fun openA11ySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun toggleOverlay() {
        if (OverlayBubbleService.isRunning) {
            OverlayBubbleService.stop(this)
        } else {
            OverlayBubbleService.start(this)
            // Deliberately stay in the foreground: the scratch field below is
            // the quickest way to confirm the overlay works before trusting it
            // in a real app. Home dismisses this screen when they're ready.
            testField.requestFocus()
        }
        toggleButton.postDelayed({ refresh() }, 300)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) refresh()
    }

    private companion object {
        const val REQUEST_MIC = 7
    }
}
