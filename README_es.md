# Speech Android

📖 Idiomas: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

SDK de voz en el dispositivo para Android, impulsado por [ONNX Runtime](https://onnxruntime.ai) y [speech-core](https://github.com/soniqo/speech-core).

Reconocimiento de voz en streaming de baja memoria (25 idiomas por defecto; TDT de 114 idiomas opcional), texto a voz, detección de actividad de voz y cancelación de ruido — todo ejecutándose localmente. Sin APIs en la nube, ningún dato sale del dispositivo.

**[APK de demostración](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[Modelos](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)** (contraparte Apple) · **[speech-core](https://github.com/soniqo/speech-core)** (motor de pipeline + compilación Linux/embebido)

## Alcance

Este repositorio es el **empaquetado para Android**: SDK de Kotlin, puente JNI, app demo. El motor C++ y los envoltorios de modelos ONNX (Silero VAD, Parakeet STT, Kokoro TTS, DeepFilterNet3) viven en [speech-core](https://github.com/soniqo/speech-core) y se incorporan vía un submódulo git. Linux / automoción (Yocto, Qualcomm SA8295P/SA8255P) vive en [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux).

## Modelos

| Modelo | Tarea | Descarga | Pico de memoria | Idiomas |
| --- | --- | --- | --- | --- |
| [Parakeet-EOU 120M](https://huggingface.co/soniqo/Parakeet-EOU-120M-ONNX-INT8) | STT en streaming + EOU (por defecto) | 153 MB | 232 MB | 25 |
| [Parakeet TDT v3](https://huggingface.co/soniqo/Parakeet-TDT-v3-ONNX) | STT de cobertura amplia (opcional) | 891 MB | ~1.1-1.3 GB | 114 |
| [Kokoro 82M](https://huggingface.co/soniqo/Kokoro-82M-ONNX) | Texto a voz (por defecto) | 330 MB | 640 MB | 8 (en, fr, es, it, pt, hi, ja, zh) |
| [Supertonic-3](https://huggingface.co/soniqo/Supertonic-3-LiteRT) | Texto a voz (LiteRT, flow-matching, G2P-free, 44.1 kHz) | ~380 MB | 832 MB | 31 |
| [Silero VAD v5](https://huggingface.co/soniqo/Silero-VAD-v5-ONNX) | Detección de actividad de voz | 2 MB | <10 MB | Cualquiera |
| [DeepFilterNet3](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | Cancelación de ruido | ~8 MB | no se carga por defecto | Cualquiera |
| [FunctionGemma 270M](https://huggingface.co/soniqo/FunctionGemma-270M-LiteRT-LM) | LLM en dispositivo — llamadas estructuradas a funciones / herramientas | 283 MB | depende del runtime de la app | Ajustado para EN |

Los modelos se descargan automáticamente al primer inicio vía `ModelManager.ensureModels()`.

`SpeechConfig()` usa `SttModel.PARAKEET_EOU` y `TtsModel.KOKORO` por defecto para mantener las integraciones del SDK y el reconocedor del sistema en la ruta Android de baja memoria. La app demo opta por `SttModel.PARAKEET` para que las pantallas de eco y dictado usen el modelo TDT más grande de 114 idiomas.

Para reconocimiento enfocado por idioma, usa `SpeechConfig(sttModel = SttModel.PARAKEET, languageHints = listOf("en", "fr"))`. Define `language = "en"` si quieres fijar un único idioma.

**Supertonic-3** es un TTS multilingüe opcional de mayor calidad — selecciónalo con `SpeechConfig(ttsModel = TtsModel.SUPERTONIC)` (requiere el backend LiteRT). El host ejecuta sus cuatro grafos de flow-matching no autorregresivos en el dispositivo a 44.1 kHz; el front-end es G2P-free (NFKD + índice Unicode — sin fonemizador), por lo que los 31 idiomas pasan por una sola ruta.

## Prueba la demo

Descarga el [APK firmado](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) e instálalo en cualquier dispositivo Android arm64 (8+). El paquete de modelos de baja memoria por defecto (~500 MB) se descarga automáticamente en el primer inicio.

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
- STT Parakeet TDT de 114 idiomas en las pantallas de eco y dictado
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
25 idiomas base BCP-47 que cubre Parakeet-EOU, además de la etiqueta regional
exacta solicitada cuando corresponde a un idioma base soportado. Los idiomas
se marcan como `installedOnDeviceLanguage` cuando los modelos están presentes,
o como `supportedOnDeviceLanguage` antes de descargarlos. El servicio no toma
el foco de audio de la app que lo invoca.

**Limitación:** Gboard, Samsung Keyboard y Google Assistant incluyen sus
propios reconocedores y se saltan el predeterminado del sistema. Las apps
que llaman explícitamente a la API `SpeechRecognizer` del framework (o
construyen su propia UI sobre ella) son las que pasan por tu servicio.

## Texto a voz del sistema (`TextToSpeechService`)

La app demo también expone
`audio.soniqo.speech.service.SpeechTextToSpeechService`, por lo que Android
puede seleccionar la app en Ajustes → Sistema → Idiomas e introducción →
Salida de texto a voz. Esta ruta usa `ModelManager.ensureTtsModels()` y una
caché separada `models_tts/`, de modo que el TTS del framework descarga solo
los recursos de Kokoro en vez del paquete completo de VAD/STT/enhancer.

Para exponer el motor desde otra app, declara el servicio:

```xml
<service
    android:name="audio.soniqo.speech.service.SpeechTextToSpeechService"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.TTS_SERVICE" />
    </intent-filter>
    <meta-data
        android:name="android.speech.tts"
        android:resource="@xml/tts_engine" />
</service>
```

Añade `app/src/main/res/xml/tts_engine.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<tts-engine xmlns:android="http://schemas.android.com/apk/res/android" />
```

## Rendimiento

Medido en Galaxy S23 Android, solo CPU salvo indicación. Un RTF menor es más rápido.

| Modelo | Tarea | RTF | Latencia | Pico de memoria |
| --- | --- | --- | --- | --- |
| Parakeet-EOU 120M ONNX INT8 | STT en streaming + EOU | 0.21 | parciales en streaming | 232 MB |
| Kokoro 82M ONNX FP32 | TTS | 0.53 | por frase | 640 MB |
| Supertonic-3 LiteRT | TTS | 0.34 | ~1.1s TTFA | 832 MB |
| Silero VAD v5 | VAD | <0.01 | <1ms por bloque de 32ms | <10 MB |

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
