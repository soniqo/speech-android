#pragma once

#include <string>
#include <unordered_map>

/// Non-English phonemizers for Kokoro TTS.
///
/// Dictionary-first approach with rule-based G2P fallback:
/// 1. Split text into words (whitespace + punctuation boundaries)
/// 2. For each word: try dictionary lookup (lowercase), if found use it
/// 3. If not found: apply rule-based grapheme-to-phoneme conversion
/// 4. Pass punctuation tokens through
///
/// Languages:
/// - French, Spanish, Portuguese, Italian, Hindi — dictionary + rule-based
/// - Japanese — Katakana/Hiragana tables, kanji passthrough
/// - Chinese — Pinyin->IPA conversion (requires pre-segmented pinyin input)
namespace multilingual {

// --- Dictionary-first phonemizers (preferred entry points) ---

std::string french_phonemize(const std::string& text,
    const std::unordered_map<std::string, std::string>& dict);

std::string spanish_phonemize(const std::string& text,
    const std::unordered_map<std::string, std::string>& dict);

std::string italian_phonemize(const std::string& text,
    const std::unordered_map<std::string, std::string>& dict);

std::string portuguese_phonemize(const std::string& text,
    const std::unordered_map<std::string, std::string>& dict);

std::string hindi_phonemize(const std::string& text,
    const std::unordered_map<std::string, std::string>& dict);

std::string japanese_phonemize(const std::string& text);

std::string chinese_phonemize(const std::string& text);

// --- Rule-based G2P fallback (used when word not in dictionary) ---

std::string french_g2p(const std::string& text);
std::string spanish_g2p(const std::string& text);
std::string portuguese_g2p(const std::string& text);
std::string italian_g2p(const std::string& text);
std::string japanese_g2p(const std::string& text);
std::string chinese_g2p(const std::string& text);
std::string hindi_g2p(const std::string& text);

} // namespace multilingual
