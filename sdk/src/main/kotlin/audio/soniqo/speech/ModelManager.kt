package audio.soniqo.speech

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
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
    private const val POCKET_TTS_REVISION = "v1.0.0"
    private const val POCKET_TTS_DIR = "pocket_tts"
    private const val NEMOTRON_LITERT_INT8_REVISION = "v1.0.0"
    private const val NEMOTRON_LITERT_FP16_REVISION =
        "1503a9a1eb75b813b83ba65bf5e9fecea4a46091"

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

    @VisibleForTesting
    internal fun models(
        precision: ModelPrecision,
        sttModel: SttModel = SttModel.PARAKEET_EOU,
        sttBackend: SttBackend = SttBackend.ONNX,
        ttsModel: TtsModel = TtsModel.KOKORO_SHORT_TURN,
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
                    // weights with FP32 activations/compute on the CPU runtime).
                    SttBackend.ONNX -> listOf("encoder.onnx", "encoder.onnx.data",
                        "decoder.onnx", "decoder.onnx.data", "joint.onnx", "joint.onnx.data",
                        "vocab.json", "languages.json", "config.json")
                        .map { ModelFile("$base-ONNX-FP16", it) }
                    SttBackend.LITERT -> {
                        val q = if (precision == ModelPrecision.INT8) "INT8" else "FP16"
                        val revision = nemotronLiteRtRevision(precision)
                        listOf(
                            "nemotron-multilingual-encoder.tflite",
                            "nemotron-multilingual-decoder.tflite",
                            "nemotron-multilingual-joint.tflite",
                            "vocab.json", "languages.json", "io_map.json", "config.json",
                        ).map { ModelFile("$base-LiteRT-$q", it, revision) }
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

    @VisibleForTesting
    internal fun ttsModels(ttsModel: TtsModel): List<ModelFile> = when (ttsModel) {
        TtsModel.KOKORO, TtsModel.KOKORO_SHORT_TURN -> listOf(
            // Both graph profiles share one external weight blob. Keeping both
            // protos in the same cache makes profile switches a ~2.6 MB fetch,
            // never a second ~310 MB weights download.
            ModelFile("Kokoro-82M-ONNX", "kokoro-e2e.onnx"),
            ModelFile("Kokoro-82M-ONNX", "kokoro-e2e-realtime.onnx"),
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
            ModelFile("Kokoro-82M-ONNX", "voices/ff_siwis.bin"),
            ModelFile("Kokoro-82M-ONNX", "voices/ef_dora.bin"),
            ModelFile("Kokoro-82M-ONNX", "voices/if_sara.bin"),
            ModelFile("Kokoro-82M-ONNX", "voices/pf_dora.bin"),
            ModelFile("Kokoro-82M-ONNX", "voices/hf_alpha.bin"),
            ModelFile("Kokoro-82M-ONNX", "voices/jf_alpha.bin"),
            ModelFile("Kokoro-82M-ONNX", "voices/zf_xiaobei.bin"),
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
        TtsModel.POCKET -> listOf(
            // Runtime files from the immutable public fixed-Alba bundle. Keep
            // them below pocket_tts/: Parakeet and Pocket both ship vocab.json.
            "decoder.int8.onnx",
            "encoder.onnx",
            "lm_flow.int8.onnx",
            "lm_main.int8.onnx",
            "text_conditioner.onnx",
            "token_scores.json",
            "vocab.json",
            // Retain the model/voice license and release manifest beside the
            // runtime files distributed to the device.
            "LICENSE",
            "manifest.json",
        ).map { filename ->
            ModelFile(
                repo = "Pocket-TTS-100M-ONNX-INT8",
                filename = filename,
                revision = POCKET_TTS_REVISION,
                localFilename = "$POCKET_TTS_DIR/$filename",
            )
        }
    }

    // FunctionGemma is kept out of the pipeline model set: only agent demos
    // opt in via [ensureLlmModels]. The Control profile downloads one reusable
    // LoRA-capable base and its small adapter as distinct files.
    @VisibleForTesting
    internal fun llmModels(llmModel: LlmModel): List<ModelFile> = when (llmModel) {
        LlmModel.FUNCTIONGEMMA -> listOf(
            ModelFile("FunctionGemma-270M-LiteRT-LM", "model.litertlm"),
        )
        LlmModel.FUNCTIONGEMMA_CONTROL_LORA -> listOf(
            ModelFile("FunctionGemma-270M-LiteRT-LM", "model-lora16-android.litertlm"),
            ModelFile("FunctionGemma-270M-LiteRT-LM", "control-r4-rank16.tflite"),
        )
    }

    data class ModelFile(
        val repo: String,
        /** Path within the Hugging Face repository. */
        val filename: String,
        /** Immutable tag/commit where available; existing bundles use main. */
        val revision: String = "main",
        /** Cache-relative path. May differ to avoid cross-model collisions. */
        val localFilename: String = filename,
    )

    data class Progress(
        val file: String,
        val bytesDownloaded: Long,
        /** Total size of the file currently downloading, or 0 if unknown. */
        val fileTotalBytes: Long,
        val totalFiles: Int,
        val completed: Int,
        /**
         * Bytes on disk across every file this call still had to fetch —
         * finished files plus the one in flight. Cached files contribute
         * nothing, so this counts only what the transfer is responsible for.
         */
        val totalBytesDownloaded: Long = 0L,
        /**
         * Best estimate of [totalBytesDownloaded]'s ceiling, seeded from
         * [EXPECTED_SIZES] and corrected to the real `Content-Length` as each
         * file's response header arrives. 0 when nothing needs downloading.
         */
        val totalBytes: Long = 0L,
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
        ttsModel: TtsModel = TtsModel.KOKORO_SHORT_TURN,
    ): Boolean {
        val dir = modelDirFile(context, precision, sttModel, sttBackend, ttsModel)
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
            val dest = File(dir, model.localFilename)
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
        ttsModel: TtsModel = TtsModel.KOKORO_SHORT_TURN,
    ): Boolean {
        val dir = File(context.filesDir, "models_tts")
        if (!dir.exists()) return false

        val versionFile = File(dir, "version.txt")
        val cached = versionFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        if (cached < MODEL_VERSION) return false
        if (cachedModelSet(dir) != ttsModelSetKey(ttsModel)) return false

        return ttsModels(ttsModel).all { model ->
            val dest = File(dir, model.localFilename)
            dest.exists() && isValidModel(dest, model.filename)
        }
    }

    /**
     * True iff the LLM cache contains a valid FunctionGemma bundle.
     * Cheap and side-effect free — does not start a download.
     */
    fun areLlmModelsReady(
        context: Context,
        llmModel: LlmModel = LlmModel.FUNCTIONGEMMA,
    ): Boolean {
        val dir = File(llmModelDir(context, llmModel))
        if (!dir.exists()) return false

        val versionFile = File(dir, "version.txt")
        val cached = versionFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        if (cached < MODEL_VERSION) return false
        if (cachedModelSet(dir) != llmModelSetKey(llmModel)) return false

        return llmModels(llmModel).all { model ->
            val dest = File(dir, model.localFilename)
            dest.exists() && isValidModel(dest, model.filename)
        }
    }

    /** Path to the model directory for [precision], without downloading. */
    fun modelDir(
        context: Context,
        precision: ModelPrecision = ModelPrecision.INT8,
        sttModel: SttModel = SttModel.PARAKEET_EOU,
        sttBackend: SttBackend = SttBackend.ONNX,
        ttsModel: TtsModel = TtsModel.KOKORO_SHORT_TURN,
    ): String = modelDirFile(context, precision, sttModel, sttBackend, ttsModel).absolutePath

    /** Path to the TTS-only model directory, without downloading. */
    fun ttsModelDir(context: Context): String =
        File(context.filesDir, "models_tts").absolutePath

    /** Path to the LLM model directory, without downloading. */
    fun llmModelDir(
        context: Context,
        llmModel: LlmModel = LlmModel.FUNCTIONGEMMA,
    ): String {
        val name = when (llmModel) {
            LlmModel.FUNCTIONGEMMA -> "models_llm"
            LlmModel.FUNCTIONGEMMA_CONTROL_LORA -> "models_llm-control-lora"
        }
        return File(context.filesDir, name).absolutePath
    }

    /** Absolute path of the FunctionGemma .litertlm bundle, without downloading. */
    fun llmModelFile(
        context: Context,
        llmModel: LlmModel = LlmModel.FUNCTIONGEMMA,
    ): String = File(
        llmModelDir(context, llmModel),
        llmModels(llmModel).first { it.filename.endsWith(".litertlm") }.filename,
    ).absolutePath

    /** Adapter path for LoRA profiles, or null for a standalone LLM bundle. */
    fun llmAdapterFile(context: Context, llmModel: LlmModel): String? =
        llmModels(llmModel).firstOrNull { it.filename.endsWith(".tflite") }
            ?.let { File(llmModelDir(context, llmModel), it.filename).absolutePath }

    /** Returns the model directory path, downloading models if needed. */
    suspend fun ensureModels(
        context: Context,
        precision: ModelPrecision = ModelPrecision.INT8,
        sttModel: SttModel = SttModel.PARAKEET_EOU,
        sttBackend: SttBackend = SttBackend.ONNX,
        ttsModel: TtsModel = TtsModel.KOKORO_SHORT_TURN,
        onProgress: ((Progress) -> Unit)? = null,
    ): String = withContext(Dispatchers.IO) {
        val dir = modelDirFile(context, precision, sttModel, sttBackend, ttsModel)
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
        ttsModel: TtsModel = TtsModel.KOKORO_SHORT_TURN,
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

    /**
     * Returns the path of the selected FunctionGemma .litertlm base,
     * downloading its complete artifact set if needed. Separate from
     * [ensureModels] so LLM files only land on devices that run an agent.
     */
    suspend fun ensureLlmModels(
        context: Context,
        llmModel: LlmModel = LlmModel.FUNCTIONGEMMA,
        onProgress: ((Progress) -> Unit)? = null,
    ): String = withContext(Dispatchers.IO) {
        val dir = File(llmModelDir(context, llmModel))
        dir.mkdirs()

        val versionFile = File(dir, "version.txt")
        val setFile = File(dir, MODEL_SET_FILENAME)
        val requestedModelSet = llmModelSetKey(llmModel)
        val cached = versionFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        val cachedSet = cachedModelSet(dir)

        // Only wipe a genuinely stale/different cached set — never a fresh or
        // in-progress download. Without this guard the partial .tmp is deleted
        // on every worker retry / app restart (the version marker is written
        // only on completion), so a 283 MB download over a flaky connection or
        // across screen-doze could never resume. Markers present + matching =
        // resume via HTTP Range in downloadFile.
        val hadMarkers = versionFile.exists() || setFile.exists()
        if (hadMarkers && (cached < MODEL_VERSION || cachedSet != requestedModelSet)) {
            clearModelCache(dir)
        }

        // Write the set marker up front so a resumed run recognizes this
        // download and keeps its .tmp. areLlmModelsReady still gates on the
        // real, fully-downloaded file, so an early marker can't look "ready".
        setFile.writeText(requestedModelSet)
        versionFile.writeText(MODEL_VERSION.toString())

        downloadMissingModels(dir, llmModels(llmModel), onProgress)

        File(
            dir,
            llmModels(llmModel).first { it.filename.endsWith(".litertlm") }.filename,
        ).absolutePath
    }

    private fun downloadMissingModels(
        dir: File,
        allFiles: List<ModelFile>,
        onProgress: ((Progress) -> Unit)?,
    ) {
        // Byte budget for the progress bar. Weighting every file equally makes
        // the bar lurch through the small JSON assets and then sit still for
        // minutes on the two files that are ~93% of the bytes, so the bar is
        // driven by bytes instead. Cached files are excluded from both sides of
        // the ratio: the bar measures the transfer, not the manifest.
        val pending = allFiles.filterNot { model ->
            File(dir, model.localFilename).let { it.exists() && isValidModel(it, model.filename) }
        }
        var totalBytes = pending.sumOf { expectedBytes(it) }
        // Bytes belonging to files this call has already finished.
        var priorBytes = 0L

        var completed = 0
        for (model in allFiles) {
            val dest = File(dir, model.localFilename)
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

            // Replaced by the real Content-Length on the first callback, which
            // keeps [totalBytes] honest even when EXPECTED_SIZES is stale.
            var expected = expectedBytes(model)
            var fileBytes = 0L

            val url = "$BASE_URL/${model.repo}/resolve/${model.revision}/${model.filename}"
            downloadFile(url, dest) { bytes, fileTotal ->
                if (fileTotal > 0 && fileTotal != expected) {
                    totalBytes += fileTotal - expected
                    expected = fileTotal
                }
                fileBytes = bytes
                onProgress?.invoke(Progress(
                    file = model.localFilename,
                    bytesDownloaded = bytes,
                    fileTotalBytes = fileTotal,
                    totalFiles = allFiles.size,
                    completed = completed,
                    totalBytesDownloaded = priorBytes + bytes,
                    totalBytes = totalBytes,
                ))
            }
            // Prefer the observed byte count; fall back to the estimate when the
            // server advertised no length, so the running total still advances.
            priorBytes += maxOf(fileBytes, expected)
            completed++
        }
    }

    /**
     * Estimated bytes [ensureModels] must transfer for this configuration —
     * expected sizes of every file not already cached and valid, or the whole
     * manifest when the cache is stale and about to be cleared. 0 when
     * everything needed is already on disk.
     *
     * Lets a caller driving two downloads back to back (pipeline models then
     * the LLM bundle) render them as one continuous bar instead of two sweeps.
     */
    fun plannedModelBytes(
        context: Context,
        precision: ModelPrecision = ModelPrecision.INT8,
        sttModel: SttModel = SttModel.PARAKEET_EOU,
        sttBackend: SttBackend = SttBackend.ONNX,
        ttsModel: TtsModel = TtsModel.KOKORO_SHORT_TURN,
    ): Long {
        val dir = modelDirFile(context, precision, sttModel, sttBackend, ttsModel)
        val stale = cacheIsStale(dir, modelSetKey(precision, sttModel, sttBackend, ttsModel))
        return plannedBytes(dir, models(precision, sttModel, sttBackend, ttsModel), stale)
    }

    /** [plannedModelBytes] for the FunctionGemma bundle fetched by [ensureLlmModels]. */
    fun plannedLlmBytes(
        context: Context,
        llmModel: LlmModel = LlmModel.FUNCTIONGEMMA,
    ): Long {
        val dir = File(llmModelDir(context, llmModel))
        // Mirrors ensureLlmModels: a directory with no markers is a fresh or
        // in-progress download, never a stale cache to be wiped.
        val hadMarkers = File(dir, "version.txt").exists() || File(dir, MODEL_SET_FILENAME).exists()
        val stale = hadMarkers && cacheIsStale(dir, llmModelSetKey(llmModel))
        return plannedBytes(dir, llmModels(llmModel), stale)
    }

    private fun plannedBytes(dir: File, files: List<ModelFile>, stale: Boolean): Long =
        files.sumOf { model ->
            val dest = File(dir, model.localFilename)
            if (!stale && dest.exists() && isValidModel(dest, model.filename)) 0L
            else expectedBytes(model)
        }

    private fun cacheIsStale(dir: File, wantedModelSet: String): Boolean {
        if (!dir.exists()) return true
        val cached = File(dir, "version.txt").takeIf { it.exists() }
            ?.readText()?.trim()?.toIntOrNull() ?: 0
        return cached < MODEL_VERSION || cachedModelSet(dir) != wantedModelSet
    }

    @VisibleForTesting
    internal fun modelSetKey(
        precision: ModelPrecision,
        sttModel: SttModel,
        sttBackend: SttBackend,
        ttsModel: TtsModel,
    ): String = buildList {
        add("v$MODEL_VERSION")
        add("precision=${precision.name}")
        add("stt=${sttModel.name}")
        add("backend=${sttBackend.name}")
        add("tts=${ttsCacheName(ttsModel)}")
        if (sttModel == SttModel.NEMOTRON_MULTILINGUAL && sttBackend == SttBackend.LITERT) {
            add("sttRevision=${nemotronLiteRtRevision(precision)}")
        }
    }.joinToString("|")

    private fun nemotronLiteRtRevision(precision: ModelPrecision): String =
        if (precision == ModelPrecision.INT8) {
            NEMOTRON_LITERT_INT8_REVISION
        } else {
            NEMOTRON_LITERT_FP16_REVISION
        }

    private fun modelDirFile(
        context: Context,
        precision: ModelPrecision,
        sttModel: SttModel,
        sttBackend: SttBackend,
        ttsModel: TtsModel,
    ): File = File(context.filesDir, modelDirName(precision, sttModel, sttBackend, ttsModel))

    @VisibleForTesting
    internal fun modelDirName(
        precision: ModelPrecision,
        sttModel: SttModel,
        sttBackend: SttBackend,
        ttsModel: TtsModel,
    ): String {
        if (
            precision == ModelPrecision.INT8 &&
            sttModel == SttModel.PARAKEET_EOU &&
            sttBackend == SttBackend.ONNX &&
            ttsModel.isKokoro
        ) {
            return "models"
        }
        return listOf(
            "models",
            precision.name,
            sttModel.name,
            sttBackend.name,
            ttsCacheName(ttsModel),
        ).joinToString("-").lowercase()
    }

    @VisibleForTesting
    internal fun ttsModelSetKey(ttsModel: TtsModel): String = listOf(
        "v$MODEL_VERSION",
        "profile=TTS",
        "tts=${ttsCacheName(ttsModel)}",
    ).joinToString("|")

    private fun ttsCacheName(ttsModel: TtsModel): String =
        if (ttsModel.isKokoro) TtsModel.KOKORO.name else ttsModel.name

    @VisibleForTesting
    internal fun llmModelSetKey(llmModel: LlmModel): String = listOf(
        "v$MODEL_VERSION",
        "profile=LLM",
        "llm=${llmModel.name}",
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

    @VisibleForTesting
    internal fun downloadFile(
        url: String,
        dest: File,
        client: OkHttpClient = this.client,
        maxRetries: Int = MAX_RETRIES,
        retryDelayMs: Long = RETRY_DELAY_MS,
        onBytes: (downloaded: Long, fileTotal: Long) -> Unit,
    ) {
        val tmp = File(dest.parentFile, "${dest.name}.tmp")

        var lastException: IOException? = null

        for (attempt in 1..maxRetries) {
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
                // Content-Range carries the authoritative total on a 206 and
                // is present even when Content-Length is not, so it is the
                // better source when resuming.
                val contentRange = if (isResume) response.header("Content-Range") else null
                val rangeTotal = contentRange
                    ?.substringAfter('/', "")
                    ?.trim()
                    ?.toLongOrNull()
                    ?: 0L

                // A 206 does not guarantee the body starts where we asked. A
                // server (or CDN) that answers from byte 0 while we append at
                // our offset produces a file larger than the real one, with a
                // valid header and a corrupt middle — which no minimum-size
                // check can catch. Start over when the offsets disagree.
                val rangeStart = contentRange
                    ?.substringAfter("bytes ", "")
                    ?.substringBefore('-', "")
                    ?.trim()
                    ?.toLongOrNull()
                val append = isResume && (rangeStart == null || rangeStart == existingBytes)
                if (isResume && !append) {
                    LOGI("Range ignored by server (asked $existingBytes, got $rangeStart); restarting")
                }
                val fileTotal = when {
                    rangeTotal > 0 -> rangeTotal
                    contentLength <= 0 -> 0L
                    append -> existingBytes + contentLength
                    else -> contentLength
                }

                FileOutputStream(tmp, append).use { output ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(65536)
                        var total = if (append) existingBytes else 0L
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

                // Validate the finished size whenever the server told us what
                // to expect. This used to skip the resume path entirely, so a
                // resumed transfer that ended short was renamed into place as
                // if complete — producing a file that passes the header check
                // and then fails to parse at load time.
                if (fileTotal > 0 && tmp.length() != fileTotal) {
                    throw IOException(
                        "Incomplete download: got ${tmp.length()} bytes, expected $fileTotal"
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
                if (attempt < maxRetries) {
                    // Longer backoff for server errors (503 etc.)
                    val isServerError = e.message?.contains("temporarily unavailable") == true
                    val delay = if (isServerError) retryDelayMs * attempt * 3 else retryDelayMs * attempt
                    Thread.sleep(delay)
                }
            }
        }

        // All retries exhausted — preserve the partial .tmp so the next
        // ensureModels() call can pick up where this one left off via the
        // Range: header. Particularly important when called from
        // ModelDownloadWorker, where Result.retry() spins up a fresh
        // ensureModels() invocation after WorkManager's backoff window.
        throw IOException("Download failed after $maxRetries attempts: ${lastException?.message}", lastException)
    }

    /**
     * Published sizes of the model files, used to seed a byte-weighted
     * progress bar before any response header has arrived. Only an estimate:
     * [downloadMissingModels] corrects each entry to the real `Content-Length`
     * as the download starts, so a stale value costs bar accuracy for a moment
     * and nothing else. Files absent here are assumed [DEFAULT_EXPECTED_BYTES]
     * (they are all small JSON assets).
     *
     * Keyed by repo filename like [MIN_SIZES], so the handful of names shared
     * between bundles (Pocket and Parakeet both ship `vocab.json`, Pocket and
     * Nemotron both ship `encoder.onnx`) collide. All of them are small enough
     * that the mis-estimate is immaterial to the bar.
     */
    private val EXPECTED_SIZES = mapOf(
        "silero-vad.onnx" to 2_243_022L,
        "parakeet-eou-encoder.onnx" to 131_741_896L,
        "parakeet-eou-decoder.onnx" to 15_757_826L,
        "parakeet-eou-joint.onnx" to 5_589_132L,
        "kokoro-e2e.onnx" to 3_047_254L,
        "kokoro-e2e-realtime.onnx" to 2_413_312L,
        "kokoro-e2e.onnx.data" to 324_564_624L,
        // Pocket TTS bundle — the Control demo's default TTS, so these matter
        // for its bar; without them the total under-counts by ~126 MB and then
        // visibly grows as the files self-correct to Content-Length.
        "decoder.int8.onnx" to 22_695_710L,
        "encoder.onnx" to 512_407L,
        "lm_flow.int8.onnx" to 9_962_530L,
        "lm_main.int8.onnx" to 76_341_079L,
        "text_conditioner.onnx" to 16_388_498L,
        "token_scores.json" to 123_617L,
        "us_gold.json" to 3_000_469L,
        "us_silver.json" to 3_099_517L,
        "dict_fr.json" to 51_497L,
        "dict_pt.json" to 37_438L,
        "deepfilter-auxiliary.bin" to 126_976L,
        "model.litertlm" to 297_212_528L,
        "model-lora16-android.litertlm" to 327_438_928L,
        "control-r4-rank16.tflite" to 9_502_720L,
    )

    /** Assumed size of a model file with no [EXPECTED_SIZES] entry. */
    private const val DEFAULT_EXPECTED_BYTES = 256_000L

    private fun expectedBytes(model: ModelFile): Long =
        EXPECTED_SIZES[model.filename] ?: DEFAULT_EXPECTED_BYTES

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
        "kokoro-e2e-realtime.onnx" to 1_000_000L,        // ~2.5 MB shared-weight graph
        "kokoro-e2e.onnx.data" to 50_000_000L,           // ~310 MB
        "decoder.int8.onnx" to 20_000_000L,              // Pocket decoder, ~22.7 MB
        "encoder.onnx" to 400_000L,                      // Pocket Alba encoder, ~0.5 MB
        "lm_flow.int8.onnx" to 9_000_000L,               // Pocket flow model, ~10.0 MB
        "lm_main.int8.onnx" to 70_000_000L,              // Pocket recurrent LM, ~76.3 MB
        "text_conditioner.onnx" to 15_000_000L,          // Pocket text encoder, ~16.4 MB
        "token_scores.json" to 100_000L,                 // Pocket tokenizer scores
        "silero-vad.onnx" to 500_000L,                   // ~2 MB
        "model.litertlm" to 200_000_000L,                // ~283 MB FunctionGemma bundle
        "model-lora16-android.litertlm" to 300_000_000L, // ~327 MB LoRA-capable base
        "control-r4-rank16.tflite" to 9_000_000L,        // ~9.5 MB Control adapter
        "nemotron-multilingual-encoder.tflite" to 600_000_000L, // ~623 MB INT8
        "nemotron-multilingual-decoder.tflite" to 50_000_000L,  // ~60 MB
        "nemotron-multilingual-joint.tflite" to 30_000_000L,    // ~38 MB
    )

    @VisibleForTesting
    internal fun isValidModel(file: File, filename: String): Boolean {
        if (file.length() == 0L) return false

        // speech-core passes one [1, 256] float32 style vector to Kokoro.
        // Upstream voice tables are much larger and must be compacted before
        // publication; accepting one here would silently read the wrong row.
        if (
            filename.startsWith("voices/") &&
            filename.endsWith(".bin") &&
            file.length() != KOKORO_VOICE_BYTES
        ) {
            return false
        }

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

    private const val KOKORO_VOICE_BYTES = 256L * 4L

    private fun LOGI(msg: String) = Log.i("Speech", msg)
}
