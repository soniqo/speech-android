package audio.soniqo.speech.demo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
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
import audio.soniqo.speech.SttModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var pipeline: SpeechPipeline? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var mediaPlayer: android.media.MediaPlayer? = null
    @Volatile private var recording = false
    private val ttsBuffer = mutableListOf<ByteArray>()
    private var speechStartTime = 0L
    private var pipelineStarted = false
    private var observingDownload = false

    private lateinit var statusView: TextView
    private lateinit var micButton: TextView
    private lateinit var vadView: VadGraphView
    private lateinit var chatLayout: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var downloadProgress: ProgressBar

    companion object {
        private const val TTS_SAMPLE_RATE = 24000
        private val DEMO_STT_MODEL = SttModel.PARAKEET
        private val isEmulator = android.os.Build.FINGERPRINT.contains("generic")
                || android.os.Build.MODEL.contains("Emulator")
                || android.os.Build.MODEL.contains("sdk")
                || android.os.Build.HARDWARE.contains("ranchu")
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

        // Android 15 / One UI 8 force edge-to-edge — without this, the mic
        // button at the bottom slides under the gesture-nav bar.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, sb.top, v.paddingRight, sb.bottom)
            insets
        }

        // Status bar
        statusView = TextView(this).apply {
            text = "speech-android"
            textSize = 16f
            setTextColor(Color.parseColor("#888888"))
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(48, 80, 48, 20)
        }
        root.addView(statusView)

        // Model download progress
        downloadProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(48, 0, 48, 24) }
        }
        root.addView(downloadProgress)

        // Divider
        root.addView(divider())

        // VAD graph
        vadView = VadGraphView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 200)
            setPadding(32, 16, 32, 16)
        }
        root.addView(vadView)

        // Divider
        root.addView(divider())

        // Chat scroll area
        chatScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        chatLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        chatScroll.addView(chatLayout)
        root.addView(chatScroll)

        // Divider
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

    private fun divider() = View(this).apply {
        setBackgroundColor(Color.parseColor("#222222"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    // ---------------------------------------------------------------------------
    // Chat bubbles
    // ---------------------------------------------------------------------------

    private fun addBubble(text: String, isUser: Boolean) {
        lifecycleScope.launch(Dispatchers.Main) {
            val tv = TextView(this@MainActivity).apply {
                this.text = text
                textSize = 15f
                setTextColor(if (isUser) Color.WHITE else Color.parseColor("#CCCCCC"))
                setBackgroundColor(
                    if (isUser) Color.parseColor("#1A56C4")
                    else Color.parseColor("#1E1E1E")
                )
                setPadding(28, 18, 28, 18)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = 12
                    if (isUser) {
                        gravity = Gravity.END
                        marginStart = 100
                    } else {
                        gravity = Gravity.START
                        marginEnd = 100
                    }
                }
            }
            chatLayout.addView(tv)
            chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun addSystemLine(text: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            val tv = TextView(this@MainActivity).apply {
                this.text = text
                textSize = 12f
                setTextColor(Color.parseColor("#444444"))
                gravity = Gravity.CENTER_HORIZONTAL
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 16 }
            }
            chatLayout.addView(tv)
            chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    // ---------------------------------------------------------------------------
    // Pipeline
    // ---------------------------------------------------------------------------

    private fun loadPipeline() {
        setStatus("initializing...")
        downloadProgress.visibility = View.VISIBLE
        downloadProgress.progress = 0

        // Models download in a foreground worker so the transfer survives
        // backgrounding the app. Enqueue (KEEP reuses any in-flight download)
        // and observe by the UNIQUE name — observing by a fresh request id
        // would stay null forever whenever an existing run is reused (e.g. on
        // rotation / re-entry), which looked like a hang. See issue #30.
        ModelDownloadWorker.enqueue(
            applicationContext,
            ModelPrecision.INT8,
            sttModel = DEMO_STT_MODEL,
        )
        if (observingDownload) return
        observingDownload = true
        WorkManager.getInstance(applicationContext)
            .getWorkInfosForUniqueWorkLiveData(
                ModelDownloadWorker.uniqueName(sttModel = DEMO_STT_MODEL)
            )
            .observe(this) { infos ->
                val info = infos.firstOrNull { !it.state.isFinished }
                    ?: infos.lastOrNull() ?: return@observe
                when (info.state) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED,
                    WorkInfo.State.RUNNING -> {
                        val total = info.progress.getInt(ModelDownloadWorker.KEY_TOTAL, 0)
                        if (total > 0) {
                            setStatus(downloadStatus(info))
                            downloadProgress.progress =
                                info.progress.getInt(ModelDownloadWorker.KEY_PERCENT, 0)
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val modelDir = info.outputData.getString(ModelDownloadWorker.KEY_MODEL_DIR)
                        if (modelDir == null) {
                            addSystemLine("worker succeeded but no model dir")
                            setStatus("error")
                            return@observe
                        }
                        downloadProgress.progress = 100
                        downloadProgress.visibility = View.GONE
                        if (!pipelineStarted) {
                            pipelineStarted = true
                            initPipeline(modelDir)
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        val err = info.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                            ?: "unknown"
                        addSystemLine("download failed: $err")
                        setStatus("error — tap to retry")
                        statusView.setOnClickListener { retryInit() }
                    }
                    WorkInfo.State.CANCELLED -> setStatus("cancelled")
                }
            }
    }

    /** "<file>  <done>/<total> MB  ·  <n>/<files>" from a worker progress payload. */
    private fun downloadStatus(info: WorkInfo): String {
        val file = info.progress.getString(ModelDownloadWorker.KEY_FILE) ?: ""
        val done = info.progress.getInt(ModelDownloadWorker.KEY_COMPLETED, 0)
        val total = info.progress.getInt(ModelDownloadWorker.KEY_TOTAL, 0)
        val bytes = info.progress.getLong(ModelDownloadWorker.KEY_BYTES_DOWNLOADED, 0L)
        val fileTotal = info.progress.getLong(ModelDownloadWorker.KEY_FILE_TOTAL_BYTES, 0L)
        val mb = if (fileTotal > 0)
            "${bytes / 1_000_000}/${fileTotal / 1_000_000} MB"
        else
            "${bytes / 1_000_000} MB"
        return "$file  $mb  ·  $done/$total"
    }

    private fun initPipeline(modelDir: String) {
        lifecycleScope.launch {
            try {
                val config = SpeechConfig(
                    modelDir = modelDir,
                    useNnapi = !isEmulator,
                    sttModel = DEMO_STT_MODEL,
                    precision = ModelPrecision.INT8,
                    emitPartialTranscriptions = true,
                )

                val p = SpeechPipeline(config)
                pipeline = p

                launch {
                    p.events.collect { event ->
                        when (event) {
                            is SpeechEvent.SpeechStarted -> {
                                vadView.setSpeechActive(true)
                                speechStartTime = System.currentTimeMillis()
                                setStatus("listening...")
                                setMicColor("#4CAF50")
                            }

                            is SpeechEvent.SpeechEnded -> {
                                vadView.setSpeechActive(false)
                                val speechDur = System.currentTimeMillis() - speechStartTime
                                setStatus("transcribing... (${"%.1f".format(speechDur / 1000f)}s)")
                                setMicColor("#FF9800")
                            }

                            is SpeechEvent.PartialTranscription -> {
                                setStatus("hearing: ${event.text}")
                            }

                            is SpeechEvent.TranscriptionCompleted -> {
                                addBubble(event.text, isUser = true)
                                addSystemLine("stt: ${"%.0f".format(event.sttMs)}ms")
                                setStatus("synthesizing...")
                            }

                            is SpeechEvent.ResponseCreated -> {
                                ttsBuffer.clear()
                                try { java.io.File(filesDir, "tts_output.raw").delete() } catch (_: Exception) {}
                            }

                            is SpeechEvent.ResponseAudioDelta -> {
                                ttsBuffer.add(event.audio)
                                // Save TTS audio for debugging
                                try {
                                    java.io.File(filesDir, "tts_output.raw").appendBytes(event.audio)
                                } catch (_: Exception) {}
                            }

                            is SpeechEvent.ResponseDone -> {
                                android.util.Log.i("Speech", "ResponseDone -> TTS ready")
                                val totalBytes = ttsBuffer.sumOf { it.size }
                                val durationSec = totalBytes / 2f / TTS_SAMPLE_RATE
                                addSystemLine("tts: ${"%.0f".format(event.ttsMs)}ms → ${"%.1f".format(durationSec)}s audio")

                                if (isEmulator) {
                                    // Emulator: skip playback (QEMU audio kills mic)
                                    // Save to file for host-side playback via adb
                                    saveTtsAudio()
                                    addBubble("[audio ${"%,.1f".format(durationSec)}s]", isUser = false)
                                    p.stop()
                                    p.start()
                                    setStatus("listening")
                                    setMicColor("#4CAF50")
                                } else {
                                    // Real device: play audio
                                    micPaused = true
                                    setStatus("speaking...")
                                    setMicColor("#FF9800")
                                    playTtsAudio()
                                    val delayMs = (durationSec * 1000).toLong() + 200
                                    lifecycleScope.launch {
                                        kotlinx.coroutines.delay(delayMs)
                                        // If the app was backgrounded during playback, onStop
                                        // already released the mic — don't restart the pipeline
                                        // in the background or paint a false "listening" state.
                                        if (!lifecycle.currentState.isAtLeast(
                                                androidx.lifecycle.Lifecycle.State.STARTED)) {
                                            return@launch
                                        }
                                        stopAudioTrack()
                                        micPaused = false
                                        p.stop()
                                        p.start()
                                        setStatus("listening")
                                        setMicColor("#4CAF50")
                                        android.util.Log.i("Speech", "TTS done -> restarted pipeline")
                                    }
                                }
                            }

                            is SpeechEvent.ResponseInterrupted -> {
                                ttsBuffer.clear()
                                stopAudioTrack()
                                setMicColor("#555555")
                            }

                            is SpeechEvent.Error -> {
                                addSystemLine("error: ${event.message}")
                                setStatus("error")
                                setMicColor("#FF5252")
                            }

                            else -> {}
                        }
                    }
                }

                p.start()

                withContext(Dispatchers.Main) {
                    micButton.isEnabled = true
                    setStatus("tap to talk")
                    addSystemLine("ready")

                    p.nnapiFallbackReason?.let { reason ->
                        addSystemLine("⚠ NNAPI failed, using CPU: $reason")
                        addSystemLine(deviceInfoSummary())
                        val info = "NNAPI fallback: $reason\n${deviceInfoSummary()}"
                        addReportButton(info)
                    }
                }

            } catch (e: Throwable) {
                val log = buildDiagnosticLog(e)
                writeCrashLog(log)
                withContext(Dispatchers.Main) {
                    addSystemLine("init error: ${e.message}")
                    addSystemLine(deviceInfoSummary())
                    addReportButton(log)
                    setStatus("error — tap to retry")
                    statusView.setOnClickListener { retryInit() }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Diagnostics
    // ---------------------------------------------------------------------------

    private fun retryInit() {
        statusView.setOnClickListener(null)
        statusView.setOnLongClickListener(null)
        pipelineStarted = false
        loadPipeline()
    }

    private fun deviceInfoSummary(): String {
        val mfr = android.os.Build.MANUFACTURER
        val model = android.os.Build.MODEL
        val ver = android.os.Build.VERSION.RELEASE
        val api = android.os.Build.VERSION.SDK_INT
        val hw = android.os.Build.HARDWARE
        val rt = Runtime.getRuntime()
        val freeMb = rt.freeMemory() / 1_048_576
        val maxMb = rt.maxMemory() / 1_048_576
        return "$mfr $model · Android $ver (API $api) · $hw · RAM ${freeMb}/${maxMb}MB"
    }

    private fun buildDiagnosticLog(e: Throwable): String {
        val rt = Runtime.getRuntime()
        return buildString {
            appendLine("=== Speech SDK Error Log ===")
            appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date())}")
            appendLine()
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("Hardware: ${android.os.Build.HARDWARE}")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                appendLine("SoC: ${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}")
            }
            appendLine("ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("RAM: ${rt.freeMemory() / 1_048_576}MB free / ${rt.maxMemory() / 1_048_576}MB max")
            appendLine()
            appendLine("Error: ${e.message}")
            appendLine()
            appendLine("Stack trace:")
            appendLine(e.stackTraceToString())
        }
    }

    private fun writeCrashLog(log: String) {
        try {
            java.io.File(filesDir, "crash.log").writeText(log)
        } catch (_: Exception) {}
    }

    private fun addReportButton(log: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val issueBtn = TextView(this).apply {
            text = "Open Issue"
            setTextColor(Color.parseColor("#4FC3F7"))
            textSize = 13f
            setPadding(24, 12, 24, 12)
            setOnClickListener { openGitHubIssue(log) }
        }

        val emailBtn = TextView(this).apply {
            text = "Send Email"
            setTextColor(Color.parseColor("#4FC3F7"))
            textSize = 13f
            setPadding(24, 12, 24, 12)
            setOnClickListener { sendEmail(log) }
        }

        val copyBtn = TextView(this).apply {
            text = "Copy Log"
            setTextColor(Color.parseColor("#4FC3F7"))
            textSize = 13f
            setPadding(24, 12, 24, 12)
            setOnClickListener { copyToClipboard(log) }
        }

        row.addView(issueBtn)
        row.addView(emailBtn)
        row.addView(copyBtn)
        chatLayout.addView(row)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun openGitHubIssue(log: String) {
        val title = android.net.Uri.encode("Issue on ${android.os.Build.MODEL}")
        val body = android.net.Uri.encode(
            "**Device:** ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
            "**Android:** ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n" +
            "**Hardware:** ${android.os.Build.HARDWARE}\n\n" +
            "**Log:**\n```\n$log\n```\n"
        )
        val url = "https://github.com/soniqo/speech-android/issues/new?title=$title&body=$body"
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (_: Exception) {
            copyToClipboard(log)
        }
    }

    private fun sendEmail(log: String) {
        val subject = "Speech SDK bug: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
            data = android.net.Uri.parse("mailto:")
            putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf("root@ivan.digital"))
            putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
            putExtra(android.content.Intent.EXTRA_TEXT, log)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            copyToClipboard(log)
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Speech error log", text))
        android.widget.Toast.makeText(this, "Log copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------------------
    // Audio output (TTS playback)
    // ---------------------------------------------------------------------------

    private fun saveTtsAudio() {
        if (ttsBuffer.isEmpty()) return
        val totalSize = ttsBuffer.sumOf { it.size }
        val merged = ByteArray(totalSize)
        var offset = 0
        for (chunk in ttsBuffer) { chunk.copyInto(merged, offset); offset += chunk.size }
        ttsBuffer.clear()
        val file = java.io.File(filesDir, "tts_output.raw")
        file.writeBytes(merged)
        android.util.Log.i("Speech", "TTS saved: ${totalSize} bytes to ${file.absolutePath}")
    }

    private fun playTtsAudio() {
        stopAudioTrack()
        android.util.Log.i("Speech", "playTtsAudio: ${ttsBuffer.size} chunks")
        if (ttsBuffer.isEmpty()) return

        // Merge all chunks, then prepend silence + apply fade-in/out so the
        // audio HAL has time to spin up before the first phoneme and the
        // playback ends on a zero sample (no abrupt-cut click).
        val rawSize = ttsBuffer.sumOf { it.size }
        android.util.Log.i("Speech", "playTtsAudio: $rawSize bytes = ${rawSize/2/TTS_SAMPLE_RATE.toFloat()}s")
        val raw = ByteArray(rawSize)
        var offset = 0
        for (chunk in ttsBuffer) {
            chunk.copyInto(raw, offset)
            offset += chunk.size
        }
        ttsBuffer.clear()

        val leadSilenceSamples = TTS_SAMPLE_RATE * 80 / 1000   // 80ms HAL warmup
        val fadeInSamples      = TTS_SAMPLE_RATE * 5  / 1000   // 5ms fade-in
        val fadeOutSamples     = TTS_SAMPLE_RATE * 10 / 1000   // 10ms fade-out
        val processed = applyFades(raw, leadSilenceSamples, fadeInSamples, fadeOutSamples)
        val totalSize = processed.size

        // Write WAV file and play via MediaPlayer (louder on emulator)
        val wavFile = java.io.File(filesDir, "tts_playback.wav")
        java.io.FileOutputStream(wavFile).use { fos ->
            // WAV header
            val dataSize = totalSize
            val fileSize = 36 + dataSize
            val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(fileSize)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16) // chunk size
            header.putShort(1) // PCM
            header.putShort(1) // mono
            header.putInt(TTS_SAMPLE_RATE)
            header.putInt(TTS_SAMPLE_RATE * 2) // byte rate
            header.putShort(2) // block align
            header.putShort(16) // bits per sample
            header.put("data".toByteArray())
            header.putInt(dataSize)
            fos.write(header.array())
            fos.write(processed)
        }

        val mp = android.media.MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        mp.setDataSource(wavFile.absolutePath)
        mp.prepare()
        mp.setVolume(1.0f, 1.0f)
        mp.start()
        mediaPlayer = mp
        android.util.Log.i("Speech", "playTtsAudio: MediaPlayer started, duration=${mp.duration}ms")
    }

    private fun stopAudioTrack() {
        audioTrack?.let { track ->
            audioTrack = null
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
        mediaPlayer?.let { mp ->
            mediaPlayer = null
            try { mp.stop() } catch (_: Exception) {}
            mp.release()
        }
    }

    private fun applyFades(
        raw: ByteArray,
        leadSilenceSamples: Int,
        fadeInSamples: Int,
        fadeOutSamples: Int,
    ): ByteArray {
        val rawSamples = raw.size / 2
        val outSamples = leadSilenceSamples + rawSamples
        val out = ByteArray(outSamples * 2)
        val bb = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.position(leadSilenceSamples * 2)
        val src = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val fadeOutStart = (rawSamples - fadeOutSamples).coerceAtLeast(0)
        for (i in 0 until rawSamples) {
            val sample = src.short.toInt()
            val gain = when {
                i < fadeInSamples -> i.toFloat() / fadeInSamples
                i >= fadeOutStart -> (rawSamples - i).toFloat() / fadeOutSamples
                else -> 1f
            }
            bb.putShort((sample * gain).toInt().toShort())
        }
        return out
    }

    // ---------------------------------------------------------------------------
    // Microphone
    // ---------------------------------------------------------------------------

    private fun toggleMicrophone() {
        if (recording) {
            stopMicrophone()
            setStatus("tap to talk")
            setMicColor("#555555")
        } else {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                return
            }
            startMicrophone()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, results: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 1 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startMicrophone()
        }
    }

    private fun startMicrophone() {
        val p = pipeline ?: return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return

        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (bufferSize <= 0) {
            addSystemLine("mic unavailable (PCM_FLOAT unsupported, code $bufferSize)")
            setStatus("mic error")
            return
        }

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, bufferSize)
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            addSystemLine("mic init failed")
            setStatus("mic error")
            return
        }
        audioRecord = record

        recording = true
        record.startRecording()
        android.util.Log.i("Speech", "Mic started, state=${record.state}")
        setStatus("listening...")
        setMicColor("#4CAF50")

        // Record mic to file for debugging
        val recFile = java.io.File(filesDir, "mic_recording.raw")

        lifecycleScope.launch(Dispatchers.IO) {
            var totalFrames = 0L
            var maxPeak = 0f
            // use{} guarantees the stream is closed even if the loop body throws.
            java.io.FileOutputStream(recFile).use { recStream ->
                val buffer = FloatArray(512)
                while (recording) {
                    val read = try {
                        audioRecord?.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) ?: 0
                    } catch (_: IllegalStateException) {
                        break  // AudioRecord released mid-read (stopped on another thread)
                    }
                    if (read > 0) {
                        if (!micPaused) {
                            p.pushAudio(buffer)
                        }
                        val peak = if (micPaused) 0f else (buffer.take(read).maxOfOrNull { kotlin.math.abs(it) } ?: 0f)
                        if (peak > maxPeak) maxPeak = peak
                        vadView.addLevel(peak)

                        // Save to file
                        val bytes = java.nio.ByteBuffer.allocate(read * 4)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        bytes.asFloatBuffer().put(buffer, 0, read)
                        recStream.write(bytes.array())

                        totalFrames += read
                        if (totalFrames % 16000 == 0L) {
                            android.util.Log.i("Speech", "Mic: ${totalFrames/16000}s peak=${"%.4f".format(maxPeak)}")
                            maxPeak = 0f
                        }
                    }
                }
            }
            android.util.Log.i("Speech", "Mic stopped, recorded ${totalFrames} frames to ${recFile.absolutePath}")
        }
    }

    private fun stopMicrophone() {
        recording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    @Volatile private var micPaused = false

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun setStatus(text: String) {
        lifecycleScope.launch(Dispatchers.Main) { statusView.text = text }
    }

    private fun setMicColor(hex: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            micButton.setTextColor(Color.parseColor(hex))
        }
    }

    // ---------------------------------------------------------------------------
    // Cleanup
    // ---------------------------------------------------------------------------

    override fun onStop() {
        super.onStop()
        // Release the mic and stop playback when the app is no longer visible so
        // we don't hold the microphone or drain the battery in the background.
        // The model download (a foreground worker) is unaffected.
        if (recording) {
            stopMicrophone()
            setStatus("tap to talk")
            setMicColor("#555555")
        }
        micPaused = false
        stopAudioTrack()
    }

    override fun onDestroy() {
        stopMicrophone()
        stopAudioTrack()
        pipeline?.stop()
        pipeline?.close()
        super.onDestroy()
    }
}

// ---------------------------------------------------------------------------
// VAD Graph — scrolling bar chart of audio levels with speech state
// ---------------------------------------------------------------------------

class VadGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val levels = FloatArray(300)
    private val states = BooleanArray(300)
    private var writePos = 0
    private var count = 0
    @Volatile private var speechActive = false

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint().apply { color = Color.parseColor("#111111") }
    private val linePaint = Paint().apply {
        color = Color.parseColor("#333333")
        strokeWidth = 1f
    }

    fun addLevel(level: Float) {
        synchronized(levels) {
            levels[writePos] = level
            states[writePos] = speechActive
            writePos = (writePos + 1) % levels.size
            if (count < levels.size) count++
        }
        postInvalidate()
    }

    fun setSpeechActive(active: Boolean) {
        speechActive = active
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val padL = paddingLeft.toFloat()
        val padR = paddingRight.toFloat()
        val padT = paddingTop.toFloat()
        val padB = paddingBottom.toFloat()
        val drawW = w - padL - padR
        val drawH = h - padT - padB

        canvas.drawRect(padL, padT, padL + drawW, padT + drawH, bgPaint)

        val threshY = padT + drawH * 0.5f
        canvas.drawLine(padL, threshY, padL + drawW, threshY, linePaint)

        synchronized(levels) {
            if (count == 0) return

            val barCount = minOf(count, levels.size)
            val barW = drawW / levels.size
            val gap = 1f

            for (i in 0 until barCount) {
                val idx = (writePos - barCount + i + levels.size) % levels.size
                val level = levels[idx].coerceIn(0f, 1f)
                val isSpeech = states[idx]

                val barH = level * drawH
                val x = padL + (levels.size - barCount + i) * barW

                barPaint.color = if (isSpeech)
                    Color.parseColor("#4CAF50")
                else
                    Color.parseColor("#444444")

                canvas.drawRect(
                    x + gap, padT + drawH - barH,
                    x + barW - gap, padT + drawH,
                    barPaint
                )
            }
        }
    }
}
