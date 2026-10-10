package audio.soniqo.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [ModelDownloadWorker] fetching the standalone meeting-transcription sets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelDownloadWorkerMeetingModelsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockkObject(ModelManager)
        coEvery { ModelManager.ensureVadModels(any(), any()) } returns "/vad"
        coEvery { ModelManager.ensureTranscriberModels(any(), any(), any(), any()) } returns "/transcriber"
        coEvery { ModelManager.ensureDiarizerModels(any(), any()) } returns "/diarizer"
        coEvery { ModelManager.ensureSpeakerEmbeddingModels(any(), any()) } returns "/embedding"
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `meeting sets download without the pipeline`() = runBlocking {
        val worker = TestListenableWorkerBuilder<ModelDownloadWorker>(context)
            .setInputData(workDataOf(
                ModelDownloadWorker.KEY_INCLUDE_PIPELINE to false,
                ModelDownloadWorker.KEY_INCLUDE_VAD to true,
                ModelDownloadWorker.KEY_INCLUDE_TRANSCRIBER to true,
                ModelDownloadWorker.KEY_INCLUDE_DIARIZER to true,
                ModelDownloadWorker.KEY_INCLUDE_SPEAKER_EMBEDDING to true,
            ))
            .build()

        val result = worker.doWork()

        assertTrue("expected Success, got $result", result is ListenableWorker.Result.Success)
        val output = (result as ListenableWorker.Result.Success).outputData
        assertNull(output.getString(ModelDownloadWorker.KEY_MODEL_DIR))
        assertEquals("/vad", output.getString(ModelDownloadWorker.KEY_VAD_MODEL_DIR))
        assertEquals("/transcriber", output.getString(ModelDownloadWorker.KEY_TRANSCRIBER_MODEL_DIR))
        assertEquals("/diarizer", output.getString(ModelDownloadWorker.KEY_DIARIZER_MODEL_DIR))
        assertEquals("/embedding", output.getString(ModelDownloadWorker.KEY_SPEAKER_EMBEDDING_MODEL_DIR))
        coVerify(exactly = 0) {
            ModelManager.ensureModels(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        coVerify { ModelManager.ensureTranscriberModels(any(), SttBackend.LITERT, ModelPrecision.INT8, any()) }
    }

    @Test
    fun `the transcriber bundle requested reaches the download`() = runBlocking {
        val worker = TestListenableWorkerBuilder<ModelDownloadWorker>(context)
            .setInputData(workDataOf(
                ModelDownloadWorker.KEY_INCLUDE_PIPELINE to false,
                ModelDownloadWorker.KEY_INCLUDE_TRANSCRIBER to true,
                ModelDownloadWorker.KEY_TRANSCRIBER_BACKEND to SttBackend.ONNX.name,
                ModelDownloadWorker.KEY_TRANSCRIBER_PRECISION to ModelPrecision.FP32.name,
            ))
            .build()

        assertTrue(worker.doWork() is ListenableWorker.Result.Success)
        coVerify { ModelManager.ensureTranscriberModels(any(), SttBackend.ONNX, ModelPrecision.FP32, any()) }
        coVerify(exactly = 0) { ModelManager.ensureDiarizerModels(any(), any()) }
    }

    @Test
    fun `the pipeline still downloads by default`() = runBlocking {
        coEvery {
            ModelManager.ensureModels(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "/pipeline"

        val worker = TestListenableWorkerBuilder<ModelDownloadWorker>(context).build()
        val result = worker.doWork() as ListenableWorker.Result.Success

        assertEquals("/pipeline", result.outputData.getString(ModelDownloadWorker.KEY_MODEL_DIR))
        coVerify(exactly = 0) { ModelManager.ensureTranscriberModels(any(), any(), any(), any()) }
    }

    @Test
    fun `the default request keeps the standard unique name`() {
        assertEquals(ModelDownloadWorker.UNIQUE_NAME, ModelDownloadWorker.uniqueName())
    }

    @Test
    fun `pipeline names are unchanged when no standalone set is added`() {
        assertEquals(
            "${ModelDownloadWorker.UNIQUE_NAME}.INT8.NEMOTRON_MULTILINGUAL.LITERT.KOKORO",
            ModelDownloadWorker.uniqueName(
                sttModel = SttModel.NEMOTRON_MULTILINGUAL,
                sttBackend = SttBackend.LITERT,
            ),
        )
    }

    @Test
    fun `meeting sets get a unique name of their own`() {
        assertEquals(
            "${ModelDownloadWorker.UNIQUE_NAME}.noPipeline.vad.transcriber.LITERT.INT8" +
                ".diarizer.speakerEmbedding",
            ModelDownloadWorker.uniqueName(
                includePipeline = false,
                includeVad = true,
                includeTranscriber = true,
                includeDiarizer = true,
                includeSpeakerEmbedding = true,
            ),
        )
    }
}
