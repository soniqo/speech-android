package audio.soniqo.speech

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads and caches ONNX models from HuggingFace.
 *
 * Models are stored in the app's internal files directory under `models/`.
 * Uses OkHttp with timeouts, retry, and resume for reliable large-file downloads.
 */
object ModelManager {

    private const val BASE_URL = "https://huggingface.co/soniqo"

    // Bump when models on HuggingFace are updated to trigger cache invalidation.
    // v4: Parakeet INT8 moved from Parakeet-TDT-v3-ONNX (INT64 decoder-joint) to
    // Parakeet-TDT-0.6B-ONNX (INT32) to match speech-core; the filenames are
    // unchanged, so this bump is required to evict the stale, incompatible files.
    private const val MODEL_VERSION = 4

    private const val MAX_RETRIES = 5
    private const val RETRY_DELAY_MS = 2000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun models(
        precision: ModelPrecision,
        sttModel: SttModel = SttModel.PARAKEET,
        sttBackend: SttBackend = SttBackend.ONNX,
    ): List<ModelFile> {
        val suffix = if (precision == ModelPrecision.INT8) "-int8" else ""
        val files = mutableListOf(
            // VAD (no quantized variant — already 2 MB)
            ModelFile("Silero-VAD-v5-ONNX", "silero-vad.onnx"),
        )

        // STT — Parakeet (auto-detect) or Nemotron-3.5 multilingual.
        when (sttModel) {
            // Parakeet-TDT-0.6B-ONNX (not the older Parakeet-TDT-v3-ONNX): its
            // decoder-joint takes INT32 `targets`/`target_length`, matching what
            // speech-core's ParakeetStt::tdt_decode now sends. The v3 export used
            // INT64, so pairing it with the current engine aborts at runtime with
            // "ORT: Unexpected input data type. Actual: (tensor(int32)),
            // expected: (tensor(int64))". This also matches the repo already used
            // for the FP32 external-data file below.
            SttModel.PARAKEET -> files += listOf(
                ModelFile("Parakeet-TDT-0.6B-ONNX", "parakeet-encoder${suffix}.onnx"),
                ModelFile("Parakeet-TDT-0.6B-ONNX", "parakeet-decoder-joint${suffix}.onnx"),
                ModelFile("Parakeet-TDT-0.6B-ONNX", "vocab.json"),
            )
            SttModel.NEMOTRON_MULTILINGUAL -> {
                val base = "Nemotron-3.5-ASR-Streaming-Multilingual-0.6B"
                files += when (sttBackend) {
                    // INT8 ONNX uses ConvInteger in the encoder, which the mobile
                    // onnxruntime-android build does not implement ("Could not find
                    // an implementation for ConvInteger"), so the ONNX backend on
                    // Android always uses FP16 (verified on-device). For an int8
                    // footprint on Android use the LiteRT backend (channelwise int8
                    // via the NNAPI/XNNPACK delegate).
                    SttBackend.ONNX -> listOf("encoder.onnx", "encoder.onnx.data",
                        "decoder.onnx", "decoder.onnx.data", "joint.onnx", "joint.onnx.data",
                        "vocab.json", "languages.json", "config.json")
                        .map { ModelFile("$base-ONNX-FP16", it) }
                    SttBackend.LITERT -> {
                      val q = if (precision == ModelPrecision.INT8) "INT8" else "FP16"
                      listOf(
                        "nemotron-multilingual-encoder.tflite",
                        "nemotron-multilingual-decoder.tflite",
                        "nemotron-multilingual-joint.tflite",
                        "vocab.json", "languages.json", "io_map.json", "config.json")
                        .map { ModelFile("$base-LiteRT-$q", it) }
                    }
                }
            }
        }

        files += listOf(
            // TTS (E2E model — single file + external weights)
            ModelFile("Kokoro-82M-ONNX", "kokoro-e2e.onnx"),
            ModelFile("Kokoro-82M-ONNX", "kokoro-e2e.onnx.data"),
            ModelFile("Kokoro-82M-ONNX", "vocab_index.json"),
            ModelFile("Kokoro-82M-ONNX", "us_gold.json"),
            ModelFile("Kokoro-82M-ONNX", "us_silver.json"),
            ModelFile("Kokoro-82M-ONNX", "dict_fr.json"),
            ModelFile("Kokoro-82M-ONNX", "dict_es.json"),
            ModelFile("Kokoro-82M-ONNX", "dict_it.json"),
            ModelFile("Kokoro-82M-ONNX", "dict_pt.json"),
            ModelFile("Kokoro-82M-ONNX", "dict_hi.json"),
            ModelFile("Kokoro-82M-ONNX", "voices/af_heart.bin"),
            // Noise cancellation
            ModelFile("DeepFilterNet3-ONNX", "deepfilter-auxiliary.bin"),
        )
        return files
        // Note: FP32 Parakeet encoder also needs parakeet-encoder.onnx.data.
    }

