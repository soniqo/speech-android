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
    // v6: default STT switched to Parakeet-EOU-120M-ONNX-INT8, the low-memory
    // streaming + end-of-utterance bundle. Evict the old default TDT v3 files
    // so existing installs do not keep ~900 MB of unused ASR weights.
    private const val MODEL_VERSION = 6

    private const val MAX_RETRIES = 5
    private const val RETRY_DELAY_MS = 2000L
    private const val MODEL_SET_FILENAME = "model-set.txt"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun models(
        precision: ModelPrecision,
        sttModel: SttModel = SttModel.PARAKEET_EOU,
        sttBackend: SttBackend = SttBackend.ONNX,
        ttsModel: TtsModel = TtsModel.KOKORO,
    ): List<ModelFile> {
        val suffix = if (precision == ModelPrecision.INT8) "-int8" else ""
        val files = mutableListOf(
            // VAD (no quantized variant — already 2 MB)
            ModelFile("Silero-VAD-v5-ONNX", "silero-vad.onnx"),
        )

        // STT — Parakeet-EOU low-memory streaming, Parakeet TDT v3, or
        // Nemotron-3.5 multilingual.
        when (sttModel) {
            // Parakeet-EOU-120M is the Android default: cache-aware streaming
            // RNN-T with inline <EOU>/<EOB> tokens, 25 European languages, and
            // a much smaller runtime footprint than the 0.6B TDT path.
            // Published as INT8-only (encoder INT8, decoder/joint FP32), so
            // [precision] intentionally does not alter filenames here.
            SttModel.PARAKEET_EOU -> files += listOf(
                ModelFile("Parakeet-EOU-120M-ONNX-INT8", "parakeet-eou-encoder.onnx"),
                ModelFile("Parakeet-EOU-120M-ONNX-INT8", "parakeet-eou-decoder.onnx"),
                ModelFile("Parakeet-EOU-120M-ONNX-INT8", "parakeet-eou-joint.onnx"),
                ModelFile("Parakeet-EOU-120M-ONNX-INT8", "vocab.json"),
                ModelFile("Parakeet-EOU-120M-ONNX-INT8", "config.json"),
            )
            // Parakeet-TDT-v3-ONNX is the larger broad-coverage multilingual
            // export (8192-token vocab with Cyrillic/Greek/accented Latin).
            // Its INT8 decoder-joint was re-exported so `targets`/`target_length`
            // are INT32 (was INT64) and the length input is named `target_length`
            // (was `prednet_lengths_orig`), matching speech-core's
            // ParakeetStt::tdt_decode. (The Parakeet-TDT-0.6B-ONNX export is
            // English-only — speaking e.g. Russian transliterates to Latin — so
            // it must not be used for the default STT.)
            SttModel.PARAKEET -> files += listOf(
                ModelFile("Parakeet-TDT-v3-ONNX", "parakeet-encoder${suffix}.onnx"),
                ModelFile("Parakeet-TDT-v3-ONNX", "parakeet-decoder-joint${suffix}.onnx"),
                ModelFile("Parakeet-TDT-v3-ONNX", "vocab.json"),
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

        files += ttsModels(ttsModel)

        // Noise cancellation
        files += ModelFile("DeepFilterNet3-ONNX", "deepfilter-auxiliary.bin")
        return files
        // Note: FP32 Parakeet encoder also needs parakeet-encoder.onnx.data.
    }

    private fun ttsModels(ttsModel: TtsModel): List<ModelFile> = when (ttsModel) {
        TtsModel.KOKORO -> listOf(
            // E2E model — single file + external weights
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
        )
        // Four LiteRT graphs + the G2P-free tokenizer assets + the 10-voice catalog.
        TtsModel.SUPERTONIC -> listOf(
            "duration_predictor.tflite", "text_encoder.tflite",
            "vector_estimator.tflite", "vocoder.tflite",
            "tts.json", "unicode_indexer.json",
            "voice_styles/F1.json", "voice_styles/F2.json", "voice_styles/F3.json",
            "voice_styles/F4.json", "voice_styles/F5.json",
            "voice_styles/M1.json", "voice_styles/M2.json", "voice_styles/M3.json",
            "voice_styles/M4.json", "voice_styles/M5.json",
        ).map { ModelFile("Supertonic-3-LiteRT", it) }
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
    // downloadFile's 64 KB read loop can fire thousands of callbacks for
    // large model files, flooding WorkManager's setProgress/setForeground.
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
        sttModel: SttModel = SttModel.PARAKEET_EOU,
        sttBackend: SttBackend = SttBackend.ONNX,
        ttsModel: TtsModel = TtsModel.KOKORO,
    ): Boolean {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) return false

        val versionFile = File(dir, "version.txt")
        val cached = versionFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        if (cached < MODEL_VERSION) return false
        if (cachedModelSet(dir) != modelSetKey(precision, sttModel, sttBackend, ttsModel)) return false

        val fileList = models(precision, sttModel, sttBackend, ttsModel)
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

    /**
     * True iff the TTS-only cache contains every file needed for [ttsModel].
     * This is used by the Android framework TextToSpeechService path, where
     * downloading VAD/STT/enhancer assets would be unnecessary overhead.
     */
    fun areTtsModelsReady(
        context: Context,
        ttsModel: TtsModel = TtsModel.KOKORO,
    ): Boolean {
        val dir = File(context.filesDir, "models_tts")
        if (!dir.exists()) return false

        val versionFile = File(dir, "version.txt")
        val cached = versionFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        if (cached < MODEL_VERSION) return false
        if (cachedModelSet(dir) != ttsModelSetKey(ttsModel)) return false

        return ttsModels(ttsModel).all { model ->
            val dest = File(dir, model.filename)
            dest.exists() && isValidModel(dest, model.filename)
        }
    }

    /** Path to the model directory for [precision], without downloading. */
    fun modelDir(context: Context): String =
        File(context.filesDir, "models").absolutePath

    /** Path to the TTS-only model directory, without downloading. */
    fun ttsModelDir(context: Context): String =
        File(context.filesDir, "models_tts").absolutePath

    /** Returns the model directory path, downloading models if needed. */
    suspend fun ensureModels(
        context: Context,
        precision: ModelPrecision = ModelPrecision.INT8,
        sttModel: SttModel = SttModel.PARAKEET_EOU,
        sttBackend: SttBackend = SttBackend.ONNX,
        ttsModel: TtsModel = TtsModel.KOKORO,
        onProgress: ((Progress) -> Unit)? = null,
    ): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "models")
        dir.mkdirs()
        File(dir, "voices").mkdirs()

        // Invalidate cache if model version or requested model set changed.
        val versionFile = File(dir, "version.txt")
        val cached = versionFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        val requestedModelSet = modelSetKey(precision, sttModel, sttBackend, ttsModel)
        if (cached < MODEL_VERSION || cachedModelSet(dir) != requestedModelSet) {
            clearModelCache(dir)
        }

        // Note: leftover .tmp files are intentionally preserved here. If a
        // previous run was interrupted, downloadFile resumes via Range:
        // bytes=N- on the next attempt. Stale .tmp from an old MODEL_VERSION
        // or from a different model set are already wiped above.

        val fileList = models(precision, sttModel, sttBackend, ttsModel)
        // FP32 Parakeet encoder needs the external data file.
        val allFiles = if (precision == ModelPrecision.FP32 && sttModel == SttModel.PARAKEET) {
            fileList + ModelFile("Parakeet-TDT-0.6B-ONNX", "parakeet-encoder.onnx.data")
        } else {
            fileList
        }

        downloadMissingModels(dir, allFiles, onProgress)

        // Write manifest
        File(dir, "precision.txt").writeText(precision.name)
        versionFile.writeText(MODEL_VERSION.toString())
        File(dir, MODEL_SET_FILENAME).writeText(requestedModelSet)

        dir.absolutePath
    }

    /** Returns the TTS-only model directory path, downloading models if needed. */
    suspend fun ensureTtsModels(
        context: Context,
        ttsModel: TtsModel = TtsModel.KOKORO,
        onProgress: ((Progress) -> Unit)? = null,
    ): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "models_tts")
        dir.mkdirs()
        File(dir, "voices").mkdirs()
        File(dir, "voice_styles").mkdirs()

        val versionFile = File(dir, "version.txt")
        val cached = versionFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        val requestedModelSet = ttsModelSetKey(ttsModel)
        if (cached < MODEL_VERSION || cachedModelSet(dir) != requestedModelSet) {
            clearModelCache(dir)
            File(dir, "voices").mkdirs()
            File(dir, "voice_styles").mkdirs()
        }

        downloadMissingModels(dir, ttsModels(ttsModel), onProgress)

        File(dir, "precision.txt").writeText("TTS")
        versionFile.writeText(MODEL_VERSION.toString())
        File(dir, MODEL_SET_FILENAME).writeText(requestedModelSet)

        dir.absolutePath
    }

    private fun downloadMissingModels(
        dir: File,
        allFiles: List<ModelFile>,
        onProgress: ((Progress) -> Unit)?,
    ) {
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
    }

    private fun modelSetKey(
        precision: ModelPrecision,
        sttModel: SttModel,
        sttBackend: SttBackend,
        ttsModel: TtsModel,
    ): String = listOf(
        "v$MODEL_VERSION",
        "precision=${precision.name}",
        "stt=${sttModel.name}",
        "backend=${sttBackend.name}",
        "tts=${ttsModel.name}",
    ).joinToString("|")

    private fun ttsModelSetKey(ttsModel: TtsModel): String = listOf(
        "v$MODEL_VERSION",
        "profile=TTS",
        "tts=${ttsModel.name}",
    ).joinToString("|")

    private fun cachedModelSet(dir: File): String? =
        File(dir, MODEL_SET_FILENAME).takeIf { it.exists() }?.readText()?.trim()

    private fun clearModelCache(dir: File) {
        dir.listFiles()?.forEach { entry ->
            if (entry.name == "voices") {
                entry.listFiles()?.forEach { it.deleteRecursively() }
            } else {
                entry.deleteRecursively()
            }
        }
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
        "parakeet-eou-encoder.onnx" to 100_000_000L,    // ~132 MB
        "parakeet-eou-decoder.onnx" to 10_000_000L,     // ~16 MB
        "parakeet-eou-joint.onnx" to 1_000_000L,        // ~6 MB
        "kokoro-e2e.onnx" to 1_000L,                     // Small (weights in .data file)
        "kokoro-e2e.onnx.data" to 50_000_000L,           // ~310 MB
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
