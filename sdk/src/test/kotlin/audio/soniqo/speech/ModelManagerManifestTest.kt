package audio.soniqo.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.RandomAccessFile
import java.nio.file.Files

/**
 * Regression coverage for issue #36.
 *
 * The Android downloader fetches Supertonic-3 LiteRT assets from a single
 * Hugging Face repo. If the full voice-style catalog is not in that manifest,
 * `ensureModels(..., ttsModel = TtsModel.SUPERTONIC)` can fail with HTTP 404s
 * before the native Supertonic engine is created.
 */
class ModelManagerManifestTest {

    @Test
    fun `default SpeechConfig uses low-memory Parakeet EOU`() {
        assertEquals(SttModel.PARAKEET_EOU, SpeechConfig().sttModel)
        assertEquals(TtsModel.KOKORO_SHORT_TURN, SpeechConfig().ttsModel)
        assertFalse(SpeechConfig().useNnapi)
        assertEquals(TtsModel.KOKORO_SHORT_TURN, SpeechSynthesizerConfig().ttsModel)
        assertFalse(SpeechSynthesizerConfig().useNnapi)
    }

    @Test
    fun `tts native ids are stable`() {
        assertEquals(0, TtsModel.KOKORO.nativeId)
        assertEquals(1, TtsModel.SUPERTONIC.nativeId)
        assertEquals(2, TtsModel.KOKORO_SHORT_TURN.nativeId)
        assertEquals(3, TtsModel.POCKET.nativeId)
    }

    @Test
    fun `default stt manifest uses Parakeet EOU bundle`() {
        val eouFiles = modelFiles(sttModel = SttModel.PARAKEET_EOU)
            .filter { it.repo == "Parakeet-EOU-120M-ONNX-INT8" }

        val expected = listOf(
            ModelManager.ModelFile("Parakeet-EOU-120M-ONNX-INT8", "parakeet-eou-encoder.onnx"),
            ModelManager.ModelFile("Parakeet-EOU-120M-ONNX-INT8", "parakeet-eou-decoder.onnx"),
            ModelManager.ModelFile("Parakeet-EOU-120M-ONNX-INT8", "parakeet-eou-joint.onnx"),
            ModelManager.ModelFile("Parakeet-EOU-120M-ONNX-INT8", "vocab.json"),
            ModelManager.ModelFile("Parakeet-EOU-120M-ONNX-INT8", "config.json"),
        )

        assertEquals(expected, eouFiles)
    }

    @Test
    fun `canary manifest ships the int8 pair and its decode contract`() {
        val canaryFiles = modelFiles(sttModel = SttModel.CANARY)
            .filter { it.repo == "Canary-180M-Flash-ONNX" }

        // config.json is not optional here: the wrapper reads the decode
        // prompt, cache dimensions and end-of-text id out of the bundle, and
        // refuses to construct without them.
        val expected = listOf(
            ModelManager.ModelFile("Canary-180M-Flash-ONNX", "canary-encoder-int8.onnx"),
            ModelManager.ModelFile("Canary-180M-Flash-ONNX", "canary-decoder-int8.onnx"),
            ModelManager.ModelFile("Canary-180M-Flash-ONNX", "vocab.json"),
            ModelManager.ModelFile("Canary-180M-Flash-ONNX", "config.json"),
        )

        assertEquals(expected, canaryFiles)
    }

    @Test
    fun `canary keeps its int8 filenames at fp32 precision`() {
        // The bundle publishes one quantization, so precision must not rewrite
        // the filenames the way it does for Parakeet.
        assertEquals(
            modelFiles(sttModel = SttModel.CANARY).filter { it.repo == "Canary-180M-Flash-ONNX" },
            modelFiles(precision = ModelPrecision.FP32, sttModel = SttModel.CANARY)
                .filter { it.repo == "Canary-180M-Flash-ONNX" },
        )
    }

    @Test
    fun `model set key changes when stt model changes`() {
        assertNotEquals(
            modelSetKey(sttModel = SttModel.PARAKEET_EOU),
            modelSetKey(sttModel = SttModel.PARAKEET),
        )
    }

    @Test
    fun `model dir name separates non-default model sets`() {
        assertEquals(
            "models",
            modelDirName(sttModel = SttModel.PARAKEET_EOU),
        )
        assertNotEquals(
            modelDirName(sttModel = SttModel.PARAKEET_EOU),
            modelDirName(sttModel = SttModel.PARAKEET),
        )
    }