    data class ModelFile(val repo: String, val filename: String)

    data class Progress(
        val file: String,
        val bytesDownloaded: Long,
        /** Total size of the file currently downloading, or 0 if unknown. */
        val fileTotalBytes: Long,
        val totalFiles: Int,
        val completed: Int,
    )

    // Report intra-file byte progress at most this often. Without throttling,
    // downloadFile's 64 KB read loop fires the callback ~13k times for the
    // ~840 MB encoder, flooding WorkManager's setProgress/setForeground.
    private const val PROGRESS_REPORT_INTERVAL_BYTES = 1_000_000L

    /**
     * True iff every required model file for [precision] is already on disk
     * and passes [isValidModel] (right ONNX magic, above the per-file size
     * floor) and the cached version matches [MODEL_VERSION].
     *
     * Cheap and side-effect free — does not start a download. Use this from
     * `SpeechRecognitionService.onCheckRecognitionSupport()` (or any path
     * that must not block) to decide whether to invoke [ensureModels] /
     * `ModelDownloadWorker` first.
     */
    fun areModelsReady(
        context: Context,
        precision: ModelPrecision = ModelPrecision.INT8,
        sttModel: SttModel = SttModel.PARAKEET,
        sttBackend: SttBackend = SttBackend.ONNX,
    ): Boolean {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) return false

        val versionFile = File(dir, "version.txt")
        val cached = versionFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        if (cached < MODEL_VERSION) return false

