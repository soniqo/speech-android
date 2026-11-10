package audio.soniqo.speech

/** Optional reclamation of pages that Android's native allocator no longer uses. */
object SpeechMemory {
    /**
     * Returns unused native pages to the OS without closing models or changing
     * allocator settings. Call on a worker thread after inference has drained
     * or models have closed, rather than from an audio callback or UI thread.
     *
     * This operates on the process allocator. It preserves live allocations and
     * does not guarantee a particular RSS/PSS reduction. Returns false if the
     * device allocator does not support the purge request (including API 26–27).
     */
    @JvmStatic
    fun releaseUnused(): Boolean = NativeBridge.nativeReleaseUnusedMemory()
}
