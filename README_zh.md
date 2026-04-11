# Speech Android

📖 阅读语言: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

适用于 Android 和嵌入式 Linux 的设备端语音 SDK,基于 [ONNX Runtime](https://onnxruntime.ai) 和 [speech-core](https://github.com/soniqo/speech-core) 构建。

语音识别(114 种语言)、文本转语音(8 种语言)、语音活动检测和噪声消除——全部在本地运行。无需云端 API,数据不会离开设备。

**[演示 APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[模型](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)**(Apple 对应版本)· **[speech-core](https://github.com/soniqo/speech-core)**(管线引擎)

## 平台

| 平台 | API | 加速 | 目录 |
| --- | --- | --- | --- |
| Android | Kotlin (`SpeechPipeline`) | NNAPI(Snapdragon、Exynos、Tensor) | `sdk/` |
| 嵌入式 Linux | C (`speech.h`) | QNN(Hexagon DSP) | `linux/` |

## 模型

| 模型 | 任务 | INT8 大小 | 语言 |
| --- | --- | --- | --- |
| [Parakeet TDT v3](https://huggingface.co/aufklarer/Parakeet-TDT-v3-ONNX) | 语音识别 | 891 MB | 114 |
| [Kokoro 82M](https://huggingface.co/aufklarer/Kokoro-82M-ONNX) | 文本转语音 | 330 MB | 8(en、fr、es、it、pt、hi、ja、zh) |
| [Silero VAD v5](https://huggingface.co/aufklarer/Silero-VAD-v5-ONNX) | 语音活动检测 | 2 MB | 任意 |
| [DeepFilterNet3](https://huggingface.co/aufklarer/DeepFilterNet3-ONNX) | 噪声消除 | ~8 MB | 任意 |

模型在首次启动时自动下载(Android)或手动放置(Linux)。

## Android

### 试用演示

下载[已签名的 APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) 并安装到任何 arm64 Android 设备(8 及以上)。模型(~1.2 GB)在首次启动时自动下载。

### 添加依赖

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.8")
}
```

### Kotlin 用法

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

### 从源代码构建

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 34 个端到端测试
```

### 演示应用

[`app/`](app/) 模块是一个最小化的语音助手演示,包含:

- 实时 VAD 波形可视化
- 回声模式:转录语音并将其合成回放(无 LLM)
- 带有 STT/TTS 延迟显示的聊天气泡 UI

```bash
./gradlew :app:installDebug
```

## 性能

在 Android 模拟器(arm64-v8a,无 NNAPI)上测量。真实硬件速度显著更快。

| 模型 | 任务 | 音频 | 推理 | RTF |
| --- | --- | --- | --- | --- |
| Parakeet TDT v3 | STT | 1.5 秒 | 175 毫秒 | 0.12 |
| Kokoro 82M | TTS | 1.9 秒输出 | 1,075 毫秒 | 0.58 |
| Silero VAD v5 | VAD | 32 毫秒块 | <1 毫秒 | <0.01 |

## 嵌入式 Linux

适用于汽车和嵌入式平台的最小化 C API。完整文档参见 [`linux/README.md`](linux/README.md)。

### C API 用法

```c
#include <speech.h>

void on_event(const speech_event_t* event, void* ctx) {
    if (event->type == SPEECH_EVENT_TRANSCRIPTION)
        printf("%s\n", event->text);
}

speech_config_t cfg = speech_config_default();
cfg.model_dir = "/opt/speech/models";
cfg.use_qnn = true;  // Hexagon DSP 加速

speech_pipeline_t p = speech_create(cfg, on_event, NULL);
speech_start(p);
speech_push_audio(p, pcm_samples, 512);
```

### 构建

```bash
cd linux && ./setup_linux.sh
cmake -B build -DORT_DIR=../ort-linux
cmake --build build
./build/speech_demo --model-dir /path/to/models
```

### 测试

```bash
linux/tests/download_models.sh              # 下载 ONNX 模型
SPEECH_MODEL_DIR=tests/models ./build/speech_test   # 12 个测试
```

### 为 Yocto 交叉编译

```bash
source /opt/poky/environment-setup-aarch64-poky-linux
cmake -B build -DCMAKE_TOOLCHAIN_FILE=toolchain-aarch64.cmake -DORT_DIR=...
cmake --build build
```

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

## 硬件加速

| 平台 | 芯片组 | 加速 |
| --- | --- | --- |
| Android | Snapdragon 8 Gen 1+ | NNAPI → Hexagon NPU |
| Android | Samsung Exynos 2200+ | NNAPI → Samsung NPU |
| Android | Google Tensor G2+ | NNAPI → Google TPU |
| 汽车 | SA8295P / SA8255P | QNN → Hexagon DSP |
| 任意 | CPU 回退 | XNNPACK |

## 相关项目

| 仓库 | 平台 |
| --- | --- |
| [speech-swift](https://github.com/soniqo/speech-swift) | Apple(macOS、iOS)— MLX + CoreML |
| [speech-core](https://github.com/soniqo/speech-core) | 跨平台 C++ 管线引擎 |
| **speech-android** | Android + 嵌入式 Linux — ONNX Runtime |

## 许可证

Apache 2.0
