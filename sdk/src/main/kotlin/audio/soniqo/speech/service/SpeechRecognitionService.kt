package audio.soniqo.speech.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextParams
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.work.WorkInfo
import androidx.work.WorkManager
import audio.soniqo.speech.ModelDownloadWorker
import audio.soniqo.speech.ModelManager
import audio.soniqo.speech.ModelPrecision
import audio.soniqo.speech.SpeechConfig
import audio.soniqo.speech.SpeechEvent
import audio.soniqo.speech.SpeechPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exposes [SpeechPipeline] via Android's [RecognitionService] API so any app
 * using [SpeechRecognizer] (Gboard, Duolingo, the system voice input picker)
 * can invoke fully on-device STT.
 *
 * Register in your app's manifest with intent filter
 * `android.speech.RecognitionService` and a `@xml/recognition_service`
 * meta-data resource. The user can then select this app under
 * Settings → System → Languages & input → Voice input.
 *
 * The service reads the microphone itself via [AudioRecord] — callers do not
 * push audio. `EXTRA_LANGUAGE` is currently logged but not enforced; the
 * default Parakeet-EOU model covers the languages advertised below.
 *
 * Open for test subclassing: [createPipeline], [resolveModelDir], and
 * [newAudioRecord] are protected seams so JVM unit tests can run without
 * loading the native library or opening a real microphone.
 */
