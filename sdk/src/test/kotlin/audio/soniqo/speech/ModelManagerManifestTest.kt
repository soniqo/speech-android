package audio.soniqo.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

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
    fun speechConfigDefault_usesLowMemoryParakeetEou() {
        assertEquals(SttModel.PARAKEET_EOU, SpeechConfig().sttModel)
    }

    @Test
    fun defaultSttManifest_usesParakeetEouBundle() {
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
    fun modelSetKey_changesWhenSttModelChanges() {
        assertNotEquals(
            modelSetKey(sttModel = SttModel.PARAKEET_EOU),
            modelSetKey(sttModel = SttModel.PARAKEET),
        )
    }

    @Test
    fun modelDirName_separatesNonDefaultModelSets() {
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
    fun ttsOnlyManifest_usesKokoroFilesWithoutPipelineAssets() {
        val files = ttsModelFiles(TtsModel.KOKORO)

        assertEquals(
            listOf(
                ModelManager.ModelFile("Kokoro-82M-ONNX", "kokoro-e2e.onnx"),
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
            ),
            files,
        )
        assertFalse(files.any { it.repo.contains("Parakeet") || it.repo.contains("Silero") })
        assertFalse(files.any { it.filename.startsWith("deepfilter") })
    }

    @Test
    fun ttsOnlyModelSetKey_isSeparateFromPipelineCacheKey() {
        assertNotEquals(
            modelSetKey(ttsModel = TtsModel.KOKORO),
            ttsModelSetKey(TtsModel.KOKORO),
        )
    }

    @Test
    fun supertonicManifest_includesCompleteVoiceCatalogFromLiteRtRepo() {
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

    @Suppress("UNCHECKED_CAST")
    private fun modelFiles(
        sttModel: SttModel = SttModel.PARAKEET_EOU,
        ttsModel: TtsModel = TtsModel.KOKORO,
    ): List<ModelManager.ModelFile> {
        val models = ModelManager::class.java.getDeclaredMethod(
            "models",
            ModelPrecision::class.java,
            SttModel::class.java,
            SttBackend::class.java,
            TtsModel::class.java,
        )
        models.isAccessible = true
        return models.invoke(
            ModelManager,
            ModelPrecision.INT8,
            sttModel,
            SttBackend.ONNX,
            ttsModel,
        ) as List<ModelManager.ModelFile>
    }

    private fun modelSetKey(
        precision: ModelPrecision = ModelPrecision.INT8,
        sttModel: SttModel = SttModel.PARAKEET_EOU,
        sttBackend: SttBackend = SttBackend.ONNX,
        ttsModel: TtsModel = TtsModel.KOKORO,
    ): String {
        val method = ModelManager::class.java.getDeclaredMethod(
            "modelSetKey",
            ModelPrecision::class.java,
            SttModel::class.java,
            SttBackend::class.java,
            TtsModel::class.java,
        )
        method.isAccessible = true
        return method.invoke(
            ModelManager,
            precision,
            sttModel,
            sttBackend,
            ttsModel,
        ) as String
    }

    private fun modelDirName(
        precision: ModelPrecision = ModelPrecision.INT8,
        sttModel: SttModel = SttModel.PARAKEET_EOU,
        sttBackend: SttBackend = SttBackend.ONNX,
        ttsModel: TtsModel = TtsModel.KOKORO,
    ): String {
        val method = ModelManager::class.java.getDeclaredMethod(
            "modelDirName",
            ModelPrecision::class.java,
            SttModel::class.java,
            SttBackend::class.java,
            TtsModel::class.java,
        )
        method.isAccessible = true
        return method.invoke(
            ModelManager,
            precision,
            sttModel,
            sttBackend,
            ttsModel,
        ) as String
    }

    @Suppress("UNCHECKED_CAST")
    private fun ttsModelFiles(ttsModel: TtsModel): List<ModelManager.ModelFile> {
        val method = ModelManager::class.java.getDeclaredMethod(
            "ttsModels",
            TtsModel::class.java,
        )
        method.isAccessible = true
        return method.invoke(ModelManager, ttsModel) as List<ModelManager.ModelFile>
    }

    private fun ttsModelSetKey(ttsModel: TtsModel): String {
        val method = ModelManager::class.java.getDeclaredMethod(
            "ttsModelSetKey",
            TtsModel::class.java,
        )
        method.isAccessible = true
        return method.invoke(ModelManager, ttsModel) as String
    }
}