        val fileList = models(precision, sttModel, sttBackend)
        val allFiles = if (precision == ModelPrecision.FP32 && sttModel == SttModel.PARAKEET) {
            fileList + ModelFile("Parakeet-TDT-0.6B-ONNX", "parakeet-encoder.onnx.data")
        } else {
            fileList
        }
        return allFiles.all { model ->
            val dest = File(dir, model.filename)
            dest.exists() && isValidModel(dest, model.filename)
        }
    }

    /** Path to the model directory for [precision], without downloading. */
    fun modelDir(context: Context): String =
        File(context.filesDir, "models").absolutePath

    /** Returns the model directory path, downloading models if needed. */
    suspend fun ensureModels(
        context: Context,
        precision: ModelPrecision = ModelPrecision.INT8,
        sttModel: SttModel = SttModel.PARAKEET,
        sttBackend: SttBackend = SttBackend.ONNX,
        onProgress: ((Progress) -> Unit)? = null,
    ): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "models")
        dir.mkdirs()
        File(dir, "voices").mkdirs()

        // Invalidate cache if model version changed
        val versionFile = File(dir, "version.txt")
        val cached = versionFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        if (cached < MODEL_VERSION) {
            dir.listFiles()?.filter { it.name != "voices" }?.forEach { it.delete() }
            dir.resolve("voices").listFiles()?.forEach { it.delete() }
        }

        // Note: leftover .tmp files are intentionally preserved here. If a
        // previous run was interrupted, downloadFile resumes via Range:
        // bytes=N- on the next attempt. Stale .tmp from an old MODEL_VERSION
        // is already wiped above.

        val fileList = models(precision, sttModel, sttBackend)
        // FP32 Parakeet encoder needs the external data file.
        val allFiles = if (precision == ModelPrecision.FP32 && sttModel == SttModel.PARAKEET) {
            fileList + ModelFile("Parakeet-TDT-0.6B-ONNX", "parakeet-encoder.onnx.data")
        } else {
            fileList
        }

        var completed = 0
        for (model in allFiles) {
            val dest = File(dir, model.filename)
            if (dest.exists() && isValidModel(dest, model.filename)) {
                completed++
                continue
            }
            // Delete corrupt/incomplete files and redownload
            if (dest.exists()) {
                LOGI("Deleting invalid model file: ${model.filename} (${dest.length()} bytes)")
                dest.delete()
            }
            dest.parentFile?.mkdirs()

            val url = "$BASE_URL/${model.repo}/resolve/main/${model.filename}"
            downloadFile(url, dest) { bytes, fileTotal ->
                onProgress?.invoke(Progress(model.filename, bytes, fileTotal, allFiles.size, completed))
            }
            completed++
        }

        // Write manifest
        File(dir, "precision.txt").writeText(precision.name)
        versionFile.writeText(MODEL_VERSION.toString())

        dir.absolutePath
    }

    private fun downloadFile(url: String, dest: File, onBytes: (downloaded: Long, fileTotal: Long) -> Unit) {
        val tmp = File(dest.parentFile, "${dest.name}.tmp")

        var lastException: IOException? = null

        for (attempt in 1..MAX_RETRIES) {
            try {
                val existingBytes = if (tmp.exists()) tmp.length() else 0L

                val requestBuilder = Request.Builder().url(url)
                if (existingBytes > 0) {
                    // Resume partial download
                    requestBuilder.header("Range", "bytes=$existingBytes-")
                }

                val response = client.newCall(requestBuilder.build()).execute()

                if (!response.isSuccessful && response.code != 206) {
                    val code = response.code
                    response.close()
                    if (code in 500..599) {
                        // Server error — longer backoff, likely temporary
                        throw IOException("Server temporarily unavailable (HTTP $code). " +
                            "HuggingFace may be busy — try again in a few minutes.")
                    }
                    throw IOException("HTTP $code for $url")
                }

                val body = response.body ?: throw IOException("Empty response for $url")

                // Validate Content-Length when starting fresh
                val contentLength = body.contentLength()
                val isResume = response.code == 206

                // Full file size: on a 206 resume, contentLength is only the
                // remaining range, so add what's already on disk. 0 means the
                // server didn't advertise a length (progress stays file-count
                // based for this file).
                val fileTotal = when {
                    contentLength <= 0 -> 0L
                    isResume -> existingBytes + contentLength
                    else -> contentLength
                }

                FileOutputStream(tmp, isResume).use { output ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(65536)
                        var total = if (isResume) existingBytes else 0L
                        var lastReported = -1L
                        onBytes(total, fileTotal)
                        while (true) {
                            val n = input.read(buf)
                            if (n == -1) break
                            output.write(buf, 0, n)
                            total += n
                            // Throttle: only surface progress every ~1 MB.
                            if (lastReported < 0 || total - lastReported >= PROGRESS_REPORT_INTERVAL_BYTES) {
                                onBytes(total, fileTotal)
                                lastReported = total
                            }
                        }
                        onBytes(total, fileTotal)
                    }
                }

                response.close()

                // Validate downloaded size if Content-Length was provided
                if (!isResume && contentLength > 0 && tmp.length() != contentLength) {
                    throw IOException(
                        "Incomplete download: got ${tmp.length()} bytes, expected $contentLength"
                    )
                }

                // Success — move to final location
                if (!tmp.renameTo(dest)) {
                    // renameTo can fail on some filesystems; fall back to copy
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                return

            } catch (e: IOException) {
                lastException = e
                if (attempt < MAX_RETRIES) {
                    // Longer backoff for server errors (503 etc.)
                    val isServerError = e.message?.contains("temporarily unavailable") == true
                    val delay = if (isServerError) RETRY_DELAY_MS * attempt * 3 else RETRY_DELAY_MS * attempt
                    Thread.sleep(delay)
                }
            }
        }

        // All retries exhausted — preserve the partial .tmp so the next
        // ensureModels() call can pick up where this one left off via the
        // Range: header. Particularly important when called from
        // ModelDownloadWorker, where Result.retry() spins up a fresh
        // ensureModels() invocation after WorkManager's backoff window.
        throw IOException("Download failed after $MAX_RETRIES attempts: ${lastException?.message}", lastException)
    }

    // ONNX files start with these bytes (protobuf magic for ONNX IR)
    private val ONNX_MAGIC = byteArrayOf(0x08, 0x0)

    /** Minimum expected sizes for key model files. */
    private val MIN_SIZES = mapOf(
        "parakeet-encoder-int8.onnx" to 100_000_000L,   // ~840 MB
        "parakeet-decoder-joint-int8.onnx" to 10_000_000L, // ~51 MB
        "kokoro-e2e.onnx" to 1_000L,                     // Small (weights in .data file)
        "kokoro-e2e.onnx.data" to 50_000_000L,           // ~89 MB
        "silero-vad.onnx" to 500_000L,                   // ~2 MB
    )

    private fun isValidModel(file: File, filename: String): Boolean {
        if (file.length() == 0L) return false

        // Check minimum size for known large files
        MIN_SIZES[filename]?.let { minSize ->
            if (file.length() < minSize) return false
        }

        // Validate ONNX magic bytes for .onnx files
        if (filename.endsWith(".onnx")) {
            try {
                file.inputStream().use { stream ->
                    val header = ByteArray(2)
                    if (stream.read(header) != 2) return false
                    if (header[0] != ONNX_MAGIC[0]) return false
                }
            } catch (_: Exception) {
                return false
            }
        }

        return true
    }

    private fun LOGI(msg: String) = Log.i("Speech", msg)
}
