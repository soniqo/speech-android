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

    private const val BASE_URL = "https://huggingface.co/aufklarer"

    // Bump when models on HuggingFace are updated to trigger cache invalidation.
    private const val MODEL_VERSION = 2

    private const val MAX_RETRIES = 5
    private const val RETRY_DELAY_MS = 2000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun models(precision: ModelPrecision): List<ModelFile> {
        val suffix = if (precision == ModelPrecision.INT8) "-int8" else ""
        return listOf(
            // VAD (no quantized variant — already 2 MB)
            ModelFile("Silero-VAD-v5-ONNX", "silero-vad.onnx"),
            // STT (v3 — multilingual, 114 languages)
            ModelFile("Parakeet-TDT-v3-ONNX", "parakeet-encoder${suffix}.onnx"),
            ModelFile("Parakeet-TDT-v3-ONNX", "parakeet-decoder-joint${suffix}.onnx"),
            ModelFile("Parakeet-TDT-v3-ONNX", "vocab.json"),
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
        // Note: FP32 encoder also needs parakeet-encoder.onnx.data (external weights)
    }

    data class ModelFile(val repo: String, val filename: String)

    data class Progress(
        val file: String,
        val bytesDownloaded: Long,
        val totalFiles: Int,
        val completed: Int,
    )

    /** Returns the model directory path, downloading models if needed. */
    suspend fun ensureModels(
        context: Context,
        precision: ModelPrecision = ModelPrecision.INT8,
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

        // Clean up leftover partial downloads from previous crashes
        dir.walk().filter { it.extension == "tmp" }.forEach { it.delete() }

        val fileList = models(precision)
        // FP32 encoder needs the external data file
        val allFiles = if (precision == ModelPrecision.FP32) {
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
            downloadFile(url, dest) { bytes ->
                onProgress?.invoke(Progress(model.filename, bytes, allFiles.size, completed))
            }
            completed++
        }

        // Write manifest
        File(dir, "precision.txt").writeText(precision.name)
        versionFile.writeText(MODEL_VERSION.toString())

        dir.absolutePath
    }

    private fun downloadFile(url: String, dest: File, onBytes: (Long) -> Unit) {
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

                FileOutputStream(tmp, isResume).use { output ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(65536)
                        var total = if (isResume) existingBytes else 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n == -1) break
                            output.write(buf, 0, n)
                            total += n
                            onBytes(total)
                        }
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

        // All retries exhausted — clean up partial file and throw
        tmp.delete()
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
