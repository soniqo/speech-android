#ifndef SPEECH_H
#define SPEECH_H

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct speech_pipeline_s* speech_pipeline_t;

typedef enum {
    SPEECH_EVENT_READY = 0,
    SPEECH_EVENT_SPEECH_STARTED,
    SPEECH_EVENT_SPEECH_ENDED,
    SPEECH_EVENT_PARTIAL_TRANSCRIPTION,
    SPEECH_EVENT_TRANSCRIPTION,
    SPEECH_EVENT_RESPONSE_AUDIO,
    SPEECH_EVENT_RESPONSE_DONE,
    SPEECH_EVENT_ERROR
} speech_event_type_t;

typedef struct {
    speech_event_type_t type;
    const char* text;
    const uint8_t* audio_data;
    size_t audio_data_length;
    float confidence;
    float stt_duration_ms;
    float tts_duration_ms;
} speech_event_t;

typedef struct {
    const char* model_dir;
    bool use_int8;
    bool use_qnn;
    bool enable_enhancer;
    bool transcribe_only;
    float min_silence_duration;
} speech_config_t;

typedef void (*speech_event_fn)(const speech_event_t* event, void* context);

speech_config_t speech_config_default(void);

speech_pipeline_t speech_create(speech_config_t config,
                                speech_event_fn on_event,
                                void* event_context);

void speech_start(speech_pipeline_t pipeline);

void speech_push_audio(speech_pipeline_t pipeline,
                       const float* samples, size_t count);

void speech_resume_listening(speech_pipeline_t pipeline);

void speech_destroy(speech_pipeline_t pipeline);

const char* speech_version(void);

#ifdef __cplusplus
}
#endif

#endif
