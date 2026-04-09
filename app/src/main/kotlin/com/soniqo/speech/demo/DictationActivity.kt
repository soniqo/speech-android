package audio.soniqo.speech.demo

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import audio.soniqo.speech.ModelManager
import audio.soniqo.speech.ModelPrecision
import audio.soniqo.speech.SpeechConfig
import audio.soniqo.speech.SpeechEvent
import audio.soniqo.speech.SpeechPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dictation mode — transcribe speech to text in real-time.
 * Partial results appear as you speak. Text accumulates across utterances.
 * Copy or share the full transcript.
 */
class DictationActivity : ComponentActivity() {

    private var pipeline: SpeechPipeline? = null
    private var audioRecord: AudioRecord? = null
    private var recording = false

    private lateinit var statusView: TextView
    private lateinit var micButton: TextView
    private lateinit var transcriptView: TextView
    private lateinit var transcriptScroll: ScrollView
    private lateinit var copyBtn: TextView
    private lateinit var shareBtn: TextView
    private lateinit var clearBtn: TextView

    private val transcript = StringBuilder()
    private var partialText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
        loadPipeline()
    }

    // ---------------------------------------------------------------------------
    // UI
    // ---------------------------------------------------------------------------

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F0F0F"))
        }

        // Status bar
        statusView = TextView(this).apply {
            text = "dictation"
            textSize = 16f
            setTextColor(Color.parseColor("#888888"))
            typeface = Typeface.MONOSPACE
            setPadding(48, 80, 48, 20)
        }
        root.addView(statusView)

        root.addView(divider())

        // Transcript area
        transcriptScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        transcriptView = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(48, 32, 48, 32)
            setTextIsSelectable(true)
        }
        transcriptScroll.addView(transcriptView)
        root.addView(transcriptScroll)

        root.addView(divider())

        // Action buttons row
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 16)
        }

        copyBtn = actionButton("Copy")
        copyBtn.setOnClickListener { copyTranscript() }
        actions.addView(copyBtn)

        shareBtn = actionButton("Share")
        shareBtn.setOnClickListener { shareTranscript() }
        actions.addView(shareBtn)

        clearBtn = actionButton("Clear")
        clearBtn.setOnClickListener { clearTranscript() }
        actions.addView(clearBtn)

        root.addView(actions)

        root.addView(divider())

        // Mic button
        micButton = TextView(this).apply {
            text = "\u2B24"
            textSize = 40f
            setTextColor(Color.parseColor("#555555"))
            gravity = Gravity.CENTER
            isEnabled = false
            setPadding(0, 40, 0, 60)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { toggleMicrophone() }
        }
        root.addView(micButton)

        setContentView(root)
    }

    private fun actionButton(label: String) = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(Color.parseColor("#4FC3F7"))
        setPadding(40, 12, 40, 12)
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(Color.parseColor("#222222"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun updateDisplay() {
        val display = if (partialText.isNotEmpty()) {
            transcript.toString() + partialText
        } else {
            transcript.toString()
        }
        transcriptView.text = display.ifEmpty { "Tap mic and start speaking..." }
        transcriptScroll.post { transcriptScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ---------------------------------------------------------------------------
    // Pipeline
    // ---------------------------------------------------------------------------

    private fun loadPipeline() {
        statusView.text = "initializing..."

        lifecycleScope.launch {
            try {
                val modelDir = ModelManager.ensureModels(
                    this@DictationActivity,
                    precision = ModelPrecision.INT8,
                ) { progress ->
                    runOnUiThread {
                        statusView.text = "${progress.file} ${progress.completed}/${progress.totalFiles}"
                    }
                }

                val config = SpeechConfig(
                    modelDir = modelDir,
                    useNnapi = false,
                    precision = ModelPrecision.INT8,
                    emitPartialTranscriptions = true,
                    partialTranscriptionInterval = 0.5f,
                )

                val p = SpeechPipeline(config)
                pipeline = p

                launch {
                    p.events.collect { event ->
                        when (event) {
                            is SpeechEvent.SpeechStarted -> {
                                withContext(Dispatchers.Main) {
                                    micButton.setTextColor(Color.parseColor("#4CAF50"))
                                    statusView.text = "listening..."
                                }
                            }

                            is SpeechEvent.PartialTranscription -> {
                                withContext(Dispatchers.Main) {
                                    partialText = event.text
                                    statusView.text = "hearing..."
                                    updateDisplay()
                                }
                            }

                            is SpeechEvent.TranscriptionCompleted -> {
                                withContext(Dispatchers.Main) {
                                    partialText = ""
                                    if (event.text.isNotBlank()) {
                                        if (transcript.isNotEmpty()) transcript.append(" ")
                                        transcript.append(event.text)
                                    }
                                    statusView.text = "ready"
                                    updateDisplay()
                                }
                            }

                            is SpeechEvent.SpeechEnded -> {
                                withContext(Dispatchers.Main) {
                                    micButton.setTextColor(Color.parseColor("#FF9800"))
                                    statusView.text = "transcribing..."
                                }
                            }

                            // Ignore TTS events in dictation mode
                            is SpeechEvent.ResponseDone -> {
                                p.resumeListening()
                            }

                            is SpeechEvent.Error -> {
                                withContext(Dispatchers.Main) {
                                    statusView.text = "error: ${event.message}"
                                }
                            }

                            else -> {}
                        }
                    }
                }

                p.start()

                withContext(Dispatchers.Main) {
                    micButton.isEnabled = true
                    statusView.text = "tap to dictate"
                    updateDisplay()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusView.text = "error: ${e.message}"
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Microphone
    // ---------------------------------------------------------------------------

    private fun toggleMicrophone() {
        if (recording) {
            stopMicrophone()
            micButton.setTextColor(Color.parseColor("#555555"))
            statusView.text = "paused"
        } else {
            startMicrophone()
        }
    }

    private fun startMicrophone() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }

        val sr = 16000
        val bufSize = AudioRecord.getMinBufferSize(
            sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sr, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT, bufSize * 4
        )

        audioRecord?.startRecording()
        recording = true
        micButton.setTextColor(Color.parseColor("#4CAF50"))
        statusView.text = "listening..."

        lifecycleScope.launch(Dispatchers.IO) {
            val buf = FloatArray(512)
            while (recording) {
                val read = audioRecord?.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING) ?: -1
                if (read > 0) pipeline?.pushAudio(buf)
            }
        }
    }

    private fun stopMicrophone() {
        recording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 1 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            startMicrophone()
        }
    }

    // ---------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------

    private fun copyTranscript() {
        val text = transcript.toString()
        if (text.isBlank()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Dictation", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareTranscript() {
        val text = transcript.toString()
        if (text.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share transcript"))
    }

    private fun clearTranscript() {
        transcript.clear()
        partialText = ""
        updateDisplay()
    }

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    override fun onDestroy() {
        stopMicrophone()
        pipeline?.stop()
        pipeline?.close()
        super.onDestroy()
    }
}
