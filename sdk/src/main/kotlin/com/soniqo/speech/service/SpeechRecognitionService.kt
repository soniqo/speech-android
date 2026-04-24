package audio.soniqo.speech.service

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
 * push audio. `EXTRA_LANGUAGE` is currently logged but not enforced; Parakeet
 * v3 auto-detects across 114 languages.
 */
class SpeechRecognitionService : RecognitionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var session: Session? = null

    private class Session(
        val pipeline: SpeechPipeline,
        val audioRecord: AudioRecord,
        val micJob: Job,
        val eventJob: Job,
    )

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        if (session != null) {
            listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }

        val wantPartial = recognizerIntent
            ?.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false) ?: false
        val requestedLang = recognizerIntent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
        if (requestedLang != null) {
            Log.i(TAG, "EXTRA_LANGUAGE=$requestedLang (auto-detected by STT, hint not enforced)")
        }

        scope.launch { startSession(listener, wantPartial) }
    }

    private suspend fun startSession(listener: Callback, wantPartial: Boolean) {
        val pipeline: SpeechPipeline
        val record: AudioRecord
        try {
            val modelDir = ModelManager.ensureModels(this, ModelPrecision.INT8)
            pipeline = SpeechPipeline(
                SpeechConfig(
                    modelDir = modelDir,
                    emitPartialTranscriptions = wantPartial,
                )
            )

            val sampleRate = 16_000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT
            )
            if (minBuf <= 0) {
                listener.error(SpeechRecognizer.ERROR_AUDIO)
                pipeline.close()
                return
            }
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                minBuf * 4,
            )
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
        listener.readyForSpeech(Bundle.EMPTY)

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
        // Stop reading the mic but let the pipeline flush so VAD detects
        // end-of-utterance and emits the final TranscriptionCompleted event.
        val s = session ?: return
        s.micJob.cancel()
        runCatching { s.audioRecord.stop() }
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

    companion object {
        private const val TAG = "SoniqoRecognition"
    }
}
