# Experimental Nemotron language guide

`TranscriberConfig.languageGuide` optionally accepts
`LanguageGuideConfig(listOf("en-US", "ru-RU", "zh-CN"))` with the default
automatic language. The expected-language list is copied. The loaded bundle
must support every exact locale; invalid configuration fails explicitly.

Alternatively call `setLanguageGuide` before `beginStream`. Pass `null` or an
empty guide to clear it. Calling `setLanguage` also clears the previous guide,
even when selecting Auto again. Apply the desired guide after selecting the
language and before opening each new stream. Configuration cannot be changed
through `setLanguageGuide` while the stream is open.

The default guide retains 90% Auto and distributes 10% over selected prompt
slots. Empty/zero guides preserve the original one-hot path. This fractional
conditioning is experimental and outside the model's documented one-hot
contract. It leaves vocabulary open and can worsen recognition, including
languages absent from the preference list. It is not a language allowlist or
calibrated confidence. Neither LiteRT nor ONNX needs another model or weights.
