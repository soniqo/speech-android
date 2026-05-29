# Speech Android

📖 言語: [English](README.md) · [中文](README_zh.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [हिन्दी](README_hi.md) · [Português](README_pt.md) · [Русский](README_ru.md)

[ONNX Runtime](https://onnxruntime.ai) と [speech-core](https://github.com/soniqo/speech-core) を活用した、Android 向けオンデバイス音声 SDK。

音声認識(114 言語)、テキスト読み上げ(8 言語)、音声活動検出、ノイズキャンセリング — すべてローカルで動作。クラウド API 不要、データはデバイスから外に出ません。

**[デモ APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk)** · **[モデル](https://huggingface.co/collections/aufklarer/speech-android-models-69bb8a156cac0b96a2247f26)** · **[speech-swift](https://github.com/soniqo/speech-swift)**(Apple 版)· **[speech-core](https://github.com/soniqo/speech-core)**(パイプラインエンジン + Linux/組み込みビルド)

## スコープ

このリポジトリは **Android パッケージング** を担当します:Kotlin SDK、JNI ブリッジ、デモアプリ。C++ エンジンおよび ONNX モデルラッパー(Silero VAD、Parakeet STT、Kokoro TTS、DeepFilterNet3)は [speech-core](https://github.com/soniqo/speech-core) に存在し、git サブモジュールとして取り込まれます。Linux / 自動車向け(Yocto、Qualcomm SA8295P/SA8255P)は [speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux) に存在します。

## モデル

| モデル | タスク | INT8 サイズ | 言語 |
| --- | --- | --- | --- |
| [Parakeet TDT v3](https://huggingface.co/soniqo/Parakeet-TDT-v3-ONNX) | 音声認識 | 891 MB | 114 |
| [Kokoro 82M](https://huggingface.co/soniqo/Kokoro-82M-ONNX) | テキスト読み上げ | 330 MB | 8(en、fr、es、it、pt、hi、ja、zh) |
| [Silero VAD v5](https://huggingface.co/soniqo/Silero-VAD-v5-ONNX) | 音声活動検出 | 2 MB | 任意 |
| [DeepFilterNet3](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | ノイズキャンセリング | ~8 MB | 任意 |

モデルは初回起動時に `ModelManager.ensureModels()` 経由で自動ダウンロードされます。

## デモを試す

[署名済み APK](https://github.com/soniqo/speech-android/releases/latest/download/app-release.apk) をダウンロードし、任意の arm64 Android デバイス(8 以降)にインストールします。モデル(~1.2 GB)は初回起動時に自動ダウンロードされます。

## 依存関係を追加

```kotlin
dependencies {
    implementation("audio.soniqo:speech:0.0.9")
}
```

## Kotlin の使い方

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

## ソースからビルド

```bash
git clone --recursive https://github.com/soniqo/speech-android.git
cd speech-android
./setup.sh
./gradlew :app:assembleDebug
./gradlew :sdk:connectedAndroidTest   # 34 個の e2e テスト
```

`./setup.sh` は speech-core サブモジュールを初期化し、ONNX Runtime を
`./ort/` にダウンロードします。

## デモアプリ

[`app/`](app/) モジュールは最小限の音声アシスタントデモで、以下を含みます:

- リアルタイム VAD 波形の可視化
- エコーモード:音声を文字起こしして合成し直す(LLM なし)
- ディクテーションモード:ストリーミング部分結果
- `SpeechRecognizer` テスト画面 — システム全体の音声入力パスを実行
- STT/TTS のレイテンシ表示付きチャットバブル UI

```bash
./gradlew :app:installDebug
```

## システム音声入力(`RecognitionService`)

SDK には、Android フレームワークの `SpeechRecognizer` API に組み込めるすぐに使える `audio.soniqo.speech.service.SpeechRecognitionService` が含まれています — コードを書く必要はありません。アプリがデフォルトの音声認識サービスに選択されると、`SpeechRecognizer.createSpeechRecognizer(context)`(`ComponentName` なし)を呼び出す任意のサードパーティアプリが、あなたのパイプラインを通じて完全なオンデバイス STT を利用できます。

**1. `AndroidManifest.xml` で `RECORD_AUDIO` とサービスを宣言します:**

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

**2. `app/src/main/res/xml/recognition_service.xml` を追加します:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<recognition-service xmlns:android="http://schemas.android.com/apk/res/android" />
```

(オプションで `android:settingsActivity="..."` を追加すると、システム音声入力ピッカーに歯車アイコンが表示されます。)

**3. サービスをシステムデフォルトに設定します**(標準 Android では 設定 → システム → 言語と入力 → 音声入力ピッカー、または adb 経由):

```bash
adb shell settings put secure voice_recognition_service \
  your.package/audio.soniqo.speech.service.SpeechRecognitionService
```

**4. 確認**:デモアプリの *Recognizer test* 画面を実行します。これは `SpeechRecognizer.createSpeechRecognizer(ctx)`(コンポーネントなし)を呼び出し、すべてのフレームワークコールバックをログに記録します — logcat なしで binder のラウンドトリップを確認するのに便利です。

サービスは `onCheckRecognitionSupport`(API 33+)を実装し、Parakeet TDT v3 がカバーする 27 の BCP-47 言語を返します。モデルが存在する場合は `installedOnDeviceLanguage`、ダウンロード中は `pendingOnDeviceLanguage` でマークされます。セッション中は `AUDIOFOCUS_GAIN_TRANSIENT` でオーディオフォーカスを取得します。

**注意:** Gboard、Samsung Keyboard、Google Assistant は独自の認識エンジンを同梱しており、システムデフォルトをスキップします。あなたのサービスを通過するのは、フレームワーク `SpeechRecognizer` API を明示的に呼び出すアプリ(またはその上に独自 UI を構築するアプリ)です。

## パフォーマンス

Android エミュレータ(arm64-v8a、NNAPI なし)で測定。実機ははるかに高速です。

| モデル | タスク | 音声 | 推論 | RTF |
| --- | --- | --- | --- | --- |
| Parakeet TDT v3 | STT | 1.5 秒 | 175 ミリ秒 | 0.12 |
| Kokoro 82M | TTS | 1.9 秒出力 | 1,075 ミリ秒 | 0.58 |
| Silero VAD v5 | VAD | 32 ミリ秒チャンク | <1 ミリ秒 | <0.01 |

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
│      SpeechPipeline (Kotlin)                 │
│            │                                 │
│            ▼                                 │
│      jni_bridge.cpp  (~250 行)               │
│            │                                 │
│            ▼                                 │
│  ┌──────────────────────────────────────┐    │
│  │  speech_core_models (git サブモジュール) │    │
│  │   SileroVad / ParakeetStt /          │    │
│  │   KokoroTts / DeepFilterEnhancer     │    │
│  │            │                         │    │
│  │            ▼                         │    │
│  │  speech_core  (オーケストレーション:    │    │
│  │   パイプライン · ターン · 割り込み)     │    │
│  └──────────────────────────────────────┘    │
│            │                                 │
│            ▼                                 │
│      ONNX Runtime (CPU / NNAPI)              │
└──────────────────────────────────────────────┘
```

各モデルクラスは対応する speech-core インターフェース
(`VADInterface`、`STTInterface`、`TTSInterface`、`EnhancerInterface`)を
直接実装します — JNI ブリッジがそれらをインスタンス化し、参照を
`VoicePipeline` に渡します。C vtable アダプタの定型コードは不要です。

## ハードウェアアクセラレーション

| チップセット | アクセラレーション |
| --- | --- |
| Snapdragon 8 Gen 1+ | NNAPI → Hexagon NPU |
| Samsung Exynos 2200+ | NNAPI → Samsung NPU |
| Google Tensor G2+ | NNAPI → Google TPU |
| CPU フォールバック | XNNPACK |

自動車向け Qualcomm SA8295P / SA8255P と QNN(Hexagon DSP)については、
[speech-core/examples/linux](https://github.com/soniqo/speech-core/tree/main/examples/linux) を参照してください。

## 関連プロジェクト

| リポジトリ | スコープ |
| --- | --- |
| [speech-swift](https://github.com/soniqo/speech-swift) | Apple(macOS、iOS)— MLX + CoreML |
| [speech-core](https://github.com/soniqo/speech-core) | クロスプラットフォーム C++ パイプラインエンジン + ONNX モデルラッパー + Linux/組み込み例 |
| **speech-android** | Android ラッパー — speech-core 上の Kotlin SDK + JNI ブリッジ |

## ライセンス

Apache 2.0
