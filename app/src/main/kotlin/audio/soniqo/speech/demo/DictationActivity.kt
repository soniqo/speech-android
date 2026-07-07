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
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import audio.soniqo.speech.ModelDownloadWorker
import audio.soniqo.speech.ModelPrecision
import audio.soniqo.speech.SpeechConfig
import audio.soniqo.speech.SpeechEvent
import audio.soniqo.speech.SpeechPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dictation mode — transcribe speech to text in real-time.
 * Partial results appear as you speak. Text accumulates across utterances.
 * Copy or share the full transcript.
 */
class DictationActivity : ComponentActivity() {

    @Volatile private var pipeline: SpeechPipeline? = null
    private var audioRecord: AudioRecord? = null
    private var micJob: Job? = null
    @Volatile private var recording = false
    @Volatile private var pipelineStarted = false
    private var observingDownload = false

    private lateinit var statusView: TextView
    private lateinit var micButton: TextView
    private lateinit var transcriptView: TextView
    private lateinit var transcriptScroll: ScrollView
    private lateinit var copyBtn: TextView
    private lateinit var shareBtn: TextView
    private lateinit var clearBtn: TextView

    private val transcript = StringBuilder()
    private var partialText = ""

    internal var pipelineFactory: (SpeechConfig) -> SpeechPipeline = { config ->
        SpeechPipeline(config)
    }

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

        // Edge-to-edge insets — same as MainActivity.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, sb.top, v.paddingRight, sb.bottom)
            insets
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

    private companion object {
        private const val TAG = "DictationActivity"
    }

    private fun loadPipeline() {
        statusView.text = "initializing..."

        // Models download in a foreground worker so the transfer survives
        // backgrounding the app. Enqueue (KEEP reuses any in-flight download)
        // and observe by the UNIQUE name, not a fresh request id — the latter
        // stays null forever when an existing run is reused (issue #30).
        ModelDownloadWorker.enqueue(applicationContext, ModelPrecision.INT8)
        if (observingDownload) return
        observingDownload = true
        WorkManager.getInstance(applicationContext)
            .getWorkInfosForUniqueWorkLiveData(ModelDownloadWorker.UNIQUE_NAME)
            .observe(this) { infos ->
                val info = infos.firstOrNull { !it.state.isFinished }
                    ?: infos.lastOrNull() ?: return@observe
                when (info.state) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED,
                    WorkInfo.State.RUNNING -> {
                        val total = info.progress.getInt(ModelDownloadWorker.KEY_TOTAL, 0)
                        if (total > 0) {
                            val file = info.progress.getString(ModelDownloadWorker.KEY_FILE) ?: ""
                            val done = info.progress.getInt(ModelDownloadWorker.KEY_COMPLETED, 0)
                            val bytes = info.progress.getLong(ModelDownloadWorker.KEY_BYTES_DOWNLOADED, 0L)
                            val fileTotal = info.progress.getLong(ModelDownloadWorker.KEY_FILE_TOTAL_BYTES, 0L)
                            val mb = if (fileTotal > 0)
                                "${bytes / 1_000_000}/${fileTotal / 1_000_000} MB"
                            else "${bytes / 1_000_000} MB"
                            statusView.text = "$file  $mb  ·  $done/$total"
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val modelDir = info.outputData.getString(ModelDownloadWorker.KEY_MODEL_DIR)
                        if (modelDir == null) {
                            statusView.text = "worker succeeded but no model dir"
                            return@observe
                        }
                        if (!pipelineStarted) {
                            pipelineStarted = true
                            initPipeline(modelDir)
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        val err = info.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                            ?: "unknown"
                        statusView.text = "download failed: $err"
                    }
                    WorkInfo.State.CANCELLED -> { statusView.text = "cancelled" }
                }
            }
    }

    private fun initPipeline(modelDir: String) {
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                withContext(Dispatchers.Main) {
                    statusView.text = "loading models..."
                }

                val config = SpeechConfig(
                    modelDir = modelDir,
                    useNnapi = false,
                    precision = ModelPrecision.INT8,
                    emitPartialTranscriptions = true,
                    partialTranscriptionInterval = 0.5f,
                )

                val p = pipelineFactory(config)
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

            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Dictation pipeline init failed", e)
                pipelineStarted = false
                withContext(Dispatchers.Main) {
                    statusView.text = "error: ${e.message ?: e.javaClass.simpleName}"
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
        if (bufSize <= 0) {
            statusView.text = "mic error (PCM_FLOAT unsupported)"
            return
        }

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sr, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT, bufSize * 4
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            statusView.text = "mic init failed"
            return
        }
        audioRecord = record

        record.startRecording()
        recording = true
        micButton.setTextColor(Color.parseColor("#4CAF50"))
        statusView.text = "listening..."

        micJob?.cancel()
        micJob = lifecycleScope.launch(Dispatchers.IO) {
            val buf = FloatArray(512)
            while (recording && isActive) {
                val read = try {
                    record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                } catch (_: IllegalStateException) {
                    break  // AudioRecord released mid-read
                }
                if (read > 0) {
                    val samples = if (read == buf.size) buf else buf.copyOf(read)
                    val p = pipeline ?: continue
                    try {
                        p.pushAudio(samples)
                    } catch (e: Exception) {
                        Log.w(TAG, "Ignoring mic frame after pipeline shutdown", e)
                        break
                    }
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord.read returned $read")
                    break
                }
            }
        }
    }

    private fun stopMicrophone() {
        recording = false
        micJob?.cancel()
        micJob = null
        audioRecord?.let { record ->
            try { record.stop() } catch (_: Exception) {}
            record.release()
        }
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

    override fun onStop() {
        super.onStop()
        // Release the mic when backgrounded so we don't hold it or drain battery.
        if (recording) {
            stopMicrophone()
            micButton.setTextColor(Color.parseColor("#555555"))
            statusView.text = "paused"
        }
    }

    override fun onDestroy() {
        stopMicrophone()
        pipeline?.let { p ->
            try { p.stop() } catch (_: Exception) {}
            try { p.close() } catch (_: Exception) {}
        }
        pipeline = null
        super.onDestroy()
    }
}
