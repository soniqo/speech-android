# Speech Android

📖 Idiomas: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

SDK de voz en el dispositivo para Android y Linux embebido, impulsado por [ONNX Runtime](https://onnxruntime.ai) y [speech-core](https://github.com/soniqo/speech-core).

Reconocimiento de voz (114 idiomas), texto a voz (8 idiomas), detección de actividad de voz y cancelación de ruido — todo ejecutándose localmente. Sin APIs en la nube, ningún dato sale del dispositivo.

**[APK de demostración](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[Modelos](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)** (contraparte Apple) · **[speech-core](https://github.com/soniqo/speech-core)** (motor de pipeline)

## Plataformas

| Plataforma | API | Aceleración | Directorio |
| --- | --- | --- | --- |
| Android | Kotlin (`SpeechPipeline`) | NNAPI (Snapdragon, Exynos, Tensor) | `sdk/` |
| Linux embebido | C (`speech.h`) | QNN (Hexagon DSP) | `linux/` |

## Modelos

| Modelo | Tarea | Tamaño INT8 | Idiomas |
| --- | --- | --- | --- |
| [Parakeet TDT v3](https://huggingface.co/aufklarer/Parakeet-TDT-v3-ONNX) | Reconocimiento de voz | 891 MB | 114 |
| [Kokoro 82M](https://huggingface.co/aufklarer/Kokoro-82M-ONNX) | Texto a voz | 330 MB | 8 (en, fr, es, it, pt, hi, ja, zh) |
| [Silero VAD v5](https://huggingface.co/aufklarer/Silero-VAD-v5-ONNX) | Detección de actividad de voz | 2 MB | Cualquiera |
| [DeepFilterNet3](https://huggingface.co/aufklarer/DeepFilterNet3-ONNX) | Cancelación de ruido | ~8 MB | Cualquiera |

Los modelos se descargan automáticamente al primer inicio (Android) o se colocan manualmente (Linux).

## Android

### Prueba la demo

Descarga el [APK firmado](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) e instálalo en cualquier dispositivo Android arm64 (8+). Los modelos (~1.2 GB) se descargan automáticamente en el primer inicio.

### Añadir dependencia

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.8")
}
```

### Uso de Kotlin

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

### Compilar desde fuente

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 34 pruebas e2e
```

### Aplicación demo

El módulo [`app/`](app/) es una demo mínima de asistente de voz con:

- Visualización de forma de onda VAD en tiempo real
- Modo eco: transcribe la voz y la sintetiza de vuelta (sin LLM)
- UI de burbujas de chat con visualización de latencia STT/TTS

```bash
./gradlew :app:installDebug
```

## Rendimiento

Medido en emulador Android (arm64-v8a, sin NNAPI). El hardware real es significativamente más rápido.

| Modelo | Tarea | Audio | Inferencia | RTF |
| --- | --- | --- | --- | --- |
| Parakeet TDT v3 | STT | 1.5s | 175ms | 0.12 |
| Kokoro 82M | TTS | 1.9s salida | 1,075ms | 0.58 |
| Silero VAD v5 | VAD | bloque 32ms | <1ms | <0.01 |

## Linux embebido

API C mínima para plataformas automotrices y embebidas. Consulta [`linux/README.md`](linux/README.md) para la documentación completa.

### Uso de la API C

```c
#include <speech.h>

void on_event(const speech_event_t* event, void* ctx) {
    if (event->type == SPEECH_EVENT_TRANSCRIPTION)
        printf("%s\n", event->text);
}

speech_config_t cfg = speech_config_default();
cfg.model_dir = "/opt/speech/models";
cfg.use_qnn = true;  // Aceleración Hexagon DSP

speech_pipeline_t p = speech_create(cfg, on_event, NULL);
speech_start(p);
speech_push_audio(p, pcm_samples, 512);
```

### Compilar

```bash
cd linux && ./setup_linux.sh
cmake -B build -DORT_DIR=../ort-linux
cmake --build build
./build/speech_demo --model-dir /path/to/models
```

### Probar

```bash
linux/tests/download_models.sh              # descargar modelos ONNX
SPEECH_MODEL_DIR=tests/models ./build/speech_test   # 12 pruebas
```

### Compilación cruzada para Yocto

```bash
source /opt/poky/environment-setup-aarch64-poky-linux
cmake -B build -DCMAKE_TOOLCHAIN_FILE=toolchain-aarch64.cmake -DORT_DIR=...
cmake --build build
```

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
│   Android: SpeechPipeline (Kotlin/JNI)       │
│   Linux:   speech.h (C API)                  │
└──────────────────┬───────────────────────────┘
                   │
┌──────────────────┴───────────────────────────┐
│            speech-core (C++ submodule)        │
│   Turn detection · Interruptions · Context   │
└──┬────────┬────────┬────────┬────────────────┘
   │        │        │        │  vtables
┌──┴──┐  ┌──┴──┐  ┌──┴──┐  ┌─┴────────┐
│ VAD │  │ STT │  │ TTS │  │ Enhancer │
│Silero│  │Para-│  │Koko-│  │DeepFilter│
│     │  │keet │  │ro   │  │Net3      │
└──┬──┘  └──┬──┘  └──┬──┘  └─┬────────┘
   └────────┴────────┴────────┘
       ONNX Runtime (CPU / NNAPI / QNN)
```

## Aceleración por hardware

| Plataforma | Chipset | Aceleración |
| --- | --- | --- |
| Android | Snapdragon 8 Gen 1+ | NNAPI → Hexagon NPU |
| Android | Samsung Exynos 2200+ | NNAPI → Samsung NPU |
| Android | Google Tensor G2+ | NNAPI → Google TPU |
| Automoción | SA8295P / SA8255P | QNN → Hexagon DSP |
| Cualquiera | Fallback CPU | XNNPACK |

## Relacionados

| Repositorio | Plataforma |
| --- | --- |
| [speech-swift](https://github.com/soniqo/speech-swift) | Apple (macOS, iOS) — MLX + CoreML |
| [speech-core](https://github.com/soniqo/speech-core) | Motor de pipeline C++ multiplataforma |
| **speech-android** | Android + Linux embebido — ONNX Runtime |

## Licencia

Apache 2.0
