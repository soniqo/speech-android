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

        runCatching { setForeground(buildForegroundInfo(0, "Preparing speech models…")) }

        // Only rebuild the foreground notification when the integer percent or
        // the file changes — the underlying progress callback is already
        // throttled to ~1 MB, but re-posting a Notification on every tick still
        // janks the main thread, so we coalesce to visible changes only.
        var lastNotifiedPct = -1
        var lastNotifiedFile = ""

        return try {
            val modelDir = ModelManager.ensureModels(applicationContext, precision) { p ->
                val pct = progressPercent(p.completed, p.totalFiles, p.bytesDownloaded, p.fileTotalBytes)
                setProgressAsync(workDataOf(
                    KEY_FILE to p.file,
                    KEY_COMPLETED to p.completed,
                    KEY_TOTAL to p.totalFiles,
                    KEY_BYTES_DOWNLOADED to p.bytesDownloaded,
                    KEY_FILE_TOTAL_BYTES to p.fileTotalBytes,
                    KEY_PERCENT to pct,
                ))
                if (pct != lastNotifiedPct || p.file != lastNotifiedFile) {
                    lastNotifiedPct = pct
                    lastNotifiedFile = p.file
                    runCatching {
                        setForegroundAsync(buildForegroundInfo(
                            percent = pct,
                            text = "${p.file}  ${formatMb(p.bytesDownloaded)}/${formatMb(p.fileTotalBytes)}" +
                                "  ·  ${p.completed}/${p.totalFiles}",
                        ))
                    }
                }
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
         * Download completion percent (0-100) that advances *continuously* as
         * the current file streams, instead of only when a whole file lands.
         * It is the count of fully-finished files plus the fraction of the
         * file in flight, scaled over the total file count:
         *
         *     pct = ((completed + bytesDownloaded / fileTotalBytes) / totalFiles) * 100
         *
         * This keeps the progress bar moving through the dominant ~840 MB
         * encoder (issue #30: "stuck at 0/16, 0%"). When [fileTotalBytes] is
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
        fun request(precision: ModelPrecision = ModelPrecision.INT8) =
            OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(KEY_PRECISION to precision.name))
                .build()

        /**
         * Convenience: enqueue under the standard unique name with
         * [ExistingWorkPolicy.KEEP] (a running download is reused; otherwise a
         * new one starts). Returns the request id so callers can observe it.
         */
        fun enqueue(
            context: Context,
            precision: ModelPrecision = ModelPrecision.INT8,
        ): java.util.UUID {
            val req = request(precision)
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME, ExistingWorkPolicy.KEEP, req,
            )
            return req.id
        }
    }
}
