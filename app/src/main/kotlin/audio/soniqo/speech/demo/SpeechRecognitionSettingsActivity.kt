package audio.soniqo.speech.demo

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import audio.soniqo.speech.ModelManager
import audio.soniqo.speech.ModelPrecision
import audio.soniqo.speech.service.SpeechRecognitionService

/**
 * Settings entry that the system "Voice input" picker (Settings → System →
 * Languages & input → Voice input) opens via the gear icon next to our
 * recognizer. Currently informational — there's nothing user-tunable yet —
 * but having this Activity registered prevents Android from greying out the
 * gear, which signals to users that the recognizer is configurable / alive.
 */
class SpeechRecognitionSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F0F0F"))
            setPadding(64, 96, 64, 64)
        }

        // Edge-to-edge insets — push content below status bar / above nav.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                v.paddingTop + sb.top,
                v.paddingRight,
                v.paddingBottom + sb.bottom,
            )
            insets
        }

        root.addView(TextView(this).apply {
            text = "Speech Recognition"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        })

        val ready = ModelManager.areModelsReady(this, ModelPrecision.INT8)
        root.addView(TextView(this).apply {
            text = if (ready) "Models: ready (on-device)" else "Models: pending download"
            textSize = 14f
            setTextColor(if (ready) Color.parseColor("#4CAF50") else Color.parseColor("#FFB74D"))
            setPadding(0, 0, 0, 16)
        })

        root.addView(TextView(this).apply {
            text = "Recognition runs entirely on-device via Parakeet-EOU 120M + Silero VAD. " +
                "No audio leaves the device."
            textSize = 13f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, 0, 0, 32)
        })

        root.addView(divider())

        root.addView(TextView(this).apply {
            text = "Supported languages"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 24, 0, 12)
        })

        val langScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        val langList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        SpeechRecognitionService.SUPPORTED_LANGUAGES.forEach { tag ->
            langList.addView(TextView(this).apply {
                text = "  • $tag"
                textSize = 14f
                setTextColor(Color.parseColor("#CCCCCC"))
                typeface = Typeface.MONOSPACE
                setPadding(0, 6, 0, 6)
            })
        }
        langScroll.addView(langList)
        root.addView(langScroll)

        setContentView(root)
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(Color.parseColor("#222222"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1,
        )
    }
}
