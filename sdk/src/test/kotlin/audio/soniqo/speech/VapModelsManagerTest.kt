package audio.soniqo.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VapModelsManagerTest {
    @Test
    fun `VAP has a dedicated pinned bundle with attribution`() {
        val files = ModelManager.vapModels()
        assertEquals(setOf("vap.onnx", "config.json", "LICENSE", "NOTICE"), files.map { it.filename }.toSet())
        assertTrue(files.all { it.repo == "soniqo/VAP-OTO-ONNX" && it.revision != "main" })
        assertTrue(ModelManager.vapModelSetKey().contains("profile=VAP"))
    }

    @Test
    fun `qualified repositories preserve their owner and selected mirror`() {
        val file = ModelManager.vapModels().first()
        assertEquals("https://example.org/soniqo/VAP-OTO-ONNX/resolve/v1.0.0/vap.onnx",
            ModelManager.modelUrl(file, "https://example.org"))
        val existing = ModelManager.ModelFile("Silero-VAD-v5-ONNX", "silero-vad.onnx")
        assertEquals("https://example.org/soniqo/Silero-VAD-v5-ONNX/resolve/main/silero-vad.onnx",
            ModelManager.modelUrl(existing, "https://example.org"))
    }
}
