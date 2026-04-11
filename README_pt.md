# Speech Android

📖 Idiomas: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

SDK de voz no dispositivo para Android e Linux embarcado, baseado em [ONNX Runtime](https://onnxruntime.ai) e [speech-core](https://github.com/soniqo/speech-core).

Reconhecimento de fala (114 idiomas), texto para fala (8 idiomas), detecção de atividade vocal e cancelamento de ruído — tudo executado localmente. Sem APIs em nuvem, nenhum dado sai do dispositivo.

**[APK de demonstração](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[Modelos](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)** (contraparte Apple) · **[speech-core](https://github.com/soniqo/speech-core)** (motor de pipeline)

## Plataformas

| Plataforma | API | Aceleração | Diretório |
| --- | --- | --- | --- |
| Android | Kotlin (`SpeechPipeline`) | NNAPI (Snapdragon, Exynos, Tensor) | `sdk/` |
| Linux embarcado | C (`speech.h`) | QNN (Hexagon DSP) | `linux/` |

## Modelos

| Modelo | Tarefa | Tamanho INT8 | Idiomas |
| --- | --- | --- | --- |
| [Parakeet TDT v3](https://huggingface.co/aufklarer/Parakeet-TDT-v3-ONNX) | Reconhecimento de fala | 891 MB | 114 |
| [Kokoro 82M](https://huggingface.co/aufklarer/Kokoro-82M-ONNX) | Texto para fala | 330 MB | 8 (en, fr, es, it, pt, hi, ja, zh) |
| [Silero VAD v5](https://huggingface.co/aufklarer/Silero-VAD-v5-ONNX) | Detecção de atividade vocal | 2 MB | Qualquer |
| [DeepFilterNet3](https://huggingface.co/aufklarer/DeepFilterNet3-ONNX) | Cancelamento de ruído | ~8 MB | Qualquer |

Os modelos são baixados automaticamente no primeiro lançamento (Android) ou colocados manualmente (Linux).

## Android

### Experimente a demo

Baixe o [APK assinado](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) e instale em qualquer dispositivo Android arm64 (8+). Os modelos (~1,2 GB) são baixados automaticamente no primeiro lançamento.

### Adicionar dependência

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.8")
}
```

### Uso do Kotlin

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

// Alimente PCM float32 mono 16kHz do microfone
pipeline.pushAudio(samples)
```

### Compilar a partir do código-fonte

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 34 testes e2e
```

### Aplicativo de demonstração

O módulo [`app/`](app/) é uma demo mínima de assistente de voz com:

- Visualização de forma de onda VAD em tempo real
- Modo eco: transcreve a fala e a sintetiza de volta (sem LLM)
- UI de bolhas de chat com exibição de latência STT/TTS

```bash
./gradlew :app:installDebug
```

## Desempenho

Medido em emulador Android (arm64-v8a, sem NNAPI). Hardware real é significativamente mais rápido.

| Modelo | Tarefa | Áudio | Inferência | RTF |
| --- | --- | --- | --- | --- |
| Parakeet TDT v3 | STT | 1,5s | 175ms | 0,12 |
| Kokoro 82M | TTS | 1,9s saída | 1.075ms | 0,58 |
| Silero VAD v5 | VAD | bloco 32ms | <1ms | <0,01 |

## Linux embarcado

API C mínima para plataformas automotivas e embarcadas. Veja [`linux/README.md`](linux/README.md) para a documentação completa.

### Uso da API C

```c
#include <speech.h>

void on_event(const speech_event_t* event, void* ctx) {
    if (event->type == SPEECH_EVENT_TRANSCRIPTION)
        printf("%s\n", event->text);
}

speech_config_t cfg = speech_config_default();
cfg.model_dir = "/opt/speech/models";
cfg.use_qnn = true;  // Aceleração Hexagon DSP

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

### Testar

```bash
linux/tests/download_models.sh              # baixar modelos ONNX
SPEECH_MODEL_DIR=tests/models ./build/speech_test   # 12 testes
```

### Compilação cruzada para Yocto

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

Suporte a barge-in: falar durante a reprodução TTS interrompe e inicia uma nova transcrição.

## Arquitetura

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

## Aceleração de hardware

| Plataforma | Chipset | Aceleração |
| --- | --- | --- |
| Android | Snapdragon 8 Gen 1+ | NNAPI → Hexagon NPU |
| Android | Samsung Exynos 2200+ | NNAPI → Samsung NPU |
| Android | Google Tensor G2+ | NNAPI → Google TPU |
| Automotivo | SA8295P / SA8255P | QNN → Hexagon DSP |
| Qualquer | Fallback CPU | XNNPACK |

## Projetos relacionados

| Repositório | Plataforma |
| --- | --- |
| [speech-swift](https://github.com/soniqo/speech-swift) | Apple (macOS, iOS) — MLX + CoreML |
| [speech-core](https://github.com/soniqo/speech-core) | Motor de pipeline C++ multiplataforma |
| **speech-android** | Android + Linux embarcado — ONNX Runtime |

## Licença

Apache 2.0
