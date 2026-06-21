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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Robolectric tests for [ModelDownloadWorker].
 *
 * Mocks the [ModelManager] singleton via mockk so the worker can be exercised
 * without touching the network or the file system. Uses
 * [TestListenableWorkerBuilder] which gives the worker a real `Context` and
 * stubs out the `setForeground` / `setProgress` plumbing — sufficient to
 * assert the doWork() result contract.
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
    fun doWork_success_returnsModelDirInOutputData() = runBlocking {
        coEvery {
            ModelManager.ensureModels(any(), any(), any(), any(), any())
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
    fun doWork_ioException_returnsRetry() = runBlocking {
        // The worker should bubble transient network/disk failures up to
        // WorkManager so it reschedules with exponential backoff.
        coEvery {
            ModelManager.ensureModels(any(), any(), any(), any(), any())
        } throws IOException("network down")

        val worker = TestListenableWorkerBuilder<ModelDownloadWorker>(context).build()
        val result = worker.doWork()

        assertTrue("expected Retry, got $result", result is ListenableWorker.Result.Retry)
    }

    @Test
    fun doWork_genericThrowable_returnsFailureWithMessage() = runBlocking {
        // Non-IO exceptions are not transient (e.g. corrupt manifest, OOM)
        // — emit Failure with the message in outputData so the host activity
        // can surface a useful error.
        coEvery {
            ModelManager.ensureModels(any(), any(), any(), any(), any())
        } throws IllegalStateException("models corrupt")

        val worker = TestListenableWorkerBuilder<ModelDownloadWorker>(context).build()
        val result = worker.doWork()

        assertTrue("expected Failure, got $result", result is ListenableWorker.Result.Failure)
        val output = (result as ListenableWorker.Result.Failure).outputData
        assertEquals("models corrupt", output.getString(ModelDownloadWorker.KEY_ERROR))
    }

    @Test
    fun doWork_invalidPrecisionInput_defaultsToInt8() = runBlocking {
        coEvery {
            ModelManager.ensureModels(any(), any(), any(), any(), any())
        } returns "/fake"

        val worker = TestListenableWorkerBuilder<ModelDownloadWorker>(context)
            .setInputData(workDataOf(ModelDownloadWorker.KEY_PRECISION to "NOT_A_PRECISION"))
            .build()

        worker.doWork()

        coVerify(exactly = 1) {
            ModelManager.ensureModels(any(), ModelPrecision.INT8, any(), any(), any())
        }
    }

    @Test
    fun doWork_missingPrecisionInput_defaultsToInt8() = runBlocking {
        coEvery {
            ModelManager.ensureModels(any(), any(), any(), any(), any())
        } returns "/fake"

        val worker = TestListenableWorkerBuilder<ModelDownloadWorker>(context).build()
        worker.doWork()

        coVerify(exactly = 1) {
            ModelManager.ensureModels(any(), ModelPrecision.INT8, any(), any(), any())
        }
    }

    @Test
    fun request_buildsRequestWithPrecisionInputDataAndNoNetworkConstraint() {
        val req = ModelDownloadWorker.request(ModelPrecision.INT8)

        assertEquals(
            "INT8",
            req.workSpec.input.getString(ModelDownloadWorker.KEY_PRECISION),
        )
        // No JobScheduler network constraint — the worker handles network
        // failures itself via IOException → retry. See KDoc on `request()`.
        assertEquals(
            androidx.work.NetworkType.NOT_REQUIRED,
            req.workSpec.constraints.requiredNetworkType,
        )
    }
}
