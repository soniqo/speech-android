#include "kokoro_phonemizer.h"
#include <algorithm>
#include <cctype>

// ---------------------------------------------------------------------------
// UTF-8 helpers
// ---------------------------------------------------------------------------

/// Iterate UTF-8 string one character (potentially multi-byte) at a time.
static std::vector<std::string> utf8_chars(const std::string& s) {
    std::vector<std::string> chars;
    size_t i = 0;
    while (i < s.size()) {
        size_t len = 1;
        unsigned char c = static_cast<unsigned char>(s[i]);
        if ((c & 0xE0) == 0xC0) len = 2;
        else if ((c & 0xF0) == 0xE0) len = 3;
        else if ((c & 0xF8) == 0xF0) len = 4;
        chars.push_back(s.substr(i, len));
        i += len;
    }
    return chars;
}

static std::string to_lower(const std::string& s) {
    std::string result = s;
    std::transform(result.begin(), result.end(), result.begin(),
        [](unsigned char c) { return std::tolower(c); });
    return result;
}

static std::string capitalize(const std::string& s) {
    if (s.empty()) return s;
    std::string result = s;
    result[0] = static_cast<char>(std::toupper(static_cast<unsigned char>(result[0])));
    return result;
}

static bool is_punct(char c) {
    return std::ispunct(static_cast<unsigned char>(c)) != 0;
}

static bool is_whitespace(const std::string& s) {
    for (char c : s) if (!std::isspace(static_cast<unsigned char>(c))) return false;
    return !s.empty();
}

static bool is_all_punct(const std::string& s) {
    for (char c : s) if (!is_punct(c)) return false;
    return !s.empty();
}

static bool ends_with(const std::string& s, const std::string& suffix) {
    if (suffix.size() > s.size()) return false;
    return s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
}

static void replace_all(std::string& s, const std::string& from, const std::string& to) {
    size_t pos = 0;
    while ((pos = s.find(from, pos)) != std::string::npos) {
        s.replace(pos, from.size(), to);
        pos += to.size();
    }
}

// ---------------------------------------------------------------------------
// Loading
// ---------------------------------------------------------------------------

bool KokoroPhonemizer::load_vocab(const std::string& path) {
    auto text = json::read_file(path);
    if (text.empty()) return false;
    vocab_ = json::parse_vocab_index(text);
    return !vocab_.empty();
}

bool KokoroPhonemizer::load_dictionaries(const std::string& dir) {
    auto gold_text = json::read_file(dir + "/us_gold.json");
    if (!gold_text.empty()) {
        gold_dict_ = json::parse_dictionary(gold_text);
        grow_dictionary(gold_dict_);
    }

    auto silver_text = json::read_file(dir + "/us_silver.json");
    if (!silver_text.empty()) {
        silver_dict_ = json::parse_dictionary(silver_text);
        grow_dictionary(silver_dict_);
    }

    return !gold_dict_.empty() || !silver_dict_.empty();
}

void KokoroPhonemizer::grow_dictionary(
    std::unordered_map<std::string, json::DictEntry>& dict)
{
    std::unordered_map<std::string, json::DictEntry> additions;
    for (auto& [key, entry] : dict) {
        auto lower = to_lower(key);
        if (key == lower && !key.empty()) {
            auto cap = capitalize(key);
            if (dict.find(cap) == dict.end()) additions[cap] = entry;
        }
        if (!key.empty() && std::isupper(static_cast<unsigned char>(key[0]))) {
            if (dict.find(lower) == dict.end()) additions[lower] = entry;
        }
    }
    for (auto& [k, v] : additions) dict[k] = std::move(v);
}

// ---------------------------------------------------------------------------
// Tokenization
// ---------------------------------------------------------------------------

std::vector<int64_t> KokoroPhonemizer::tokenize(
    const std::string& text, int max_length)
{
    auto phonemes = text_to_phonemes(text);
    std::vector<int64_t> ids = {BOS_ID};

    // Tokenize IPA string character by character
    // Spaces dropped (not in vocab) — matches iOS behavior
    auto chars = utf8_chars(phonemes);
    for (auto& ch : chars) {
        auto it = vocab_.find(ch);
        if (it != vocab_.end()) {
            ids.push_back(it->second);
        }
        // Unknown chars (including spaces) silently dropped
    }

    ids.push_back(EOS_ID);

    if (static_cast<int>(ids.size()) > max_length) {
        ids.resize(max_length - 1);
        ids.push_back(EOS_ID);
    }

    return ids;
}

std::vector<int64_t> KokoroPhonemizer::pad(
    const std::vector<int64_t>& ids, int length)
{
    if (static_cast<int>(ids.size()) >= length) {
        return std::vector<int64_t>(ids.begin(), ids.begin() + length);
    }
    auto result = ids;
    result.resize(length, PAD_ID);
    return result;
}

