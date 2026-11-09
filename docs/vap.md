# Voice Activity Projection

VAP uses synchronized user-microphone and agent-playback audio to forecast
who will speak. Download its standalone bundle and run inference on a worker
thread:

```kotlin
val modelDir = ModelManager.ensureVapModels(context)
VoiceActivityProjection(VoiceActivityProjectionConfig(modelDir)).use { model ->
    val forecast = model.forecast(userPcm16k, agentPcm16k)
    println(forecast.userNearTerm)
    println(forecast.agentNearTerm)
}
```

The channels use 16 kHz Float32 PCM and a shared sample count. Supply zeros
for an inactive agent. Each request recomputes their latest 20 seconds and
adds leading silence when shorter. The six values describe near-term and
later floor probabilities and current speech activity, in user/agent order.
The caller decides when to speak or interrupt from these forecasts.

`close()` releases the native model; calls and close are serialized. The
optional bundle has its own `models_vap` cache. `areVapModelsReady()` and
`plannedVapBytes()` inspect that cache, while `ensureVapModels()` resumes an
interrupted download. The model leaves existing pipeline defaults in place.

The JNI layer uses the C++ ONNX implementation. Its model checks compare
reference probabilities from the original otoSpeech checkpoint and keep the
two channels separate. Desktop inference timings do not measure phone latency.

Weights: [viks66/VAP_checkpoints](https://huggingface.co/viks66/VAP_checkpoints),
`oto.ckpt`, CC BY 4.0. Architecture:
[VoiceActivityProjection](https://github.com/ErikEkstedt/VoiceActivityProjection), MIT.
