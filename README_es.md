# Speech Android

📖 Idiomas: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

SDK de voz en el dispositivo para Android, impulsado por [ONNX Runtime](https://onnxruntime.ai) y [speech-core](https://github.com/soniqo/speech-core).

Reconocimiento de voz (114 idiomas), texto a voz (8 idiomas), detección de actividad de voz y cancelación de ruido — todo ejecutándose localmente. Sin APIs en la nube, ningún dato sale del dispositivo.

**[APK de demostración](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[Modelos](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)** (contraparte Apple) · **[speech-core](https://github.com/soniqo/speech-core)** (motor de pipeline + compilación Linux/embebido)

## Alcance

Este repositorio es el **empaquetado para Android**: SDK de Kotlin, puente JNI, app demo. El motor C++ y los envoltorios de modelos ONNX (Silero VAD, Parakeet STT, Kokoro TTS, DeepFilterNet3) viven en [speech-core](https://github.com/soniqo/speech-core) y se incorporan vía un submódulo git. Linux / automoción (Yocto, Qualcomm SA8295P/SA8255P) vive en [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux).

## Modelos

| Modelo | Tarea | Tamaño INT8 | Idiomas |
| --- | --- | --- | --- |
| [Parakeet TDT v3](https://huggingface.co/soniqo/Parakeet-TDT-v3-ONNX) | Reconocimiento de voz | 891 MB | 114 |
| [Kokoro 82M](https://huggingface.co/soniqo/Kokoro-82M-ONNX) | Texto a voz | 330 MB | 8 (en, fr, es, it, pt, hi, ja, zh) |
| [Silero VAD v5](https://huggingface.co/soniqo/Silero-VAD-v5-ONNX) | Detección de actividad de voz | 2 MB | Cualquiera |
| [DeepFilterNet3](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | Cancelación de ruido | ~8 MB | Cualquiera |

Los modelos se descargan automáticamente al primer inicio vía `ModelManager.ensureModels()`.

## Prueba la demo

Descarga el [APK firmado](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) e instálalo en cualquier dispositivo Android arm64 (8+). Los modelos (~1.2 GB) se descargan automáticamente en el primer inicio.

## Añadir dependencia

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.9")
}
```

## Uso de Kotlin

```kotlin
val modelDir = ModelManager.ensureModels(context)

val pipeline = SpeechPipeline(
    SpeechConfig(modelDir = modelDir, useNnapi = true)
)

pipeline.events.collect { event ->
    when (event) {
        is SpeechEvent.TranscriptionCompleted -> println(event.text)
        is SpeechEvent.ResponseDone -> pipeline.resumeListening()
        else -> {}
    }
}

pipeline.start()

// Alimenta PCM float32 mono 16kHz desde el micrófono
pipeline.pushAudio(samples)
```

## Compilar desde fuente

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 34 pruebas e2e
```

`./setup.sh` inicializa el submódulo speech-core y descarga ONNX Runtime
en `./ort/`.

## Aplicación demo

El módulo [`app/`](app/) es una demo mínima de asistente de voz con:

- Visualización de forma de onda VAD en tiempo real
- Modo eco: transcribe la voz y la sintetiza de vuelta (sin LLM)
- Modo dictado: resultados parciales en streaming
- Pantalla de prueba `SpeechRecognizer` — ejercita la ruta de entrada de voz a nivel de sistema
- UI de burbujas de chat con visualización de latencia STT/TTS

```bash
./gradlew :app:installDebug
```

## Entrada de voz del sistema (`RecognitionService`)

El SDK incluye un `audio.soniqo.speech.service.SpeechRecognitionService` listo
para usar que se conecta a la API `SpeechRecognizer` del framework de Android
— sin código que escribir. Una vez que tu app está seleccionada como
reconocedor de voz predeterminado, cualquier app de terceros que llame a
`SpeechRecognizer.createSpeechRecognizer(context)` (sin `ComponentName`)
obtiene STT completamente en el dispositivo a través de tu pipeline.

**1. Declara `RECORD_AUDIO` y el servicio en `AndroidManifest.xml`:**

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />

<application>
    <service
        android:name="audio.soniqo.speech.service.SpeechRecognitionService"
        android:exported="true"
        android:permission="android.permission.RECORD_AUDIO">
        <intent-filter>
            <action android:name="android.speech.RecognitionService" />
        </intent-filter>
        <meta-data
            android:name="android.speech"
            android:resource="@xml/recognition_service" />
    </service>
</application>
```

**2. Añade `app/src/main/res/xml/recognition_service.xml`:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<recognition-service xmlns:android="http://schemas.android.com/apk/res/android" />
```

(Opcionalmente añade `android:settingsActivity="..."` para exponer un icono
de engranaje en el selector de entrada de voz del sistema.)

**3. Configura el servicio como predeterminado del sistema** (Ajustes →
Sistema → Idiomas e introducción → Selector de entrada de voz en Android
puro, o vía adb):

```bash
adb shell settings put secure voice_recognition_service \
  your.package/audio.soniqo.speech.service.SpeechRecognitionService
```

**4. Verifica** ejecutando la pantalla *Recognizer test* de la app demo, que
llama a `SpeechRecognizer.createSpeechRecognizer(ctx)` (sin componente) y
registra cada callback del framework — útil para confirmar el round-trip del
binder sin necesitar logcat.

El servicio implementa `onCheckRecognitionSupport` (API 33+) devolviendo los
27 idiomas BCP-47 que cubre Parakeet TDT v3, marcados como
`installedOnDeviceLanguage` cuando los modelos están presentes (o
`pendingOnDeviceLanguage` mientras se descargan). Se adquiere foco de audio
con `AUDIOFOCUS_GAIN_TRANSIENT` durante la sesión.

**Limitación:** Gboard, Samsung Keyboard y Google Assistant incluyen sus
propios reconocedores y se saltan el predeterminado del sistema. Las apps
que llaman explícitamente a la API `SpeechRecognizer` del framework (o
construyen su propia UI sobre ella) son las que pasan por tu servicio.

## Rendimiento

Medido en emulador Android (arm64-v8a, sin NNAPI). El hardware real es significativamente más rápido.

| Modelo | Tarea | Audio | Inferencia | RTF |
| --- | --- | --- | --- | --- |
| Parakeet TDT v3 | STT | 1.5s | 175ms | 0.12 |
| Kokoro 82M | TTS | 1.9s salida | 1,075ms | 0.58 |
| Silero VAD v5 | VAD | bloque 32ms | <1ms | <0.01 |

## Pipeline

```text
Idle → Listening → Transcribing → Speaking → Idle
              ↑                         |
              └─── resumeListening() ───┘
```

Soporte de barge-in: hablar durante la reproducción TTS interrumpe e inicia una nueva transcripción.

## Arquitectura

```text
┌──────────────────────────────────────────────┐
│      SpeechPipeline (Kotlin)                 │
│            │                                 │
│            ▼                                 │
│      jni_bridge.cpp  (~250 líneas)           │
│            │                                 │
│            ▼                                 │
│  ┌──────────────────────────────────────┐    │
│  │  speech_core_models (submódulo git)  │    │
│  │   SileroVad / ParakeetStt /          │    │
│  │   KokoroTts / DeepFilterEnhancer     │    │
│  │            │                         │    │
│  │            ▼                         │    │
│  │  speech_core  (orquestación:         │    │
│  │   pipeline · turno · interrupciones) │    │
│  └──────────────────────────────────────┘    │
│            │                                 │
│            ▼                                 │
│      ONNX Runtime (CPU / NNAPI)              │
└──────────────────────────────────────────────┘
```

Cada clase de modelo implementa directamente la interfaz correspondiente de
speech-core (`VADInterface`, `STTInterface`, `TTSInterface`,
`EnhancerInterface`) — el puente JNI las instancia y entrega las referencias
a `VoicePipeline`. Sin código repetitivo de adaptadores con vtables en C.

## Aceleración por hardware

| Chipset | Aceleración |
| --- | --- |
| Snapdragon 8 Gen 1+ | NNAPI → Hexagon NPU |
| Samsung Exynos 2200+ | NNAPI → Samsung NPU |
| Google Tensor G2+ | NNAPI → Google TPU |
| Fallback CPU | XNNPACK |

Para Qualcomm SA8295P / SA8255P de automoción con QNN (Hexagon DSP), consulta
[speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux).

## Relacionados

| Repositorio | Alcance |
| --- | --- |
| [speech-swift](https://github.com/soniqo/speech-swift) | Apple (macOS, iOS) — MLX + CoreML |
| [speech-core](https://github.com/soniqo/speech-core) | Motor de pipeline C++ multiplataforma + envoltorios de modelos ONNX + ejemplos Linux/embebido |
| **speech-android** | Envoltorio Android — SDK Kotlin + puente JNI sobre speech-core |

## Licencia

Apache 2.0
