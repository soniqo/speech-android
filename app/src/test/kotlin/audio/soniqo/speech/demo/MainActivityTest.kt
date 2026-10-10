package audio.soniqo.speech.demo

import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import audio.soniqo.speech.PipelineMode
import audio.soniqo.speech.PipelineState
import audio.soniqo.speech.SpeechConfig
import audio.soniqo.speech.SpeechEvent
import audio.soniqo.speech.SpeechPipeline
import audio.soniqo.speech.SttModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivityTest {

    @Test
    fun initPipeline_loadsModelsOffMainThread() {
        val controller = buildActivity()
        val activity = controller.get()
        val pipeline = FakePipeline()
        val ranOnMainThread = AtomicBoolean(true)
        val capturedConfig = AtomicReference<SpeechConfig>()
        activity.pipelineFactory = { config ->
            capturedConfig.set(config)
            ranOnMainThread.set(Looper.myLooper() == Looper.getMainLooper())
            pipeline
        }

        try {
            callPrivate(activity, "initPipeline", "/unused/models")
            assertTrue("pipeline did not become ready", await { micButton(activity).isEnabled })
            assertFalse("Echo must load models off the UI thread", ranOnMainThread.get())
            assertEquals(SttModel.PARAKEET, capturedConfig.get()?.sttModel)
            assertEquals(PipelineMode.ECHO, capturedConfig.get()?.pipelineMode)
            assertEquals(1, pipeline.startCalls.get())
            assertEquals("tap to talk", statusView(activity).text.toString())

            assertTrue(pipeline.eventFlow.tryEmit(SpeechEvent.SpeechStarted))
            assertTrue("speech event did not update the UI", await {
                statusView(activity).text.toString() == "listening..."
            })
        } finally {
            destroyActivity(controller)
        }
        assertEquals(1, pipeline.closeCalls.get())
    }

    @Test
    fun slowModelLoading_keepsUiResponsiveAndMicDisabledUntilReady() {
        val controller = buildActivity()
        val activity = controller.get()
        val pipeline = FakePipeline()
        val factoryStarted = CountDownLatch(1)
        val finishLoading = CountDownLatch(1)
        activity.pipelineFactory = {
            factoryStarted.countDown()
            check(finishLoading.await(5, TimeUnit.SECONDS)) { "test did not release model loading" }
            pipeline
        }

        try {
            callPrivate(activity, "initPipeline", "/unused/models")
            assertTrue("model loading did not start", await { factoryStarted.count == 0L })

            val uiCallback = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { uiCallback.countDown() }
            assertTrue("UI stopped responding while models loaded", await { uiCallback.count == 0L })
            assertFalse(micButton(activity).isEnabled)
            assertEquals("loading models...", statusView(activity).text.toString())
            assertEquals(0, pipeline.startCalls.get())

            finishLoading.countDown()
            assertTrue("pipeline did not become ready", await { micButton(activity).isEnabled })
            assertEquals(1, pipeline.startCalls.get())
        } finally {
            finishLoading.countDown()
            destroyActivity(controller)
        }
    }

    @Test
    fun destructionDuringModelLoading_closesLatePipelineOffMainThread() {
        val controller = buildActivity()
        val activity = controller.get()
        val pipeline = FakePipeline()
        val factoryStarted = CountDownLatch(1)
        val finishLoading = CountDownLatch(1)
        var destroyed = false
        activity.pipelineFactory = {
            factoryStarted.countDown()
            check(finishLoading.await(5, TimeUnit.SECONDS)) { "test did not release model loading" }
            pipeline
        }

        try {
            callPrivate(activity, "initPipeline", "/unused/models")
            assertTrue("model loading did not start", await { factoryStarted.count == 0L })
            destroyActivity(controller)
            destroyed = true
            finishLoading.countDown()

            assertTrue("late pipeline was leaked", await { pipeline.closeCalls.get() == 1 })
            assertFalse("cancelled model cleanup blocked the UI", pipeline.closedOnMainThread.get())
            assertEquals(0, pipeline.startCalls.get())
            assertNull(field(activity, "pipeline"))
            assertFalse(micButton(activity).isEnabled)
            assertFalse("cancellation was reported as a crash", File(activity.filesDir, "crash.log").exists())
        } finally {
            finishLoading.countDown()
            if (!destroyed) {
                destroyActivity(controller)
            }
        }
    }

    private fun buildActivity(): ActivityController<MainActivity> {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        // Build the screen without onCreate, which would start a model download.
        callPrivate(activity, "buildUI")
        return controller
    }

    private fun destroyActivity(controller: ActivityController<MainActivity>) {
        // Without onCreate, lifecycle callbacks aren't registered. Cancel as Android would on destruction.
        controller.get().lifecycleScope.cancel()
        controller.destroy()
    }

    private fun micButton(activity: MainActivity) = field(activity, "micButton") as TextView

    private fun statusView(activity: MainActivity) = field(activity, "statusView") as TextView

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun callPrivate(target: Any, name: String, vararg args: Any) {
        val types = args.map { it.javaClass }.toTypedArray()
        target.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(target, *args)
    }

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        return condition()
    }

    private class FakePipeline : SpeechPipeline {
        val eventFlow = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 16)
        override val events: SharedFlow<SpeechEvent> = eventFlow
        override val state = PipelineState.Idle
        override val nnapiFallbackReason: String? = null
        val startCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        val closedOnMainThread = AtomicBoolean(true)

        override fun start() { startCalls.incrementAndGet() }
        override fun stop() = Unit
        override fun pushAudio(samples: FloatArray) = Unit
        override fun resumeListening() = Unit
        override fun close() {
            closedOnMainThread.set(Looper.myLooper() == Looper.getMainLooper())
            closeCalls.incrementAndGet()
        }
    }
}
