#include "speech.h"

#include <string>
#include <cstring>

#include <speech_core/speech_core_c.h>
#include "models/onnx_engine.h"
#include "models/silero_vad.h"
#include "models/parakeet_stt.h"
#include "models/kokoro_tts.h"
#include "models/deepfilter.h"

// ---------------------------------------------------------------------------
// Pipeline handle
// ---------------------------------------------------------------------------

struct speech_pipeline_s {
    sc_pipeline_t pipeline = nullptr;
    SileroVad* vad = nullptr;
    ParakeetStt* stt = nullptr;
    KokoroTts* tts = nullptr;
    DeepFilterEnhancer* enhancer = nullptr;
    speech_event_fn user_callback = nullptr;
    void* user_context = nullptr;

    ~speech_pipeline_s() {
        if (pipeline) sc_pipeline_destroy(pipeline);
        delete enhancer;
        delete tts;
        delete stt;
        delete vad;
    }
};

// ---------------------------------------------------------------------------
// speech-core vtable adapters (pure C++, no platform deps)
// ---------------------------------------------------------------------------

static float vad_process_chunk(void* ctx, const float* samples, size_t len) {
    return static_cast<SileroVad*>(ctx)->process_chunk(samples, len);
}
static void vad_reset(void* ctx) {
    static_cast<SileroVad*>(ctx)->reset();
}
static int vad_sample_rate(void* ctx) {
    return static_cast<SileroVad*>(ctx)->input_sample_rate();
}
static size_t vad_chunk_size(void* ctx) {
    return static_cast<SileroVad*>(ctx)->chunk_size();
}

static sc_transcription_result_t stt_transcribe(
    void* ctx, const float* audio, size_t len, int sr)
{
    auto* stt = static_cast<ParakeetStt*>(ctx);
    auto r = stt->transcribe(audio, len, sr);

    static thread_local std::string text_buf;
    static thread_local std::string lang_buf;
    text_buf = std::move(r.text);
    lang_buf = std::move(r.language);

    return {
        .text = text_buf.c_str(),
        .language = lang_buf.empty() ? nullptr : lang_buf.c_str(),
        .confidence = r.confidence,
        .start_time = 0.0f,
        .end_time = 0.0f,
    };
}
static int stt_sample_rate(void* ctx) {
    return static_cast<ParakeetStt*>(ctx)->input_sample_rate();
}

static void tts_synthesize(
    void* ctx, const char* text, const char* language,
    sc_tts_chunk_fn on_chunk, void* chunk_ctx)
{
    static_cast<KokoroTts*>(ctx)->synthesize(text, language, on_chunk, chunk_ctx);
}
static int tts_sample_rate(void* ctx) {
    return static_cast<KokoroTts*>(ctx)->output_sample_rate();
}
static void tts_cancel(void* ctx) {
    static_cast<KokoroTts*>(ctx)->cancel();
}

static void enhancer_enhance(
    void* ctx, const float* input, size_t len, int sr, float* output)
{
    static_cast<DeepFilterEnhancer*>(ctx)->enhance(input, len, sr, output);
}
static int enhancer_sample_rate(void* ctx) {
    return static_cast<DeepFilterEnhancer*>(ctx)->input_sample_rate();
}

// ---------------------------------------------------------------------------
// Event bridge: sc_event_t → speech_event_t
// ---------------------------------------------------------------------------

static void on_pipeline_event(const sc_event_t* event, void* context) {
    auto* h = static_cast<speech_pipeline_s*>(context);
    if (!h->user_callback) return;

    speech_event_t out = {};
    out.text = event->text;
    out.audio_data = event->audio_data;
    out.audio_data_length = event->audio_data_length;
    out.confidence = event->confidence;
    out.stt_duration_ms = event->stt_duration_ms;
    out.tts_duration_ms = event->tts_duration_ms;

    switch (event->type) {
        case SC_EVENT_SESSION_CREATED:         out.type = SPEECH_EVENT_READY; break;
        case SC_EVENT_SPEECH_STARTED:          out.type = SPEECH_EVENT_SPEECH_STARTED; break;
        case SC_EVENT_SPEECH_ENDED:            out.type = SPEECH_EVENT_SPEECH_ENDED; break;
        case SC_EVENT_PARTIAL_TRANSCRIPTION:   out.type = SPEECH_EVENT_PARTIAL_TRANSCRIPTION; break;
        case SC_EVENT_TRANSCRIPTION_COMPLETED: out.type = SPEECH_EVENT_TRANSCRIPTION; break;
        case SC_EVENT_RESPONSE_AUDIO_DELTA:    out.type = SPEECH_EVENT_RESPONSE_AUDIO; break;
        case SC_EVENT_RESPONSE_DONE:           out.type = SPEECH_EVENT_RESPONSE_DONE; break;
        case SC_EVENT_ERROR:                   out.type = SPEECH_EVENT_ERROR; break;
        default: return;  // skip unmapped events
    }

    h->user_callback(&out, h->user_context);
}

