package audio.soniqo.speech.control

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import audio.soniqo.speech.ModelDownloadWorker
import audio.soniqo.speech.ModelManager
import audio.soniqo.speech.LlmModel
import audio.soniqo.speech.ModelPrecision
import audio.soniqo.speech.PipelineMode
import audio.soniqo.speech.SpeechConfig
import audio.soniqo.speech.SpeechEvent
import audio.soniqo.speech.SpeechPipeline
import audio.soniqo.speech.SpeechSynthesisResult
import audio.soniqo.speech.SttModel
import audio.soniqo.speech.TtsModel
import audio.soniqo.speech.control.ui.ControlActions
import audio.soniqo.speech.control.ui.ControlScreen
import audio.soniqo.speech.control.ui.SoniqoControlTheme
import audio.soniqo.speech.llm.FunctionGemma
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Soniqo Control — voice command recognition for basic phone functionality,
 * running the full on-device pipeline from the soniqo blog post "Running a
 * voice agent on-device: one pipeline, three memory budgets":
 *
 *   Silero VAD → Parakeet-EOU 120M (TRANSCRIBE_ONLY) → FunctionGemma 270M
 *   (LiteRT-LM) → device tool (dial / contacts / music / volume) → Pocket
 *   TTS streams the model-authored `say` argument.
 *
 * The UI is Jetpack Compose ([ControlScreen]) subscribing to a single
 * [ControlStore] state flow; this Activity owns the pipeline and pushes
 * state into the store. Debug-build scripted driving: long-press "hold to
 * type" or `adb shell am start -n .../.ControlAgentActivity --es command
 * "call anna"`. Intent harness extras are ignored in non-debuggable builds.
 */
class ControlAgentActivity : ComponentActivity() {

    private var pipeline: SpeechPipeline? = null
    private var llmRuntime: LiteRtLmRuntime? = null
    private var functionGemma: FunctionGemma? = null
    private var audioRecord: AudioRecord? = null
    private val speechPlayerLock = Any()
    @Volatile private var speechPlayer: StreamingPcmPlayer? = null
    private var mediaPlayer: MediaPlayer? = null

    @Volatile private var recording = false
    @Volatile private var micPaused = false
    // Debug mic tap: launch with `--ez record_mic true` to dump everything the
    // microphone session captures (including audio consumed while the agent
    // speaks) as 16 kHz mono PCM16 under files/mic_debug/, pullable via
    // run-as. Off by default — nothing is ever recorded without the flag.
    @Volatile private var micDebugEnabled = false
    @Volatile private var micDebugStream: java.io.FileOutputStream? = null
    // Software input gain (see MicGain). `--ez mic_gain false` disables it,
    // e.g. to replay recordings at their original level for benchmarks.
    @Volatile private var micGainEnabled = true
    private val replayInFlight = AtomicBoolean(false)
    private val micGain = MicGain()
    private val turnInFlight = AtomicBoolean(false)
    @Volatile private var ready = false
    private var pendingCommand: String? = null
    private var pendingReplay: String? = null

    private val store = ControlStore()
    private val device = AndroidDeviceActions()
    private val memory = MemoryMonitor()

    private var pipelineStarted = false
    private var observingDownload = false
    // True between a mic tap and the mic being stopped. Gates the permission
    // callback so the startup request can't switch capture on unasked.
    private var micRequested = false
    // STT model override for on-device A/B: `--es stt_model PARAKEET` selects
    // the 0.6B TDT v3; unset keeps DEMO_STT. Read before models load.
    private var sttOverride: String? = null

    private val acceptsDebugIntents: Boolean
        get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun activeStt(): SttModel =
        sttOverride?.let { runCatching { SttModel.valueOf(it) }.getOrNull() } ?: DEMO_STT
    // Drives the TTS language (and Nemotron's prompt slot if ever selected).
    // Parakeet STT autodetects regardless — like every other Parakeet
    // runtime, there is no language forcing, so accented speech can
    // occasionally decode into a non-Latin script on the multilingual TDT.
    private fun activeLanguage(): String = "en"

    // Contextual-biasing phrases for the STT beam search: the fixed command
    // grammar and the brand, plus the track titles/artists currently on the
    // device. The command core is always present; without media permission
    // listMusic() returns empty and only the fixed phrases apply.
    //
    // Reads the library through the silent [listMusicOrEmpty]: this runs during
    // startup, and a user who has not yet answered the permission dialog should
    // not see a "media permission not granted" note about a request they were
    // never shown. The music tools still report the denial, because there it is
    // what made the command fail.
    private fun buildContextPhrases(): List<String> {
        val phrases = mutableListOf(
            "Soniqo",
            "set volume", "volume up", "volume down", "mute", "unmute",
            "play music", "play", "stop", "stop playing", "pause", "resume",
            "next track", "previous track",
            "call", "dial", "dial number", "redial",
            "find music", "search music", "list music",
            "what can you do",
        )
        runCatching {
            device.listMusicOrEmpty(null).forEach { t ->
                if (t.title.isNotBlank()) phrases.add(t.title)
                t.artist?.let { if (it.isNotBlank()) phrases.add(it) }
            }
        }
        return phrases.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /** Push the current biasing set at the pipeline, if one is running. */
    private fun applyContextPhrases() {
        if (EOU_BEAM_SIZE <= 1) return
        pipeline?.let { runCatching { it.setContextPhrases(buildContextPhrases()) } }
    }

    private var speechStartMs = 0L
    private var speechEndMs = 0L

    companion object {
        private const val TAG = "SpeechControl"

        private const val REQ_PERMISSIONS = 1

        // STT model for the demo. PARAKEET_EOU is the low-memory default
        // (25 languages, ~232 MB) — fast, ~2 s round trip, ~1.3 GB total.
        // PARAKEET is Parakeet-TDT v3 (114 languages, auto-detect) but
        // ~891 MB download and ~2 GB total, which is ~5× slower under
        // emulation and needs a real device with an NPU to feel good.
        private val DEMO_STT = SttModel.PARAKEET_EOU
        // Pocket is a recurrent ONNX model that emits real 80 ms frames. Keep
        // Kokoro available through the SDK for multilingual/voice selection.
        private val DEMO_TTS = TtsModel.POCKET

        // Parakeet-EOU RNN-T decode width. > 1 enables beam search, which
        // contextual biasing (buildContextPhrases) rides on. Truncation seen in
        // the replay bench traced to the fixture cadence, not the decoder
        // (beam == greedy on the host); testing live on-device.
        private const val EOU_BEAM_SIZE = 4

        private val isEmulator = Build.FINGERPRINT.contains("generic")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("sdk")
                || Build.HARDWARE.contains("ranchu")

        private val mediaPermission =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (acceptsDebugIntents) {
            pendingCommand = intent?.getStringExtra("command")
            pendingReplay = if (pendingCommand == null) {
                intent?.getStringExtra("replay_pcm")
            } else {
                null
            }
            micDebugEnabled = intent?.getBooleanExtra("record_mic", false) == true
            micGainEnabled = intent?.getBooleanExtra("mic_gain", true) != false
            intent?.getStringExtra("stt_model")?.let { sttOverride = it }
        }
        setContent {
            SoniqoControlTheme {
                val state by store.state.collectAsStateWithLifecycle()
                ControlScreen(
                    state = state,
                    actions = ControlActions(
                        onMicTap = ::toggleMicrophone,
                        onOpenType = { store.setTypeDialog(true) },
                        onSubmitTyped = { cmd ->
                            store.setTypeDialog(false)
                            handleCommand(cmd, sttMs = 0f, voiceAnchored = false)
                        },
                        onDismissType = { store.setTypeDialog(false) },
                        onOpenInfo = { store.setInfoDialog(true) },
                        onDismissInfo = { store.setInfoDialog(false) },
                    ),
                )
            }
        }
        bench("baseline")
        // Ask up front rather than on the first mic tap. The models take
        // minutes to download, and the dialogs are dead time that fits inside
        // it — but more importantly the demo reads contacts and the music
        // library during startup (STT biasing) and on the very first command,
        // so deferring the request produced "permission not granted" notes for
        // dialogs the user had never been shown.
        requestDemoPermissions()
        loadModels()
    }

    /**
     * The runtime permissions the demo needs, requested as one batch. Android
     * shows them as a sequence of per-group dialogs. On API 33+ the media
     * permission is READ_MEDIA_AUDIO, which the system surfaces under
     * "Music and audio" — not the pre-13 "Files and media" storage group.
     */
    private fun demoPermissions(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        add(mediaPermission)
        // Without this the foreground download worker runs but its progress
        // notification is silently dropped, so leaving the app looks like the
        // download stopped.
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun missingPermissions(): List<String> = demoPermissions().filter {
        checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    private fun requestDemoPermissions() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMISSIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!acceptsDebugIntents) return
        micGainEnabled = intent.getBooleanExtra("mic_gain", micGainEnabled)
        val command = intent.getStringExtra("command")
        val replay = intent.getStringExtra("replay_pcm")
        when {
            command != null -> {
                pendingReplay = null
                if (ready) handleCommand(command, sttMs = 0f, voiceAnchored = false)
                else pendingCommand = command
            }
            replay != null -> {
                pendingCommand = null
                if (ready) runReplay(replay) else pendingReplay = replay
            }
        }
    }

    // -----------------------------------------------------------------------
    // Store helpers
    // -----------------------------------------------------------------------

    private fun bench(stage: String) {
        val mb = memory.sample()
        Log.i(TAG, "[bench] $stage: $mb MB")
        store.setMemory(memory.currentMb(), memory.peakMb)
    }

    /** Friendly stage name for a model file, so the status reads "downloading
     *  transcription model" instead of "downloading parakeet-eou-encoder.onnx". */
    private fun modelLabel(file: String): String = when {
        file.startsWith("pocket_tts/") -> "speech synthesis"
        file.startsWith("silero") -> "voice detection"
        file.startsWith("parakeet") || file == "vocab.json" || file == "config.json" ||
            file == "encoder.onnx" || file == "decoder.onnx" || file == "joint.onnx" ||
            file.startsWith("nemotron") -> "transcription model"
        file == "model.litertlm" || file.startsWith("model-lora") ||
            file.startsWith("control-r4-") -> "language model"
        file.startsWith("kokoro") || file.startsWith("dict") || file.startsWith("us_") ||
            file.startsWith("voices") || file.startsWith("voice_styles") ||
            file == "vocab_index.json" || file.endsWith(".tflite") -> "speech synthesis"
        file.startsWith("deepfilter") -> "noise filter"
        else -> "models"
    }

    /** Rest state after a turn: keep listening if the mic is live, else idle. */
    private fun returnToRest() {
        store.setMic(if (recording) MicState.LISTENING else MicState.IDLE)
        store.setStatus(if (recording) "listening" else "tap to talk")
    }

    // -----------------------------------------------------------------------
    // Model download + init
    // -----------------------------------------------------------------------

    private fun loadModels() {
        store.setStatus("downloading models")
        store.setDownload(0)
        // includeLlm = true: the FunctionGemma bundle downloads in this same
        // foreground worker, so the whole ~800 MB setup survives the screen
        // dozing / Wi-Fi power-save (an in-app download dies on "unable to
        // resolve huggingface.co" the moment the phone sleeps).
        ModelDownloadWorker.enqueue(
            applicationContext,
            ModelPrecision.INT8,
            sttModel = activeStt(),
            ttsModel = DEMO_TTS,
            includeLlm = true,
            llmModel = LlmModel.FUNCTIONGEMMA_CONTROL_LORA,
        )
        if (observingDownload) return
        observingDownload = true
        WorkManager.getInstance(applicationContext)
            .getWorkInfosForUniqueWorkLiveData(
                ModelDownloadWorker.uniqueName(
                    sttModel = activeStt(),
                    ttsModel = DEMO_TTS,
                    includeLlm = true,
                    llmModel = LlmModel.FUNCTIONGEMMA_CONTROL_LORA,
                ))
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
                            val label = modelLabel(file)
                            store.setStatus("downloading $label")
                            store.setDownloadStage(label)
                            store.setDownload(info.progress.getInt(ModelDownloadWorker.KEY_PERCENT, 0))
                            // Byte counts, rate and ETA. The LLM bundle alone is
                            // ~290 MB, so without this the panel gives no way to
                            // tell a slow link from a stalled one.
                            store.setDownloadDetail(ModelDownloadWorker.detailLine(
                                info.progress.getLong(
                                    ModelDownloadWorker.KEY_TOTAL_BYTES_DOWNLOADED, 0L),
                                info.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L),
                                info.progress.getLong(ModelDownloadWorker.KEY_BYTES_PER_SEC, 0L),
                                info.progress.getLong(ModelDownloadWorker.KEY_ETA_SECONDS, -1L),
                            ))
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val modelDir = info.outputData.getString(ModelDownloadWorker.KEY_MODEL_DIR)
                        if (modelDir == null) { store.setStatus("error — no model dir"); return@observe }
                        if (!pipelineStarted) { pipelineStarted = true; initEverything(modelDir) }
                    }
                    WorkInfo.State.FAILED -> {
                        val err = info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "unknown"
                        store.addNote("download failed: $err")
                        store.setStatus("download failed")
                    }
                    WorkInfo.State.CANCELLED -> store.setStatus("download cancelled")
                }
            }
    }

    private fun initEverything(modelDir: String) {
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                store.setStatus("loading pipeline")
                store.setDownloadStage(null)
                val p = SpeechPipeline(SpeechConfig(
                    modelDir = modelDir,
                    useNnapi = !isEmulator,
                    sttModel = activeStt(),
                    ttsModel = DEMO_TTS,
                    pipelineMode = PipelineMode.TRANSCRIBE_ONLY,
                    emitPartialTranscriptions = true,
                    language = activeLanguage(),
                    // 0.5 s default cut people off mid-command on-device —
                    // dictated digits and thinking pauses exceed it easily.
                    endOfSpeechSilenceSec = 0.8f,
                    // Greedy by default (EOU_BEAM_SIZE); beam under-emits
                    // on-device. Ignored by other STT models.
                    beamSize = EOU_BEAM_SIZE,
                ))
                pipeline = p
                bench("pipeline loaded")

                // Samsung takes roughly 225 ms to allocate its AudioTrack.
                // Pay that once while models are loading, then retain the
                // stream across turns so it is never on the TTFA path.
                if (DEMO_TTS == TtsModel.POCKET) {
                    acquireSpeechPlayer(p.ttsSampleRate)
                    bench("speech output ready")
                }

                launch { p.events.collect { onSpeechEvent(it) } }
                p.start()
                // Bias recognition toward the command grammar, the brand, and
                // whatever music is on this device right now. No-op in greedy
                // mode; only meaningful once EOU_BEAM_SIZE > 1. Rebuilt by
                // onRequestPermissionsResult if the media grant lands later.
                applyContextPhrases()

                store.setStatus("downloading language model")
                store.setDownloadStage("language model")
                val llmProfile = LlmModel.FUNCTIONGEMMA_CONTROL_LORA
                val llmPath = ModelManager.ensureLlmModels(
                    applicationContext,
                    llmModel = llmProfile,
                ) { prog ->
                    // Normally a no-op: the worker already fetched the bundle
                    // with includeLlm = true, so this just revalidates. It only
                    // streams when that worker path failed and left it behind.
                    if (prog.totalBytes > 0) {
                        store.setDownload(ModelDownloadWorker.progressPercent(
                            prog.totalBytesDownloaded, prog.totalBytes))
                        store.setDownloadDetail(ModelDownloadWorker.detailLine(
                            prog.totalBytesDownloaded, prog.totalBytes, 0L, -1L))
                    }
                    store.setStatus("downloading language model")
                }
                val adapterPath = requireNotNull(
                    ModelManager.llmAdapterFile(applicationContext, llmProfile),
                ) { "Control LLM profile is missing its adapter" }
                store.setStatus("loading LLM engine")
                val runtime = LiteRtLmRuntime(llmPath, adapterPath)
                runtime.initialize()
                llmRuntime = runtime
                functionGemma = FunctionGemma(runtime, maxNewTokens = 128)
                bench("llm loaded")

                memory.start()
                launch {
                    while (true) { store.setMemory(memory.currentMb(), memory.peakMb); delay(1000) }
                }

                ready = true
                store.setDownload(null)
                store.setDownloadDetail(null)
                store.setMic(MicState.IDLE)
                store.setStatus("ready · on-device")
                p.nnapiFallbackReason?.let { Log.w(TAG, "NNAPI unavailable, using CPU: $it") }
                pendingCommand?.let { cmd ->
                    pendingCommand = null
                    handleCommand(cmd, sttMs = 0f, voiceAnchored = false)
                }
                pendingReplay?.let { name ->
                    pendingReplay = null
                    runReplay(name)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "init failed", e)
                store.addNote("init error: ${e.message}")
                store.setStatus("error")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Pipeline events → agent turn
    // -----------------------------------------------------------------------

    private fun onSpeechEvent(event: SpeechEvent) {
        // The pipeline keeps running while idle, so VAD/STT can fire on stray
        // room audio (notably the emulator's host mic). Only let speech events
        // drive the UI while the user actually has a mic session open —
        // otherwise the orb could get stuck "listening" with no turn behind it.
        if (!recording && !replayInFlight.get() && event !is SpeechEvent.Error) return
        when (event) {
            is SpeechEvent.SpeechStarted -> {
                speechStartMs = System.currentTimeMillis()
                store.setMic(MicState.LISTENING)
                store.setStatus("listening")
            }
            is SpeechEvent.SpeechEnded -> {
                speechEndMs = System.currentTimeMillis()
                store.setStatus("transcribing")
            }
            is SpeechEvent.PartialTranscription -> store.setStatus("hearing: ${event.text}")
            is SpeechEvent.TranscriptionCompleted -> {
                val text = event.text.trim()
                if (text.isNotEmpty()) handleCommand(text, event.sttMs, voiceAnchored = true)
                else returnToRest()  // empty utterance — don't strand "transcribing"
            }
            is SpeechEvent.Error -> {
                store.addNote("error: ${event.message}")
                store.setStatus("error")
            }
            else -> {}
        }
    }

    private fun handleCommand(text: String, sttMs: Float, voiceAnchored: Boolean) {
        if (!turnInFlight.compareAndSet(false, true)) {
            store.addNote("dropped (turn in flight): $text")
            return
        }
        val turnId = store.beginTurn(text)
        lifecycleScope.launch(Dispatchers.Default) {
            try { runAgentTurn(turnId, text, sttMs, voiceAnchored) }
            finally { turnInFlight.set(false) }
        }
    }

    // FunctionGemma was trained on lowercase, unpunctuated utterances (the
    // EOU model's output style). TDT emits cased, punctuated text; fold it
    // back to the training distribution before routing. The UI transcript
    // keeps the raw text.
    private val llmPunct = Regex("[.,!?;:-]+")
    private val llmSpaces = Regex("\\s+")
    private fun normalizeForLlm(text: String): String =
        text.lowercase().replace(llmPunct, " ").replace(llmSpaces, " ").trim()

    /** STT/typed command → FunctionGemma tool call → device action → speak. */
    private suspend fun runAgentTurn(turnId: Long, text: String, sttMs: Float, voiceAnchored: Boolean) {
        val llm = functionGemma
        val p = pipeline
        if (llm == null || p == null) { store.addNote("LLM not ready yet"); return }
        val speechSec = if (voiceAnchored && speechEndMs > speechStartMs)
            (speechEndMs - speechStartMs) / 1000f else 0f

        store.setMic(MicState.THINKING)
        store.setStatus("thinking")
        val llmStart = System.currentTimeMillis()
        val turnAnchorMs = if (voiceAnchored && speechEndMs > 0) speechEndMs else llmStart

        // Every command is routed by FunctionGemma. The tool list is filtered
        // to the current device state (e.g. stop_music only while music plays),
        // so the model can only choose valid actions.
        var llmMs = 0L
        var rawLength = 0
        val musicPlaying = device.isMusicPlaying
        val tools = ControlTools.availableTools(musicPlaying = musicPlaying)
        val prompt = normalizeForLlm(text)
        val raw = try {
            // The Control adapter was trained on this compact serialization:
            // schemas live in its weights, while the prompt carries the
            // state-filtered function names and current music state.
            val runtime = llmRuntime ?: error("LLM runtime not ready")
            runtime.generate(CompactPrompt.format(tools, musicPlaying, prompt), 128)
        } catch (e: Exception) {
            Log.e(TAG, "LLM generation failed", e)
            store.updateTurn(turnId) { it.copy(toolLabel = "llm error: ${e.message}", failed = true) }
            returnToRest(); return
        }
        llmMs = System.currentTimeMillis() - llmStart
        rawLength = raw.length
        val calls = llm.parseToolCalls(raw)
        val selectedCall = ControlTools.selectSingleCall(calls)
        // Routing telemetry for analysis: input, what the model emitted, and
        // which tool (of the state-filtered set) it chose.
        Log.i(TAG, "ROUTE in='$prompt' musicPlaying=$musicPlaying " +
            "compact=true " +
            "tools=[${tools.joinToString(",") { it.name }}] " +
            "calls=${calls.size} chose=${selectedCall?.name ?: "NONE"} " +
            "raw='${raw.replace("\n", " ").take(160)}'")
        val outcome = selectedCall?.let { call ->
            try { ControlTools.execute(call, device) }
            catch (e: Exception) { Log.e(TAG, "tool execution failed", e); null }
        } ?: run {
            val label = when {
                calls.isEmpty() -> "no tool call"
                calls.size > 1 -> "ambiguous: ${calls.size} tool calls"
                else -> "unhandled: ${calls.single().name}"
            }
            store.updateTurn(turnId) { it.copy(toolLabel = label, failed = true) }
            Log.i(TAG, "unparsed LLM output: ${raw.take(200)}")
            null
        }
        val actionMs = System.currentTimeMillis() - turnAnchorMs

        val spoken = outcome?.spoken ?: ControlTools.NO_TOOL_RESPONSE
        outcome?.let { o -> store.updateTurn(turnId) { it.copy(toolLabel = o.label) } }
        store.updateTurn(turnId) { it.copy(spoken = spoken) }

        store.setMic(MicState.SPEAKING)
        store.setStatus("speaking")
        micPaused = true
        // Pocket is genuinely recurrent, so send it the whole response and
        // play its 80 ms callbacks through one continuous AudioTrack. Kokoro
        // retains bounded app-side splitting for its fixed output graph.
        val pieces = if (DEMO_TTS == TtsModel.POCKET) listOf(spoken) else SpeechChunks.split(spoken)
        val ttsStartWallMs = System.currentTimeMillis()
        val ttsStartNanos = SystemClock.elapsedRealtimeNanos()
        val firstChunkMs = AtomicLong(0)
        val firstPresentationMs = AtomicLong(0)
        var playbackStartMs = 0L
        var audioSecTotal = 0f
        var nativeChunks = 0
        var playbackUnderruns = 0
        var playbackPerformanceMode = -1
        var playbackBufferBytes = 0

        fun publishFirstAudioMetrics(ttsMs: Long) {
            val roundMs = (ttsStartWallMs - turnAnchorMs + ttsMs).coerceAtLeast(ttsMs)
            val metrics = TurnMetrics(
                sttMs = sttMs, speechSec = speechSec,
                llmMs = llmMs, llmChars = rawLength,
                ttsMs = ttsMs, ttsAudioSec = 0f,
                actionMs = actionMs, roundMs = roundMs,
                memMb = memory.currentMb(),
            )
            store.updateTurn(turnId) { it.copy(metrics = metrics) }
            store.setLastMetrics(metrics)
            store.setMemory(memory.currentMb(), memory.peakMb)
            Log.i(TAG, "TURN ${metrics.format()}")
        }

        try {
            kotlinx.coroutines.coroutineScope {
                val audio = Channel<SpeechSynthesisResult>(Channel.UNLIMITED)
                val producer = launch(Dispatchers.Default) {
                    try {
                        for (piece in pieces) {
                            p.synthesizeStreaming(piece, "en") { chunk, _ ->
                                // Pocket terminates with an empty final callback;
                                // it is control flow, not an audio frame.
                                if (chunk.pcm16.isNotEmpty()) {
                                    firstChunkMs.compareAndSet(
                                        0,
                                        (SystemClock.elapsedRealtimeNanos() - ttsStartNanos) /
                                            1_000_000,
                                    )
                                    check(audio.trySend(chunk).isSuccess) {
                                        "TTS playback channel closed"
                                    }
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        audio.close(e)
                        throw e
                    } finally {
                        audio.close()
                    }
                }
                var playbackCompleted = false
                try {
                    var player: StreamingPcmPlayer? = null
                    var presentationWatcher: kotlinx.coroutines.Job? = null
                    for (current in audio) {
                        nativeChunks++
                        val sec = current.pcm16.size / 2f / current.sampleRate
                        audioSecTotal += sec
                        val active = player
                        if (active == null) {
                            val created = acquireSpeechPlayer(current.sampleRate)
                            player = created
                            created.start(current.pcm16)
                            playbackStartMs =
                                (SystemClock.elapsedRealtimeNanos() - ttsStartNanos) / 1_000_000
                            playbackPerformanceMode = created.performanceMode
                            playbackBufferBytes = created.bufferSizeBytes
                            publishFirstAudioMetrics(playbackStartMs)
                            presentationWatcher = launch(Dispatchers.Default) {
                                created.awaitFirstPresentationNanos()?.let { presentedNanos ->
                                    val presentedMs = ((presentedNanos - ttsStartNanos) / 1_000_000)
                                        .coerceAtLeast(playbackStartMs)
                                    firstPresentationMs.set(presentedMs)
                                    publishFirstAudioMetrics(presentedMs)
                                    Log.i(
                                        TAG,
                                        "tts first-presentation ${presentedMs}ms " +
                                            "(model callback ${firstChunkMs.get()}ms)",
                                    )
                                }
                            }
                        } else {
                            check(active.sampleRate == current.sampleRate) {
                                "TTS sample rate changed mid-turn: " +
                                    "${active.sampleRate} -> ${current.sampleRate}"
                            }
                            active.write(current.pcm16)
                        }
                    }
                    producer.join()
                    val active = player ?: error("TTS completed without audio")
                    active.awaitDrained()
                    presentationWatcher?.join()
                    playbackUnderruns = active.underrunCount
                    active.resetForNextUtterance()
                    playbackCompleted = true
                } finally {
                    if (producer.isActive) p.cancelSynthesis()
                    if (!playbackCompleted) stopPlayback()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS failed", e)
            store.addNote("tts error: ${e.message}")
            micPaused = false; returnToRest(); return
        }
        val presentedMs = firstPresentationMs.get().takeIf { it > 0 } ?: playbackStartMs
        val perf = if (playbackPerformanceMode == android.media.AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) {
            "low-latency"
        } else {
            "normal"
        }
        Log.i(TAG, "tts model-callback ${firstChunkMs.get()}ms · playback-start ${playbackStartMs}ms " +
            "· first-presentation ${presentedMs}ms · app-pieces=${pieces.size} " +
            "· native-frames=$nativeChunks · audio ${"%.1f".format(audioSecTotal)}s " +
            "· output=$perf buffer=$playbackBufferBytes underruns=$playbackUnderruns · " +
            "round ${System.currentTimeMillis() - turnAnchorMs}ms total")
        micPaused = false
        returnToRest()
    }

    // -----------------------------------------------------------------------
    // Device actions — the real Android bindings behind the tools
    // -----------------------------------------------------------------------

    private inner class AndroidDeviceActions : DeviceActions {
        @Volatile var nowPlaying: String? = null
        @Volatile var lastCall: String? = null
        @Volatile var lastContact: String? = null
        private var audioFocus: AudioFocusRequest? = null

        override val isMusicPlaying: Boolean
            get() = try { mediaPlayer?.isPlaying == true } catch (_: Exception) { false }
        val volumeLevel: Int
            get() {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                return if (max > 0)
                    (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 10f / max).roundToInt()
                else 0
            }

        override fun lookupContact(name: String): Contact? {
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
                store.addNote("contacts permission not granted"); return null
            }
            return try {
                contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ),
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                    arrayOf("%$name%"),
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                )?.use { cursor ->
                    if (cursor.moveToFirst())
                        Contact(cursor.getString(0), cursor.getString(1))
                            .also { lastContact = "${it.name} · ${it.number}" }
                    else null
                }
            } catch (e: SecurityException) { Log.w(TAG, "contact lookup denied", e); null }
        }

        override fun dial(number: String) {
            lastCall = number
            startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        override fun playMusic(query: String?): Track? {
            if (checkSelfPermission(mediaPermission) != PackageManager.PERMISSION_GRANTED) {
                store.addNote("media permission not granted"); return null
            }
            val selection = query?.let {
                "(${MediaStore.Audio.Media.TITLE} LIKE ? OR ${MediaStore.Audio.Media.ARTIST} LIKE ?)"
            }
            val args = query?.let { arrayOf("%$it%", "%$it%") }
            return try {
                contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST),
                    selection, args, MediaStore.Audio.Media.TITLE,
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return null
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                    val title = cursor.getString(1) ?: "track"
                    val artist = cursor.getString(2)
                        ?.takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING }
                    stopMusic()

                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                    val am = getSystemService(AUDIO_SERVICE) as AudioManager
                    // Without focus, Samsung routes media playback nowhere while
                    // the assistant's TTS stream is active — the track "plays"
                    // silently. Take focus so it reaches the speaker.
                    val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attrs).build()
                    am.requestAudioFocus(focus)
                    audioFocus = focus

                    // "Play music" with the media stream at 0 just produces
                    // silence (playback is muted, not stopped). Nudge it to
                    // audible so the command actually does something; if the
                    // user already set a level, leave it.
                    val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol * 0.6f).toInt(), 0)
                    }

                    mediaPlayer = MediaPlayer().apply {
                        setAudioAttributes(attrs)
                        setOnErrorListener { _, what, extra ->
                            Log.e(TAG, "MediaPlayer error what=$what extra=$extra"); false
                        }
                        // Clean up when the track ends so isMusicPlaying (which
                        // gates the stop_music tool) reflects reality.
                        setOnCompletionListener { stopMusic() }
                        setDataSource(this@ControlAgentActivity, uri)
                        prepare()
                        start()
                    }
                    Log.i(TAG, "music start: '$title' playing=${mediaPlayer?.isPlaying} " +
                        "vol=${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/$maxVol")

                    Track(title, artist).also {
                        nowPlaying = if (artist != null) "$title · $artist" else title
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "playMusic failed", e); null }
        }

        override fun listMusic(query: String?): List<Track> {
            if (checkSelfPermission(mediaPermission) != PackageManager.PERMISSION_GRANTED) {
                store.addNote("media permission not granted"); return emptyList()
            }
            return listMusicOrEmpty(query)
        }

        /**
         * [listMusic] without the feed note. For callers where an empty library
         * is a normal outcome rather than a failed command — STT biasing runs
         * at startup, possibly before the permission dialog has been answered.
         */
        fun listMusicOrEmpty(query: String?): List<Track> {
            if (checkSelfPermission(mediaPermission) != PackageManager.PERMISSION_GRANTED) {
                return emptyList()
            }
            val selection = query?.let {
                "(${MediaStore.Audio.Media.TITLE} LIKE ? OR ${MediaStore.Audio.Media.ARTIST} LIKE ?)"
            }
            val args = query?.let { arrayOf("%$it%", "%$it%") }
            return try {
                contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST),
                    selection, args, MediaStore.Audio.Media.TITLE,
                )?.use { cursor ->
                    val tracks = mutableListOf<Track>()
                    while (cursor.moveToNext() && tracks.size < 25) {
                        val title = cursor.getString(0) ?: continue
                        val artist = cursor.getString(1)
                            ?.takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING }
                        tracks.add(Track(title, artist))
                    }
                    tracks
                } ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "listMusic failed", e); emptyList()
            }
        }

        override fun stopMusic() {
            mediaPlayer?.let { mp ->
                mediaPlayer = null
                try { mp.stop() } catch (_: Exception) {}
                mp.release()
            }
            audioFocus?.let {
                audioFocus = null
                (getSystemService(AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it)
            }
            nowPlaying = null
        }

        override fun setVolume(level: Int) {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC,
                (level * max / 10f).roundToInt().coerceIn(0, max), 0)
        }
    }

    // -----------------------------------------------------------------------
    // Audio out (agent speech)
    // -----------------------------------------------------------------------

    private fun acquireSpeechPlayer(sampleRate: Int): StreamingPcmPlayer =
        synchronized(speechPlayerLock) {
            speechPlayer?.also {
                check(it.sampleRate == sampleRate) {
                    "TTS sample rate changed: ${it.sampleRate} -> $sampleRate"
                }
            } ?: StreamingPcmPlayer(sampleRate).also { speechPlayer = it }
        }

    private fun stopPlayback() {
        val player = synchronized(speechPlayerLock) {
            speechPlayer.also { speechPlayer = null }
        } ?: return
        player.close()
    }

    // -----------------------------------------------------------------------
    // Microphone
    // -----------------------------------------------------------------------

    private fun toggleMicrophone() {
        if (recording) {
            micRequested = false
            stopMicrophone()
            store.setMic(MicState.IDLE)
            store.setStatus("tap to talk")
        } else {
            micRequested = true
            // Normally already granted from onCreate; re-asking covers a user
            // who dismissed the dialog during setup. Android returns an instant
            // denial once a permission is permanently denied, and
            // onRequestPermissionsResult still starts the mic if RECORD_AUDIO
            // itself came through, so the button never becomes inert.
            if (missingPermissions().isNotEmpty()) { requestDemoPermissions(); return }
            startMicrophone()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, results: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != REQ_PERMISSIONS) return

        // Biasing was built during startup, when the media permission was
        // likely still unanswered and the library read as empty. Now that there
        // is an answer, rebuild it — otherwise track titles never reach the
        // decoder until the app is restarted.
        if (permissions.contains(mediaPermission) &&
            checkSelfPermission(mediaPermission) == PackageManager.PERMISSION_GRANTED) {
            applyContextPhrases()
        }
        // Only start capturing if the user asked for it: onCreate requests
        // permissions before any mic tap, and a grant there must not switch the
        // microphone on by itself.
        if (micRequested &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startMicrophone()
        }
    }

    /** Bench hook: stream a recorded mic_debug PCM file through the live
     *  pipeline (bit-identical input across model/quant configs). ~4x
     *  realtime; VAD timing derives from chunk count, not wallclock. */
    private fun runReplay(name: String) {
        val p = pipeline ?: return
        val safeName = name.length in 1..128 && name.endsWith(".pcm") &&
            name.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        if (!safeName) {
            store.addNote("replay: invalid file name")
            return
        }
        if (!replayInFlight.compareAndSet(false, true)) {
            store.addNote("replay: already running")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = java.io.File(java.io.File(filesDir, "mic_debug"), name)
                if (!file.exists()) {
                    store.addNote("replay: $name not found")
                    return@launch
                }
                micGain.reset()
                store.addNote("replay: $name gain=$micGainEnabled stt=${activeStt()}")
                Log.i(TAG, "REPLAY start $name gain=$micGainEnabled stt=${activeStt()}")
                val bytes = file.readBytes()
                val total = bytes.size / 2
                val buf = FloatArray(512)
                var index = 0
                while (index < total && replayInFlight.get()) {
                    val n = minOf(512, total - index)
                    for (j in 0 until n) {
                        val lo = bytes[2 * (index + j)].toInt() and 0xFF
                        val hi = bytes[2 * (index + j) + 1].toInt()
                        buf[j] = ((hi shl 8) or lo) / 32768f
                    }
                    if (n < 512) java.util.Arrays.fill(buf, n, 512, 0f)
                    if (micGainEnabled) micGain.process(buf, n)
                    p.pushAudio(buf)
                    index += n
                    // 512 samples = 32 ms of audio → push at ~1x real time so
                    // heavier decoders (EOU beam search) get their real-time
                    // budget. Pushing faster (was 8 ms = 4x) backs the beam up
                    // and the pipeline truncates finals — a fixture artifact,
                    // not a decoder bug.
                    delay(32)
                }
                java.util.Arrays.fill(buf, 0f)
                repeat(40) { p.pushAudio(buf); delay(32) }
                Log.i(TAG, "REPLAY done $name")
            } finally {
                replayInFlight.set(false)
            }
        }
    }

    private fun startMicrophone() {
        val p = pipeline ?: return
        // Permissions are revocable while the app is running. Guard again at
        // the point of use instead of relying only on toggleMicrophone().
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            store.addNote("microphone permission not granted")
            store.setMic(MicState.IDLE)
            store.setStatus("tap to talk")
            return
        }
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (bufferSize <= 0) { store.addNote("mic unavailable (code $bufferSize)"); return }
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, bufferSize)
        } catch (e: SecurityException) {
            Log.w(TAG, "microphone permission revoked", e)
            store.addNote("microphone permission not granted")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release(); store.addNote("mic init failed"); return
        }
        audioRecord = record
        recording = true
        micGain.reset()
        // Duck our own playback while listening: full-volume music over the
        // mic is the main SNR killer for speak-over-music commands.
        try { mediaPlayer?.setVolume(0.25f, 0.25f) } catch (_: Exception) {}
        try {
            record.startRecording()
        } catch (e: SecurityException) {
            Log.w(TAG, "microphone permission revoked before recording", e)
            recording = false
            audioRecord = null
            record.release()
            store.addNote("microphone permission not granted")
            return
        }
        store.setMic(MicState.LISTENING)
        store.setStatus("listening")
        if (micDebugEnabled) {
            try {
                val dir = java.io.File(filesDir, "mic_debug").apply { mkdirs() }
                dir.listFiles()?.sortedBy { it.name }?.dropLast(4)?.forEach { it.delete() }
                val file = java.io.File(dir, "mic_${System.currentTimeMillis()}.pcm")
                micDebugStream = java.io.FileOutputStream(file)
                store.addNote("mic debug: recording to ${file.name} (16 kHz mono s16le)")
            } catch (e: Exception) {
                Log.w(TAG, "mic debug open failed", e)
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val buffer = FloatArray(512)
            val debugBytes = ByteArray(buffer.size * 2)
            var deadReads = 0
            while (recording) {
                val read = try {
                    audioRecord?.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) ?: 0
                } catch (_: IllegalStateException) { break }
                if (read > 0) micDebugStream?.let { stream ->
                    // Full-session tap: every captured sample, even while the
                    // agent is speaking (micPaused), so end-of-utterance and
                    // echo behavior can be analyzed offline.
                    try {
                        var b = 0
                        for (i in 0 until read) {
                            val s = (buffer[i].coerceIn(-1f, 1f) * 32767f).toInt()
                            debugBytes[b++] = (s and 0xFF).toByte()
                            debugBytes[b++] = ((s shr 8) and 0xFF).toByte()
                        }
                        stream.write(debugBytes, 0, read * 2)
                    } catch (e: Exception) {
                        Log.w(TAG, "mic debug write failed", e)
                        if (micDebugStream === stream) micDebugStream = null
                        runCatching { stream.close() }
                    }
                }
                if (read > 0 && !micPaused) {
                    deadReads = 0
                    if (micGainEnabled) micGain.process(buffer, read)
                    p.pushAudio(buffer)
                    var peak = 0f
                    for (i in 0 until read) { val a = abs(buffer[i]); if (a > peak) peak = a }
                    store.setMicLevel(peak)
                } else if (micPaused) {
                    store.setMicLevel(0f)
                } else if (read <= 0 && ++deadReads > 40) {
                    // Blocking reads returning nothing: the input device died
                    // underneath us (seen with QEMU audio after playback).
                    Log.w(TAG, "mic delivering no audio — ending session")
                    break
                }
            }
            // If the loop ended while the session was still supposed to be
            // live, resync state so the UI never shows a dead "listening".
            if (recording) {
                recording = false
                runCatching { audioRecord?.release() }
                audioRecord = null
                store.setMicLevel(0f)
                store.setMic(MicState.IDLE)
                store.setStatus("mic ended — tap to talk")
                store.addNote("mic session ended unexpectedly — tap the orb to restart")
            }
        }
    }

    private fun stopMicrophone() {
        recording = false
        try { mediaPlayer?.setVolume(1f, 1f) } catch (_: Exception) {}
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        store.setMicLevel(0f)
        micDebugStream?.let { stream ->
            micDebugStream = null
            try { stream.close() } catch (_: Exception) {}
        }
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override fun onStop() {
        super.onStop()
        if (recording) {
            stopMicrophone()
            store.setMic(MicState.IDLE)
            store.setStatus("tap to talk")
        }
        micPaused = false
        stopPlayback()
    }

    override fun onDestroy() {
        stopMicrophone()
        stopPlayback()
        device.stopMusic()
        memory.stop()
        pipeline?.stop()
        pipeline?.close()
        llmRuntime?.close()
        super.onDestroy()
    }
}
