# Local acoustic phrase spotting

`KeywordSpotter` is a thin synchronized Kotlin/JNI interface over speech-core's
`OnnxZipformerKws`. It owns no microphone, downloader, transcript parser,
fallback or action router. Supply verified local ONNX assets and token IDs
from their matching tokenizer. Application policy remains with the caller.

```kotlin
KeywordSpotter(KeywordSpotterConfig(
    modelDir = "/local/verified/kws",
    phrases = listOf(KeywordPhrase("alpha beta", matchingTokenIds)),
)).use { spotter ->
    val hits = spotter.pushAudio(normalizedMono16k)
}
```

Audio blocks are at most two seconds. The decoder emits whole acoustic phrases
only after its independent threshold and trailing-blank checks. Hits include
token IDs, 40 ms stream-frame positions and accepted-audio session time. Search
resets after a hit without rewinding stream time. Explicit `reset` restarts the
clock; `endStream` finalizes the tail, and subsequent input requires reset.
Close is idempotent. Invalid configurations, unsupported tensors and native
failures throw rather than returning successful empty results.

This branch pins the core feature commit for
[speech-core PR #150](https://github.com/soniqo/speech-core/pull/150), so the
normal SDK build needs no local core-directory override. Merge the core PR
first and pin its merged commit before releasing the SDK. A downstream app's
model source, immutable revision and SHA-256
manifest are separate release requirements; this API does not provide assets
or authorize a public release from a local Maven build.
