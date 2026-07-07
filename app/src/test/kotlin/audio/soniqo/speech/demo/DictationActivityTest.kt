package audio.soniqo.speech.demo

import android.os.Looper
import audio.soniqo.speech.PipelineState
import audio.soniqo.speech.SpeechConfig
import audio.soniqo.speech.SpeechEvent
import audio.soniqo.speech.SpeechPipeline
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DictationActivityTest {

    @Test
    fun initPipeline_loadsNativePipelineOffMainThread() {
        val controller = Robolectric.buildActivity(DictationActivity::class.java)
        val activity = controller.get()
        callPrivate(activity, "buildUI")

        val factoryCalled = CountDownLatch(1)
        val ranOnMainThread = AtomicBoolean(true)
        activity.pipelineFactory = { _: SpeechConfig ->
            ranOnMainThread.set(Looper.myLooper() == Looper.getMainLooper())
            factoryCalled.countDown()
            FakePipeline()
        }

        callPrivate(activity, "initPipeline", "/unused/models")
        assertTrue("pipeline factory was not called", await(factoryCalled))
        assertFalse(
            "DictationActivity must not load SpeechPipeline on the UI thread",
            ranOnMainThread.get(),
        )

        controller.destroy()
    }

    private fun callPrivate(target: Any, name: String, vararg args: Any) {
        val types = args.map { it.javaClass }.toTypedArray()
        val method = target.javaClass.getDeclaredMethod(name, *types)
        method.isAccessible = true
        method.invoke(target, *args)
    }

    private fun await(latch: CountDownLatch): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (latch.count > 0 && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        return latch.await(0, TimeUnit.MILLISECONDS)
    }

    private class FakePipeline : SpeechPipeline {
        override val events: SharedFlow<SpeechEvent> = MutableSharedFlow()
        override val state: PipelineState = PipelineState.Idle
        override val nnapiFallbackReason: String? = null

        override fun start() = Unit
        override fun stop() = Unit
        override fun pushAudio(samples: FloatArray) = Unit
        override fun resumeListening() = Unit
        override fun close() = Unit
    }
}
