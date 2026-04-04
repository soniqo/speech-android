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
import android.widget.ScrollView
import android.widget.TextView
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

class MainActivity : ComponentActivity() {

    private var pipeline: SpeechPipeline? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var recording = false
    private val ttsBuffer = mutableListOf<ByteArray>()
    private var lastTtsMs = 0f
    private var speechStartTime = 0L

    private lateinit var statusView: TextView
    private lateinit var micButton: TextView
    private lateinit var vadView: VadGraphView
    private lateinit var chatLayout: LinearLayout
    private lateinit var chatScroll: ScrollView

    companion object {
        private const val TTS_SAMPLE_RATE = 24000
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

        // Status bar
        statusView = TextView(this).apply {
            text = "speech-android"
            textSize = 16f
            setTextColor(Color.parseColor("#888888"))
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(48, 80, 48, 20)
        }
        root.addView(statusView)

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

        lifecycleScope.launch {
            try {
                val modelDir = ModelManager.ensureModels(
                    this@MainActivity,
                    precision = ModelPrecision.INT8,
                ) { progress ->
                    setStatus("${progress.file} ${progress.completed}/${progress.totalFiles}")
                }

                val config = SpeechConfig(
                    modelDir = modelDir,
                    useNnapi = false,
                    precision = ModelPrecision.INT8,
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
                                lastTtsMs = event.ttsMs
                                // Save TTS audio for debugging
                                try {
                                    java.io.File(filesDir, "tts_output.raw").appendBytes(event.audio)
                                } catch (_: Exception) {}
                            }

                            is SpeechEvent.ResponseDone -> {
                                android.util.Log.i("Speech", "ResponseDone -> TTS ready")
                                val totalBytes = ttsBuffer.sumOf { it.size }
                                val durationSec = totalBytes / 2f / TTS_SAMPLE_RATE
                                addSystemLine("tts: ${"%.0f".format(lastTtsMs)}ms → ${"%.1f".format(durationSec)}s audio")

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
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addSystemLine("init error: ${e.message}")
                    setStatus("error")
                }
            }
        }
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

        // Merge all chunks into one buffer
        val totalSize = ttsBuffer.sumOf { it.size }
        android.util.Log.i("Speech", "playTtsAudio: $totalSize bytes = ${totalSize/2/TTS_SAMPLE_RATE.toFloat()}s")
        val merged = ByteArray(totalSize)
        var offset = 0
        for (chunk in ttsBuffer) {
            chunk.copyInto(merged, offset)
            offset += chunk.size
        }
        ttsBuffer.clear()

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
            fos.write(merged)
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

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, bufferSize)

        recording = true
        audioRecord?.startRecording()
        android.util.Log.i("Speech", "Mic started, state=${audioRecord?.state}")
        setStatus("listening...")
        setMicColor("#4CAF50")

        // Record mic to file for debugging
        val recFile = java.io.File(filesDir, "mic_recording.raw")
        val recStream = java.io.FileOutputStream(recFile)

        lifecycleScope.launch(Dispatchers.IO) {
            val buffer = FloatArray(512)
            var totalFrames = 0L
            var maxPeak = 0f
            while (recording) {
                val read = audioRecord?.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) ?: 0
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
            recStream.close()
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

    private fun pauseMicrophone() {
        micPaused = true
        android.util.Log.i("Speech", "Mic paused for TTS playback")
    }

    private fun resumeMicrophone() {
        micPaused = false
        android.util.Log.i("Speech", "Mic resumed after TTS playback")
    }

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
