# Speech Android

📖 阅读语言: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

适用于 Android 的设备端语音 SDK,基于 [ONNX Runtime](https://onnxruntime.ai) 和 [speech-core](https://github.com/soniqo/speech-core) 构建。

语音识别(114 种语言)、文本转语音(8 种语言)、语音活动检测和噪声消除——全部在本地运行。无需云端 API,数据不会离开设备。

**[演示 APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[模型](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)**(Apple 对应版本)· **[speech-core](https://github.com/soniqo/speech-core)**(管线引擎 + Linux/嵌入式构建)

## 范围

本仓库是 **Android 打包**:Kotlin SDK、JNI 桥接、演示应用。C++ 引擎和 ONNX 模型封装(Silero VAD、Parakeet STT、Kokoro TTS、DeepFilterNet3)位于 [speech-core](https://github.com/soniqo/speech-core),通过 git 子模块引入。Linux / 汽车(Yocto、Qualcomm SA8295P/SA8255P)位于 [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux)。

## 模型

| 模型 | 任务 | INT8 大小 | 语言 |
| --- | --- | --- | --- |
| [Parakeet TDT v3](https://huggingface.co/soniqo/Parakeet-TDT-v3-ONNX) | 语音识别 | 891 MB | 114 |
| [Kokoro 82M](https://huggingface.co/soniqo/Kokoro-82M-ONNX) | 文本转语音 | 330 MB | 8(en、fr、es、it、pt、hi、ja、zh) |
| [Silero VAD v5](https://huggingface.co/soniqo/Silero-VAD-v5-ONNX) | 语音活动检测 | 2 MB | 任意 |
| [DeepFilterNet3](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | 噪声消除 | ~8 MB | 任意 |

模型在首次启动时通过 `ModelManager.ensureModels()` 自动下载。

## 试用演示

下载[已签名的 APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) 并安装到任何 arm64 Android 设备(8 及以上)。模型(~1.2 GB)在首次启动时自动下载。

## 添加依赖

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.9")
}
```

## Kotlin 用法

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

// 从麦克风输入 16kHz 单声道 float32 PCM
pipeline.pushAudio(samples)
```

## 从源代码构建

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 34 个端到端测试
```

`./setup.sh` 会初始化 speech-core 子模块并将 ONNX Runtime 下载到 `./ort/`。

## 演示应用

[`app/`](app/) 模块是一个最小化的语音助手演示,包含:

- 实时 VAD 波形可视化
- 回声模式:转录语音并将其合成回放(无 LLM)
- 听写模式:流式部分结果
- `SpeechRecognizer` 测试界面 — 演练系统级语音输入路径
- 带有 STT/TTS 延迟显示的聊天气泡 UI

```bash
./gradlew :app:installDebug
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

服务实现了 `onCheckRecognitionSupport`(API 33+),返回 Parakeet TDT v3 涵盖的 27 个 BCP-47 语言,在模型存在时标记为 `installedOnDeviceLanguage`(下载中时为 `pendingOnDeviceLanguage`)。会话期间使用 `AUDIOFOCUS_GAIN_TRANSIENT` 获取音频焦点。

**注意:** Gboard、三星键盘和 Google Assistant 都自带识别器,会跳过系统默认。显式调用框架 `SpeechRecognizer` API(或在其上构建自己 UI)的应用才会经过你的服务。

## 性能

在 Android 模拟器(arm64-v8a,无 NNAPI)上测量。真实硬件速度显著更快。

| 模型 | 任务 | 音频 | 推理 | RTF |
| --- | --- | --- | --- | --- |
| Parakeet TDT v3 | STT | 1.5 秒 | 175 毫秒 | 0.12 |
| Kokoro 82M | TTS | 1.9 秒输出 | 1,075 毫秒 | 0.58 |
| Silero VAD v5 | VAD | 32 毫秒块 | <1 毫秒 | <0.01 |

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
│  │   KokoroTts / DeepFilterEnhancer     │    │
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
