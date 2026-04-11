# Speech Android

📖 言語: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

[ONNX Runtime](https://onnxruntime.ai) と [speech-core](https://github.com/soniqo/speech-core) を活用した、Android および組み込み Linux 向けのオンデバイス音声 SDK。

音声認識(114 言語)、テキスト読み上げ(8 言語)、音声活動検出、ノイズキャンセリング — すべてローカルで動作。クラウド API 不要、データはデバイスから外に出ません。

**[デモ APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[モデル](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)**(Apple 版)· **[speech-core](https://github.com/soniqo/speech-core)**(パイプラインエンジン)

## プラットフォーム

| プラットフォーム | API | アクセラレーション | ディレクトリ |
| --- | --- | --- | --- |
| Android | Kotlin (`SpeechPipeline`) | NNAPI(Snapdragon、Exynos、Tensor) | `sdk/` |
| 組み込み Linux | C (`speech.h`) | QNN(Hexagon DSP) | `linux/` |

## モデル

| モデル | タスク | INT8 サイズ | 言語 |
| --- | --- | --- | --- |
| [Parakeet TDT v3](https://huggingface.co/aufklarer/Parakeet-TDT-v3-ONNX) | 音声認識 | 891 MB | 114 |
| [Kokoro 82M](https://huggingface.co/aufklarer/Kokoro-82M-ONNX) | テキスト読み上げ | 330 MB | 8(en、fr、es、it、pt、hi、ja、zh) |
| [Silero VAD v5](https://huggingface.co/aufklarer/Silero-VAD-v5-ONNX) | 音声活動検出 | 2 MB | 任意 |
| [DeepFilterNet3](https://huggingface.co/aufklarer/DeepFilterNet3-ONNX) | ノイズキャンセリング | ~8 MB | 任意 |

モデルは初回起動時に自動ダウンロード(Android)または手動配置(Linux)されます。

## Android

### デモを試す

[署名済み APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) をダウンロードし、任意の arm64 Android デバイス(8 以降)にインストールします。モデル(~1.2 GB)は初回起動時に自動ダウンロードされます。

### 依存関係を追加

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.8")
}
```

### Kotlin の使い方

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

// マイクから 16kHz モノラル float32 PCM を入力
pipeline.pushAudio(samples)
```

### ソースからビルド

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 34 個の e2e テスト
```

### デモアプリ

[`app/`](app/) モジュールは最小限の音声アシスタントデモで、以下を含みます:

- リアルタイム VAD 波形の可視化
- エコーモード:音声を文字起こしして合成し直す(LLM なし)
- STT/TTS のレイテンシ表示付きチャットバブル UI

```bash
./gradlew :app:installDebug
```

## パフォーマンス

Android エミュレータ(arm64-v8a、NNAPI なし)で測定。実機ははるかに高速です。

| モデル | タスク | 音声 | 推論 | RTF |
| --- | --- | --- | --- | --- |
| Parakeet TDT v3 | STT | 1.5 秒 | 175 ミリ秒 | 0.12 |
| Kokoro 82M | TTS | 1.9 秒出力 | 1,075 ミリ秒 | 0.58 |
| Silero VAD v5 | VAD | 32 ミリ秒チャンク | <1 ミリ秒 | <0.01 |

## 組み込み Linux

自動車および組み込みプラットフォーム向けの最小限の C API。詳細は [`linux/README.md`](linux/README.md) を参照してください。

### C API の使い方

```c
#include <speech.h>

void on_event(const speech_event_t* event, void* ctx) {
    if (event->type == SPEECH_EVENT_TRANSCRIPTION)
        printf("%s\n", event->text);
}

speech_config_t cfg = speech_config_default();
cfg.model_dir = "/opt/speech/models";
cfg.use_qnn = true;  // Hexagon DSP アクセラレーション

speech_pipeline_t p = speech_create(cfg, on_event, NULL);
speech_start(p);
speech_push_audio(p, pcm_samples, 512);
```

### ビルド

```bash
cd linux && ./setup_linux.sh
cmake -B build -DORT_DIR=../ort-linux
cmake --build build
./build/speech_demo --model-dir /path/to/models
```

### テスト

```bash
linux/tests/download_models.sh              # ONNX モデルをダウンロード
SPEECH_MODEL_DIR=tests/models ./build/speech_test   # 12 個のテスト
```

### Yocto 向けクロスコンパイル

```bash
source /opt/poky/environment-setup-aarch64-poky-linux
cmake -B build -DCMAKE_TOOLCHAIN_FILE=toolchain-aarch64.cmake -DORT_DIR=...
cmake --build build
```

## パイプライン

```text
Idle → Listening → Transcribing → Speaking → Idle
              ↑                         |
              └─── resumeListening() ───┘
```

割り込み対応:TTS 再生中の発話は再生を中断して新しい文字起こしを開始します。

## アーキテクチャ

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

## ハードウェアアクセラレーション

| プラットフォーム | チップセット | アクセラレーション |
| --- | --- | --- |
| Android | Snapdragon 8 Gen 1+ | NNAPI → Hexagon NPU |
| Android | Samsung Exynos 2200+ | NNAPI → Samsung NPU |
| Android | Google Tensor G2+ | NNAPI → Google TPU |
| 自動車 | SA8295P / SA8255P | QNN → Hexagon DSP |
| 任意 | CPU フォールバック | XNNPACK |

## 関連プロジェクト

| リポジトリ | プラットフォーム |
| --- | --- |
| [speech-swift](https://github.com/soniqo/speech-swift) | Apple(macOS、iOS)— MLX + CoreML |
| [speech-core](https://github.com/soniqo/speech-core) | クロスプラットフォーム C++ パイプラインエンジン |
| **speech-android** | Android + 組み込み Linux — ONNX Runtime |

## ライセンス

Apache 2.0
