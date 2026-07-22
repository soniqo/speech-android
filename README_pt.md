# Speech Android

📖 Idiomas: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

SDK de voz no dispositivo para Android, baseado em [ONNX Runtime](https://onnxruntime.ai) e [speech-core](https://github.com/soniqo/speech-core).

Reconhecimento de fala em streaming com baixa memória (25 idiomas por padrão, TDT de 114 idiomas opcional), texto para fala, detecção de atividade vocal e cancelamento de ruído — tudo executado localmente. Sem APIs em nuvem, nenhum dado sai do dispositivo.

**[📚 Documentação Android](https://soniqo.audio/pt/getting-started/android)**

**[APK de demonstração](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[APK do Control Demo](https://github.com/soniqo/speech-android/releases/latest/download/control-demo-release.apk)** · **[Modelos](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)** (contraparte Apple) · **[speech-core](https://github.com/soniqo/speech-core)** (motor de pipeline + build Linux/embarcado)

## Demonstração

<p align="center">
  <a href="https://www.youtube.com/watch?v=7L7_Uvvxtv0">
    <img src="https://img.youtube.com/vi/7L7_Uvvxtv0/maxresdefault.jpg" width="640" alt="Um agente de voz totalmente offline em 1.2 GB no Android — assista à demo no YouTube">
  </a>
</p>
<p align="center"><em>O loop de comando completo do <a href="control-demo/">control-demo</a> — Silero VAD → Parakeet STT → FunctionGemma → ação do dispositivo → resposta do Pocket TTS — totalmente offline em 1.2 GB de RAM</em></p>

## Escopo

Este repositório é o **empacotamento Android**: SDK Kotlin, ponte JNI, app de demonstração. O motor C++ e os wrappers de modelo ONNX (Silero VAD, Parakeet STT, Kokoro/Pocket TTS, DeepFilterNet3) ficam em [speech-core](https://github.com/soniqo/speech-core) e são incorporados via submódulo git. Linux / automotivo (Yocto, Qualcomm SA8295P/SA8255P) está em [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux).

## Modelos

| Modelo | Tarefa | Download | Pico de memória | Idiomas |
| --- | --- | --- | --- | --- |
| [Parakeet-EOU 120M](https://soniqo.audio/pt/guides/dictate) | STT em streaming + EOU (padrão) | [153 MB](https://huggingface.co/soniqo/Parakeet-EOU-120M-ONNX-INT8) | 232 MB | 25 |
| [Parakeet TDT v3](https://soniqo.audio/pt/guides/parakeet/android) | STT de ampla cobertura (opcional) | [891 MB](https://huggingface.co/soniqo/Parakeet-TDT-v3-ONNX) | ~1,1-1,3 GB | 114 |
| [Kokoro 82M](https://soniqo.audio/pt/guides/kokoro/android) | Texto para fala (padrão) | [330 MB](https://huggingface.co/soniqo/Kokoro-82M-ONNX) | 640 MB | 8 (en, fr, es, it, pt, hi, ja, zh) |
| [Pocket TTS 100M](https://huggingface.co/soniqo/Pocket-TTS-100M-ONNX-INT8) | Texto para fala em streaming (opcional, voz Alba fixa) | ~126 MB | ainda não medido | Inglês |
| [Supertonic-3](https://soniqo.audio/pt/guides/supertonic) | Texto para fala (LiteRT, flow-matching, G2P-free, 44,1 kHz) | [~380 MB](https://huggingface.co/soniqo/Supertonic-3-LiteRT) | 832 MB | 31 |
| [Silero VAD v5](https://soniqo.audio/pt/guides/vad/android) | Detecção de atividade vocal | [2 MB](https://huggingface.co/soniqo/Silero-VAD-v5-ONNX) | <10 MB | Qualquer |
| [DeepFilterNet3](https://soniqo.audio/pt/guides/denoise/android) | Cancelamento de ruído | [~8 MB](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | não carregado por padrão | Qualquer |
| [FunctionGemma 270M](https://soniqo.audio/pt/guides/function-calls) | LLM no dispositivo — chamadas estruturadas de função / ferramenta | [283 MB](https://huggingface.co/soniqo/FunctionGemma-270M-LiteRT-LM) | depende do runtime do app | Ajustado para EN |

Os modelos são baixados automaticamente no primeiro lançamento via `ModelManager.ensureModels()`.

`SpeechConfig()` usa `SttModel.PARAKEET_EOU` e `TtsModel.KOKORO_SHORT_TURN` por padrão para manter integrações do SDK e o reconhecedor do sistema no caminho Android de baixa memória. O app demo seleciona `SttModel.PARAKEET` para que as telas de eco e ditado usem o modelo TDT maior de 114 idiomas.

Para reconhecimento focado em idiomas, use `SpeechConfig(sttModel = SttModel.PARAKEET, languageHints = listOf("en", "fr"))`. Defina `language = "en"` quando quiser fixar um único idioma.

O **Supertonic-3** é um TTS multilíngue opcional de maior qualidade — selecione-o com `SpeechConfig(ttsModel = TtsModel.SUPERTONIC)` (requer o backend LiteRT). O host executa seus quatro grafos de flow-matching não autorregressivos no dispositivo a 44,1 kHz; o front-end é G2P-free (NFKD + índice Unicode — sem phonemizer), de modo que todos os 31 idiomas seguem um único caminho.

## Experimente a demo

Baixe o [APK assinado](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) e instale em qualquer dispositivo Android arm64 (8+). O pacote padrão de modelos de baixa memória (~500 MB) é baixado automaticamente no primeiro lançamento.

## Adicionar dependência

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.13")
}
```

## Uso do Kotlin

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

// Alimente PCM float32 mono 16kHz do microfone
pipeline.pushAudio(samples)
```

## Compilar a partir do código-fonte

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 38 testes e2e
```

`./setup.sh` inicializa o submódulo speech-core e baixa o ONNX Runtime
para `./ort/`.

## Aplicativo de demonstração

O módulo [`app/`](app/) é uma demo mínima de assistente de voz com:

- Visualização de forma de onda VAD em tempo real
- Modo eco: transcreve a fala e a sintetiza de volta (sem LLM)
- Modo ditado: resultados parciais em streaming
- STT Parakeet TDT de 114 idiomas nas telas de eco e ditado
- Tela de teste `SpeechRecognizer` — exercita o caminho de entrada de voz em todo o sistema
- UI de bolhas de chat com exibição de latência STT/TTS

```bash
./gradlew :app:installDebug
```

### Demo de controle do pipeline completo

O app separado [`control-demo/`](control-demo/) executa todo o agente
localmente: Silero VAD → Parakeet-EOU STT → chamadas de ferramentas do
FunctionGemma 270M → ações do dispositivo Android → Pocket TTS. Ele mostra a
latência de cada etapa e vincula diretamente o `:sdk` deste checkout, usando
assim as otimizações locais de voz.

Baixe o [APK assinado do Control Demo](https://github.com/soniqo/speech-android/releases/latest/download/control-demo-release.apk)
da versão mais recente ou instale um build de desenvolvimento a partir do código-fonte:

```bash
./gradlew :control-demo:installDebug
```

## Entrada de voz do sistema (`RecognitionService`)

O SDK fornece um `audio.soniqo.speech.service.SpeechRecognitionService` pronto
para uso que se conecta à API `SpeechRecognizer` do framework do Android —
sem código a escrever. Uma vez que seu app é selecionado como o reconhecedor
de voz padrão, qualquer app de terceiros chamando
`SpeechRecognizer.createSpeechRecognizer(context)` (sem `ComponentName`)
obtém STT totalmente no dispositivo através do seu pipeline.

**1. Declare `RECORD_AUDIO` e o serviço em `AndroidManifest.xml`:**

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

**2. Adicione `app/src/main/res/xml/recognition_service.xml`:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<recognition-service xmlns:android="http://schemas.android.com/apk/res/android" />
```

(Opcionalmente adicione `android:settingsActivity="..."` para expor um ícone
de engrenagem no seletor de entrada de voz do sistema.)

**3. Defina o serviço como padrão do sistema** (Configurações → Sistema →
Idiomas e entrada → Seletor de entrada de voz no Android puro, ou via adb):

```bash
adb shell settings put secure voice_recognition_service \
  your.package/audio.soniqo.speech.service.SpeechRecognitionService
```

**4. Verifique** executando a tela *Recognizer test* do app demo, que chama
`SpeechRecognizer.createSpeechRecognizer(ctx)` (sem componente) e registra
cada callback do framework — útil para confirmar o round-trip do binder sem
precisar do logcat.

O serviço implementa `onCheckRecognitionSupport` (API 33+) retornando os
25 idiomas base BCP-47 cobertos pelo Parakeet-EOU, além da etiqueta regional
exata solicitada quando ela corresponde a um idioma base suportado. Os idiomas
são marcados como `installedOnDeviceLanguage` quando os modelos estão
presentes, ou como `supportedOnDeviceLanguage` antes do download. O serviço
não toma o foco de áudio do app chamador.

**Limitação:** Gboard, Samsung Keyboard e Google Assistant agrupam seus
próprios reconhecedores e ignoram o padrão do sistema. Apps que chamam
explicitamente a API `SpeechRecognizer` do framework (ou constroem sua
própria UI em cima dela) são os que passam pelo seu serviço.

## Texto para fala do sistema (`TextToSpeechService`)

O app de demonstração também expõe
`audio.soniqo.speech.service.SpeechTextToSpeechService`, então o Android pode
selecionar o app em Configurações → Sistema → Idiomas e entrada → Saída de
texto para fala. Esse caminho usa `ModelManager.ensureTtsModels()` e um cache
separado `models_tts/`, portanto o TTS do framework baixa apenas os recursos
do Kokoro em vez do pacote completo VAD/STT/enhancer.

Para expor o motor a partir de outro app, declare o serviço:

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

Adicione `app/src/main/res/xml/tts_engine.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<tts-engine xmlns:android="http://schemas.android.com/apk/res/android" />
```

## Desempenho

Medido em um Galaxy S23 Ultra (SM-S918B), apenas CPU salvo indicação. RTF é
tempo de parede ÷ duração do áudio emitido: menor é mais rápido, e <1,0 é mais rápido que o tempo real.

| Modelo | Tarefa | RTF | Latência | Pico de memória |
| --- | --- | --- | --- | --- |
| Parakeet-EOU 120M ONNX INT8 | STT em streaming + EOU | 0,21 | parciais em streaming | 232 MB |
| Kokoro 82M grafo completo (publicado, CPU com dois threads) | TTS | 1,81 | por frase | ~604 MB |
| Kokoro 82M turno curto (grafo de 3,0 s, padrão) | TTS | 0,75–0,88 | respostas delimitadas; nova tentativa segura | ~527 MB |
| Supertonic-3 LiteRT | TTS | 0,34 | ~1,1s TTFA | 832 MB |
| Silero VAD v5 | VAD | <0,01 | <1ms por bloco de 32ms | <10 MB |

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
│      SpeechPipeline (Kotlin)                 │
│            │                                 │
│            ▼                                 │
│      jni_bridge.cpp  (~250 linhas)           │
│            │                                 │
│            ▼                                 │
│  ┌──────────────────────────────────────┐    │
│  │  speech_core_models (submódulo git)  │    │
│  │   SileroVad / ParakeetStt /          │    │
│  │   KokoroTts / OnnxPocketTts /        │    │
│  │   DeepFilterEnhancer                  │    │
│  │            │                         │    │
│  │            ▼                         │    │
│  │  speech_core  (orquestração:         │    │
│  │   pipeline · turn · interrupções)    │    │
│  └──────────────────────────────────────┘    │
│            │                                 │
│            ▼                                 │
│      ONNX Runtime (CPU / NNAPI)              │
└──────────────────────────────────────────────┘
```

Cada classe de modelo implementa diretamente a interface correspondente de
speech-core (`VADInterface`, `STTInterface`, `TTSInterface`,
`EnhancerInterface`) — a ponte JNI as instancia e entrega referências ao
`VoicePipeline`. Sem boilerplate de adaptador C-vtable.

## Aceleração de hardware

| Chipset | Aceleração |
| --- | --- |
| Snapdragon 8 Gen 1+ | NNAPI → Hexagon NPU |
| Samsung Exynos 2200+ | NNAPI → Samsung NPU |
| Google Tensor G2+ | NNAPI → Google TPU |
| Fallback CPU | XNNPACK |

Para Qualcomm SA8295P / SA8255P automotivo com QNN (Hexagon DSP), veja
[speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux).

## Projetos relacionados

| Repositório | Escopo |
| --- | --- |
| [speech-swift](https://github.com/soniqo/speech-swift) | Apple (macOS, iOS) — MLX + CoreML |
| [speech-core](https://github.com/soniqo/speech-core) | Motor de pipeline C++ multiplataforma + wrappers de modelo ONNX + exemplos Linux/embarcado |
| **speech-android** | Wrapper Android — SDK Kotlin + ponte JNI sobre speech-core |

## Licença

Apache 2.0
