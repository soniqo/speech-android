package audio.soniqo.speech.demo.overlay

import android.Manifest
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import audio.soniqo.speech.ModelManager
import audio.soniqo.speech.ModelPrecision
import audio.soniqo.speech.PipelineMode
import audio.soniqo.speech.SpeechConfig
import audio.soniqo.speech.SpeechEvent
import audio.soniqo.speech.SpeechPipeline
import audio.soniqo.speech.SttModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Floating dictation bubble drawn over other apps.
 *
 * Idle it is a single mic button. Tapping it starts recording and swaps in
 * **Stop** and **Cancel**: Stop types the transcript into whatever text field
 * has input focus (via [DictationAccessibilityService]), Cancel throws it away.
 *
 * The overlay window is non-focusable on purpose — if it took focus, the text
 * field we are dictating into would lose it and there would be nowhere to type.
 */
class OverlayBubbleService : Service() {

    private enum class UiState { LOADING, IDLE, RECORDING, TRANSCRIBING }

    private lateinit var windowManager: WindowManager
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var bubble: LinearLayout
    private lateinit var micButton: TextView
    private lateinit var recordingRow: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var stopPill: TextView
    private lateinit var cancelPill: TextView
    private lateinit var micDot: GradientDrawable
    private lateinit var busyBubble: FrameLayout
    private var snapAnimator: ValueAnimator? = null

    /** Which edge the bubble is pinned to; the anchor that survives resizing. */
    private var dockedRight = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var pipeline: SpeechPipeline? = null
    private var audioRecord: AudioRecord? = null
    private var micJob: Job? = null
    private var finalizeJob: Job? = null
    @Volatile private var recording = false

    /**
     * Open only for the dictation currently being captured or finalized.
     * Results that arrive outside it belong to a committed or cancelled
     * dictation and must not leak into the next one.
     */
    @Volatile private var sessionActive = false

    /** When the engine last emitted anything; drives [drainEngine]. */
    @Volatile private var lastEngineEventAt = 0L

    private var state = UiState.LOADING
    private val transcript = StringBuilder()
    private var partialText = ""
    private var speechActive = false

    internal var pipelineFactory: (SpeechConfig) -> SpeechPipeline = { SpeechPipeline(it) }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        addBubble()
        loadPipeline()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        snapAnimator?.cancel()
        stopMicrophone()
        finalizeJob?.cancel()
        scope.cancel()
        pipeline?.let { p ->
            try { p.stop() } catch (_: Exception) {}
            try { p.close() } catch (_: Exception) {}
        }
        pipeline = null
        if (this::bubble.isInitialized && bubble.isAttachedToWindow) {
            try { windowManager.removeView(bubble) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Overlay UI
    // -------------------------------------------------------------------------

    private fun addBubble() {
        micButton = TextView(this).apply {
            // The dot is a drawable, not a glyph: text centering depends on
            // font ascent/descent, which left it visibly off-center.
            val size = dp(56)
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = micBackground()
        }

        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#BBBBBB"))
            maxLines = 2
            setPadding(dp(14), 0, dp(6), 0)
            gravity = Gravity.CENTER_VERTICAL
            maxWidth = dp(150)
        }

        // Icon-only so the recording state stays barely wider than the idle
        // bubble — a labelled row ran off the screen edge when docked right.
        stopPill = iconButton("■", "#4CAF50") { stopAndCommit() }
        cancelPill = iconButton("✕", "#FF5252") { cancelRecording() }

        recordingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(statusView)
            addView(stopPill)
            addView(cancelPill)
        }

        // Shown between Stop and the text landing in the field, so the wait
        // for the final transcription isn't a silent dead spot.
        busyBubble = FrameLayout(this).apply {
            val size = dp(56)
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = circle(Color.parseColor(BUBBLE_BG))
            visibility = View.GONE
            addView(
                ProgressBar(this@OverlayBubbleService).apply {
                    isIndeterminate = true
                    indeterminateTintList =
                        ColorStateList.valueOf(Color.parseColor(ACCENT))
                    layoutParams = FrameLayout.LayoutParams(dp(26), dp(26)).apply {
                        gravity = Gravity.CENTER
                    }
                }
            )
        }

        bubble = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(micButton)
            addView(busyBubble)
            addView(recordingRow)
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE keeps input focus (and the keyboard) on the app
            // underneath; without it the target text field deselects the
            // moment the bubble appears.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dp(240)
        }

