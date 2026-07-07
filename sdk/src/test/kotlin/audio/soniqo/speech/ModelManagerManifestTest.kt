package audio.soniqo.speech

import org.junit.Assert.assertEquals
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
    fun supertonicManifest_includesCompleteVoiceCatalogFromLiteRtRepo() {
        val styleFiles = modelFiles(TtsModel.SUPERTONIC)
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
    private fun modelFiles(ttsModel: TtsModel): List<ModelManager.ModelFile> {
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
            SttModel.PARAKEET,
            SttBackend.ONNX,
            ttsModel,
        ) as List<ModelManager.ModelFile>
    }
}
