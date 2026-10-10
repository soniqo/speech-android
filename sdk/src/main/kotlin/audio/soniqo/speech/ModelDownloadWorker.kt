package audio.soniqo.speech

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException

/**
 * Downloads the speech models in a foreground worker so the transfer survives
 * app backgrounding and process death. Wraps [ModelManager.ensureModels] —
 * resumes partial downloads via the same on-disk `.tmp` files, retries on
 * `IOException`, and reports progress via [setProgress].
 *
 * Besides the pipeline set it can fetch the standalone meeting-transcription
 * sets — Silero VAD, the [StreamingTranscriber] recognizer, the
 * [SpeakerDiarizer] and the [SpeakerEmbedder] — in the same worker and on the
 * same progress bar. Pass `includePipeline = false` to fetch only those.
 *
 * ### Usage
 *
 * ```
 * WorkManager.getInstance(context).enqueueUniqueWork(
 *     ModelDownloadWorker.UNIQUE_NAME,
 *     ExistingWorkPolicy.KEEP,
 *     ModelDownloadWorker.request(ModelPrecision.INT8),
 * )
 *
 * WorkManager.getInstance(context)
 *     .getWorkInfosForUniqueWorkLiveData(ModelDownloadWorker.UNIQUE_NAME)
 *     .observe(this) { infos ->
 *         val info = infos.firstOrNull() ?: return@observe
 *         when (info.state) {
 *             WorkInfo.State.RUNNING -> {
 *                 val pct = info.progress.getInt(ModelDownloadWorker.KEY_PERCENT, 0)
 *                 ...
 *             }
 *             WorkInfo.State.SUCCEEDED -> {
 *                 val dir = info.outputData.getString(ModelDownloadWorker.KEY_MODEL_DIR)
 *                 ...
 *             }
 *             else -> Unit
 *         }
 *     }
 * ```
 *
 * Requires the host app to declare `POST_NOTIFICATIONS` (API 33+) for the
 * progress notification to appear; the worker still runs without it.
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    /** One ensure*() call of the download, with its share of the byte bar. */
    private class Phase(
        val plannedBytes: Long,
        /** Output key for the directory this phase returns, or null. */
        val outputKey: String?,
        val download: suspend (report: (ModelManager.Progress) -> Unit) -> String,
    )

    override suspend fun doWork(): Result {
        val precision = inputData.getString(KEY_PRECISION)
            ?.let { runCatching { ModelPrecision.valueOf(it) }.getOrNull() }
            ?: ModelPrecision.INT8
        val sttModel = inputData.getString(KEY_STT_MODEL)
            ?.let { runCatching { SttModel.valueOf(it) }.getOrNull() }
            ?: SttModel.PARAKEET_EOU
        val sttBackend = inputData.getString(KEY_STT_BACKEND)
            ?.let { runCatching { SttBackend.valueOf(it) }.getOrNull() }
            ?: SttBackend.ONNX
        val ttsModel = inputData.getString(KEY_TTS_MODEL)
            ?.let { runCatching { TtsModel.valueOf(it) }.getOrNull() }
            ?: TtsModel.KOKORO_SHORT_TURN
        val llmModel = inputData.getString(KEY_LLM_MODEL)
            ?.let { runCatching { LlmModel.valueOf(it) }.getOrNull() }
            ?: LlmModel.FUNCTIONGEMMA
        // When set, the FunctionGemma bundle is downloaded here too, so the
        // whole ~800 MB setup runs in this foreground worker — surviving doze
        // and Wi-Fi power-save that would kill an in-app download.
        val includeLlm = inputData.getBoolean(KEY_INCLUDE_LLM, false)
        val supertonicLatentBuckets = inputData.getBoolean(KEY_SUPERTONIC_LATENT_BUCKETS, false)
        val enableSmartTurn = inputData.getBoolean(KEY_ENABLE_SMART_TURN, false)
        val enableEnhancer = inputData.getBoolean(KEY_ENABLE_ENHANCER, true)
        val includePipeline = inputData.getBoolean(KEY_INCLUDE_PIPELINE, true)
        val includeVad = inputData.getBoolean(KEY_INCLUDE_VAD, false)
        val includeTranscriber = inputData.getBoolean(KEY_INCLUDE_TRANSCRIBER, false)
        val transcriberBackend = inputData.getString(KEY_TRANSCRIBER_BACKEND)
            ?.let { runCatching { SttBackend.valueOf(it) }.getOrNull() }
            ?: SttBackend.LITERT
        val transcriberPrecision = inputData.getString(KEY_TRANSCRIBER_PRECISION)
            ?.let { runCatching { ModelPrecision.valueOf(it) }.getOrNull() }
            ?: ModelPrecision.INT8
        val includeDiarizer = inputData.getBoolean(KEY_INCLUDE_DIARIZER, false)
        val includeSpeakerEmbedding = inputData.getBoolean(KEY_INCLUDE_SPEAKER_EMBEDDING, false)

        runCatching { setForeground(buildForegroundInfo(0, "Preparing speech models…")) }

        // Every requested set is one back-to-back ensure*() call, each
        // reporting bytes only for its own set. Planning them all up front
        // lets them render as one continuous 0→100 bar instead of sweeps that
        // visibly reset to zero in between.
        val context = applicationContext
        val phases = buildList {
            if (includePipeline) {
                add(Phase(
                    plannedBytes = ModelManager.plannedModelBytes(
                        context, precision, sttModel, sttBackend, ttsModel,
                        enableSmartTurn, enableEnhancer,
                    ),
                    outputKey = KEY_MODEL_DIR,
                ) { report ->
                    ModelManager.ensureModels(
                        context,
                        precision = precision,
                        sttModel = sttModel,
                        sttBackend = sttBackend,
                        ttsModel = ttsModel,
                        onProgress = report,
                        supertonicLatentBuckets = supertonicLatentBuckets,
                        enableSmartTurn = enableSmartTurn,
                        enableEnhancer = enableEnhancer,
                    )
                })
            }
            if (includeVad) {
                add(Phase(ModelManager.plannedVadBytes(context), KEY_VAD_MODEL_DIR) { report ->
                    ModelManager.ensureVadModels(context, onProgress = report)
                })
            }
            if (includeTranscriber) {
                add(Phase(
                    plannedBytes = ModelManager.plannedTranscriberBytes(
                        context, transcriberBackend, transcriberPrecision,
                    ),
                    outputKey = KEY_TRANSCRIBER_MODEL_DIR,
                ) { report ->
                    ModelManager.ensureTranscriberModels(
                        context,
                        backend = transcriberBackend,
                        precision = transcriberPrecision,
                        onProgress = report,
                    )
                })
            }
            if (includeDiarizer) {
                add(Phase(ModelManager.plannedDiarizerBytes(context), KEY_DIARIZER_MODEL_DIR) { report ->
                    ModelManager.ensureDiarizerModels(context, onProgress = report)
                })
            }
            if (includeSpeakerEmbedding) {
                add(Phase(
                    ModelManager.plannedSpeakerEmbeddingBytes(context),
                    KEY_SPEAKER_EMBEDDING_MODEL_DIR,
                ) { report ->
                    ModelManager.ensureSpeakerEmbeddingModels(context, onProgress = report)
                })
            }
            if (includeLlm) {
                add(Phase(ModelManager.plannedLlmBytes(context, llmModel), outputKey = null) { report ->
                    ModelManager.ensureLlmModels(context, llmModel = llmModel, onProgress = report)
                })
            }
        }

        // Bytes attributed to phases that have already finished, and bytes
        // planned for phases not yet started. Both are folded into every
        // sample so the numerator and denominator span the whole download.
        var phaseBase = 0L
        var laterPhases = phases.drop(1).sumOf { it.plannedBytes }

        // Rate is measured from the first sample rather than from bytes
        // already on disk, so resuming a partial download doesn't report an
        // instant multi-hundred-MB/s spike.
        var startedAtNanos = 0L
        var baselineBytes = 0L
        var lastPct = 0
        var lastDone = 0L

        // Only rebuild the foreground notification when the integer percent or
        // the file changes — the underlying progress callback is already
        // throttled to ~1 MB, but re-posting a Notification on every tick still
        // janks the main thread, so we coalesce to visible changes only.
        var lastNotifiedPct = -1
        var lastNotifiedFile = ""
        val report: (ModelManager.Progress) -> Unit = { p ->
            val done = phaseBase + p.totalBytesDownloaded
            val total = phaseBase + p.totalBytes + laterPhases
            lastDone = done

            if (startedAtNanos == 0L) {
                startedAtNanos = System.nanoTime()
                baselineBytes = done
            }
            val elapsedSec = (System.nanoTime() - startedAtNanos) / 1_000_000_000.0
            val transferred = done - baselineBytes
            // Below a second of samples the rate is mostly noise; suppress it
            // rather than show an ETA that swings by minutes.
            val bytesPerSec =
                if (elapsedSec >= 1.0 && transferred > 0) (transferred / elapsedSec).toLong() else 0L
            val etaSec =
                if (bytesPerSec > 0 && total > done) (total - done) / bytesPerSec else -1L

            // Byte-weighted when a total is known, else the legacy file-count
            // estimate. Clamped monotonic: refining the total against a real
            // Content-Length can otherwise nudge the bar backwards.
            val pct = if (total > 0) {
                progressPercent(done, total)
            } else {
                progressPercent(p.completed, p.totalFiles, p.bytesDownloaded, p.fileTotalBytes)
            }.coerceAtLeast(lastPct)
            lastPct = pct

            setProgressAsync(workDataOf(
                KEY_FILE to p.file,
                KEY_COMPLETED to p.completed,
                KEY_TOTAL to p.totalFiles,
                KEY_BYTES_DOWNLOADED to p.bytesDownloaded,
                KEY_FILE_TOTAL_BYTES to p.fileTotalBytes,
                KEY_PERCENT to pct,
                KEY_TOTAL_BYTES_DOWNLOADED to done,
                KEY_TOTAL_BYTES to total,
                KEY_BYTES_PER_SEC to bytesPerSec,
                KEY_ETA_SECONDS to etaSec,
            ))
            if (pct != lastNotifiedPct || p.file != lastNotifiedFile) {
                lastNotifiedPct = pct
                lastNotifiedFile = p.file
                runCatching {
                    setForegroundAsync(buildForegroundInfo(
                        percent = pct,
                        text = detailLine(done, total, bytesPerSec, etaSec),
                    ))
                }
            }
        }

        return try {
            val outputs = mutableListOf<Pair<String, Any?>>()
            phases.forEachIndexed { index, phase ->
                if (index > 0) {
                    // Hand the bar to the next phase: what the finished phase
                    // actually transferred is now behind us. Falls back to its
                    // estimate when it was fully cached and never reported a
                    // sample.
                    phaseBase = if (lastDone > phaseBase) {
                        lastDone
                    } else {
                        phaseBase + phases[index - 1].plannedBytes
                    }
                    laterPhases = phases.drop(index + 1).sumOf { it.plannedBytes }
                }
                val dir = phase.download(report)
                phase.outputKey?.let { outputs += it to dir }
            }
            Result.success(workDataOf(*outputs.toTypedArray()))
        } catch (e: IOException) {
            // Network / disk hiccup — let WorkManager retry with backoff.
            Result.retry()
        } catch (t: Throwable) {
            Result.failure(workDataOf(KEY_ERROR to (t.message ?: t::class.java.simpleName)))
        }
    }

    private fun buildForegroundInfo(percent: Int, text: String): ForegroundInfo {
        ensureChannel()
        val indeterminate = percent <= 0
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Speech models")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, percent.coerceIn(0, 100), indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notif)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ID,
            "Speech model downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Progress for downloading on-device speech models" })
    }

    private fun formatMb(bytes: Long): String =
        if (bytes <= 0) "…" else "%.0f MB".format(bytes / 1_000_000.0)

    companion object {
        /** Pass to [WorkManager.enqueueUniqueWork] to dedupe concurrent downloads. */
        const val UNIQUE_NAME = "audio.soniqo.speech.modelDownload"

        /**
         * Byte-weighted completion percent (0-100) over the whole download.
         *
         * The file-count form below weights a 2 KB `config.json` the same as a
         * 325 MB weights blob, so on the default manifest the bar sprints
         * through fifteen small assets and then appears frozen for minutes on
         * the two files that are ~93% of the transfer. Scaling by bytes makes
         * the bar advance at the rate the network actually delivers.
         *
         * Pure + side-effect free so it is unit-testable.
         */
        fun progressPercent(bytesDownloaded: Long, totalBytes: Long): Int {
            if (totalBytes <= 0L) return 0
            return ((bytesDownloaded.toDouble() / totalBytes) * 100.0)
                .toInt().coerceIn(0, 100)
        }

        /**
         * Human-readable transfer line: `412 / 789 MB · 3.6 MB/s · 2 min left`.
         * Rate and ETA are dropped until there is enough history to make them
         * meaningful, so the text degrades to just the byte counts.
         */
        fun detailLine(
            bytesDownloaded: Long,
            totalBytes: Long,
            bytesPerSec: Long,
            etaSeconds: Long,
        ): String = buildList {
            add(
                if (totalBytes > 0) {
                    "%.0f / %.0f MB".format(
                        bytesDownloaded / 1_000_000.0, totalBytes / 1_000_000.0,
                    )
                } else {
                    "%.0f MB".format(bytesDownloaded / 1_000_000.0)
                }
            )
            if (bytesPerSec > 0) add("%.1f MB/s".format(bytesPerSec / 1_000_000.0))
            if (etaSeconds >= 0) add("${formatEta(etaSeconds)} left")
        }.joinToString(" · ")

        // Minutes round rather than truncate: flooring reports 105 s as
        // "1 min left", which then sits there for nearly two.
        private fun formatEta(seconds: Long): String = when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${(seconds + 30) / 60} min"
            else -> "%.1f h".format(seconds / 3600.0)
        }

        /**
         * Download completion percent (0-100) that advances *continuously* as
         * the current file streams, instead of only when a whole file lands.
         * It is the count of fully-finished files plus the fraction of the
         * file in flight, scaled over the total file count:
         *
         *     pct = ((completed + bytesDownloaded / fileTotalBytes) / totalFiles) * 100
         *
         * This keeps the progress bar moving through large model files
         * instead of appearing stuck at the previous whole-file count. When
         * [fileTotalBytes] is
         * unknown (0) it degrades to the previous whole-file behaviour for
         * that file only. Pure + side-effect free so it is unit-testable.
         */
        fun progressPercent(
            completed: Int,
            totalFiles: Int,
            bytesDownloaded: Long,
            fileTotalBytes: Long,
        ): Int {
            if (totalFiles <= 0) return 0
            val fraction = if (fileTotalBytes > 0) {
                (bytesDownloaded.toDouble() / fileTotalBytes).coerceIn(0.0, 1.0)
            } else 0.0
            return (((completed + fraction) / totalFiles) * 100.0).toInt().coerceIn(0, 100)
        }

        // Input keys
        const val KEY_PRECISION = "precision"
        const val KEY_STT_MODEL = "sttModel"
        const val KEY_STT_BACKEND = "sttBackend"
        const val KEY_TTS_MODEL = "ttsModel"
        const val KEY_INCLUDE_LLM = "includeLlm"
        const val KEY_SUPERTONIC_LATENT_BUCKETS = "supertonicLatentBuckets"
        const val KEY_ENABLE_SMART_TURN = "enableSmartTurn"
        const val KEY_ENABLE_ENHANCER = "enableEnhancer"
        const val KEY_LLM_MODEL = "llmModel"
        /** Download the pipeline set ([ModelManager.ensureModels]); default true. */
        const val KEY_INCLUDE_PIPELINE = "includePipeline"
        const val KEY_INCLUDE_VAD = "includeVad"
        const val KEY_INCLUDE_TRANSCRIBER = "includeTranscriber"
        const val KEY_TRANSCRIBER_BACKEND = "transcriberBackend"
        const val KEY_TRANSCRIBER_PRECISION = "transcriberPrecision"
        const val KEY_INCLUDE_DIARIZER = "includeDiarizer"
        const val KEY_INCLUDE_SPEAKER_EMBEDDING = "includeSpeakerEmbedding"

        // Output keys
        const val KEY_MODEL_DIR = "modelDir"
        const val KEY_VAD_MODEL_DIR = "vadModelDir"
        const val KEY_TRANSCRIBER_MODEL_DIR = "transcriberModelDir"
        const val KEY_DIARIZER_MODEL_DIR = "diarizerModelDir"
        const val KEY_SPEAKER_EMBEDDING_MODEL_DIR = "speakerEmbeddingModelDir"
        const val KEY_ERROR = "error"

        // Progress keys
        const val KEY_FILE = "file"
        const val KEY_COMPLETED = "completed"
        const val KEY_TOTAL = "totalFiles"
        const val KEY_BYTES_DOWNLOADED = "bytesDownloaded"
        const val KEY_FILE_TOTAL_BYTES = "fileTotalBytes"
        const val KEY_PERCENT = "percent"

        /** Bytes transferred so far across the whole download (Long). */
        const val KEY_TOTAL_BYTES_DOWNLOADED = "totalBytesDownloaded"
        /** Estimated size of the whole download (Long); 0 when unknown. */
        const val KEY_TOTAL_BYTES = "totalBytes"
        /** Observed transfer rate (Long, bytes/sec); 0 until it settles. */
        const val KEY_BYTES_PER_SEC = "bytesPerSec"
        /** Seconds remaining at the current rate (Long); -1 when unknown. */
        const val KEY_ETA_SECONDS = "etaSeconds"

        private const val CHANNEL_ID = "audio.soniqo.speech.models"
        // Stable, unlikely-to-collide id (decimal of 0xC0FFEE).
        private const val NOTIFICATION_ID = 12648430

        /**
         * Build a one-shot download request. No JobScheduler network
         * constraint — the underlying OkHttp client surfaces network failures
         * as `IOException`, which the worker translates into `Result.retry()`.
         * Avoids JobScheduler's `CONSTRAINT_CONNECTIVITY` waiting on a
         * `VALIDATED` capability, which can sit unsatisfied for a long time
         * on flaky or captive networks even when the device has working
         * internet.
         */
        fun uniqueName(
            precision: ModelPrecision = ModelPrecision.INT8,
            sttModel: SttModel = SttModel.PARAKEET_EOU,
            sttBackend: SttBackend = SttBackend.ONNX,
            ttsModel: TtsModel = TtsModel.KOKORO_SHORT_TURN,
            includeLlm: Boolean = false,
            llmModel: LlmModel = LlmModel.FUNCTIONGEMMA,
            supertonicLatentBuckets: Boolean = false,
            enableSmartTurn: Boolean = false,
            includePipeline: Boolean = true,
            includeVad: Boolean = false,
            includeTranscriber: Boolean = false,
            transcriberBackend: SttBackend = SttBackend.LITERT,
            transcriberPrecision: ModelPrecision = ModelPrecision.INT8,
            includeDiarizer: Boolean = false,
            includeSpeakerEmbedding: Boolean = false,
            enableEnhancer: Boolean = true,
        ): String {
            val standaloneSets = includeVad || includeTranscriber ||
                includeDiarizer || includeSpeakerEmbedding
            if (
                precision == ModelPrecision.INT8 &&
                sttModel == SttModel.PARAKEET_EOU &&
                sttBackend == SttBackend.ONNX &&
                ttsModel.isKokoro &&
                !includeLlm &&
                !enableSmartTurn &&
                enableEnhancer &&
                includePipeline &&
                !standaloneSets
            ) {
                return UNIQUE_NAME
            }
            val buckets = if (supertonicLatentBuckets && ttsModel == TtsModel.SUPERTONIC) ".buckets" else ""
            val llm = when {
                !includeLlm -> ""
                llmModel == LlmModel.FUNCTIONGEMMA -> ".llm"
                else -> ".llm.${llmModel.name}"
            }
            val ttsName = if (ttsModel.isKokoro) TtsModel.KOKORO.name else ttsModel.name
            val smartTurn = if (enableSmartTurn) ".smartTurn" else ""
            val enhancer = if (enableEnhancer) "" else ".noEnhancer"
            val pipeline = if (includePipeline) {
                ".${precision.name}.${sttModel.name}.${sttBackend.name}.$ttsName$buckets$smartTurn$enhancer"
            } else {
                ".noPipeline"
            }
            val standalone = buildString {
                if (includeVad) append(".vad")
                if (includeTranscriber) {
                    append(".transcriber.${transcriberBackend.name}.${transcriberPrecision.name}")
                }
                if (includeDiarizer) append(".diarizer")
                if (includeSpeakerEmbedding) append(".speakerEmbedding")
            }
            return "$UNIQUE_NAME$pipeline$standalone$llm"
        }

        fun request(
            precision: ModelPrecision = ModelPrecision.INT8,
            sttModel: SttModel = SttModel.PARAKEET_EOU,
            sttBackend: SttBackend = SttBackend.ONNX,
            ttsModel: TtsModel = TtsModel.KOKORO_SHORT_TURN,
            includeLlm: Boolean = false,
            llmModel: LlmModel = LlmModel.FUNCTIONGEMMA,
            supertonicLatentBuckets: Boolean = false,
            enableSmartTurn: Boolean = false,
            includePipeline: Boolean = true,
            includeVad: Boolean = false,
            includeTranscriber: Boolean = false,
            transcriberBackend: SttBackend = SttBackend.LITERT,
            transcriberPrecision: ModelPrecision = ModelPrecision.INT8,
            includeDiarizer: Boolean = false,
            includeSpeakerEmbedding: Boolean = false,
            enableEnhancer: Boolean = true,
        ) =
            OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(
                    KEY_PRECISION to precision.name,
                    KEY_STT_MODEL to sttModel.name,
                    KEY_STT_BACKEND to sttBackend.name,
                    KEY_TTS_MODEL to ttsModel.name,
                    KEY_INCLUDE_LLM to includeLlm,
                    KEY_LLM_MODEL to llmModel.name,
                    KEY_SUPERTONIC_LATENT_BUCKETS to supertonicLatentBuckets,
                    KEY_ENABLE_SMART_TURN to enableSmartTurn,
                    KEY_ENABLE_ENHANCER to enableEnhancer,
                    KEY_INCLUDE_PIPELINE to includePipeline,
                    KEY_INCLUDE_VAD to includeVad,
                    KEY_INCLUDE_TRANSCRIBER to includeTranscriber,
                    KEY_TRANSCRIBER_BACKEND to transcriberBackend.name,
                    KEY_TRANSCRIBER_PRECISION to transcriberPrecision.name,
                    KEY_INCLUDE_DIARIZER to includeDiarizer,
                    KEY_INCLUDE_SPEAKER_EMBEDDING to includeSpeakerEmbedding,
                ))
                .build()

        /**
         * Convenience: enqueue under the standard unique name with
         * [ExistingWorkPolicy.KEEP] (a running download is reused; otherwise a
         * new one starts). Returns the request id so callers can observe it.
         */
        fun enqueue(
            context: Context,
            precision: ModelPrecision = ModelPrecision.INT8,
            sttModel: SttModel = SttModel.PARAKEET_EOU,
            sttBackend: SttBackend = SttBackend.ONNX,
            ttsModel: TtsModel = TtsModel.KOKORO_SHORT_TURN,
            includeLlm: Boolean = false,
            llmModel: LlmModel = LlmModel.FUNCTIONGEMMA,
            supertonicLatentBuckets: Boolean = false,
            enableSmartTurn: Boolean = false,
            includePipeline: Boolean = true,
            includeVad: Boolean = false,
            includeTranscriber: Boolean = false,
            transcriberBackend: SttBackend = SttBackend.LITERT,
            transcriberPrecision: ModelPrecision = ModelPrecision.INT8,
            includeDiarizer: Boolean = false,
            includeSpeakerEmbedding: Boolean = false,
            enableEnhancer: Boolean = true,
        ): java.util.UUID {
            val req = request(
                precision = precision,
                sttModel = sttModel,
                sttBackend = sttBackend,
                ttsModel = ttsModel,
                includeLlm = includeLlm,
                llmModel = llmModel,
                supertonicLatentBuckets = supertonicLatentBuckets,
                enableSmartTurn = enableSmartTurn,
                enableEnhancer = enableEnhancer,
                includePipeline = includePipeline,
                includeVad = includeVad,
                includeTranscriber = includeTranscriber,
                transcriberBackend = transcriberBackend,
                transcriberPrecision = transcriberPrecision,
                includeDiarizer = includeDiarizer,
                includeSpeakerEmbedding = includeSpeakerEmbedding,
            )
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(
                    precision = precision,
                    sttModel = sttModel,
                    sttBackend = sttBackend,
                    ttsModel = ttsModel,
                    includeLlm = includeLlm,
                    llmModel = llmModel,
                    supertonicLatentBuckets = supertonicLatentBuckets,
                    enableSmartTurn = enableSmartTurn,
                    enableEnhancer = enableEnhancer,
                    includePipeline = includePipeline,
                    includeVad = includeVad,
                    includeTranscriber = includeTranscriber,
                    transcriberBackend = transcriberBackend,
                    transcriberPrecision = transcriberPrecision,
                    includeDiarizer = includeDiarizer,
                    includeSpeakerEmbedding = includeSpeakerEmbedding,
                ),
                ExistingWorkPolicy.KEEP, req,
            )
            return req.id
        }
    }
}