        micButton.setOnTouchListener(DragTouchListener { startRecording() })
        windowManager.addView(bubble, layoutParams)
        render()
    }

    private fun iconButton(glyph: String, color: String, onClick: () -> Unit) =
        TextView(this).apply {
            text = glyph
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            // Font padding skews vertical centering of a lone glyph.
            includeFontPadding = false
            background = circle(Color.parseColor(color))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                marginStart = dp(8)
            }
            setOnClickListener { onClick() }
        }

    /** Idle bubble: bordered circle with a concentric dot drawn as a shape. */
    private fun micBackground(): LayerDrawable {
        val base = circle(Color.parseColor(BUBBLE_BG))
        micDot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(ACCENT))
        }
        return LayerDrawable(arrayOf(base, micDot)).apply {
            val inset = dp(19)
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    private fun circle(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(dp(2), Color.parseColor(BORDER))
    }

    private fun render() {
        when (state) {
            // Loading reuses the status row (without the buttons) so model
            // download progress is visible on the bubble itself.
            UiState.LOADING -> {
                micButton.visibility = View.VISIBLE
                micDot.setColor(Color.parseColor("#555555"))
                busyBubble.visibility = View.GONE
                recordingRow.visibility = View.VISIBLE
                statusView.visibility = View.VISIBLE
                stopPill.visibility = View.GONE
                cancelPill.visibility = View.GONE
            }
            UiState.IDLE -> {
                micButton.visibility = View.VISIBLE
                micDot.setColor(Color.parseColor(ACCENT))
                busyBubble.visibility = View.GONE
                recordingRow.visibility = View.GONE
            }
            // Buttons only — no label, no status text.
            UiState.RECORDING -> {
                micButton.visibility = View.GONE
                busyBubble.visibility = View.GONE
                recordingRow.visibility = View.VISIBLE
                statusView.visibility = View.GONE
                stopPill.visibility = View.VISIBLE
                cancelPill.visibility = View.VISIBLE
            }
            UiState.TRANSCRIBING -> {
                micButton.visibility = View.GONE
                busyBubble.visibility = View.VISIBLE
                recordingRow.visibility = View.GONE
            }
        }
        applyDock()
    }

    /**
     * Pin the bubble to whichever edge it is docked to.
     *
     * The window is WRAP_CONTENT, so it changes width as the state changes.
     * Anchoring the *docked edge* rather than the left corner is what makes it
     * expand inward and land back exactly where it started — clamping alone
     * shifted a right-docked bubble left and never moved it back.
     */
    private fun applyDock(animate: Boolean = false) {
        bubble.post {
            if (!bubble.isAttachedToWindow) return@post
            val (screenW, screenH) = screenBounds()
            val targetX = if (dockedRight) {
                (screenW - bubble.width).coerceAtLeast(0)
            } else {
                0
            }
            val targetY = layoutParams.y
                .coerceIn(0, (screenH - bubble.height).coerceAtLeast(0))
            if (animate) moveAnimated(targetX, targetY) else moveNow(targetX, targetY)
        }
    }

    private fun moveNow(x: Int, y: Int) {
        snapAnimator?.cancel()
        if (x == layoutParams.x && y == layoutParams.y) return
        layoutParams.x = x
        layoutParams.y = y
        updateLayout()
    }

    /** Short glide to the docked edge after a drag. */
    private fun moveAnimated(x: Int, y: Int) {
        snapAnimator?.cancel()
        val startX = layoutParams.x
        val startY = layoutParams.y
        if (x == startX && y == startY) return
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_DURATION_MS
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                layoutParams.x = (startX + (x - startX) * f).toInt()
                layoutParams.y = (startY + (y - startY) * f).toInt()
                updateLayout()
            }
            start()
        }
    }

    private fun updateLayout() {
        try {
            windowManager.updateViewLayout(bubble, layoutParams)
        } catch (_: IllegalArgumentException) {
            // Detached mid-update — service is shutting down.
        }
    }

    private fun screenBounds(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = resources.displayMetrics
            m.widthPixels to m.heightPixels
        }

    private fun setState(next: UiState) {
        state = next
        render()
    }

    private fun setStatus(text: String) {
        statusView.text = text
    }

    /** Moves the bubble on drag, fires [onTap] on a tap that never became one. */
    private inner class DragTouchListener(private val onTap: () -> Unit) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@OverlayBubbleService).scaledTouchSlop
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layoutParams.x
                    startY = layoutParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (!dragging && kotlin.math.hypot(dx.toFloat(), dy.toFloat()) < slop) {
                        return true
                    }
                    dragging = true
                    val bounds = screenBounds()
                    layoutParams.x = (startX + dx)
                        .coerceIn(0, (bounds.first - bubble.width).coerceAtLeast(0))
                    layoutParams.y = (startY + dy)
                        .coerceIn(0, (bounds.second - bubble.height).coerceAtLeast(0))
                    updateLayout()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        // Snap to whichever edge the bubble's centre is nearer.
                        val screenW = screenBounds().first
                        dockedRight = layoutParams.x + bubble.width / 2 > screenW / 2
                        applyDock(animate = true)
                    } else {
                        view.performClick()
                        onTap()
                    }
                    return true
                }
            }
            return false
        }
    }

    // -------------------------------------------------------------------------
    // Pipeline
    // -------------------------------------------------------------------------

    private fun loadPipeline() {
        setState(UiState.LOADING)
        scope.launch(Dispatchers.Default) {
            try {
                val modelDir = ModelManager.ensureModels(
                    context = applicationContext,
                    precision = ModelPrecision.INT8,
                    sttModel = STT_MODEL,
                ) { progress ->
                    scope.launch { setStatus("${progress.completed}/${progress.totalFiles}") }
                }

                val config = SpeechConfig(
                    modelDir = modelDir,
                    sttModel = STT_MODEL,
                    precision = ModelPrecision.INT8,
                    pipelineMode = PipelineMode.TRANSCRIBE_ONLY,
                    emitPartialTranscriptions = true,
                    partialTranscriptionInterval = 0.5f,
                )

                val p = pipelineFactory(config)
                pipeline = p
                launch { collectEvents(p) }
                p.start()

                withContext(Dispatchers.Main) { setState(UiState.IDLE) }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Overlay pipeline init failed", e)
                withContext(Dispatchers.Main) {
                    toast("Speech models failed to load: ${e.message ?: e.javaClass.simpleName}")
                    stopSelf()
                }
            }
        }
    }

    private suspend fun collectEvents(p: SpeechPipeline) {
        p.events.collect { event ->
            // Tracked regardless of the session gate — the drain needs to know
            // when the engine last produced anything, including results it is
            // about to discard.
            when (event) {
                is SpeechEvent.SpeechStarted,
                is SpeechEvent.SpeechEnded,
                is SpeechEvent.PartialTranscription,
                is SpeechEvent.TranscriptionCompleted ->
                    lastEngineEventAt = System.currentTimeMillis()
                else -> {}
            }

            when (event) {
                is SpeechEvent.SpeechStarted -> withContext(Dispatchers.Main) {
                    if (!sessionActive) return@withContext
                    speechActive = true
                }

                // Partials drive the finalize wait, but are never displayed —
                // the bubble deliberately shows no dictated text.
                is SpeechEvent.PartialTranscription -> withContext(Dispatchers.Main) {
                    if (!sessionActive) return@withContext
                    partialText = event.text
                }

                is SpeechEvent.SpeechEnded -> withContext(Dispatchers.Main) {
                    if (!sessionActive) return@withContext
                    speechActive = false
                }

                // The gate matters most here: the engine keeps draining audio
                // after the mic stops, so a late result from a committed or
                // cancelled dictation would otherwise land in the next one.
                is SpeechEvent.TranscriptionCompleted -> withContext(Dispatchers.Main) {
                    if (!sessionActive) {
                        Log.d(TAG, "Dropping transcription from a finished session")
                        return@withContext
                    }
                    partialText = ""
                    speechActive = false
                    if (event.text.isNotBlank()) {
                        if (transcript.isNotEmpty()) transcript.append(" ")
                        transcript.append(event.text.trim())
                    }
                }

                is SpeechEvent.Error -> withContext(Dispatchers.Main) {
                    Log.w(TAG, "Pipeline error: ${event.message}")
                }

                else -> {}
            }
        }
    }

    /**
     * Push silence so the VAD sees end-of-speech and closes whatever utterance
     * was still open when the mic stopped. Without this the audio sits inside
     * the engine and gets transcribed into the *next* dictation.
     */
    private fun pushSilence() {
        val silence = FloatArray(FRAME_SAMPLES)
        repeat(SILENCE_FRAMES) {
            try {
                pipeline?.pushAudio(silence) ?: return
            } catch (e: Exception) {
                Log.w(TAG, "Silence flush stopped early", e)
                return
            }
        }
    }

    /**
     * Wait for the utterance to actually finish transcribing after the flush.
     *
     * Watching `speechActive`/`partialText` alone is not enough: both are false
     * in the window between SpeechEnded and TranscriptionCompleted, so a wait
     * keyed on them returns *before* the result exists and commits nothing.
     * Short phrases that never emit a partial land in the same hole. Waiting
     * for the engine to fall quiet covers both, since every event it emits
     * pushes the quiet window back.
     */
    private suspend fun awaitFinalTranscription() {
        val flushedAt = System.currentTimeMillis()
        val deadline = flushedAt + FINALIZE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val now = System.currentTimeMillis()
            val settled = now - flushedAt >= FINALIZE_SETTLE_MS
            val quiet = now - lastEngineEventAt >= FINALIZE_QUIET_MS
            if (settled && quiet && !speechActive && partialText.isEmpty()) return
            delay(50)
        }
        Log.w(TAG, "Final transcription did not settle within ${FINALIZE_TIMEOUT_MS}ms")
    }

    /**
     * Wait until the engine stops emitting. speech-core never clears its
     * pending-utterance queue on stop/start, so anything still queued when a
     * new dictation begins would surface inside it. Holding the UI busy until
     * the engine falls quiet is what actually prevents the leak.
     */
    private suspend fun drainEngine() {
        val cap = System.currentTimeMillis() + DRAIN_CAP_MS
        while (System.currentTimeMillis() < cap) {
            if (System.currentTimeMillis() - lastEngineEventAt > DRAIN_QUIET_MS) return
            delay(50)
        }
        Log.w(TAG, "Engine still emitting after ${DRAIN_CAP_MS}ms; giving up on drain")
    }

    /** Everything heard this session, including any unfinalized partial. */
    private fun dictatedText(): String =
        (transcript.toString() + if (partialText.isNotEmpty()) " $partialText" else "").trim()

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Dictation", text))
    }

    // -------------------------------------------------------------------------
    // Recording
    // -------------------------------------------------------------------------

    private fun startRecording() {
        if (state != UiState.IDLE) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toast("Microphone permission is required")
            return
        }

        val sr = 16000
        val bufSize = AudioRecord.getMinBufferSize(
            sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (bufSize <= 0) {
            toast("Microphone unavailable")
            return
        }

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, bufSize * 4,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            toast("Microphone init failed")
            return
        }
        audioRecord = record

        transcript.clear()
        partialText = ""
        speechActive = false
        sessionActive = true
        record.startRecording()
        recording = true
        setState(UiState.RECORDING)
        setStatus("listening…")

        micJob = scope.launch(Dispatchers.IO) {
            val buf = FloatArray(512)
            while (recording && isActive) {
                val read = try {
                    record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                } catch (_: IllegalStateException) {
                    break // released mid-read
                }
                if (read > 0) {
                    val samples = if (read == buf.size) buf else buf.copyOf(read)
                    try {
                        pipeline?.pushAudio(samples) ?: break
                    } catch (e: Exception) {
                        Log.w(TAG, "Dropping mic frame after pipeline shutdown", e)
                        break
                    }
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord.read returned $read")
                    break
                }
            }
        }
    }

    /** Stop: flush the tail of the utterance, then type it into the focused field. */
    private fun stopAndCommit() {
        if (state != UiState.RECORDING) return
        stopMicrophone()
        setState(UiState.TRANSCRIBING)

        finalizeJob?.cancel()
        finalizeJob = scope.launch {
            // The user almost always taps Stop mid-utterance, before the VAD
            // has closed the segment. Flushing silence closes it now, so the
            // tail is transcribed into *this* dictation instead of lingering.
            withContext(Dispatchers.Default) { pushSilence() }

            awaitFinalTranscription()

            val text = dictatedText()
            // Close the session before the insert, not after: everything the
            // engine emits from here on belongs to a finished dictation.
            sessionActive = false
            transcript.clear()
            partialText = ""

            // Swallow anything still queued behind the committed result, so
            // it cannot reappear in the next dictation.
            drainEngine()

            if (text.isBlank()) {
                setState(UiState.IDLE)
                toast("Nothing heard")
                return@launch
            }
            val result = withContext(Dispatchers.Default) {
                DictationAccessibilityService.insertIntoFocusedField(text)
            }
            // Only now go idle — staying busy through the insert stops a second
            // dictation from starting on top of this one.
            setState(UiState.IDLE)
            when (result) {
                DictationAccessibilityService.InsertResult.INSERTED -> {}
                // Never drop what the user said — park it on the clipboard so
                // a long-press paste still gets them there.
                DictationAccessibilityService.InsertResult.NO_FOCUSED_FIELD -> {
                    copyToClipboard(text)
                    toast("No text field focused — copied to clipboard")
                }
                DictationAccessibilityService.InsertResult.SERVICE_DISABLED -> {
                    copyToClipboard(text)
                    toast("Accessibility service off — copied to clipboard")
                }
            }
        }
    }

    /** Cancel: drop everything heard so far and leave the target untouched. */
    private fun cancelRecording() {
        if (state != UiState.RECORDING) return
        finalizeJob?.cancel()
        // Before clearing, so a result still in flight can't refill it.
        sessionActive = false
        stopMicrophone()
        transcript.clear()
        partialText = ""
        speechActive = false

        // Cancelled audio still has to be flushed out of the engine, or it
        // resurfaces in the next dictation — exactly what Cancel must prevent.
        // The busy state keeps a new recording from starting mid-drain.
        setState(UiState.TRANSCRIBING)
        finalizeJob = scope.launch {
            withContext(Dispatchers.Default) { pushSilence() }
            drainEngine()
            setState(UiState.IDLE)
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

    // -------------------------------------------------------------------------
    // Plumbing
    // -------------------------------------------------------------------------

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Dictation overlay",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayBubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Dictation overlay")
            .setContentText("Tap the bubble to dictate")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null as Icon?, "Stop overlay", stopIntent,
                ).build()
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "OverlayBubble"
        private const val CHANNEL_ID = "dictation_overlay"
        private const val NOTIFICATION_ID = 4711
        private const val FINALIZE_TIMEOUT_MS = 4000L
        /** Minimum wait after the flush before concluding nothing was heard. */
        private const val FINALIZE_SETTLE_MS = 700L
        /** Engine must be silent this long for the utterance to count as done. */
        private const val FINALIZE_QUIET_MS = 500L
        private const val BUBBLE_BG = "#1E1E1E"
        private const val BORDER = "#8A8A8A"
        private const val ACCENT = "#4FC3F7"
        private const val SNAP_DURATION_MS = 160L
        private const val FRAME_SAMPLES = 512
        /** ~1.5 s of silence at 16 kHz — comfortably past the VAD's 0.5 s. */
        private const val SILENCE_FRAMES = 48
        private const val DRAIN_QUIET_MS = 400L
        private const val DRAIN_CAP_MS = 2500L
        private val STT_MODEL = SttModel.PARAKEET

        const val ACTION_STOP = "audio.soniqo.speech.demo.overlay.STOP"

        @Volatile
        private var running = false

        val isRunning: Boolean get() = running

        fun start(context: Context) {
            val intent = Intent(context, OverlayBubbleService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayBubbleService::class.java))
        }
    }
}
