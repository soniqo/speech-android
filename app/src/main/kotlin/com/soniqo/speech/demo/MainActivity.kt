package com.soniqo.speech.demo

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.soniqo.speech.ModelManager
import com.soniqo.speech.SpeechConfig
import com.soniqo.speech.SpeechEvent
import com.soniqo.speech.SpeechPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var pipeline: SpeechPipeline? = null
    private var audioRecord: AudioRecord? = null
    private var recording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val textView = TextView(this).apply {
            textSize = 18f
            setPadding(32, 64, 32, 32)
            text = "Loading models..."
        }
        setContentView(textView)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }

        startPipeline(textView)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, results: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 1 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            val textView = findViewById<TextView>(android.R.id.content)
                ?: (window.decorView as? android.view.ViewGroup)
                    ?.getChildAt(0) as? TextView
                ?: return
            startPipeline(textView)
        }
    }

    private fun startPipeline(textView: TextView) {
        lifecycleScope.launch {
            // Download models
            val modelDir = ModelManager.ensureModels(this@MainActivity) { progress ->
                lifecycleScope.launch(Dispatchers.Main) {
                    textView.text = "Downloading ${progress.file}... (${progress.completed}/${progress.totalFiles})"
                }
            }

            textView.text = "Starting pipeline..."

            val config = SpeechConfig(
                modelDir = modelDir,
                useNnapi = true,
            )

            val p = SpeechPipeline(config)
            pipeline = p

            // Collect events
            launch {
                p.events.collect { event ->
                    withContext(Dispatchers.Main) {
                        when (event) {
                            is SpeechEvent.SpeechStarted ->
                                textView.append("\n[listening...]")
                            is SpeechEvent.TranscriptionCompleted ->
                                textView.append("\n> ${event.text} (${event.sttMs.toInt()}ms)")
                            is SpeechEvent.ResponseDone ->
                                textView.append("\n[done]")
                            is SpeechEvent.Error ->
                                textView.append("\n[error: ${event.message}]")
                            else -> {}
                        }
                    }
                }
            }

            p.start()
            startMicrophone(p)
            textView.text = "Listening..."
        }
    }

    private fun startMicrophone(pipeline: SpeechPipeline) {
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            bufferSize,
        )

        recording = true
        audioRecord?.startRecording()

        lifecycleScope.launch(Dispatchers.IO) {
            val buffer = FloatArray(512)  // 32ms chunks matching VAD
            while (recording) {
                val read = audioRecord?.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) ?: 0
                if (read > 0) {
                    pipeline.pushAudio(buffer)
                }
            }
        }
    }

    override fun onDestroy() {
        recording = false
        audioRecord?.stop()
        audioRecord?.release()
        pipeline?.stop()
        pipeline?.close()
        super.onDestroy()
    }
}