    @Test
    fun `tts-only manifest uses Kokoro files without pipeline assets`() {
        val files = ModelManager.ttsModels(TtsModel.KOKORO)

        assertEquals(
            listOf(
                ModelManager.ModelFile("Kokoro-82M-ONNX", "kokoro-e2e.onnx"),
                ModelManager.ModelFile("Kokoro-82M-ONNX", "kokoro-e2e-realtime.onnx"),
                ModelManager.ModelFile("Kokoro-82M-ONNX", "kokoro-e2e.onnx.data"),
                ModelManager.ModelFile("Kokoro-82M-ONNX", "vocab_index.json"),
                ModelManager.ModelFile("Kokoro-82M-ONNX", "us_gold.json"),
                ModelManager.ModelFile("Kokoro-82M-ONNX", "us_silver.json"),
                ModelManager.ModelFile("Kokoro-82M-ONNX", "dict_fr.json"),
                ModelManager.ModelFile("Kokoro-82M-ONNX", "dict_es.json"),
                ModelManager.ModelFile("Kokoro-82M-ONNX", "dict_it.json"),
                ModelManager.ModelFile("Kokoro-82M-ONNX", "dict_pt.json"),
                ModelManager.ModelFile("Kokoro-82M-ONNX", "dict_hi.json"),
                ModelManager.ModelFile("Kokoro-82M-ONNX", "voices/af_heart.bin"),
                ModelManager.ModelFile("Kokoro-82M-ONNX", "voices/ff_siwis.bin"),
                ModelManager.ModelFile("Kokoro-82M-ONNX", "voices/ef_dora.bin"),
                ModelManager.ModelFile("Kokoro-82M-ONNX", "voices/if_sara.bin"),
                ModelManager.ModelFile("Kokoro-82M-ONNX", "voices/pf_dora.bin"),
                ModelManager.ModelFile("Kokoro-82M-ONNX", "voices/hf_alpha.bin"),
                ModelManager.ModelFile("Kokoro-82M-ONNX", "voices/jf_alpha.bin"),
                ModelManager.ModelFile("Kokoro-82M-ONNX", "voices/zf_xiaobei.bin"),
            ),
            files,
        )
        assertFalse(files.any { it.repo.contains("Parakeet") || it.repo.contains("Silero") })
        assertFalse(files.any { it.filename.startsWith("deepfilter") })
        assertEquals(1, files.count { it.filename == "kokoro-e2e.onnx.data" })
    }

