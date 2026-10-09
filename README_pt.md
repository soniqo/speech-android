# Speech Android

📖 Idiomas: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

SDK de voz no dispositivo para Android, baseado em [ONNX Runtime](https://onnxruntime.ai) e [speech-core](https://github.com/soniqo/speech-core).

Reconhecimento de fala em streaming com baixa memória e um modelo TDT maior opcional, ambos para 25 idiomas europeus, além de texto para fala, detecção de atividade vocal e cancelamento de ruído — tudo executado localmente. Sem APIs em nuvem, nenhum dado sai do dispositivo.

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
| [Parakeet TDT v3](https://soniqo.audio/pt/guides/parakeet/android) | STT de ampla cobertura (opcional) | [891 MB](https://huggingface.co/soniqo/Parakeet-TDT-v3-ONNX) | ~1,1-1,3 GB | 25 europeus |
| [Nemotron-3.5 multilíngue](https://soniqo.audio/pt/guides/nemotron) | STT em streaming condicionado por prompt (opcional) | [~721 MB](https://huggingface.co/soniqo/Nemotron-3.5-ASR-Streaming-Multilingual-0.6B-LiteRT-INT8) | ainda não medido | mais de 100 (incluindo zh) |
| [Canary 180M Flash](https://huggingface.co/soniqo/Canary-180M-Flash-ONNX) | STT offline + tradução (opcional) | [273 MB](https://huggingface.co/soniqo/Canary-180M-Flash-ONNX) | ~780 MB | 4 (en, de, es, fr) |
| [Kokoro 82M](https://soniqo.audio/pt/guides/kokoro/android) | Texto para fala (padrão) | [330 MB](https://huggingface.co/soniqo/Kokoro-82M-ONNX) | 640 MB | 8 (en, fr, es, it, pt, hi, ja, zh) |
| [Pocket TTS 100M](https://huggingface.co/soniqo/Pocket-TTS-100M-ONNX-INT8) | Texto para fala em streaming (opcional, voz Alba fixa) | ~126 MB | ainda não medido | Inglês |
| [Supertonic-3](https://soniqo.audio/pt/guides/supertonic) | Texto para fala (LiteRT, flow-matching, G2P-free, 44,1 kHz) | [~380 MB](https://huggingface.co/soniqo/Supertonic-3-LiteRT) | 832 MB | 31 |
| [Silero VAD v5](https://soniqo.audio/pt/guides/vad/android) | Detecção de atividade vocal | [2 MB](https://huggingface.co/soniqo/Silero-VAD-v5-ONNX) | <10 MB | Qualquer |
| [Sortformer 4 locutores](https://huggingface.co/soniqo/Sortformer-Diarization-4spk-ONNX) | Diarização de locutores em streaming (opcional) | [475 MB](https://huggingface.co/soniqo/Sortformer-Diarization-4spk-ONNX) | ainda não medido | Qualquer |
| [ReDimNet2-B6](https://huggingface.co/soniqo/ReDimNet2-B6-ONNX-FP32) | Embeddings de locutor (opcional) | [51 MB](https://huggingface.co/soniqo/ReDimNet2-B6-ONNX-FP32) | ainda não medido | Qualquer |
| [DeepFilterNet3](https://soniqo.audio/pt/guides/denoise/android) | Cancelamento de ruído | [~8 MB](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | não carregado por padrão | Qualquer |
| [FunctionGemma 270M](https://soniqo.audio/pt/guides/function-calls) | LLM no dispositivo — chamadas estruturadas de função / ferramenta | [283 MB](https://huggingface.co/soniqo/FunctionGemma-270M-LiteRT-LM) | depende do runtime do app | Ajustado para EN |

Os modelos são baixados automaticamente no primeiro lançamento via `ModelManager.ensureModels()`.

`SpeechConfig()` usa `SttModel.PARAKEET_EOU` e `TtsModel.KOKORO_SHORT_TURN` por padrão para manter integrações do SDK e o reconhecedor do sistema no caminho Android de baixa memória. O app demo seleciona `SttModel.PARAKEET` para que as telas de eco e ditado usem o modelo TDT maior com 25 idiomas europeus.

Os dois modelos Parakeet sempre detectam o idioma automaticamente: nenhum aceita `language` ou `languageHints`, e nenhum oferece suporte a chinês. Para selecionar um idioma, incluindo mandarim, use o backend Nemotron condicionado por prompt:

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
    language = "zh-CN", // ou "zh-TW"
)
```

O **Supertonic-3** é um TTS multilíngue opcional de maior qualidade — selecione-o com `SpeechConfig(ttsModel = TtsModel.SUPERTONIC)` (requer o backend LiteRT). O host executa seus quatro grafos de flow-matching não autorregressivos no dispositivo a 44,1 kHz; o front-end é G2P-free (NFKD + índice Unicode — sem phonemizer), de modo que todos os 31 idiomas seguem um único caminho.

Frases longas: o pacote também inclui um par opcional de grafos com janela latente L=128 (`vector_estimator_L128.tflite` + `vocoder_L128.tflite`, +341 MB). Baixe-o com `ModelDownloadWorker.enqueue(context, ttsModel = TtsModel.SUPERTONIC, supertonicLatentBuckets = true)` (ou a mesma flag em `ModelManager.ensureTtsModels`) e o speech-core sintetiza uma frase mais longa que a janela base de 4,5 s em uma única passagem em vez de dividi-la; o par é carregado no primeiro uso (+~360 MB de RSS). Desativado por padrão.

Tanto o Kokoro quanto o Supertonic aceitam um preset de voz por chamada na síntese direta — `pipeline.synthesize("Hello", "en", "M1")` (Supertonic `F1`…`F5` / `M1`…`M5`; Kokoro `af_heart`, `ff_siwis`, …) — e o mesmo argumento existe em `synthesizeStreaming` e `SpeechSynthesizer`. O preset vale apenas para essa chamada; omita-o (ou passe `""`) para manter a voz padrão do motor, e um id desconhecido lança uma exceção.

## Experimente a demo

Baixe o [APK assinado](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) e instale em qualquer dispositivo Android arm64 (8+). O pacote padrão de modelos de baixa memória (~500 MB) é baixado automaticamente no primeiro lançamento.

## Adicionar dependência

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.23")
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

### Detecção de fim de turno

Um VAD detecta silêncio, mas não sabe se uma pausa conclui o pensamento do
falante. O Smart Turn v3.2 é um classificador opcional baseado em áudio que
analisa os últimos oito segundos do turno atual e mantém aberto um turno
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

O download opcional adiciona o grafo INT8 fixado de 11,1 MB. A inferência roda
uma vez após cada pausa confirmada pelo VAD, e não em cada frame de áudio. Se a
pausa for rejeitada, a fala retomada continua no mesmo turno; o limite máximo
de silêncio ainda garante um fim. O Smart Turn foi criado pela Pipecat/Daily e
é distribuído sob BSD-2-Clause.

### Apenas detecção de atividade de voz

Apps que só precisam saber *quando* alguém está falando — controle de
push-to-talk, segmentação de gravação, ativação por voz — podem carregar o
Silero sozinho: 2 MB de download e menos de 10 MB de RAM em vez dos ~500 MB do
conjunto padrão. Sem STT, sem TTS, sem pipeline.

```kotlin
val detector = VadDetector(
    VadConfig(
        modelDir = ModelManager.ensureVadModels(context),
        emitUtteranceAudio = true, // ative para receber o segmento capturado
    )
)

detector.events.collect { event ->
    when (event) {
        is VadEvent.SpeechStarted -> startRecordingUi()
        is VadEvent.SpeechEnded -> saveSegment(event.audio) // 16 kHz float32
    }
}

// 16 kHz mono float32, quadros de 512 amostras
detector.pushAudio(samples)
// fecha um segmento aberto quando o fluxo termina
detector.flush()
```

`VadConfig` expõe a mesma detecção de turno que o pipeline usa internamente:
limiares de início e fim, duração mínima de fala, silêncio de fim de fala e o
buffer de pré-fala que preserva a primeira sílaba. Esse caminho usa
`ModelManager.ensureVadModels()` e um cache `models_vad/` separado, então nunca
baixa os pacotes de STT/TTS.

### Componentes para transcrição de reuniões

Apps que cuidam da própria captura e segmentação — um gravador de reuniões, um app de anotações — podem carregar o reconhecedor, o diarizador e o codificador de locutores separadamente, sem o pipeline. Cada um tem seu próprio download e diretório de cache, e nenhum toma decisões de produto: o transcritor devolve texto, o diarizador probabilidades de locutor por quadro e o codificador um vetor de voz de 192 dimensões. Limiares, turnos, rótulos de locutor e comparação de vozes ficam no app.

```kotlin
// Opcional: um espelho escolhido pelo usuário, com a estrutura do Hugging Face.
ModelManager.endpoint = "https://hf-mirror.com"

val transcriber = StreamingTranscriber(
    TranscriberConfig(modelDir = ModelManager.ensureTranscriberModels(context))
)
transcriber.beginStream()
transcriber.pushAudio(samples)            // 16 kHz mono float32; texto até agora
val text = transcriber.endStream().text

val diarizer = SpeakerDiarizer(
    DiarizerConfig(modelDir = ModelManager.ensureDiarizerModels(context))
)
val frames = diarizer.pushAudio(samples)  // [quadros x diarizer.speakers], muitas vezes vazio
val tail = diarizer.endStream()           // quando a gravação termina

val embedder = SpeakerEmbedder(
    SpeakerEmbedderConfig(modelDir = ModelManager.ensureSpeakerEmbeddingModels(context))
)
val voice = embedder.embed(speech)        // pelo menos 2 s; FloatArray(192)
```

`TranscriberConfig.language` é `"auto"` por padrão: o prompt de idioma automático do Nemotron, como no speech-swift; `"en-US"`, `"pt_BR"` ou apenas `"fr"` fixam um idioma. Cada transcrição traz tempos por palavra, em segundos desde `beginStream`, tirados do quadro do codificador em que cada token foi emitido. O Sortformer usa um stream por gravação e responde cerca de 30 segundos atrás do áudio, porque a exportação publicada decodifica 27,2 segundos por chamada. `ModelDownloadWorker.enqueue(context, includePipeline = false, includeTranscriber = true, includeDiarizer = true, includeSpeakerEmbedding = true)` baixa os três conjuntos em segundo plano.

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
- Sobreposição de voz: botão de microfone flutuante para ditar em qualquer app
- STT Parakeet TDT com 25 idiomas europeus nas telas de eco e ditado
- Tela de teste `SpeechRecognizer` — exercita o caminho de entrada de voz em todo o sistema
- UI de bolhas de chat com exibição de latência STT/TTS

```bash
./gradlew :app:installDebug
```

### Sobreposição de voz (ditar em qualquer app)

A **sobreposição de voz** coloca um botão de microfone arrastável sobre outros
apps. Ao tocá-lo ele vira **■ parar** / **✕ cancelar**: parar escreve a
transcrição no campo de texto que estiver em foco e cancelar a descarta. Se
nenhum campo editável estiver em foco, o texto vai para a área de transferência
em vez de se perder.

São necessárias três permissões, cada uma com sua própria tela do sistema — a
tela de configuração mostra quais ainda faltam:

| Permissão | Para quê |
| --- | --- |
| Microfone | capturar áudio |
| Sobrepor a outros apps | desenhar o botão fora do app |
| Serviço de acessibilidade | escrever no campo de texto de outro app |

A janela da sobreposição é deliberadamente não focável, para que o campo de
destino mantenha o foco de entrada enquanto os botões são tocados. O texto é
inserido no cursor com `ACTION_SET_TEXT`. Campos cujo conteúdo real não pode ser
lido — alguns apps informam o próprio placeholder como texto do campo — são
preenchidos por colagem, o que substitui o que estava na área de transferência;
o ditado é apagado dela logo em seguida.

> Instalando por APK em vez da Play Store? O Android bloqueia o botão de
> acessibilidade até liberá-lo em
> Configurações → Apps → Speech → ⋮ → **Permitir configurações restritas**.

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
