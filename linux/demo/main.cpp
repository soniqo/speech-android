#include "speech.h"

#include <cstdio>
#include <cstdlib>
#include <csignal>
#include <cstring>
#include <unistd.h>

#ifdef HAS_ALSA
#include <alsa/asoundlib.h>
#endif

static volatile bool running = true;

static void signal_handler(int) { running = false; }

static void on_event(const speech_event_t* event, void* /*ctx*/) {
    switch (event->type) {
        case SPEECH_EVENT_SPEECH_STARTED:
            fprintf(stderr, "[VAD] speech started\n");
            break;
        case SPEECH_EVENT_SPEECH_ENDED:
            fprintf(stderr, "[VAD] speech ended\n");
            break;
        case SPEECH_EVENT_TRANSCRIPTION:
            printf("[STT] %s (%.0fms, conf=%.2f)\n",
                   event->text ? event->text : "",
                   event->stt_duration_ms, event->confidence);
            fflush(stdout);
            break;
        case SPEECH_EVENT_RESPONSE_DONE:
            fprintf(stderr, "[TTS] done (%.0fms)\n", event->tts_duration_ms);
            break;
        case SPEECH_EVENT_ERROR:
            fprintf(stderr, "[ERROR] %s\n", event->text ? event->text : "unknown");
            break;
        default:
            break;
    }
}

static void print_usage(const char* prog) {
    fprintf(stderr, "Usage: %s --model-dir <path> [--qnn] [--transcribe-only] [--device <alsa_dev>]\n", prog);
}

int main(int argc, char* argv[]) {
    const char* model_dir = nullptr;
    const char* alsa_device = "default";
    bool use_qnn = false;
    bool transcribe_only = false;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--model-dir") == 0 && i + 1 < argc) {
            model_dir = argv[++i];
        } else if (strcmp(argv[i], "--qnn") == 0) {
            use_qnn = true;
        } else if (strcmp(argv[i], "--transcribe-only") == 0) {
            transcribe_only = true;
        } else if (strcmp(argv[i], "--device") == 0 && i + 1 < argc) {
            alsa_device = argv[++i];
        } else {
            print_usage(argv[0]);
            return 1;
        }
    }

    if (!model_dir) {
        print_usage(argv[0]);
        return 1;
    }

    fprintf(stderr, "speech-linux %s\n", speech_version());
    fprintf(stderr, "Models: %s\n", model_dir);
    fprintf(stderr, "QNN: %s\n", use_qnn ? "yes" : "no");

    speech_config_t config = speech_config_default();
    config.model_dir = model_dir;
    config.use_qnn = use_qnn;
    config.transcribe_only = transcribe_only;

    fprintf(stderr, "Loading models...\n");
    speech_pipeline_t pipeline = speech_create(config, on_event, nullptr);
    if (!pipeline) {
        fprintf(stderr, "Failed to create pipeline\n");
        return 1;
    }

    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);

    speech_start(pipeline);
    fprintf(stderr, "Listening... (Ctrl+C to stop)\n");

#ifdef HAS_ALSA
    snd_pcm_t* capture = nullptr;
    int err = snd_pcm_open(&capture, alsa_device, SND_PCM_STREAM_CAPTURE, 0);
    if (err < 0) {
        fprintf(stderr, "ALSA open failed: %s\n", snd_strerror(err));
        speech_destroy(pipeline);
        return 1;
    }

    snd_pcm_set_params(capture, SND_PCM_FORMAT_FLOAT_LE, SND_PCM_ACCESS_RW_INTERLEAVED,
                        1, 16000, 1, 100000);

    float buffer[512];
    while (running) {
        snd_pcm_sframes_t frames = snd_pcm_readi(capture, buffer, 512);
        if (frames < 0) {
            frames = snd_pcm_recover(capture, (int)frames, 0);
            if (frames < 0) break;
        }
        if (frames > 0) {
            speech_push_audio(pipeline, buffer, (size_t)frames);
        }
    }

    snd_pcm_close(capture);
#else
    // No ALSA: read raw float32 PCM from stdin
    fprintf(stderr, "No ALSA — reading float32 PCM from stdin (16kHz mono)\n");
    float buffer[512];
    while (running) {
        size_t n = fread(buffer, sizeof(float), 512, stdin);
        if (n == 0) break;
        speech_push_audio(pipeline, buffer, n);
        // Simulate real-time pace
        usleep((unsigned int)(n * 1000000 / 16000));
    }
#endif

    fprintf(stderr, "\nShutting down...\n");
    speech_destroy(pipeline);
    return 0;
}
