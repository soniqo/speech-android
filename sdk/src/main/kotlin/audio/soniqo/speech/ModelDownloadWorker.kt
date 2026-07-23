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

        runCatching { setForeground(buildForegroundInfo(0, "Preparing speech models…")) }

        // The pipeline models and the LLM bundle are two back-to-back
        // ensure*() calls, each reporting bytes only for its own set. Planning
        // both up front lets them render as one continuous 0→100 bar instead
        // of two sweeps that visibly reset to zero in the middle.
        val plannedPipeline = ModelManager.plannedModelBytes(
            applicationContext, precision, sttModel, sttBackend, ttsModel,
        )
        val plannedLlm =
            if (includeLlm) ModelManager.plannedLlmBytes(applicationContext, llmModel) else 0L

        // Bytes attributed to phases that have already finished, and bytes
        // planned for phases not yet started. Both are folded into every
        // sample so the numerator and denominator span the whole download.
        var phaseBase = 0L
        var laterPhases = plannedLlm

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
            val modelDir = ModelManager.ensureModels(
                applicationContext,
                precision = precision,
                sttModel = sttModel,
                sttBackend = sttBackend,
                ttsModel = ttsModel,
                onProgress = report,
            )
            if (includeLlm) {
                // Hand the bar over to the LLM phase: what the pipeline phase
                // actually transferred is now behind us, and nothing is ahead.
                // Falls back to the estimate when the pipeline was fully cached
                // and never reported a sample.
                phaseBase = if (lastDone > 0L) lastDone else plannedPipeline
                laterPhases = 0L
                ModelManager.ensureLlmModels(
                    applicationContext,
                    llmModel = llmModel,
                    onProgress = report,
                )
            }
            Result.success(workDataOf(KEY_MODEL_DIR to modelDir))
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
        const val KEY_LLM_MODEL = "llmModel"

        // Output keys
        const val KEY_MODEL_DIR = "modelDir"
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
        ): String {
            if (
                precision == ModelPrecision.INT8 &&
                sttModel == SttModel.PARAKEET_EOU &&
                sttBackend == SttBackend.ONNX &&
                ttsModel.isKokoro &&
                !includeLlm
            ) {
                return UNIQUE_NAME
            }
            val llm = when {
                !includeLlm -> ""
                llmModel == LlmModel.FUNCTIONGEMMA -> ".llm"
                else -> ".llm.${llmModel.name}"
            }
            val ttsName = if (ttsModel.isKokoro) TtsModel.KOKORO.name else ttsModel.name
            return "$UNIQUE_NAME.${precision.name}.${sttModel.name}.${sttBackend.name}.$ttsName$llm"
        }

        fun request(
            precision: ModelPrecision = ModelPrecision.INT8,
            sttModel: SttModel = SttModel.PARAKEET_EOU,
            sttBackend: SttBackend = SttBackend.ONNX,
            ttsModel: TtsModel = TtsModel.KOKORO_SHORT_TURN,
            includeLlm: Boolean = false,
            llmModel: LlmModel = LlmModel.FUNCTIONGEMMA,
        ) =
            OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(
                    KEY_PRECISION to precision.name,
                    KEY_STT_MODEL to sttModel.name,
                    KEY_STT_BACKEND to sttBackend.name,
                    KEY_TTS_MODEL to ttsModel.name,
                    KEY_INCLUDE_LLM to includeLlm,
                    KEY_LLM_MODEL to llmModel.name,
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
        ): java.util.UUID {
            val req = request(
                precision = precision,
                sttModel = sttModel,
                sttBackend = sttBackend,
                ttsModel = ttsModel,
                includeLlm = includeLlm,
                llmModel = llmModel,
            )
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(
                    precision = precision,
                    sttModel = sttModel,
                    sttBackend = sttBackend,
                    ttsModel = ttsModel,
                    includeLlm = includeLlm,
                    llmModel = llmModel,
                ),
                ExistingWorkPolicy.KEEP, req,
            )
            return req.id
        }
    }
}