// ---------------------------------------------------------------------------
// Text → Phonemes pipeline
// ---------------------------------------------------------------------------

std::string KokoroPhonemizer::text_to_phonemes(const std::string& text) {
    auto normalized = normalize_text(text);
    auto words = split_words(normalized);

    std::string result;
    for (auto& word : words) {
        if (is_whitespace(word)) {
            result += " ";
            continue;
        }
        if (is_all_punct(word)) {
            auto mapped = punctuation_to_phoneme(word);
            if (!mapped.empty()) result += mapped;
            continue;
        }
        auto phonemes = resolve_word(word);
        result += phonemes;
    }
    return result;
}

// ---------------------------------------------------------------------------
// Text normalization
// ---------------------------------------------------------------------------

std::string KokoroPhonemizer::normalize_text(const std::string& text) {
    std::string result = text;

    struct Contraction { const char* from; const char* to; };
    static const Contraction contractions[] = {
        {"can't", "can not"}, {"won't", "will not"}, {"don't", "do not"},
        {"doesn't", "does not"}, {"didn't", "did not"}, {"isn't", "is not"},
        {"aren't", "are not"}, {"wasn't", "was not"}, {"weren't", "were not"},
        {"couldn't", "could not"}, {"wouldn't", "would not"}, {"shouldn't", "should not"},
        {"haven't", "have not"}, {"hasn't", "has not"}, {"hadn't", "had not"},
        {"i'm", "i am"}, {"i've", "i have"}, {"i'll", "i will"}, {"i'd", "i would"},
        {"you're", "you are"}, {"you've", "you have"}, {"you'll", "you will"},
        {"he's", "he is"}, {"she's", "she is"}, {"it's", "it is"},
        {"we're", "we are"}, {"we've", "we have"}, {"we'll", "we will"},
        {"they're", "they are"}, {"they've", "they have"}, {"they'll", "they will"},
        {"that's", "that is"}, {"there's", "there is"}, {"let's", "let us"},
    };

    auto lower = to_lower(result);
    for (auto& c : contractions) {
        if (lower.find(c.from) != std::string::npos) {
            // Case-insensitive replace
            std::string from_lower(c.from);
            size_t pos = lower.find(from_lower);
            while (pos != std::string::npos) {
                result.replace(pos, from_lower.size(), c.to);
                lower = to_lower(result);
                pos = lower.find(from_lower, pos + std::string(c.to).size());
            }
        }
    }

    // Collapse multiple spaces
    replace_all(result, "  ", " ");

    // Trim
    size_t start = result.find_first_not_of(" \t\n\r");
    size_t end = result.find_last_not_of(" \t\n\r");
    if (start == std::string::npos) return "";
    return result.substr(start, end - start + 1);
}

std::vector<std::string> KokoroPhonemizer::split_words(const std::string& text) {
    std::vector<std::string> words;
    std::string current;

    for (char c : text) {
        if (std::isspace(static_cast<unsigned char>(c))) {
            if (!current.empty()) { words.push_back(current); current.clear(); }
            words.emplace_back(1, ' ');
        } else if (is_punct(c)) {
            if (!current.empty()) { words.push_back(current); current.clear(); }
            words.emplace_back(1, c);
        } else {
            current += c;
        }
    }
    if (!current.empty()) words.push_back(current);
    return words;
}

// ---------------------------------------------------------------------------
// Word resolution
// ---------------------------------------------------------------------------

std::string KokoroPhonemizer::resolve_word(const std::string& word) {
    auto lower = to_lower(word);
    auto sp = special_case(lower);
    if (!sp.empty()) return sp;
    auto dict = lookup_dict(lower);
    if (!dict.empty()) return dict;
    auto stemmed = stem_and_lookup(lower);
    if (!stemmed.empty()) return stemmed;
    // Fallback: return word as-is (will be mostly dropped during tokenization)
    return lower;
}

std::string KokoroPhonemizer::lookup_dict(const std::string& word) {
    auto it = gold_dict_.find(word);
    if (it != gold_dict_.end()) return resolve_entry(it->second);
    it = silver_dict_.find(word);
    if (it != silver_dict_.end()) return resolve_entry(it->second);
    return "";
}

std::string KokoroPhonemizer::resolve_entry(const json::DictEntry& entry) {
    if (!entry.is_heteronym()) return entry.simple;
    auto it = entry.pos_map.find("DEFAULT");
    if (it != entry.pos_map.end()) return it->second;
    if (!entry.pos_map.empty()) return entry.pos_map.begin()->second;
    return "";
}

