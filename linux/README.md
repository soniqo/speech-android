# speech-linux

On-device speech SDK for embedded Linux — VAD, STT (multilingual), TTS, noise cancellation.

Targets automotive (Qualcomm SA8295P, SA8255P) and embedded ARM64 platforms running Yocto or similar Linux distributions.

## Quick Start

```bash
# Download ONNX Runtime
./setup_linux.sh

# Build
cmake -B build -DORT_DIR=../ort-linux
cmake --build build

# Run tests
cd build && ctest

# Run demo (ALSA mic)
./speech_demo --model-dir /path/to/models

# Run demo (stdin PCM pipe)
arecord -f FLOAT_LE -r 16000 -c 1 | ./speech_demo --model-dir /path/to/models
```

## C API

```c
#include <speech.h>

void on_event(const speech_event_t* event, void* ctx) {
    if (event->type == SPEECH_EVENT_TRANSCRIPTION)
        printf("STT: %s\n", event->text);
}

int main() {
    speech_config_t cfg = speech_config_default();
    cfg.model_dir = "/opt/speech/models";

    speech_pipeline_t p = speech_create(cfg, on_event, NULL);
    speech_start(p);

    // Feed 16kHz mono float32 PCM from your audio source
    while (has_audio()) {
        float buf[512];
        read_audio(buf, 512);
        speech_push_audio(p, buf, 512);
    }

    speech_destroy(p);
}
```

### Functions

| Function | Description |
|---|---|
| `speech_config_default()` | Default config (INT8, CPU, 400ms silence threshold) |
| `speech_create(config, callback, ctx)` | Load models, create pipeline. Returns `NULL` on failure |
| `speech_start(pipeline)` | Start processing audio |
| `speech_push_audio(pipeline, samples, count)` | Feed PCM float32 at 16 kHz |
| `speech_resume_listening(pipeline)` | Resume after TTS playback |
| `speech_destroy(pipeline)` | Free all resources |
| `speech_version()` | Version string |

### Events

| Event | Fields | Description |
|---|---|---|
| `SPEECH_EVENT_READY` | — | Pipeline initialized |
| `SPEECH_EVENT_SPEECH_STARTED` | — | VAD detected speech |
| `SPEECH_EVENT_SPEECH_ENDED` | — | VAD detected silence |
| `SPEECH_EVENT_TRANSCRIPTION` | `text`, `confidence`, `stt_duration_ms` | Final transcription |
| `SPEECH_EVENT_RESPONSE_AUDIO` | `audio_data`, `audio_data_length` | TTS PCM16 audio chunk (24 kHz) |
| `SPEECH_EVENT_RESPONSE_DONE` | `tts_duration_ms` | TTS complete |
| `SPEECH_EVENT_ERROR` | `text` | Error message |

### Configuration

```c
speech_config_t cfg = speech_config_default();
cfg.model_dir = "/opt/speech/models";  // required
cfg.use_int8 = true;                   // INT8 quantized models (default)
cfg.use_qnn = true;                    // Qualcomm QNN EP (Hexagon DSP)
cfg.enable_enhancer = true;            // DeepFilterNet noise cancellation
cfg.transcribe_only = true;            // STT only, no TTS echo
cfg.min_silence_duration = 0.4f;       // seconds before end-of-speech
```

## Models

Download from HuggingFace (`aufklarer/` org) into a single directory:

```
models/
  silero-vad.onnx                    2 MB   Voice activity detection
  parakeet-encoder-int8.onnx       840 MB   STT encoder (multilingual, 114 languages)
  parakeet-decoder-joint-int8.onnx  51 MB   STT decoder
  vocab.json                       156 KB   BPE vocabulary (8192 tokens)
  kokoro-int8.onnx                 330 MB   TTS (English)
  vocab_index.json                   2 KB   TTS phonemizer vocab
  us_gold.json                       2 B    TTS phonemizer dict
  us_silver.json                     2 B    TTS phonemizer dict
  voices/af_heart.bin                1 KB   Voice embedding
```

## Cross-Compilation (Yocto)

```bash
# Source Yocto SDK environment
source /opt/poky/environment-setup-aarch64-poky-linux

# Build with cross-toolchain
cmake -B build \
    -DCMAKE_TOOLCHAIN_FILE=toolchain-aarch64.cmake \
    -DORT_DIR=/path/to/ort-linux-aarch64

cmake --build build
```

## QNN (Qualcomm Hexagon DSP)

For hardware acceleration on SA8295P / SA8255P:

1. Build ONNX Runtime with QNN EP or use Qualcomm's prebuilt
2. Place `libQnnHtp.so` in the library path
3. Set `cfg.use_qnn = true`

The pipeline falls back to CPU if QNN is unavailable.

## Architecture

```
libspeech.so
  ├── speech.h (C API)
  ├── speech-core (pipeline orchestration)
  ├── Silero VAD v5 (voice activity detection)
  ├── Parakeet TDT v3 (multilingual STT, 114 languages)
  ├── Kokoro 82M (TTS)
  ├── DeepFilterNet3 (noise cancellation)
  └── ONNX Runtime (CPU / QNN EP)
```

All inference runs on-device. No network required after model download.

## Thread Safety

- `speech_push_audio()` is thread-safe (single producer)
- Event callback fires from an internal worker thread
- Do not call `speech_destroy()` from the event callback
