package com.soniqo.speech

sealed class SpeechEvent {
    data object SessionCreated : SpeechEvent()
    data object SpeechStarted : SpeechEvent()
    data object SpeechEnded : SpeechEvent()
    data class PartialTranscription(val text: String, val confidence: Float) : SpeechEvent()
    data class TranscriptionCompleted(val text: String, val confidence: Float, val sttMs: Float) : SpeechEvent()
    data object ResponseCreated : SpeechEvent()
    data object ResponseInterrupted : SpeechEvent()
    data class ResponseAudioDelta(val audio: ByteArray, val ttsMs: Float) : SpeechEvent()
    data object ResponseDone : SpeechEvent()
    data class Error(val message: String) : SpeechEvent()
}

enum class PipelineState(val value: Int) {
    Idle(0),
    Listening(1),
    Transcribing(2),
    Thinking(3),
    Speaking(4);

    companion object {
        fun from(value: Int) = entries.firstOrNull { it.value == value } ?: Idle
    }
}
