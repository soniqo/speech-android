package audio.soniqo.speech

internal object NativeBridge {

    init {
        System.loadLibrary("speech_android")
    }

    external fun nativeCreate(
        modelDir: String,
        useNnapi: Boolean,
        useInt8: Boolean,
        callback: EventCallback,
        llmCallback: LlmCallback?,
    ): Long

    external fun nativeNnapiFallbackReason(): String?
    external fun nativeDestroy(handle: Long)
    external fun nativeStart(handle: Long)
    external fun nativeStop(handle: Long)
    external fun nativePushAudio(handle: Long, samples: FloatArray, count: Int)
    external fun nativeResumeListen(handle: Long)
    external fun nativeGetState(handle: Long): Int

    /**
     * Called from Kotlin (via LlmBridge) to deliver an LLM token back to
     * the native pipeline worker thread that is blocked waiting for it.
     */
    external fun nativeDeliverLlmToken(
        onTokenFnPtr: Long,
        tokenCtxPtr: Long,
        token: String,
        isFinal: Boolean,
    )

    /** Called from native code on the pipeline worker thread. */
    interface EventCallback {
        fun onEvent(
            type: Int,
            text: String?,
            audio: ByteArray?,
            confidence: Float,
            sttMs: Float,
            ttsMs: Float,
        )
    }

    /** Called from native code on the pipeline worker thread to request an LLM response. */
    interface LlmCallback {
        fun chat(
            roles: Array<String>,
            contents: Array<String>,
            onTokenFnPtr: Long,
            tokenCtxPtr: Long,
        )
        fun cancel()
    }
}
