# Keep JNI callback interfaces — native code looks up methods by name
-keep class audio.soniqo.speech.NativeBridge { *; }
-keep class audio.soniqo.speech.NativeBridge$EventCallback { *; }
-keep class audio.soniqo.speech.NativeBridge$LlmCallback { *; }

# Keep all public SDK classes
-keep class audio.soniqo.speech.SpeechPipeline { *; }
-keep class audio.soniqo.speech.SpeechConfig { *; }
-keep class audio.soniqo.speech.SpeechEvent { *; }
-keep class audio.soniqo.speech.SpeechEvent$* { *; }
-keep class audio.soniqo.speech.ModelManager { *; }
-keep class audio.soniqo.speech.ModelPrecision { *; }
-keep class audio.soniqo.speech.PipelineState { *; }
