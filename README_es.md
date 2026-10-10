# Speech Android

📖 Idiomas: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

SDK de voz en el dispositivo para Android, impulsado por [ONNX Runtime](https://onnxruntime.ai) y [speech-core](https://github.com/soniqo/speech-core).

Reconocimiento de voz en streaming de baja memoria y un modelo TDT opcional más grande, ambos para 25 idiomas europeos, además de texto a voz, detección de actividad de voz y cancelación de ruido — todo ejecutándose localmente. Sin APIs en la nube, ningún dato sale del dispositivo.

**[📚 Documentación de Android](https://soniqo.audio/es/getting-started/android)**

**[APK de demostración](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[APK de Control Demo](https://github.com/soniqo/speech-android/releases/latest/download/control-demo-release.apk)** · **[Modelos](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)** (contraparte Apple) · **[speech-core](https://github.com/soniqo/speech-core)** (motor de pipeline + compilación Linux/embebido)

## Demostración

<p align="center">
  <a href="https://www.youtube.com/watch?v=7L7_Uvvxtv0">
    <img src="https://img.youtube.com/vi/7L7_Uvvxtv0/maxresdefault.jpg" width="640" alt="Un agente de voz totalmente offline en 1.2 GB en Android — ver la demo en YouTube">
  </a>
</p>
<p align="center"><em>El bucle de comandos de <a href="control-demo/">control-demo</a> — Silero VAD → Parakeet STT → FunctionGemma → acción del dispositivo → respuesta de Pocket TTS — totalmente offline en 1.2 GB de RAM</em></p>

## Alcance

Este repositorio es el **empaquetado para Android**: SDK de Kotlin, puente JNI, app demo. El motor C++ y los envoltorios de modelos ONNX (Silero VAD, Parakeet STT, Kokoro/Pocket TTS, DeepFilterNet3) viven en [speech-core](https://github.com/soniqo/speech-core) y se incorporan vía un submódulo git. Linux / automoción (Yocto, Qualcomm SA8295P/SA8255P) vive en [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux).

## Modelos

| Modelo | Tarea | Descarga | Pico de memoria | Idiomas |
| --- | --- | --- | --- | --- |
| [Parakeet-EOU 120M](https://soniqo.audio/es/guides/dictate) | STT en streaming + EOU (por defecto) | [153 MB](https://huggingface.co/soniqo/Parakeet-EOU-120M-ONNX-INT8) | 232 MB | 25 |
| [Parakeet TDT v3](https://soniqo.audio/es/guides/parakeet/android) | STT de cobertura amplia (opcional) | [891 MB](https://huggingface.co/soniqo/Parakeet-TDT-v3-ONNX) | ~1.1-1.3 GB | 25 europeos |
| [Nemotron-3.5 multilingüe](https://soniqo.audio/es/guides/nemotron) | STT en streaming condicionado por prompt (opcional) | [~721 MB](https://huggingface.co/soniqo/Nemotron-3.5-ASR-Streaming-Multilingual-0.6B-LiteRT-INT8) | aún no medido | más de 100 (incluido zh) |
| [Canary 180M Flash](https://huggingface.co/soniqo/Canary-180M-Flash-ONNX) | STT sin conexión + traducción (opcional) | [273 MB](https://huggingface.co/soniqo/Canary-180M-Flash-ONNX) | ~780 MB | 4 (en, de, es, fr) |
| [Kokoro 82M](https://soniqo.audio/es/guides/kokoro/android) | Texto a voz (por defecto) | [330 MB](https://huggingface.co/soniqo/Kokoro-82M-ONNX) | 640 MB | 8 (en, fr, es, it, pt, hi, ja, zh) |
| [Pocket TTS 100M](https://huggingface.co/soniqo/Pocket-TTS-100M-ONNX-INT8) | Texto a voz en streaming (opcional, voz Alba fija) | ~126 MB | aún no medido | Inglés |
| [Supertonic-3](https://soniqo.audio/es/guides/supertonic) | Texto a voz (LiteRT, flow-matching, G2P-free, 44.1 kHz) | [~380 MB](https://huggingface.co/soniqo/Supertonic-3-LiteRT) | 832 MB | 31 |
| [Silero VAD v5](https://soniqo.audio/es/guides/vad/android) | Detección de actividad de voz | [2 MB](https://huggingface.co/soniqo/Silero-VAD-v5-ONNX) | <10 MB | Cualquiera |
| [Sortformer 4 hablantes](https://huggingface.co/soniqo/Sortformer-Diarization-4spk-ONNX) | Diarización de hablantes en streaming (opcional) | [475 MB](https://huggingface.co/soniqo/Sortformer-Diarization-4spk-ONNX) | aún no medido | Cualquiera |
| [ReDimNet2-B6](https://huggingface.co/soniqo/ReDimNet2-B6-ONNX-FP32) | Embeddings de hablante (opcional) | [51 MB](https://huggingface.co/soniqo/ReDimNet2-B6-ONNX-FP32) | aún no medido | Cualquiera |
| [DeepFilterNet3](https://soniqo.audio/es/guides/denoise/android) | Cancelación de ruido | [~8 MB](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | no se carga por defecto | Cualquiera |
| [FunctionGemma 270M](https://soniqo.audio/es/guides/function-calls) | LLM en dispositivo — llamadas estructuradas a funciones / herramientas | [283 MB](https://huggingface.co/soniqo/FunctionGemma-270M-LiteRT-LM) | depende del runtime de la app | Ajustado para EN |

Los modelos se descargan automáticamente al primer inicio vía `ModelManager.ensureModels()`.

`SpeechConfig()` usa `SttModel.PARAKEET_EOU` y `TtsModel.KOKORO_SHORT_TURN` por defecto para mantener las integraciones del SDK y el reconocedor del sistema en la ruta Android de baja memoria. La app demo opta por `SttModel.PARAKEET` para que las pantallas de eco y dictado usen el modelo TDT más grande para 25 idiomas europeos.

Ambos modelos Parakeet siempre detectan el idioma automáticamente: no aceptan `language` ni `languageHints` y no son compatibles con chino. Para fijar un idioma, incluido el mandarín, usa el backend Nemotron condicionado por prompt:

```kotlin
val sttModel = SttModel.NEMOTRON_MULTILINGUAL
val sttBackend = SttBackend.LITERT
val modelDir = ModelManager.ensureModels(
    context,
    sttModel = sttModel,
    sttBackend = sttBackend,
)
val config = SpeechConfig(
    modelDir = modelDir,
    sttModel = sttModel,
    sttBackend = sttBackend,
    language = "zh-CN", // o "zh-TW"
)
```

**Supertonic-3** es un TTS multilingüe opcional de mayor calidad — selecciónalo con `SpeechConfig(ttsModel = TtsModel.SUPERTONIC)` (requiere el backend LiteRT). El host ejecuta sus cuatro grafos de flow-matching no autorregresivos en el dispositivo a 44.1 kHz; el front-end es G2P-free (NFKD + índice Unicode — sin fonemizador), por lo que los 31 idiomas pasan por una sola ruta.

Frases largas: el paquete también incluye un par opcional de grafos con ventana latente L=128 (`vector_estimator_L128.tflite` + `vocoder_L128.tflite`, +341 MB). Descárgalo con `ModelDownloadWorker.enqueue(context, ttsModel = TtsModel.SUPERTONIC, supertonicLatentBuckets = true)` (o el mismo flag en `ModelManager.ensureTtsModels`) y speech-core sintetiza una frase más larga que la ventana base de 4.5 s en una sola pasada en lugar de dividirla; el par se carga en el primer uso (+~360 MB de RSS). Desactivado por defecto.

Tanto Kokoro como Supertonic aceptan un preset de voz por llamada en la síntesis directa — `pipeline.synthesize("Hello", "en", "M1")` (Supertonic `F1`…`F5` / `M1`…`M5`; Kokoro `af_heart`, `ff_siwis`, …) — y el mismo argumento existe en `synthesizeStreaming` y `SpeechSynthesizer`. El preset se aplica solo a esa llamada; omítelo (o pasa `""`) para mantener la voz por defecto del motor, y un id desconocido lanza una excepción.

## Prueba la demo

Descarga el [APK firmado](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) e instálalo en cualquier dispositivo Android arm64 (8+). El paquete de modelos de baja memoria por defecto (~500 MB) se descarga automáticamente en el primer inicio.

## Añadir dependencia

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.24")
}
```

## Uso de Kotlin

```kotlin
val modelDir = ModelManager.ensureModels(context)

val pipeline = SpeechPipeline(
    SpeechConfig(modelDir = modelDir, useNnapi = false)
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

### Detección de fin de turno

Un VAD detecta silencio, pero no sabe si una pausa completa la idea del
hablante. Smart Turn v3.2 es un clasificador opcional basado en audio que
considera los últimos ocho segundos del turno y mantiene abierto un turno
incompleto.

```kotlin
val modelDir = ModelManager.ensureModels(context, enableSmartTurn = true)

val pipeline = SpeechPipeline(
    SpeechConfig(
        modelDir = modelDir,
        enableSmartTurn = true,
        turnCompletionThreshold = 0.5f,
        turnCompletionMaxSilenceSec = 2.0f,
    )
)
```

La descarga opcional añade el grafo INT8 fijado de 11,1 MB. La inferencia se
ejecuta una vez después de cada pausa confirmada por VAD, no en cada frame de
audio. Si se rechaza la pausa, el habla reanudada continúa en el mismo turno;
el límite máximo de silencio garantiza un final. Smart Turn fue creado por
Pipecat/Daily y se distribuye bajo BSD-2-Clause.

### Solo detección de actividad de voz

Las apps que solo necesitan saber *cuándo* alguien está hablando —control de
pulsar para hablar, segmentación de grabaciones, activación por voz— pueden
cargar Silero por su cuenta: 2 MB de descarga y menos de 10 MB de RAM en lugar
de los ~500 MB del conjunto por defecto. Sin STT, sin TTS, sin pipeline.

```kotlin
val detector = VadDetector(
    VadConfig(
        modelDir = ModelManager.ensureVadModels(context),
        emitUtteranceAudio = true, // actívalo para recibir el segmento capturado
    )
)

detector.events.collect { event ->
    when (event) {
        is VadEvent.SpeechStarted -> startRecordingUi()
        is VadEvent.SpeechEnded -> saveSegment(event.audio) // 16 kHz float32
    }
}

// 16 kHz mono float32, tramas de 512 muestras
detector.pushAudio(samples)
// cierra un segmento abierto al terminar el flujo
detector.flush()
```

`VadConfig` expone la misma detección de turnos que el pipeline usa
internamente: umbrales de inicio y fin, duración mínima de habla, silencio de
fin de habla y el búfer previo que conserva la primera sílaba. Esta ruta usa
`ModelManager.ensureVadModels()` y una caché `models_vad/` aparte, así que
nunca descarga los paquetes de STT/TTS.

### Componentes para transcribir reuniones

Las apps que gestionan su propia captura y segmentación —una grabadora de reuniones, una app de notas— pueden cargar el reconocedor, el diarizador y el codificador de hablantes por separado, sin el pipeline. Cada uno tiene su propia descarga y su propio directorio de caché, y ninguno toma decisiones de producto: el transcriptor devuelve texto, el diarizador probabilidades de hablante por fotograma y el codificador un vector de voz de 192 dimensiones. Los umbrales, los turnos, las etiquetas de hablante y la comparación de voces quedan en la app.

```kotlin
// Opcional: un espejo elegido por el usuario con la estructura de Hugging Face.
ModelManager.endpoint = "https://hf-mirror.com"

val transcriber = StreamingTranscriber(
    TranscriberConfig(modelDir = ModelManager.ensureTranscriberModels(context))
)
transcriber.beginStream()
transcriber.pushAudio(samples)            // 16 kHz mono float32; texto hasta ahora
val text = transcriber.endStream().text

val diarizer = SpeakerDiarizer(
    DiarizerConfig(modelDir = ModelManager.ensureDiarizerModels(context))
)
val frames = diarizer.pushAudio(samples)  // [fotogramas x diarizer.speakers], a menudo vacío
val tail = diarizer.endStream()           // al terminar la grabación

val embedder = SpeakerEmbedder(
    SpeakerEmbedderConfig(modelDir = ModelManager.ensureSpeakerEmbeddingModels(context))
)
val voice = embedder.embed(speech)        // al menos 2 s; FloatArray(192)
```

`TranscriberConfig.language` es `"auto"` por defecto: el prompt de idioma automático de Nemotron, como en speech-swift. `"en-US"`, `"pt_BR"` o solo `"fr"` fijan un idioma. Cada transcripción incluye tiempos por palabra, en segundos desde `beginStream`, tomados de la trama del codificador en la que se emitió cada token. Sortformer usa un stream por grabación y responde unos 30 segundos por detrás del audio, porque la exportación publicada decodifica 27,2 segundos por llamada. `ModelDownloadWorker.enqueue(context, includePipeline = false, includeTranscriber = true, includeDiarizer = true, includeSpeakerEmbedding = true)` descarga los tres conjuntos en segundo plano.

## Compilar desde fuente

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 38 pruebas e2e
```

`./setup.sh` inicializa el submódulo speech-core y descarga ONNX Runtime
en `./ort/`.

## Aplicación demo

El módulo [`app/`](app/) es una demo mínima de asistente de voz con:

- Visualización de forma de onda VAD en tiempo real
- Modo eco: transcribe la voz y la sintetiza de vuelta (sin LLM)
- Modo dictado: resultados parciales en streaming
- Superposición de voz: botón de micrófono flotante para dictar en cualquier app
- STT Parakeet TDT para 25 idiomas europeos en las pantallas de eco y dictado
- Pantalla de prueba `SpeechRecognizer` — ejercita la ruta de entrada de voz a nivel de sistema
- UI de burbujas de chat con visualización de latencia STT/TTS

```bash
./gradlew :app:installDebug
```

### Superposición de voz (dictar en cualquier app)

La **superposición de voz** coloca un botón de micrófono arrastrable sobre
otras apps. Al tocarlo se convierte en **■ detener** / **✕ cancelar**: detener
escribe la transcripción en el campo de texto que tenga el foco y cancelar la
descarta. Si ningún campo editable tiene el foco, el texto va al portapapeles
en lugar de perderse.

Hacen falta tres permisos, cada uno con su propia pantalla del sistema — la
pantalla de configuración muestra cuáles faltan:

| Permiso | Para qué |
| --- | --- |
| Micrófono | capturar audio |
| Mostrar sobre otras apps | dibujar el botón fuera de la app |
| Servicio de accesibilidad | escribir en el campo de texto de otra app |

La ventana de la superposición es deliberadamente no enfocable, de modo que el
campo de destino conserva el foco de entrada mientras se pulsan los botones. El
texto se inserta en el cursor con `ACTION_SET_TEXT`. Los campos cuyo contenido
real no se puede leer —algunas apps informan su marcador de posición como el
propio texto del campo— se escriben pegando, lo que reemplaza lo que hubiera en
el portapapeles; el dictado se borra de él justo después.

> ¿Instalas desde un APK en vez de Play Store? Android bloquea el interruptor
> de accesibilidad hasta que lo permitas en
> Ajustes → Apps → Speech → ⋮ → **Permitir ajustes restringidos**.

### Demo de control del pipeline completo

La app independiente [`control-demo/`](control-demo/) ejecuta todo el agente
localmente: Silero VAD → Parakeet-EOU STT → llamadas a herramientas con
FunctionGemma 270M → acciones del dispositivo Android → Pocket TTS. Muestra la
latencia de cada etapa y enlaza directamente con el `:sdk` de este checkout,
por lo que usa las optimizaciones de voz locales.

Descarga el [APK firmado de Control Demo](https://github.com/soniqo/speech-android/releases/latest/download/control-demo-release.apk)
de la versión más reciente, o instala una compilación de desarrollo desde el código fuente:

```bash
./gradlew :control-demo:installDebug
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

Medido en un Galaxy S23 Ultra (SM-S918B), solo CPU salvo indicación. RTF es
tiempo de pared ÷ duración del audio emitido: cuanto menor, más rápido; <1,0
es más rápido que tiempo real.

| Modelo | Tarea | RTF | Latencia | Pico de memoria |
| --- | --- | --- | --- | --- |
| Parakeet-EOU 120M ONNX INT8 | STT en streaming + EOU | 0.21 | parciales en streaming | 232 MB |
| Kokoro 82M grafo completo (publicado, CPU de dos hilos) | TTS | 1.81 | por frase | ~604 MB |
| Kokoro 82M turno corto (grafo de 3.0 s, predeterminado) | TTS | 0.75–0.88 | respuestas acotadas; reintento seguro | ~527 MB |
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
│  │   KokoroTts / OnnxPocketTts /        │    │
│  │   DeepFilterEnhancer                  │    │
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
