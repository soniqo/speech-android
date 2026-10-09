# Speech Android

📖 阅读语言: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

适用于 Android 的设备端语音 SDK,基于 [ONNX Runtime](https://onnxruntime.ai) 和 [speech-core](https://github.com/soniqo/speech-core) 构建。

低内存流式语音识别和可选的大型 TDT 模型（两者均覆盖 25 种欧洲语言），以及文本转语音、语音活动检测和噪声消除——全部在本地运行。无需云端 API,数据不会离开设备。

**[📚 Android 文档](https://soniqo.audio/zh/getting-started/android)**

**[演示 APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[Control Demo APK](https://github.com/soniqo/speech-android/releases/latest/download/control-demo-release.apk)** · **[模型](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)**(Apple 对应版本)· **[speech-core](https://github.com/soniqo/speech-core)**(管线引擎 + Linux/嵌入式构建)

## 演示

<p align="center">
  <a href="https://www.youtube.com/watch?v=7L7_Uvvxtv0">
    <img src="https://img.youtube.com/vi/7L7_Uvvxtv0/maxresdefault.jpg" width="640" alt="我们把完整的离线语音代理装进 Android 的 1.2 GB — 在 YouTube 观看演示">
  </a>
</p>
<p align="center"><em><a href="control-demo/">control-demo</a> 的完整指令闭环 — Silero VAD → Parakeet STT → FunctionGemma → 设备操作 → Pocket TTS 回复 — 全程离线,仅 1.2 GB 内存</em></p>

## 范围

本仓库是 **Android 打包**:Kotlin SDK、JNI 桥接、演示应用。C++ 引擎和 ONNX 模型封装(Silero VAD、Parakeet STT、Kokoro/Pocket TTS、DeepFilterNet3)位于 [speech-core](https://github.com/soniqo/speech-core),通过 git 子模块引入。Linux / 汽车(Yocto、Qualcomm SA8295P/SA8255P)位于 [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux)。

## 模型

| 模型 | 任务 | 下载大小 | 峰值内存 | 语言 |
| --- | --- | --- | --- | --- |
| [Parakeet-EOU 120M](https://soniqo.audio/zh/guides/dictate) | 流式 STT + 端点检测(默认) | [153 MB](https://huggingface.co/soniqo/Parakeet-EOU-120M-ONNX-INT8) | 232 MB | 25 |
| [Parakeet TDT v3](https://soniqo.audio/zh/guides/parakeet/android) | 广覆盖 STT(可选) | [891 MB](https://huggingface.co/soniqo/Parakeet-TDT-v3-ONNX) | ~1.1-1.3 GB | 25 种欧洲语言 |
| [Nemotron-3.5 多语言](https://soniqo.audio/zh/guides/nemotron) | 提示词条件流式 STT(可选) | [~721 MB](https://huggingface.co/soniqo/Nemotron-3.5-ASR-Streaming-Multilingual-0.6B-LiteRT-INT8) | 尚未测量 | 100+（包括 zh） |
| [Canary 180M Flash](https://huggingface.co/soniqo/Canary-180M-Flash-ONNX) | 离线 STT + 翻译（可选） | [273 MB](https://huggingface.co/soniqo/Canary-180M-Flash-ONNX) | ~780 MB | 4 (en, de, es, fr) |
| [Kokoro 82M](https://soniqo.audio/zh/guides/kokoro/android) | 文本转语音(默认) | [330 MB](https://huggingface.co/soniqo/Kokoro-82M-ONNX) | 640 MB | 8(en、fr、es、it、pt、hi、ja、zh) |
| [Pocket TTS 100M](https://huggingface.co/soniqo/Pocket-TTS-100M-ONNX-INT8) | 流式文本转语音(可选,固定 Alba 音色) | ~126 MB | 尚未测量 | 英语 |
| [Supertonic-3](https://soniqo.audio/zh/guides/supertonic) | 文本转语音(LiteRT、流匹配、免 G2P、44.1 kHz) | [~380 MB](https://huggingface.co/soniqo/Supertonic-3-LiteRT) | 832 MB | 31 |
| [Silero VAD v5](https://soniqo.audio/zh/guides/vad/android) | 语音活动检测 | [2 MB](https://huggingface.co/soniqo/Silero-VAD-v5-ONNX) | <10 MB | 任意 |
| [Sortformer 4 说话人](https://huggingface.co/soniqo/Sortformer-Diarization-4spk-ONNX) | 流式说话人分离(可选) | [475 MB](https://huggingface.co/soniqo/Sortformer-Diarization-4spk-ONNX) | 尚未测量 | 任意 |
| [ReDimNet2-B6](https://huggingface.co/soniqo/ReDimNet2-B6-ONNX-FP32) | 说话人嵌入(可选) | [51 MB](https://huggingface.co/soniqo/ReDimNet2-B6-ONNX-FP32) | 尚未测量 | 任意 |
| [DeepFilterNet3](https://soniqo.audio/zh/guides/denoise/android) | 噪声消除 | [~8 MB](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | 默认不加载 | 任意 |
| [FunctionGemma 270M](https://soniqo.audio/zh/guides/function-calls) | 端侧 LLM — 结构化函数 / 工具调用 | [283 MB](https://huggingface.co/soniqo/FunctionGemma-270M-LiteRT-LM) | 取决于应用运行时 | EN 调优 |

模型在首次启动时通过 `ModelManager.ensureModels()` 自动下载。

`SpeechConfig()` 默认使用 `SttModel.PARAKEET_EOU` 和 `TtsModel.KOKORO_SHORT_TURN`,让 SDK 集成和系统识别服务走低内存 Android 路径。演示应用会选用 `SttModel.PARAKEET`,使回声和听写界面使用覆盖 25 种欧洲语言的较大型 TDT 模型。

两个 Parakeet 模型始终自动检测语言：它们都不接受 `language` 或 `languageHints`，也都不支持中文。要固定一种语言（包括普通话），请使用提示词条件 Nemotron 后端：

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
    language = "zh-CN", // 或 "zh-TW"
)
```

**Supertonic-3** 是可选启用的更高质量多语言 TTS — 通过 `SpeechConfig(ttsModel = TtsModel.SUPERTONIC)` 选用(需要 LiteRT 后端)。宿主在设备端以 44.1 kHz 运行其四个非自回归流匹配图;前端免 G2P(NFKD + Unicode 索引 — 无音素转换器),因此全部 31 种语言走同一条路径。

长句:模型包还附带一对可选的 L=128 潜在窗口图(`vector_estimator_L128.tflite` + `vocoder_L128.tflite`,+341 MB)。通过 `ModelDownloadWorker.enqueue(context, ttsModel = TtsModel.SUPERTONIC, supertonicLatentBuckets = true)`(或 `ModelManager.ensureTtsModels` 上的同名标志)下载后,speech-core 会把超过 4.5 s 基础窗口的句子一次合成,而不是拆开;这对图在首次使用时加载(+~360 MB RSS)。默认关闭。

Kokoro 与 Supertonic 都支持在直接合成时按调用指定音色预设 — `pipeline.synthesize("Hello", "en", "M1")`(Supertonic:`F1`…`F5` / `M1`…`M5`;Kokoro:`af_heart`、`ff_siwis` 等)— `synthesizeStreaming` 和 `SpeechSynthesizer` 也有同样的参数。该预设仅对本次调用生效;省略它(或传 `""`)则沿用引擎默认音色,未知 id 会抛出异常。

## 试用演示

下载[已签名的 APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) 并安装到任何 arm64 Android 设备(8 及以上)。默认低内存模型包(~500 MB)在首次启动时自动下载。

## 添加依赖

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.23")
}
```

## Kotlin 用法

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

// 从麦克风输入 16kHz 单声道 float32 PCM
pipeline.pushAudio(samples)
```

### 轮次结束检测

VAD 能检测静音，但无法判断一次停顿是否意味着说话者已经表达完毕。Smart Turn v3.2
是可选的原生音频分类器；它分析当前轮次的最后 8 秒，并在话语未完成时保持轮次开放。

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

启用后会额外下载固定版本的 11.1 MB INT8 模型。推理只在 VAD 确认每次停顿后运行
一次，而不是对每个音频帧运行。若模型否决该停顿，恢复的语音仍属于同一轮次；最大静音
时长则保证轮次最终会结束。Smart Turn 由 Pipecat/Daily 创建，并以 BSD-2-Clause
许可证发布。

### 仅语音活动检测

只需知道*何时*有人说话的应用——按住说话门控、录音分段、语音唤醒——可以单独加载
Silero:下载 2 MB、占用不到 10 MB 内存,而非默认模型集的约 500 MB。不加载 STT、
TTS 或管线。

```kotlin
val detector = VadDetector(
    VadConfig(
        modelDir = ModelManager.ensureVadModels(context),
        emitUtteranceAudio = true, // 选择接收捕获到的片段
    )
)

detector.events.collect { event ->
    when (event) {
        is VadEvent.SpeechStarted -> startRecordingUi()
        is VadEvent.SpeechEnded -> saveSegment(event.audio) // 16 kHz float32
    }
}

// 16 kHz 单声道 float32,512 采样一帧
detector.pushAudio(samples)
// 流结束时关闭仍打开的片段
detector.flush()
```

`VadConfig` 暴露管线内部使用的同一套轮次检测:起始与结束阈值、最短语音时长、
语音结束静音时长,以及保留首个音节的前置语音缓冲。该路径使用
`ModelManager.ensureVadModels()` 和独立的 `models_vad/` 缓存,因此永远不会拉取
STT/TTS 模型包。

### 会议转写组件

自行负责采集和分段的应用(例如会议录音或记笔记应用)可以脱离管线,单独加载识别器、说话人分离模型和说话人编码器。每个模型都有自己的下载和缓存目录,也都不替应用做产品决策:转写器返回文本,分离模型返回逐帧的说话人概率,编码器返回 192 维声纹向量。阈值、轮次、说话人标签和声纹匹配都留给应用。

```kotlin
// 可选:用户自行选择的、采用 Hugging Face 目录结构的镜像。
ModelManager.endpoint = "https://hf-mirror.com"

val transcriber = StreamingTranscriber(
    TranscriberConfig(modelDir = ModelManager.ensureTranscriberModels(context))
)
transcriber.beginStream()
transcriber.pushAudio(samples)            // 16 kHz 单声道 float32;返回目前为止的文本
val text = transcriber.endStream().text

val diarizer = SpeakerDiarizer(
    DiarizerConfig(modelDir = ModelManager.ensureDiarizerModels(context))
)
val frames = diarizer.pushAudio(samples)  // [帧数 x diarizer.speakers],经常为空
val tail = diarizer.endStream()           // 录音结束时

val embedder = SpeakerEmbedder(
    SpeakerEmbedderConfig(modelDir = ModelManager.ensureSpeakerEmbeddingModels(context))
)
val voice = embedder.embed(speech)        // 至少 2 秒;FloatArray(192)
```

`TranscriberConfig.language` 默认为 `"auto"`,即 Nemotron 的自动语言提示,与 speech-swift 相同;`"en-US"`、`"pt_BR"` 或单独的 `"fr"` 会固定一种语言。每份转写结果都带有词级时间,以 `beginStream` 起算的秒数表示,取自每个词元被输出时的编码器帧。Sortformer 每段录音使用一个流,结果比音频晚约 30 秒,因为已发布的导出模型每次调用解码 27.2 秒。`ModelDownloadWorker.enqueue(context, includePipeline = false, includeTranscriber = true, includeDiarizer = true, includeSpeakerEmbedding = true)` 会在后台下载这三组模型。

## 从源代码构建

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 38 个端到端测试
```

`./setup.sh` 会初始化 speech-core 子模块并将 ONNX Runtime 下载到 `./ort/`。

## 演示应用

[`app/`](app/) 模块是一个最小化的语音助手演示,包含:

- 实时 VAD 波形可视化
- 回声模式:转录语音并将其合成回放(无 LLM)
- 听写模式:流式部分结果
- 语音悬浮窗:悬浮麦克风按钮,可向任意应用听写
- 回声和听写界面使用覆盖 25 种欧洲语言的 Parakeet TDT STT
- `SpeechRecognizer` 测试界面 — 演练系统级语音输入路径
- 带有 STT/TTS 延迟显示的聊天气泡 UI

```bash
./gradlew :app:installDebug
```

### 语音悬浮窗(向任意应用听写)

**语音悬浮窗**在其他应用之上放置一个可拖动的麦克风按钮。点击后变为
**■ 停止** / **✕ 取消**:停止会把转录文本输入到当前获得焦点的文本框,
取消则丢弃。若没有可编辑字段获得焦点,文本会写入剪贴板而不会丢失。

需要三项授权,各有独立的系统页面 — 设置界面会显示仍缺少哪些:

| 权限 | 用途 |
| --- | --- |
| 麦克风 | 采集音频 |
| 显示在其他应用上层 | 在应用之外绘制按钮 |
| 无障碍服务 | 向其他应用的文本框输入 |

悬浮窗窗口特意设为不可获得焦点,以便点击按钮时目标文本框保持输入焦点。
文本通过 `ACTION_SET_TEXT` 插入到光标处。对于无法读取真实内容的字段
(某些应用会把占位符当作字段自身的文本上报),则改用粘贴写入,
这会覆盖剪贴板上原有的内容;粘贴后会立即清除其中的听写文本。

> 通过 APK 而非 Play 商店安装?Android 会锁定无障碍开关,
> 需先在 设置 → 应用 → Speech → ⋮ → **允许受限设置** 中解除。

### 完整管线控制演示

独立的 [`control-demo/`](control-demo/) 应用在本地运行完整智能体:
Silero VAD → Parakeet-EOU STT → FunctionGemma 270M 工具调用 →
Android 设备操作 → Pocket TTS。它显示各阶段延迟,并直接链接此检出的
`:sdk`,因此会使用本地语音优化。

从最新版本下载[已签名的 Control Demo APK](https://github.com/soniqo/speech-android/releases/latest/download/control-demo-release.apk),或从源码安装开发版本:

```bash
./gradlew :control-demo:installDebug
```

## 系统语音输入(`RecognitionService`)

SDK 自带可直接使用的 `audio.soniqo.speech.service.SpeechRecognitionService`,接入 Android 框架的 `SpeechRecognizer` API — 无需编写代码。一旦你的应用被设为默认语音识别器,任何调用 `SpeechRecognizer.createSpeechRecognizer(context)`(不指定 `ComponentName`)的第三方应用都能通过你的流水线获得完全本地的 STT。

**1. 在 `AndroidManifest.xml` 中声明 `RECORD_AUDIO` 和服务:**

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

**2. 添加 `app/src/main/res/xml/recognition_service.xml`:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<recognition-service xmlns:android="http://schemas.android.com/apk/res/android" />
```

(可选添加 `android:settingsActivity="..."` 以在系统语音输入选择器中显示设置图标。)

**3. 将服务设为系统默认**(原生 Android 上 设置 → 系统 → 语言和输入 → 语音输入选择器,或通过 adb):

```bash
adb shell settings put secure voice_recognition_service \
  your.package/audio.soniqo.speech.service.SpeechRecognitionService
```

**4. 验证**:运行演示应用的*识别器测试*界面,它调用 `SpeechRecognizer.createSpeechRecognizer(ctx)`(不带组件)并记录每个框架回调 — 无需 logcat 即可确认 binder 往返。

服务实现了 `onCheckRecognitionSupport`(API 33+),返回 Parakeet-EOU 涵盖的 25 个 BCP-47 基础语言;如果请求的精确地区标签映射到受支持的基础语言,也会返回该标签。模型存在时语言会标记为 `installedOnDeviceLanguage`,下载前标记为 `supportedOnDeviceLanguage`。服务不会从调用应用抢占音频焦点。

**注意:** Gboard、三星键盘和 Google Assistant 都自带识别器,会跳过系统默认。显式调用框架 `SpeechRecognizer` API(或在其上构建自己 UI)的应用才会经过你的服务。

## 系统文字转语音(`TextToSpeechService`)

演示应用还暴露 `audio.soniqo.speech.service.SpeechTextToSpeechService`, 因此 Android 可以在 设置 → 系统 → 语言和输入 → 文字转语音输出 中选择该应用。此路径使用 `ModelManager.ensureTtsModels()` 和单独的 `models_tts/` 缓存, 所以框架 TTS 只下载 Kokoro 资源, 而不会拉取完整的 VAD/STT/enhancer 管线包。

要在其他应用中暴露该引擎, 请声明服务:

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

添加 `app/src/main/res/xml/tts_engine.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<tts-engine xmlns:android="http://schemas.android.com/apk/res/android" />
```

## 性能

在 Galaxy S23 Ultra（SM-S918B）上测量，仅 CPU。RTF 为墙钟时间÷生成音频时长；数值越低越快，<1.0 表示快于实时。

| 模型 | 任务 | RTF | 延迟 | 峰值内存 |
| --- | --- | --- | --- | --- |
| Parakeet-EOU 120M ONNX INT8 | 流式 STT + EOU | 0.21 | 流式 partials | 232 MB |
| Kokoro 82M 完整图（公开版，CPU 双线程） | TTS | 1.81 | 句级 | ~604 MB |
| Kokoro 82M 短回合（3.0 秒图，默认） | TTS | 0.75–0.88 | 受限回复；安全重试 | ~527 MB |
| Supertonic-3 LiteRT | TTS | 0.34 | ~1.1 秒 TTFA | 832 MB |
| Silero VAD v5 | VAD | <0.01 | 每 32 毫秒块 <1 毫秒 | <10 MB |

## 管线

```text
Idle → Listening → Transcribing → Speaking → Idle
              ↑                         |
              └─── resumeListening() ───┘
```

支持打断:在 TTS 播放期间说话会中断并开始新的转录。

## 架构

```text
┌──────────────────────────────────────────────┐
│      SpeechPipeline (Kotlin)                 │
│            │                                 │
│            ▼                                 │
│      jni_bridge.cpp  (~250 行)               │
│            │                                 │
│            ▼                                 │
│  ┌──────────────────────────────────────┐    │
│  │  speech_core_models(git 子模块)      │    │
│  │   SileroVad / ParakeetStt /          │    │
│  │   KokoroTts / OnnxPocketTts /        │    │
│  │   DeepFilterEnhancer                  │    │
│  │            │                         │    │
│  │            ▼                         │    │
│  │  speech_core(编排:                  │    │
│  │   管线 · 轮次 · 打断)               │    │
│  └──────────────────────────────────────┘    │
│            │                                 │
│            ▼                                 │
│      ONNX Runtime (CPU / NNAPI)              │
└──────────────────────────────────────────────┘
```

每个模型类直接实现对应的 speech-core 接口(`VADInterface`、`STTInterface`、`TTSInterface`、`EnhancerInterface`)—— JNI 桥接实例化它们并将引用交给 `VoicePipeline`。无需 C-vtable 适配器样板代码。

## 硬件加速

| 芯片组 | 加速 |
| --- | --- |
| Snapdragon 8 Gen 1+ | NNAPI → Hexagon NPU |
| Samsung Exynos 2200+ | NNAPI → Samsung NPU |
| Google Tensor G2+ | NNAPI → Google TPU |
| CPU 回退 | XNNPACK |

汽车 Qualcomm SA8295P / SA8255P 搭配 QNN(Hexagon DSP)的方案,请参见 [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux)。

## 相关项目

| 仓库 | 范围 |
| --- | --- |
| [speech-swift](https://github.com/soniqo/speech-swift) | Apple(macOS、iOS)— MLX + CoreML |
| [speech-core](https://github.com/soniqo/speech-core) | 跨平台 C++ 管线引擎 + ONNX 模型封装 + Linux/嵌入式示例 |
| **speech-android** | Android 封装 — 基于 speech-core 的 Kotlin SDK + JNI 桥接 |

## 许可证

Apache 2.0
