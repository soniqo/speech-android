package audio.soniqo.speech.demo

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import audio.soniqo.speech.service.SpeechTextToSpeechService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TtsEngineManifestTest {

    @Suppress("DEPRECATION")
    @Test
    fun demoManifest_registersFrameworkTtsEngine() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val services = context.packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
            PackageManager.GET_META_DATA,
        )

        val service = services.firstOrNull {
            it.serviceInfo.name == SpeechTextToSpeechService::class.java.name
        }

        assertNotNull("TTS engine service should be discoverable", service)
        assertEquals(context.packageName, service!!.serviceInfo.packageName)
        assertTrue(service.serviceInfo.exported)
        assertTrue(service.serviceInfo.metaData.containsKey(TextToSpeech.Engine.SERVICE_META_DATA))
        assertTrue(service.serviceInfo.metaData.getInt(TextToSpeech.Engine.SERVICE_META_DATA) != 0)
    }
}
