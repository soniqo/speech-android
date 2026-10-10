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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Robolectric tests for [ModelDownloadWorker]. Mocks the [ModelManager]
 * singleton so the worker's doWork() contract can be asserted without network
 * or file-system access.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelDownloadWorkerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockkObject(ModelManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `success returns model dir in output data`() = runBlocking {
        coEvery {
            ModelManager.ensureModels(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "/fake/model/dir"

        val worker = TestListenableWorkerBuilder<ModelDownloadWorker>(context)
            .setInputData(workDataOf(ModelDownloadWorker.KEY_PRECISION to "INT8"))
            .build()

        val result = worker.doWork()

        assertTrue("expected Success, got $result", result is ListenableWorker.Result.Success)
        val output = (result as ListenableWorker.Result.Success).outputData
        assertEquals("/fake/model/dir", output.getString(ModelDownloadWorker.KEY_MODEL_DIR))
    }

    @Test
    fun `io exception returns retry`() = runBlocking {
        // Transient network/disk failures go back to WorkManager so it
        // reschedules with exponential backoff.
        coEvery {
            ModelManager.ensureModels(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws IOException("network down")

        val worker = TestListenableWorkerBuilder<ModelDownloadWorker>(context).build()
        val result = worker.doWork()

        assertTrue("expected Retry, got $result", result is ListenableWorker.Result.Retry)
    }

    @Test
    fun `generic throwable returns failure with message`() = runBlocking {
        // Non-IO exceptions are not transient — Failure carries the message so
        // the host activity can surface it.
        coEvery {
            ModelManager.ensureModels(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("models corrupt")

        val worker = TestListenableWorkerBuilder<ModelDownloadWorker>(context).build()
        val result = worker.doWork()

        assertTrue("expected Failure, got $result", result is ListenableWorker.Result.Failure)
        val output = (result as ListenableWorker.Result.Failure).outputData
        assertEquals("models corrupt", output.getString(ModelDownloadWorker.KEY_ERROR))
    }

    @Test
    fun `missing or invalid inputs fall back to INT8 and short-turn Kokoro`() = runBlocking {
        coEvery {
            ModelManager.ensureModels(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "/fake"

        TestListenableWorkerBuilder<ModelDownloadWorker>(context).build().doWork()
        TestListenableWorkerBuilder<ModelDownloadWorker>(context)
            .setInputData(workDataOf(ModelDownloadWorker.KEY_PRECISION to "NOT_A_PRECISION"))
            .build()
            .doWork()

        coVerify(exactly = 2) {
            ModelManager.ensureModels(
                any(),
                ModelPrecision.INT8,
                any(),
                any(),
                TtsModel.KOKORO_SHORT_TURN,
                any(),
                false,
                false,
                true,
            )
        }
    }

    @Test
    fun `model inputs are passed to ModelManager`() = runBlocking {
        coEvery {
            ModelManager.ensureModels(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "/fake"

        val worker = TestListenableWorkerBuilder<ModelDownloadWorker>(context)
            .setInputData(workDataOf(
                ModelDownloadWorker.KEY_PRECISION to "INT8",
                ModelDownloadWorker.KEY_STT_MODEL to "PARAKEET",
                ModelDownloadWorker.KEY_STT_BACKEND to "ONNX",
                ModelDownloadWorker.KEY_TTS_MODEL to "SUPERTONIC",
            ))
            .build()

        worker.doWork()

        coVerify(exactly = 1) {
            ModelManager.ensureModels(
                any(),
                ModelPrecision.INT8,
                SttModel.PARAKEET,
                SttBackend.ONNX,
                TtsModel.SUPERTONIC,
                any(),
                false,
                false,
                true,
            )
        }
    }

    @Test
    fun `Smart Turn input downloads the optional model`() = runBlocking {
        coEvery {
            ModelManager.ensureModels(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "/fake"

        val worker = TestListenableWorkerBuilder<ModelDownloadWorker>(context)
            .setInputData(workDataOf(ModelDownloadWorker.KEY_ENABLE_SMART_TURN to true))
            .build()

        worker.doWork()

        coVerify(exactly = 1) {
            ModelManager.ensureModels(
                any(), any(), any(), any(), any(), any(), any(), true, true,
            )
        }
    }

    @Test
    fun `control lora input downloads selected llm profile`() = runBlocking {
        coEvery {
            ModelManager.ensureModels(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "/fake"
        coEvery {
            ModelManager.ensureLlmModels(any(), any(), any())
        } returns "/fake/model-lora16-android.litertlm"

        val worker = TestListenableWorkerBuilder<ModelDownloadWorker>(context)
            .setInputData(workDataOf(
                ModelDownloadWorker.KEY_INCLUDE_LLM to true,
                ModelDownloadWorker.KEY_LLM_MODEL to
                    LlmModel.FUNCTIONGEMMA_CONTROL_LORA.name,
            ))
            .build()

        worker.doWork()

        coVerify(exactly = 1) {
            ModelManager.ensureLlmModels(
                any(),
                LlmModel.FUNCTIONGEMMA_CONTROL_LORA,
                any(),
            )
        }
    }

    @Test
    fun `enhancer opt out reaches download planning and execution`() = runBlocking {
        coEvery {
            ModelManager.ensureModels(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "/fake"

        val worker = TestListenableWorkerBuilder<ModelDownloadWorker>(context)
            .setInputData(workDataOf(ModelDownloadWorker.KEY_ENABLE_ENHANCER to false))
            .build()

        assertTrue(worker.doWork() is ListenableWorker.Result.Success)
        coVerify(exactly = 1) {
            ModelManager.ensureModels(any(), any(), any(), any(), any(), any(), any(), any(), false)
            ModelManager.plannedModelBytes(any(), any(), any(), any(), any(), any(), false)
        }
    }

    @Test
    fun `enhancer flag separates pipeline requests and leaves standalone names unchanged`() {
        assertTrue(
            ModelDownloadWorker.request().workSpec.input
                .getBoolean(ModelDownloadWorker.KEY_ENABLE_ENHANCER, false),
        )
        assertEquals(
            false,
            ModelDownloadWorker.request(enableEnhancer = false).workSpec.input
                .getBoolean(ModelDownloadWorker.KEY_ENABLE_ENHANCER, true),
        )
        assertNotEquals(
            ModelDownloadWorker.uniqueName(),
            ModelDownloadWorker.uniqueName(enableEnhancer = false),
        )
        assertEquals(
            ModelDownloadWorker.uniqueName(includePipeline = false, includeVad = true),
            ModelDownloadWorker.uniqueName(
                includePipeline = false, includeVad = true, enableEnhancer = false,
            ),
        )
    }

    @Test
    fun `request builds input data without network constraint`() {
        val req = ModelDownloadWorker.request(
            precision = ModelPrecision.INT8,
            sttModel = SttModel.PARAKEET,
            sttBackend = SttBackend.ONNX,
            ttsModel = TtsModel.KOKORO,
            includeLlm = true,
            llmModel = LlmModel.FUNCTIONGEMMA_CONTROL_LORA,
            enableSmartTurn = true,
        )

        assertEquals(
            "INT8",
            req.workSpec.input.getString(ModelDownloadWorker.KEY_PRECISION),
        )
        assertEquals(
            "PARAKEET",
            req.workSpec.input.getString(ModelDownloadWorker.KEY_STT_MODEL),
        )
        assertEquals(
            "ONNX",
            req.workSpec.input.getString(ModelDownloadWorker.KEY_STT_BACKEND),
        )
        assertEquals(
            "KOKORO",
            req.workSpec.input.getString(ModelDownloadWorker.KEY_TTS_MODEL),
        )
        assertTrue(req.workSpec.input.getBoolean(ModelDownloadWorker.KEY_INCLUDE_LLM, false))
        assertTrue(req.workSpec.input.getBoolean(ModelDownloadWorker.KEY_ENABLE_SMART_TURN, false))
        assertEquals(
            "FUNCTIONGEMMA_CONTROL_LORA",
            req.workSpec.input.getString(ModelDownloadWorker.KEY_LLM_MODEL),
        )
        // No JobScheduler network constraint — the worker handles network
        // failures itself via IOException → retry. See KDoc on `request()`.
        assertEquals(
            androidx.work.NetworkType.NOT_REQUIRED,
            req.workSpec.constraints.requiredNetworkType,
        )
    }

    @Test
    fun `request defaults to short-turn Kokoro`() {
        val req = ModelDownloadWorker.request()

        assertEquals(
            "KOKORO_SHORT_TURN",
            req.workSpec.input.getString(ModelDownloadWorker.KEY_TTS_MODEL),
        )
    }

    @Test
    fun `unique name includes non-default model set`() {
        assertEquals(
            ModelDownloadWorker.UNIQUE_NAME,
            ModelDownloadWorker.uniqueName(),
        )
        assertNotEquals(
            ModelDownloadWorker.UNIQUE_NAME,
            ModelDownloadWorker.uniqueName(precision = ModelPrecision.FP32),
        )
        assertNotEquals(
            ModelDownloadWorker.UNIQUE_NAME,
            ModelDownloadWorker.uniqueName(sttModel = SttModel.PARAKEET),
        )
        assertNotEquals(
            ModelDownloadWorker.UNIQUE_NAME,
            ModelDownloadWorker.uniqueName(enableSmartTurn = true),
        )
        assertNotEquals(
            ModelDownloadWorker.uniqueName(sttModel = SttModel.PARAKEET),
            ModelDownloadWorker.uniqueName(
                precision = ModelPrecision.FP32,
                sttModel = SttModel.PARAKEET,
            ),
        )
        assertNotEquals(
            ModelDownloadWorker.uniqueName(includeLlm = true),
            ModelDownloadWorker.uniqueName(
                includeLlm = true,
                llmModel = LlmModel.FUNCTIONGEMMA_CONTROL_LORA,
            ),
        )
    }
}
