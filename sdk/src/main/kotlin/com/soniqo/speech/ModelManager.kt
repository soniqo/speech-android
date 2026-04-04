package audio.soniqo.speech

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

/**
 * Downloads and caches ONNX models from HuggingFace.
 *
 * Models are stored in the app's internal files directory under `models/`.
 */
object ModelManager {

    private const val BASE_URL = "https://huggingface.co/aufklarer"

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
            if (dest.exists()) {
                completed++
                continue
            }
            dest.parentFile?.mkdirs()

            val url = "$BASE_URL/${model.repo}/resolve/main/${model.filename}"
            downloadFile(url, dest) { bytes ->
                onProgress?.invoke(Progress(model.filename, bytes, allFiles.size, completed))
            }
            completed++
        }

        // Write a manifest so JNI knows which variant was downloaded
        File(dir, "precision.txt").writeText(precision.name)

        dir.absolutePath
    }

    private fun downloadFile(url: String, dest: File, onBytes: (Long) -> Unit) {
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        try {
            val conn = URL(url).openConnection()
            conn.connect()
            conn.getInputStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        output.write(buf, 0, n)
                        total += n
                        onBytes(total)
                    }
                }
            }
            tmp.renameTo(dest)
        } finally {
            tmp.delete()
        }
    }
}