// ---------------------------------------------------------------------------
// Public C API
// ---------------------------------------------------------------------------

speech_config_t speech_config_default(void) {
    return {
        .model_dir = nullptr,
        .use_int8 = true,
        .use_qnn = false,
        .enable_enhancer = false,
        .transcribe_only = false,
        .min_silence_duration = 0.4f,
    };
}

speech_pipeline_t speech_create(speech_config_t config,
                                speech_event_fn on_event,
                                void* event_context)
{
    if (!config.model_dir) return nullptr;

    auto* h = new speech_pipeline_s();
    h->user_callback = on_event;
    h->user_context = event_context;

    std::string dir(config.model_dir);
    std::string suffix = config.use_int8 ? "-int8" : "";
    bool hw_accel = config.use_qnn;

    try {
        h->vad = new SileroVad(dir + "/silero-vad.onnx");
        h->stt = new ParakeetStt(
            dir + "/parakeet-encoder" + suffix + ".onnx",
            dir + "/parakeet-decoder-joint" + suffix + ".onnx",
            dir + "/vocab.json",
            hw_accel);
        h->tts = new KokoroTts(
            dir + "/kokoro" + suffix + ".onnx",
            dir + "/voices", dir, hw_accel);

        // VAD vtable
        sc_vad_vtable_t vad_vt = {};
        vad_vt.context = h->vad;
        vad_vt.process_chunk = vad_process_chunk;
        vad_vt.reset = ::vad_reset;
        vad_vt.input_sample_rate = ::vad_sample_rate;
        vad_vt.chunk_size = ::vad_chunk_size;

        // STT vtable
        sc_stt_vtable_t stt_vt = {};
        stt_vt.context = h->stt;
        stt_vt.transcribe = ::stt_transcribe;
        stt_vt.input_sample_rate = ::stt_sample_rate;

        // TTS vtable
        sc_tts_vtable_t tts_vt = {};
        tts_vt.context = h->tts;
        tts_vt.synthesize = ::tts_synthesize;
        tts_vt.output_sample_rate = ::tts_sample_rate;
        tts_vt.cancel = ::tts_cancel;

        // Pipeline config
        sc_config_t sc_cfg = sc_config_default();
        sc_cfg.min_silence_duration = config.min_silence_duration;
        if (config.transcribe_only) {
            sc_cfg.mode = SC_MODE_TRANSCRIBE_ONLY;
        } else {
            sc_cfg.mode = SC_MODE_ECHO;
        }

        h->pipeline = sc_pipeline_create(
            stt_vt, tts_vt, nullptr, vad_vt,
            sc_cfg, on_pipeline_event, h);

        if (!h->pipeline) {
            delete h;
            return nullptr;
        }

        // Optional enhancer
        if (config.enable_enhancer) {
            std::string aux = dir + "/deepfilter-auxiliary.bin";
            std::string df = dir + "/deepfilter" + suffix + ".onnx";
            FILE* f = fopen(df.c_str(), "r");
            if (f) {
                fclose(f);
                h->enhancer = new DeepFilterEnhancer(df, aux, hw_accel);
                sc_enhancer_vtable_t enh_vt = {};
                enh_vt.context = h->enhancer;
                enh_vt.enhance = ::enhancer_enhance;
                enh_vt.input_sample_rate = ::enhancer_sample_rate;
                sc_pipeline_set_enhancer(h->pipeline, enh_vt);
            }
        }

        return h;

    } catch (const std::exception& e) {
        LOGE("Pipeline creation failed: %s", e.what());
        delete h;
        return nullptr;
    }
}

void speech_start(speech_pipeline_t pipeline) {
    if (pipeline && pipeline->pipeline) sc_pipeline_start(pipeline->pipeline);
}

void speech_push_audio(speech_pipeline_t pipeline,
                       const float* samples, size_t count) {
    if (pipeline && pipeline->pipeline)
        sc_pipeline_push_audio(pipeline->pipeline, samples, count);
}

void speech_resume_listening(speech_pipeline_t pipeline) {
    if (pipeline && pipeline->pipeline)
        sc_pipeline_resume_listening(pipeline->pipeline);
}

void speech_destroy(speech_pipeline_t pipeline) {
    delete pipeline;
}

const char* speech_version(void) {
    return "0.0.1";
}
