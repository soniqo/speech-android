package audio.soniqo.speech.demo

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Mode picker — choose between Echo pipeline and Dictation mode.
 */
class LauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F0F0F"))
            gravity = Gravity.CENTER
            setPadding(64, 0, 64, 0)
        }

        val title = TextView(this).apply {
            text = "speech-android"
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 64)
        }
        root.addView(title)

        root.addView(modeButton("Echo", "STT \u2192 TTS echo pipeline") {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        })

        root.addView(modeButton("Dictation", "Real-time speech-to-text") {
            startActivity(Intent(this, DictationActivity::class.java))
            finish()
        })

        setContentView(root)
    }

    private fun modeButton(title: String, subtitle: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(48, 36, 48, 36)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 24 }

            addView(TextView(context).apply {
                text = title
                textSize = 20f
                setTextColor(Color.WHITE)
            })
            addView(TextView(context).apply {
                text = subtitle
                textSize = 14f
                setTextColor(Color.parseColor("#888888"))
                setPadding(0, 8, 0, 0)
            })

            setOnClickListener { onClick() }
        }
    }
}