    @Test
    fun `Kokoro runtime voices must contain exactly one style vector`() {
        val dir = Files.createTempDirectory("kokoro-voice-validation").toFile()
        try {
            val valid = dir.resolve("valid.bin").apply {
                writeBytes(ByteArray(256 * 4) { 1 })
            }
            val truncated = dir.resolve("truncated.bin").apply {
                writeBytes(ByteArray(256 * 4 - 1) { 1 })
            }
            val upstreamTable = dir.resolve("upstream-table.bin").apply {
                writeBytes(ByteArray(510 * 256 * 4) { 1 })
            }

            assertTrue(
                ModelManager.isValidModel(valid, "voices/zf_xiaobei.bin"),
            )
            assertFalse(
                ModelManager.isValidModel(truncated, "voices/zf_xiaobei.bin"),
            )
            assertFalse(
                ModelManager.isValidModel(upstreamTable, "voices/zf_xiaobei.bin"),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `kokoro profiles share assets caches and worker name`() {
        assertEquals(
            ModelManager.ttsModels(TtsModel.KOKORO),
            ModelManager.ttsModels(TtsModel.KOKORO_SHORT_TURN),
        )
        assertEquals(
            modelSetKey(ttsModel = TtsModel.KOKORO),
            modelSetKey(ttsModel = TtsModel.KOKORO_SHORT_TURN),
        )
        assertEquals(
            ModelManager.ttsModelSetKey(TtsModel.KOKORO),
            ModelManager.ttsModelSetKey(TtsModel.KOKORO_SHORT_TURN),
        )
        assertEquals(
            modelDirName(ttsModel = TtsModel.KOKORO),
            modelDirName(ttsModel = TtsModel.KOKORO_SHORT_TURN),
        )
        assertEquals(
            ModelDownloadWorker.uniqueName(ttsModel = TtsModel.KOKORO),
            ModelDownloadWorker.uniqueName(ttsModel = TtsModel.KOKORO_SHORT_TURN),
        )
        assertNotEquals(
            ModelManager.ttsModelSetKey(TtsModel.KOKORO_SHORT_TURN),
            ModelManager.ttsModelSetKey(TtsModel.SUPERTONIC),
        )
        assertTrue(
            ModelManager.ttsModels(TtsModel.KOKORO_SHORT_TURN)
                .any { it.filename == "kokoro-e2e-realtime.onnx" },
        )
    }

    @Test
    fun `tts-only model set key is separate from pipeline cache key`() {
        assertNotEquals(
            modelSetKey(ttsModel = TtsModel.KOKORO),
            ModelManager.ttsModelSetKey(TtsModel.KOKORO),
        )
    }

    @Test
    fun `supertonic manifest includes complete voice catalog from LiteRT repo`() {
        val styleFiles = modelFiles(ttsModel = TtsModel.SUPERTONIC)
            .filter { it.filename.startsWith("voice_styles/") }

        val expected = listOf(
            ModelManager.ModelFile("Supertonic-3-LiteRT", "voice_styles/F1.json"),
            ModelManager.ModelFile("Supertonic-3-LiteRT", "voice_styles/F2.json"),
            ModelManager.ModelFile("Supertonic-3-LiteRT", "voice_styles/F3.json"),
            ModelManager.ModelFile("Supertonic-3-LiteRT", "voice_styles/F4.json"),
            ModelManager.ModelFile("Supertonic-3-LiteRT", "voice_styles/F5.json"),
            ModelManager.ModelFile("Supertonic-3-LiteRT", "voice_styles/M1.json"),
            ModelManager.ModelFile("Supertonic-3-LiteRT", "voice_styles/M2.json"),
            ModelManager.ModelFile("Supertonic-3-LiteRT", "voice_styles/M3.json"),
            ModelManager.ModelFile("Supertonic-3-LiteRT", "voice_styles/M4.json"),
            ModelManager.ModelFile("Supertonic-3-LiteRT", "voice_styles/M5.json"),
        )

        assertEquals(expected, styleFiles)
    }

    @Test
    fun `pocket manifest is pinned and namespaced away from stt assets`() {
        val files = ModelManager.ttsModels(TtsModel.POCKET)

        assertEquals(
            listOf(
                "decoder.int8.onnx",
                "encoder.onnx",
                "lm_flow.int8.onnx",
                "lm_main.int8.onnx",
                "text_conditioner.onnx",
                "token_scores.json",
                "vocab.json",
                "LICENSE",
                "manifest.json",
            ),
            files.map { it.filename },
        )
        assertTrue(files.all { it.repo == "Pocket-TTS-100M-ONNX-INT8" })
        assertTrue(files.all { it.revision == "v1.0.0" })
        assertTrue(files.all { it.localFilename == "pocket_tts/${it.filename}" })
        assertNotEquals(
            modelSetKey(ttsModel = TtsModel.KOKORO),
            modelSetKey(ttsModel = TtsModel.POCKET),
        )
        assertNotEquals(
            modelDirName(ttsModel = TtsModel.KOKORO),
            modelDirName(ttsModel = TtsModel.POCKET),
        )
    }

    @Test
    fun `Nemotron LiteRT manifests are pinned to CPU-compatible revisions`() {
        val int8Files = modelFiles(
            precision = ModelPrecision.INT8,
            sttModel = SttModel.NEMOTRON_MULTILINGUAL,
            sttBackend = SttBackend.LITERT,
        ).filter { it.repo.endsWith("-LiteRT-INT8") }
        val fp16Files = modelFiles(
            precision = ModelPrecision.FP32,
            sttModel = SttModel.NEMOTRON_MULTILINGUAL,
            sttBackend = SttBackend.LITERT,
        ).filter { it.repo.endsWith("-LiteRT-FP16") }

        assertTrue(int8Files.isNotEmpty())
        assertTrue(int8Files.all { it.revision == "v1.0.0" })
        assertTrue(fp16Files.isNotEmpty())
        assertTrue(
            fp16Files.all {
                it.revision == "1503a9a1eb75b813b83ba65bf5e9fecea4a46091"
            },
        )
    }

    @Test
    fun `Nemotron LiteRT revision invalidates only its model cache`() {
        val defaultKey = modelSetKey()
        val nemotronInt8Key = modelSetKey(
            sttModel = SttModel.NEMOTRON_MULTILINGUAL,
            sttBackend = SttBackend.LITERT,
        )
        val nemotronFp16Key = modelSetKey(
            precision = ModelPrecision.FP32,
            sttModel = SttModel.NEMOTRON_MULTILINGUAL,
            sttBackend = SttBackend.LITERT,
        )

        assertFalse(defaultKey.contains("sttRevision="))
        assertTrue(nemotronInt8Key.endsWith("|sttRevision=v1.0.0"))
        assertTrue(
            nemotronFp16Key.endsWith(
                "|sttRevision=1503a9a1eb75b813b83ba65bf5e9fecea4a46091",
            ),
        )
        assertNotEquals(nemotronInt8Key, nemotronFp16Key)
    }

    @Test
    fun `Nemotron LiteRT artifacts reject truncated model files`() {
        val dir = Files.createTempDirectory("nemotron-litert-validation").toFile()
        try {
            val encoder = dir.resolve("encoder.tflite").apply {
                RandomAccessFile(this, "rw").use { it.setLength(599_999_999L) }
            }
            val decoder = dir.resolve("decoder.tflite").apply {
                RandomAccessFile(this, "rw").use { it.setLength(49_999_999L) }
            }
            val joint = dir.resolve("joint.tflite").apply {
                RandomAccessFile(this, "rw").use { it.setLength(29_999_999L) }
            }

            assertFalse(
                ModelManager.isValidModel(encoder, "nemotron-multilingual-encoder.tflite"),
            )
            assertFalse(
                ModelManager.isValidModel(decoder, "nemotron-multilingual-decoder.tflite"),
            )
            assertFalse(
                ModelManager.isValidModel(joint, "nemotron-multilingual-joint.tflite"),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `llm manifest keeps standalone FunctionGemma as default`() {
        assertEquals(
            listOf(ModelManager.ModelFile("FunctionGemma-270M-LiteRT-LM", "model.litertlm")),
            ModelManager.llmModels(LlmModel.FUNCTIONGEMMA),
        )
    }

    @Test
    fun `control lora manifest has separate reusable base and adapter`() {
        assertEquals(
            listOf(
                ModelManager.ModelFile(
                    "FunctionGemma-270M-LiteRT-LM",
                    "model-lora16-android.litertlm",
                ),
                ModelManager.ModelFile(
                    "FunctionGemma-270M-LiteRT-LM",
                    "control-r4-rank16.tflite",
                ),
            ),
            ModelManager.llmModels(LlmModel.FUNCTIONGEMMA_CONTROL_LORA),
        )
    }

    @Test
    fun `llm model set key is separate from pipeline and tts cache keys`() {
        val stock = ModelManager.llmModelSetKey(LlmModel.FUNCTIONGEMMA)
        val control = ModelManager.llmModelSetKey(LlmModel.FUNCTIONGEMMA_CONTROL_LORA)
        assertNotEquals(stock, modelSetKey())
        assertNotEquals(stock, ModelManager.ttsModelSetKey(TtsModel.KOKORO))
        assertNotEquals(stock, control)
    }

    private fun modelFiles(
        precision: ModelPrecision = ModelPrecision.INT8,
        sttModel: SttModel = SttModel.PARAKEET_EOU,
        sttBackend: SttBackend = SttBackend.ONNX,
        ttsModel: TtsModel = TtsModel.KOKORO,
    ): List<ModelManager.ModelFile> =
        ModelManager.models(precision, sttModel, sttBackend, ttsModel)

    private fun modelSetKey(
        precision: ModelPrecision = ModelPrecision.INT8,
        sttModel: SttModel = SttModel.PARAKEET_EOU,
        sttBackend: SttBackend = SttBackend.ONNX,
        ttsModel: TtsModel = TtsModel.KOKORO,
    ): String = ModelManager.modelSetKey(precision, sttModel, sttBackend, ttsModel)

    private fun modelDirName(
        sttModel: SttModel = SttModel.PARAKEET_EOU,
        ttsModel: TtsModel = TtsModel.KOKORO,
    ): String = ModelManager.modelDirName(ModelPrecision.INT8, sttModel, SttBackend.ONNX, ttsModel)
}