std::string KokoroPhonemizer::special_case(const std::string& word) {
    if (word == "the") return "\xC3\xB0\xC9\x99";  // ðə
    if (word == "a") return "\xC9\x90";              // ɐ
    if (word == "an") return "\xC9\x99n";            // ən
    if (word == "to") return "t\xCA\x8A";            // tʊ
    if (word == "of") return "\xCA\x8Cv";            // ʌv
    if (word == "i") return "a\xC9\xAA";             // aɪ
    return "";
}

std::string KokoroPhonemizer::punctuation_to_phoneme(const std::string& text) {
    if (text == "," || text == "." || text == "!" || text == "?" ||
        text == ";" || text == ":" || text == "-" || text == "'") {
        return text;
    }
    return "";
}

// ---------------------------------------------------------------------------
// Suffix stemming
// ---------------------------------------------------------------------------

std::string KokoroPhonemizer::stem_and_lookup(const std::string& word) {
    auto r = stem_s(word);
    if (!r.empty()) return r;
    r = stem_ed(word);
    if (!r.empty()) return r;
    r = stem_ing(word);
    if (!r.empty()) return r;
    return "";
}

std::string KokoroPhonemizer::stem_s(const std::string& word) {
    if (!ends_with(word, "s") || word.size() <= 2) return "";

    if (ends_with(word, "ies")) {
        auto stem = word.substr(0, word.size() - 3) + "y";
        auto phonemes = lookup_dict(stem);
        if (!phonemes.empty()) return phonemes + "z";
    }

    if (ends_with(word, "es") && word.size() > 3) {
        auto stem = word.substr(0, word.size() - 2);
        auto phonemes = lookup_dict(stem);
        if (!phonemes.empty()) {
            if (!phonemes.empty()) {
                char last = phonemes.back();
                // After sibilants: +ɪz
                if (last == 's' || last == 'z') {
                    return phonemes + "\xC9\xAA" "z"; // ɪz
                }
            }
            return phonemes + "z";
        }
    }

    auto stem = word.substr(0, word.size() - 1);
    auto phonemes = lookup_dict(stem);
    if (!phonemes.empty()) {
        // Voiceless consonants: +s, otherwise +z
        char last = phonemes.back();
        if (last == 'p' || last == 't' || last == 'k' || last == 'f') {
            return phonemes + "s";
        }
        return phonemes + "z";
    }
    return "";
}

std::string KokoroPhonemizer::stem_ed(const std::string& word) {
    if (!ends_with(word, "ed") || word.size() <= 3) return "";

    if (ends_with(word, "ied")) {
        auto stem = word.substr(0, word.size() - 3) + "y";
        auto phonemes = lookup_dict(stem);
        if (!phonemes.empty()) return phonemes + "d";
    }

    auto stem_base = word.substr(0, word.size() - 2);
    if (stem_base.size() >= 2) {
        char last = stem_base.back();
        char prev = stem_base[stem_base.size() - 2];
        if (last == prev) {
            // Doubled consonant — try dedoubled stem
            auto dedoubled = stem_base.substr(0, stem_base.size() - 1);
            auto phonemes = lookup_dict(dedoubled);
            if (!phonemes.empty()) return phonemes + ed_suffix(phonemes);
        }
    }

    auto phonemes = lookup_dict(stem_base);
    if (!phonemes.empty()) return phonemes + ed_suffix(phonemes);
    return "";
}

std::string KokoroPhonemizer::ed_suffix(const std::string& phonemes) {
    if (phonemes.empty()) return "d";
    char last = phonemes.back();
    if (last == 't' || last == 'd') return "\xC9\xAA" "d"; // ɪd
    if (last == 'p' || last == 'k' || last == 'f' || last == 's') {
        return "t";
    }
    return "d";
}

std::string KokoroPhonemizer::stem_ing(const std::string& word) {
    if (!ends_with(word, "ing") || word.size() <= 4) return "";

    auto stem = word.substr(0, word.size() - 3);

    if (stem.size() >= 2) {
        char last = stem.back();
        char prev = stem[stem.size() - 2];
        if (last == prev) {
            auto dedoubled = stem.substr(0, stem.size() - 1);
            auto phonemes = lookup_dict(dedoubled);
            if (!phonemes.empty()) return phonemes + "\xC9\xAA\xC5\x8B"; // ɪŋ
        }
    }

    auto phonemes = lookup_dict(stem);
    if (!phonemes.empty()) return phonemes + "\xC9\xAA\xC5\x8B"; // ɪŋ

    // Try stem + "e" (e.g., "making" → "make")
    auto stem_e = stem + "e";
    phonemes = lookup_dict(stem_e);
    if (!phonemes.empty()) return phonemes + "\xC9\xAA\xC5\x8B"; // ɪŋ

    return "";
}
