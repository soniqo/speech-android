#pragma once

#include <string>
#include <unordered_map>
#include <vector>
#include "../util/json.h"

/// GPL-free phonemizer for Kokoro TTS — ported from speech-swift.
///
/// Three-tier approach for English (all Apache-2.0 / BSD compatible):
/// 1. Dictionary lookup �� gold + silver IPA dictionaries from misaki
/// 2. Suffix stemming — strips -s/-ed/-ing, looks up stem, applies phonological rules
/// 3. BART G2P — encoder-decoder neural model for OOV words (optional ONNX)
///
/// Non-English languages use dictionary-first with rule-based G2P fallback.
///
/// No eSpeak-NG dependency.
class KokoroPhonemizer {
public:
    static constexpr int PAD_ID = 0;
    static constexpr int BOS_ID = 1;
    static constexpr int EOS_ID = 2;

    KokoroPhonemizer() = default;

    /// Load IPA symbol → token ID vocabulary from vocab_index.json.
    bool load_vocab(const std::string& path);

    /// Load pronunciation dictionaries (us_gold.json, us_silver.json).
    bool load_dictionaries(const std::string& dir);

    /// Load a language-specific pronunciation dictionary (dict_fr.json, etc.).
    /// Returns true if the dictionary was loaded successfully.
    bool load_language_dict(const std::string& lang, const std::string& path);

    /// Set the active language for phonemization.
    /// Supported: "en" (default), "fr", "es", "it", "pt", "hi", "ja", "zh".
    void set_language(const std::string& lang);

    /// Convert text → phoneme token IDs (with BOS/EOS, max 510).
    std::vector<int64_t> tokenize(const std::string& text, int max_length = 510);

    /// Pad token IDs to fixed length.
    std::vector<int64_t> pad(const std::vector<int64_t>& ids, int length);

    /// Convert text to IPA phoneme string.
    std::string text_to_phonemes(const std::string& text);

private:

    std::string normalize_text(const std::string& text);
    std::vector<std::string> split_words(const std::string& text);
    std::string resolve_word(const std::string& word);
    std::string lookup_dict(const std::string& word);
    std::string special_case(const std::string& word);
    std::string stem_and_lookup(const std::string& word);
    std::string stem_s(const std::string& word);
    std::string stem_ed(const std::string& word);
    std::string stem_ing(const std::string& word);
    std::string ed_suffix(const std::string& phonemes);
    std::string punctuation_to_phoneme(const std::string& text);

    void grow_dictionary(std::unordered_map<std::string, json::DictEntry>& dict);
    std::string resolve_entry(const json::DictEntry& entry);

    // IPA symbol → token ID
    std::unordered_map<std::string, int> vocab_;

    // English pronunciation dictionaries
    std::unordered_map<std::string, json::DictEntry> gold_dict_;
    std::unordered_map<std::string, json::DictEntry> silver_dict_;

    // Active language (default: English)
    std::string language_ = "en";

    // Non-English pronunciation dictionaries keyed by language code
    std::unordered_map<std::string, std::unordered_map<std::string, std::string>> lang_dicts_;
};