open class SpeechRecognitionService : RecognitionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var session: Session? = null

    // Synchronously claimed in onStartListening before the suspending setup
    // begins, so a second start request rejects with BUSY instead of racing to
    // create a parallel session that leaks AudioRecord and the pipeline.
    private val starting = AtomicBoolean(false)

    private class Session(
        val pipeline: SpeechPipeline,
        val audioRecord: AudioRecord,
        val micJob: Job,
        val eventJob: Job,
    )

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }
        if (session != null || !starting.compareAndSet(false, true)) {
            listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }

        val wantPartial = recognizerIntent
            ?.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false) ?: false
        val requestedLang = recognizerIntent?.let(::requestedLanguageTag)
        if (requestedLang != null) {
            Log.i(TAG, "EXTRA_LANGUAGE=$requestedLang")
        }
        if (requestedLang != null && !isSupportedLanguageTag(requestedLang)) {
            Log.i(TAG, "unsupported requested language: $requestedLang")
            starting.set(false)
            listener.error(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
            return
        }
        if (!areRecognitionModelsReady()) {
            Log.i(TAG, "models not ready for startListening; scheduling download")
            runCatching { enqueueRecognitionModelDownload(recognizerIntent) }
                .onFailure { Log.w(TAG, "failed to enqueue model download: ${it.message}") }
            starting.set(false)
            listener.error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
            return
        }

        scope.launch {
            try {
                startSession(listener, wantPartial)
            } finally {
                starting.set(false)
            }
        }
    }

    private suspend fun startSession(
        listener: Callback,
        wantPartial: Boolean,
    ) {
        val pipeline: SpeechPipeline
        val record: AudioRecord
        try {
            val modelDir = resolveModelDir()
            pipeline = createPipeline(
                SpeechConfig(
                    modelDir = modelDir,
                    // Parakeet-EOU accepts no language prompt. The framework
                    // request is still checked against its supported language
                    // set above, then the model performs automatic detection.
                    language = "auto",
                    emitPartialTranscriptions = wantPartial,
                )
            )

            val newRecord = newAudioRecord(newAudioRecordContext(listener))
            if (newRecord == null) {
                listener.error(SpeechRecognizer.ERROR_AUDIO)
                pipeline.close()
                return
            }
            record = newRecord
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                listener.error(SpeechRecognizer.ERROR_AUDIO)
                record.release()
                pipeline.close()
                return
            }
        } catch (t: Throwable) {
            Log.e(TAG, "failed to initialize pipeline", t)
            listener.error(mapInitError(t))
            return
        }

        pipeline.start()
        record.startRecording()

        val eventJob = scope.launch {
            pipeline.events.collect { ev -> handleEvent(ev, listener) }
        }

        val micJob = scope.launch(Dispatchers.IO) {
            val buf = FloatArray(512)
            while (isActive) {
                val n = record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n > 0) {
                    val samples = if (n == buf.size) buf else buf.copyOf(n)
                    pipeline.pushAudio(samples)
                } else if (n < 0) {
                    Log.w(TAG, "AudioRecord.read returned $n")
                    break
                }
            }
        }

        session = Session(pipeline, record, micJob, eventJob)
        listener.readyForSpeech(Bundle.EMPTY)
    }

    private fun handleEvent(event: SpeechEvent, listener: Callback) {
        when (event) {
            is SpeechEvent.SpeechStarted -> safeCallback { listener.beginningOfSpeech() }

            is SpeechEvent.SpeechEnded -> safeCallback { listener.endOfSpeech() }

            is SpeechEvent.PartialTranscription -> {
                if (event.text.isEmpty()) return
                val bundle = Bundle().apply {
                    putStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION,
                        arrayListOf(event.text),
                    )
                }
                safeCallback { listener.partialResults(bundle) }
            }

            is SpeechEvent.TranscriptionCompleted -> {
                val bundle = Bundle().apply {
                    putStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION,
                        arrayListOf(event.text),
                    )
                    putFloatArray(
                        SpeechRecognizer.CONFIDENCE_SCORES,
                        floatArrayOf(event.confidence),
                    )
                }
                safeCallback { listener.results(bundle) }
                tearDownSession()
            }

            is SpeechEvent.Error -> {
                Log.e(TAG, "pipeline error: ${event.message}")
                safeCallback { listener.error(SpeechRecognizer.ERROR_SERVER) }
                tearDownSession()
            }

            else -> Unit
        }
    }

    override fun onStopListening(listener: Callback) {
        // nativeStop does not flush — VAD only detects end-of-utterance from
        // silence in the audio stream. After cutting the mic, push ~1 s of
        // zeros so the pipeline emits its final TranscriptionCompleted event.
        val s = session ?: return
        runCatching { s.audioRecord.stop() }
        scope.launch {
            s.micJob.cancelAndJoin()
            val silence = FloatArray(512)
            repeat(32) { // 32 × 32 ms ≈ 1 s @ 16 kHz
                if (session !== s) return@launch
                runCatching { s.pipeline.pushAudio(silence) }
                delay(32)
            }
        }
    }

    override fun onCancel(listener: Callback) {
        tearDownSession()
    }

    private fun tearDownSession() {
        val s = session ?: return
        session = null
        runCatching { s.eventJob.cancel() }
        runCatching { s.micJob.cancel() }
        runCatching { s.audioRecord.stop() }
        runCatching { s.audioRecord.release() }
        runCatching { s.pipeline.stop() }
        runCatching { s.pipeline.close() }
    }

    override fun onDestroy() {
        tearDownSession()
        scope.cancel()
        super.onDestroy()
    }

    private inline fun safeCallback(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "callback delivery failed: ${e.message}")
        }
    }

    private fun mapInitError(t: Throwable): Int = when (t) {
        is java.io.IOException -> SpeechRecognizer.ERROR_NETWORK
        is SecurityException -> SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
        else -> SpeechRecognizer.ERROR_SERVER
    }

    /** Build the pipeline. Overridden in tests to inject a fake. */
    protected open fun createPipeline(config: SpeechConfig): SpeechPipeline =
        SpeechPipeline(config)

    /** Cheap readiness check used before binding a third-party recognition request. */
    protected open fun areRecognitionModelsReady(): Boolean =
        ModelManager.areModelsReady(applicationContext, ModelPrecision.INT8)

    /** Schedule model download without blocking the current framework request. */
    protected open fun enqueueRecognitionModelDownload(recognizerIntent: Intent?): java.util.UUID =
        ModelDownloadWorker.enqueue(applicationContext, ModelPrecision.INT8)

    /**
     * Resolve the model directory. If models aren't on disk yet we delegate
     * to [ModelDownloadWorker] (which runs as a foreground service so the
     * download survives the bind from Gboard timing out) and suspend until
     * it reports a terminal state. Suspension is bound to this session's
     * coroutine — if the framework cancels the request, the worker keeps
     * running on its own and serves the *next* invocation immediately.
     *
     * Overridden in tests to skip the download.
     */
    protected open suspend fun resolveModelDir(): String {
        val ctx = applicationContext
        if (areRecognitionModelsReady()) {
            return ModelManager.modelDir(ctx)
        }
        Log.i(TAG, "models not ready — delegating to ModelDownloadWorker")
        val workId = enqueueRecognitionModelDownload(null)
        val info = WorkManager.getInstance(ctx)
            .getWorkInfoByIdFlow(workId)
            .filterNotNull()
            .first { it.state.isFinished }
        return when (info.state) {
            WorkInfo.State.SUCCEEDED -> info.outputData
                .getString(ModelDownloadWorker.KEY_MODEL_DIR)
                ?: throw IllegalStateException("worker succeeded but no model dir")
            WorkInfo.State.FAILED -> throw IOException(
                info.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                    ?: "model download failed",
            )
            WorkInfo.State.CANCELLED -> throw IllegalStateException("model download cancelled")
            else -> throw IllegalStateException("unexpected worker state: ${info.state}")
        }
    }

    /**
     * Open the microphone. Returns null when the format is unsupported on this
     * device (negative [AudioRecord.getMinBufferSize]). Overridden in tests so
     * Robolectric doesn't have to stand up a real recorder.
     */
    @SuppressLint("MissingPermission") // onStartListening has already checked the permissions.
    protected open fun newAudioRecord(context: Context): AudioRecord? {
        val sampleRate = 16_000
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        if (minBuf <= 0) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return AudioRecord.Builder()
                .setContext(context)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 4)
                .build()
        }
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            minBuf * 4,
        )
    }

    /**
     * Attribute microphone access to the app that requested recognition. This
     * matches the RecognitionService contract on Android 12+ and avoids
     * stricter clients treating the recognizer as an unrelated mic user.
     */
    protected open fun newAudioRecordContext(listener: Callback): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
        return createContext(
            ContextParams.Builder()
                .setNextAttributionSource(listener.callingAttributionSource)
                .build()
        )
    }

    // -- onCheckRecognitionSupport (API 33+) --------------------------------

    /**
     * Tell the framework which BCP-47 languages we can recognize on-device.
     * If the models are already present we report them as installed; otherwise
     * we report them as supported so the caller can trigger model download
     * instead of falling back to an online recognizer.
     *
     * The list mirrors Parakeet-EOU-120M's published 25-language coverage.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCheckRecognitionSupport(
        recognizerIntent: Intent,
        listener: SupportCallback,
    ) {
        val languages = supportLanguagesFor(recognizerIntent)
        if (languages.isEmpty()) {
            listener.onError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
            return
        }
        val builder = RecognitionSupport.Builder()
        val ready = areRecognitionModelsReady()
        languages.forEach { tag ->
            if (ready) builder.addInstalledOnDeviceLanguage(tag)
            else builder.addSupportedOnDeviceLanguage(tag)
        }
        listener.onSupportResult(builder.build())
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onTriggerModelDownload(recognizerIntent: Intent) {
        if (supportLanguagesFor(recognizerIntent).isNotEmpty()) {
            enqueueRecognitionModelDownload(recognizerIntent)
        }
    }

    companion object {
        private const val TAG = "SoniqoRecognition"

        /**
         * BCP-47 tags advertised via [onCheckRecognitionSupport]. Keep this
         * in sync with the default STT model in [SpeechConfig].
         */
        @JvmField
        val SUPPORTED_LANGUAGES: List<String> = listOf(
            "bg", "cs", "da", "de", "el", "en", "es", "et",
            "fi", "fr", "hr", "hu", "it", "lt", "lv", "mt",
            "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv",
            "uk",
        )

        private val SUPPORTED_LANGUAGE_BASES: Set<String> =
            SUPPORTED_LANGUAGES.mapTo(linkedSetOf()) { it.lowercase(Locale.US) }

        internal fun isSupportedLanguageTag(tag: String): Boolean =
            languageBaseTag(tag) in SUPPORTED_LANGUAGE_BASES

        internal fun supportLanguagesFor(intent: Intent): List<String> {
            val requested = requestedLanguageTag(intent) ?: return SUPPORTED_LANGUAGES
            if (!isSupportedLanguageTag(requested)) return emptyList()
            val canonical = canonicalLanguageTag(requested)
            val base = languageBaseTag(requested)
            return listOf(canonical, base).distinct()
        }

        private fun requestedLanguageTag(intent: Intent): String? =
            listOf(
                intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE),
                intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE),
            ).firstNotNullOfOrNull { tag -> tag?.trim()?.takeIf { it.isNotEmpty() } }

        internal fun languageHintTags(intent: Intent): List<String> {
            val hints = linkedSetOf<String>()
            requestedLanguageTag(intent)?.let { hints += languageBaseTag(it) }

            listOf(
                RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
            ).forEach { extra ->
                intent.getStringArrayListExtra(extra)
                    .orEmpty()
                    .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
                    .filter(::isSupportedLanguageTag)
                    .mapTo(hints, ::languageBaseTag)
            }
            return hints.toList()
        }

        internal fun canonicalLanguageTag(tag: String): String {
            val normalized = tag.trim().replace('_', '-')
            val locale = Locale.forLanguageTag(normalized)
            return locale.takeIf { it.language.isNotBlank() }?.toLanguageTag() ?: normalized
        }

        internal fun languageBaseTag(tag: String): String {
            val canonical = canonicalLanguageTag(tag)
            val locale = Locale.forLanguageTag(canonical)
            return locale.language.takeIf { it.isNotBlank() }
                ?.lowercase(Locale.US)
                ?: canonical.substringBefore('-').lowercase(Locale.US)
        }
    }
}
