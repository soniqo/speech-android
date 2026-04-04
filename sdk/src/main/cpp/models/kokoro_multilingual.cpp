#include "kokoro_multilingual.h"
#include <algorithm>
#include <cctype>
#include <cstdint>
#include <unordered_map>
#include <vector>

// ---------------------------------------------------------------------------
// UTF-8 helpers
// ---------------------------------------------------------------------------

/// Decode one UTF-8 character, returning codepoint and advancing pos.
static uint32_t utf8_decode(const std::string& s, size_t& pos) {
    if (pos >= s.size()) return 0;
    unsigned char c = static_cast<unsigned char>(s[pos]);
    uint32_t cp;
    size_t len;
    if (c < 0x80)       { cp = c;           len = 1; }
    else if (c < 0xC0)  { cp = c;           len = 1; } // continuation — error
    else if (c < 0xE0)  { cp = c & 0x1F;    len = 2; }
    else if (c < 0xF0)  { cp = c & 0x0F;    len = 3; }
    else                 { cp = c & 0x07;    len = 4; }
    for (size_t i = 1; i < len && (pos + i) < s.size(); i++) {
        cp = (cp << 6) | (static_cast<unsigned char>(s[pos + i]) & 0x3F);
    }
    pos += len;
    return cp;
}

/// Get one UTF-8 character as a string, advancing pos.
static std::string utf8_char_at(const std::string& s, size_t& pos) {
    if (pos >= s.size()) return "";
    unsigned char c = static_cast<unsigned char>(s[pos]);
    size_t len = 1;
    if ((c & 0xE0) == 0xC0)      len = 2;
    else if ((c & 0xF0) == 0xE0) len = 3;
    else if ((c & 0xF8) == 0xF0) len = 4;
    if (pos + len > s.size()) len = s.size() - pos;
    std::string result = s.substr(pos, len);
    pos += len;
    return result;
}

/// Split UTF-8 string into individual characters.
static std::vector<std::string> utf8_split(const std::string& s) {
    std::vector<std::string> out;
    size_t pos = 0;
    while (pos < s.size()) {
        out.push_back(utf8_char_at(s, pos));
    }
    return out;
}

/// Encode a Unicode codepoint to UTF-8.
static std::string utf8_encode(uint32_t cp) {
    std::string out;
    if (cp < 0x80) {
        out += static_cast<char>(cp);
    } else if (cp < 0x800) {
        out += static_cast<char>(0xC0 | (cp >> 6));
        out += static_cast<char>(0x80 | (cp & 0x3F));
    } else if (cp < 0x10000) {
        out += static_cast<char>(0xE0 | (cp >> 12));
        out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
        out += static_cast<char>(0x80 | (cp & 0x3F));
    } else {
        out += static_cast<char>(0xF0 | (cp >> 18));
        out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
        out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
        out += static_cast<char>(0x80 | (cp & 0x3F));
    }
    return out;
}

/// Get codepoint of first UTF-8 character.
static uint32_t utf8_codepoint(const std::string& s) {
    size_t pos = 0;
    return utf8_decode(s, pos);
}

/// Check if a character is a vowel letter (for Latin languages).
static bool is_latin_vowel(char c) {
    char lc = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return lc == 'a' || lc == 'e' || lc == 'i' || lc == 'o' || lc == 'u' || lc == 'y';
}

/// Check if character at offset i in string is a vowel (ASCII only).
static bool is_vowel_at(const std::string& s, size_t i) {
    if (i >= s.size()) return false;
    return is_latin_vowel(s[i]);
}

/// Check if character is a consonant letter (ASCII).
static bool is_consonant(char c) {
    char lc = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return lc >= 'a' && lc <= 'z' && !is_latin_vowel(lc);
}

/// Lowercase an ASCII string.
static std::string to_lower_ascii(const std::string& s) {
    std::string r = s;
    for (auto& c : r) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return r;
}

/// Check if a string starts with prefix at position pos.
static bool starts_with_at(const std::string& s, size_t pos, const std::string& prefix) {
    if (pos + prefix.size() > s.size()) return false;
    return s.compare(pos, prefix.size(), prefix) == 0;
}

/// Post-process IPA for Kokoro vocab compatibility.
/// Maps IPA symbols that Kokoro doesn't have to ones it does.
static std::string kokoro_postprocess(const std::string& ipa) {
    std::string result = ipa;

    // dʒ → ʤ
    {
        std::string from = "d\xCA\x92";   // dʒ
        std::string to   = "\xCA\xA4";     // ʤ
        size_t pos = 0;
        while ((pos = result.find(from, pos)) != std::string::npos) {
            result.replace(pos, from.size(), to);
            pos += to.size();
        }
    }
    // tʃ → ʧ
    {
        std::string from = "t\xCA\x83";   // tʃ
        std::string to   = "\xCA\xA7";     // ʧ
        size_t pos = 0;
        while ((pos = result.find(from, pos)) != std::string::npos) {
            result.replace(pos, from.size(), to);
            pos += to.size();
        }
    }
    // ʁ → ɹ  (French uvular R → Kokoro's approximant R)
    {
        std::string from = "\xCA\x81";   // ʁ
        std::string to   = "\xC9\xB9";   // ɹ
        size_t pos = 0;
        while ((pos = result.find(from, pos)) != std::string::npos) {
            result.replace(pos, from.size(), to);
            pos += to.size();
        }
    }
    return result;
}

/// Check if position is at word end (next char is space, punct, or end).
static bool at_word_end(const std::string& s, size_t pos) {
    if (pos >= s.size()) return true;
    char c = s[pos];
    return c == ' ' || c == ',' || c == '.' || c == '!' || c == '?'
        || c == ';' || c == ':' || c == '-' || c == '\n' || c == '\t';
}

// ===========================================================================
// FRENCH
// ===========================================================================

std::string multilingual::french_g2p(const std::string& text) {
    std::string s = to_lower_ascii(text);
    std::string ipa;
    size_t len = s.size();

    for (size_t i = 0; i < len; ) {
        // --- Trigraphs ---
        if (i + 3 <= len) {
            std::string tri = s.substr(i, 3);
            if (tri == "eau") { ipa += "o";    i += 3; continue; }
            if (tri == "ain") {
                // ain before consonant or end = nasal
                if (i + 3 >= len || !is_vowel_at(s, i + 3)) {
                    ipa += "\xC9\x9B\xCC\x83"; // ɛ̃
                    i += 3; continue;
                }
            }
            if (tri == "ein") {
                if (i + 3 >= len || !is_vowel_at(s, i + 3)) {
                    ipa += "\xC9\x9B\xCC\x83"; // ɛ̃
                    i += 3; continue;
                }
            }
            if (tri == "oin") {
                if (i + 3 >= len || !is_vowel_at(s, i + 3)) {
                    ipa += "w\xC9\x9B\xCC\x83"; // wɛ̃
                    i += 3; continue;
                }
            }
            if (tri == "ien") {
                if (i + 3 >= len || !is_vowel_at(s, i + 3)) {
                    ipa += "j\xC9\x9B\xCC\x83"; // jɛ̃
                    i += 3; continue;
                }
            }
        }

        // --- Digraphs ---
        if (i + 2 <= len) {
            std::string di = s.substr(i, 2);

            // Nasal vowels (before consonant or end, not before vowel)
            if (di == "on" || di == "om") {
                if (i + 2 >= len || !is_vowel_at(s, i + 2)) {
                    // Check not followed by another n/m (e.g., "onne")
                    if (i + 2 < len && (s[i + 2] == 'n' || s[i + 2] == 'm')) {
                        // Not nasal — "bonne" → not nasal
                    } else {
                        ipa += "\xC9\x94\xCC\x83"; // ɔ̃
                        i += 2; continue;
                    }
                }
            }
            if (di == "an" || di == "am") {
                if (i + 2 >= len || !is_vowel_at(s, i + 2)) {
                    if (i + 2 < len && (s[i + 2] == 'n' || s[i + 2] == 'm')) {
                        // Not nasal
                    } else {
                        ipa += "\xC9\x91\xCC\x83"; // ɑ̃
                        i += 2; continue;
                    }
                }
            }
            if (di == "en" || di == "em") {
                if (i + 2 >= len || !is_vowel_at(s, i + 2)) {
                    if (i + 2 < len && (s[i + 2] == 'n' || s[i + 2] == 'm')) {
                        // Not nasal
                    } else {
                        ipa += "\xC9\x91\xCC\x83"; // ɑ̃
                        i += 2; continue;
                    }
                }
            }
            if (di == "in" || di == "im") {
                if (i + 2 >= len || !is_vowel_at(s, i + 2)) {
                    if (i + 2 < len && (s[i + 2] == 'n' || s[i + 2] == 'm')) {
                        // Not nasal
                    } else {
                        ipa += "\xC9\x9B\xCC\x83"; // ɛ̃
                        i += 2; continue;
                    }
                }
            }
            if (di == "un" || di == "um") {
                if (i + 2 >= len || !is_vowel_at(s, i + 2)) {
                    if (i + 2 < len && (s[i + 2] == 'n' || s[i + 2] == 'm')) {
                        // Not nasal
                    } else {
                        ipa += "\xC5\x93\xCC\x83"; // œ̃
                        i += 2; continue;
                    }
                }
            }

            // Other digraphs
            if (di == "ou") { ipa += "u";                 i += 2; continue; }
            if (di == "oi") { ipa += "wa";                i += 2; continue; }
            if (di == "ai") { ipa += "\xC9\x9B";         i += 2; continue; } // ɛ
            if (di == "ei") { ipa += "\xC9\x9B";         i += 2; continue; } // ɛ
            if (di == "au") { ipa += "o";                 i += 2; continue; }
            if (di == "eu") { ipa += "\xC3\xB8";         i += 2; continue; } // ø
            if (di == "ch") { ipa += "\xCA\x83";         i += 2; continue; } // ʃ
            if (di == "ph") { ipa += "f";                 i += 2; continue; }
            if (di == "gn") { ipa += "\xC9\xB2";         i += 2; continue; } // ɲ
            if (di == "qu") { ipa += "k";                 i += 2; continue; }
            if (di == "gu") {
                // gu before e/i → g (silent u)
                if (i + 2 < len && (s[i + 2] == 'e' || s[i + 2] == 'i')) {
                    ipa += "g"; i += 2; continue;
                }
            }
            if (di == "ss") { ipa += "s";                 i += 2; continue; }
            if (di == "ll") { ipa += "l";                 i += 2; continue; }
            if (di == "tt") { ipa += "t";                 i += 2; continue; }
            if (di == "nn") { ipa += "n";                 i += 2; continue; }
            if (di == "mm") { ipa += "m";                 i += 2; continue; }
            if (di == "rr") { ipa += "\xCA\x81";         i += 2; continue; } // ʁ
        }

        char c = s[i];

        // Context-dependent consonants
        if (c == 'c') {
            if (i + 1 < len && (s[i + 1] == 'e' || s[i + 1] == 'i' || s[i + 1] == 'y')) {
                ipa += "s";
            } else {
                ipa += "k";
            }
            i++; continue;
        }
        if (c == 'g') {
            if (i + 1 < len && (s[i + 1] == 'e' || s[i + 1] == 'i')) {
                ipa += "\xCA\x92"; // ʒ
            } else {
                ipa += "g";
            }
            i++; continue;
        }

        // Silent final consonants
        if ((c == 'd' || c == 't' || c == 's' || c == 'x' || c == 'z' || c == 'p')
            && at_word_end(s, i + 1)) {
            i++; continue;
        }

        // Simple consonant mappings
        if (c == 'j') { ipa += "\xCA\x92"; i++; continue; } // ʒ
        if (c == 'r') { ipa += "\xCA\x81"; i++; continue; } // ʁ
        if (c == 'x') { ipa += "ks";       i++; continue; }

        // Vowels
        if (c == 'e') {
            // Final 'e' is often silent (schwa)
            if (at_word_end(s, i + 1) && i > 0) {
                // Silent final -e (except monosyllables)
                i++; continue;
            }
            ipa += "\xC9\x99"; // ə
            i++; continue;
        }
        if (c == 'u') { ipa += "y";                     i++; continue; }
        if (c == 'y') { ipa += "i";                     i++; continue; }

        // Passthrough: a, i, o, b, d, f, k, l, m, n, p, t, v, w, z + punctuation
        if (c == ' ') { ipa += " "; i++; continue; }
        if (c >= 'a' && c <= 'z') {
            ipa += c;
            i++; continue;
        }

        // Punctuation passthrough
        if (c == ',' || c == '.' || c == '!' || c == '?' || c == ';' || c == ':' || c == '-') {
            ipa += c;
            i++; continue;
        }

        // Skip unknown characters
        size_t tmp = i;
        utf8_char_at(s, tmp);
        i = tmp;
    }

    return kokoro_postprocess(ipa);
}

// ===========================================================================
// SPANISH
// ===========================================================================

std::string multilingual::spanish_g2p(const std::string& text) {
    // Work with UTF-8 characters for accented vowels
    auto chars = utf8_split(text);
    std::string ipa;

    for (size_t i = 0; i < chars.size(); ) {
        std::string c = chars[i];
        uint32_t cp = utf8_codepoint(c);

        // Lowercase for comparison
        std::string cl;
        if (cp >= 'A' && cp <= 'Z') {
            cl = std::string(1, static_cast<char>(cp + 32));
        } else {
            cl = c;
        }

        // --- Digraphs (check two chars) ---
        std::string next_l;
        if (i + 1 < chars.size()) {
            uint32_t ncp = utf8_codepoint(chars[i + 1]);
            if (ncp >= 'A' && ncp <= 'Z') {
                next_l = std::string(1, static_cast<char>(ncp + 32));
            } else {
                next_l = chars[i + 1];
            }
        }

        if (!next_l.empty()) {
            std::string di = cl + next_l;
            if (di == "ch") { ipa += "t\xCA\x83";       i += 2; continue; } // tʃ
            if (di == "ll") { ipa += "\xCA\x9D";         i += 2; continue; } // ʝ
            if (di == "rr") { ipa += "r";                 i += 2; continue; }
            if (di == "qu") {
                // qu before e/i = k (silent u)
                if (i + 2 < chars.size()) {
                    uint32_t nncp = utf8_codepoint(chars[i + 2]);
                    char nn = static_cast<char>(std::tolower(nncp));
                    if (nn == 'e' || nn == 'i') {
                        ipa += "k"; i += 2; continue;
                    }
                }
                ipa += "k"; i += 2; continue;
            }
            if (di == "gu") {
                // gu before e/i = g (silent u)
                if (i + 2 < chars.size()) {
                    uint32_t nncp = utf8_codepoint(chars[i + 2]);
                    char nn = static_cast<char>(std::tolower(nncp));
                    if (nn == 'e' || nn == 'i') {
                        ipa += "g"; i += 2; continue;
                    }
                }
            }
        }

        // --- Accented vowels (stressed, lengthened) ---
        // á = C3 A1, é = C3 A9, í = C3 AD, ó = C3 B3, ú = C3 BA
        // Á = C3 81, É = C3 89, Í = C3 8D, Ó = C3 93, Ú = C3 9A
        if (cp == 0xE1 || cp == 0xC1)  { ipa += "a\xCB\x90"; i++; continue; } // aː
        if (cp == 0xE9 || cp == 0xC9)  { ipa += "e\xCB\x90"; i++; continue; } // eː
        if (cp == 0xED || cp == 0xCD)  { ipa += "i\xCB\x90"; i++; continue; } // iː
        if (cp == 0xF3 || cp == 0xD3)  { ipa += "o\xCB\x90"; i++; continue; } // oː
        if (cp == 0xFA || cp == 0xDA)  { ipa += "u\xCB\x90"; i++; continue; } // uː

        // ñ = C3 B1, Ñ = C3 91
        if (cp == 0xF1 || cp == 0xD1)  { ipa += "\xC9\xB2"; i++; continue; } // ɲ

        // ü = C3 BC (used in güe, güi)
        if (cp == 0xFC || cp == 0xDC)  { ipa += "w"; i++; continue; }

        // --- Context-dependent consonants ---
        if (cl == "c") {
            if (!next_l.empty() && (next_l == "e" || next_l == "i")) {
                ipa += "\xCE\xB8"; // θ (Castilian)
            } else {
                ipa += "k";
            }
            i++; continue;
        }
        if (cl == "g") {
            if (!next_l.empty() && (next_l == "e" || next_l == "i")) {
                ipa += "x"; // velar fricative
            } else {
                ipa += "g";
            }
            i++; continue;
        }
        if (cl == "j") { ipa += "x";                    i++; continue; }
        if (cl == "z") { ipa += "\xCE\xB8";             i++; continue; } // θ
        if (cl == "v") { ipa += "b";                     i++; continue; } // Spanish v = b
        if (cl == "h") { i++; continue; } // silent h
        if (cl == "x") { ipa += "ks";                   i++; continue; }

        // Simple passthrough
        if (cp == ' ')  { ipa += " "; i++; continue; }
        if (cp >= 'a' && cp <= 'z') { ipa += static_cast<char>(cp); i++; continue; }
        if (cp >= 'A' && cp <= 'Z') { ipa += static_cast<char>(cp + 32); i++; continue; }

        // Punctuation
        if (cp == ',' || cp == '.' || cp == '!' || cp == '?' || cp == ';'
            || cp == ':' || cp == '-') {
            ipa += static_cast<char>(cp);
            i++; continue;
        }
        // Inverted punctuation
        if (cp == 0xBF || cp == 0xA1) { i++; continue; } // ¿ ¡ — skip

        i++; // skip unknown
    }

    return kokoro_postprocess(ipa);
}

// ===========================================================================
// PORTUGUESE
// ===========================================================================

std::string multilingual::portuguese_g2p(const std::string& text) {
    auto chars = utf8_split(text);
    std::string ipa;

    for (size_t i = 0; i < chars.size(); ) {
        std::string c = chars[i];
        uint32_t cp = utf8_codepoint(c);

        std::string cl;
        if (cp >= 'A' && cp <= 'Z') {
            cl = std::string(1, static_cast<char>(cp + 32));
        } else {
            cl = c;
        }

        // Lookahead
        std::string next_l, next2_l;
        if (i + 1 < chars.size()) {
            uint32_t ncp = utf8_codepoint(chars[i + 1]);
            next_l = (ncp >= 'A' && ncp <= 'Z')
                ? std::string(1, static_cast<char>(ncp + 32)) : chars[i + 1];
        }
        if (i + 2 < chars.size()) {
            uint32_t ncp = utf8_codepoint(chars[i + 2]);
            next2_l = (ncp >= 'A' && ncp <= 'Z')
                ? std::string(1, static_cast<char>(ncp + 32)) : chars[i + 2];
        }

        // --- Trigraphs ---
        if (!next_l.empty() && !next2_l.empty()) {
            std::string tri = cl + next_l + next2_l;
            // ção → sɐ̃w̃
            if (cp == 0xE7 || cp == 0xC7) { // ç
                if (next_l == "a" || next_l == "\xC3\xA3") { // ã (U+00E3)
                    uint32_t n2cp = utf8_codepoint(chars[i + 2]);
                    if (n2cp == 'o' || n2cp == 0xF5) { // o or õ
                        ipa += "s\xC9\x90\xCC\x83w\xCC\x83"; // sɐ̃w̃
                        i += 3; continue;
                    }
                }
            }
            if (tri == "lha" || tri == "lhe" || tri == "lhi" || tri == "lho" || tri == "lhu") {
                // lh → ʎ
                ipa += "\xCA\x8E"; // ʎ
                i += 2; continue; // consume lh, leave vowel for next iteration
            }
            if (tri == "nha" || tri == "nhe" || tri == "nhi" || tri == "nho" || tri == "nhu") {
                ipa += "\xC9\xB2"; // ɲ
                i += 2; continue;
            }
        }

        // --- Digraphs ---
        if (!next_l.empty()) {
            std::string di = cl + next_l;
            if (di == "nh") { ipa += "\xC9\xB2";        i += 2; continue; } // ɲ
            if (di == "lh") { ipa += "\xCA\x8E";        i += 2; continue; } // ʎ
            if (di == "ch") { ipa += "\xCA\x83";        i += 2; continue; } // ʃ
            if (di == "ss") { ipa += "s";                i += 2; continue; }
            if (di == "rr") { ipa += "\xCA\x81";        i += 2; continue; } // ʁ
            if (di == "qu") { ipa += "k";                i += 2; continue; }
            if (di == "gu") {
                if (!next2_l.empty() && (next2_l == "e" || next2_l == "i")) {
                    ipa += "g"; i += 2; continue;
                }
            }
            if (di == "ou") { ipa += "ow";               i += 2; continue; }
            if (di == "ei") { ipa += "ej";               i += 2; continue; }
            if (di == "ai") { ipa += "aj";               i += 2; continue; }
            if (di == "oi") { ipa += "oj";               i += 2; continue; }
        }

        // --- Nasal vowels (ã, õ) ---
        // ã = U+00E3, õ = U+00F5
        if (cp == 0xE3 || cp == 0xC3) {
            // Check for ão
            if (!next_l.empty()) {
                uint32_t ncp = utf8_codepoint(chars[i + 1]);
                if (ncp == 'o' || ncp == 0xF5) {
                    ipa += "\xC9\x90\xCC\x83w\xCC\x83"; // ɐ̃w̃
                    i += 2; continue;
                }
            }
            ipa += "\xC9\x90\xCC\x83"; // ɐ̃
            i++; continue;
        }
        if (cp == 0xF5 || cp == 0xD5) {
            // Check for õe
            if (!next_l.empty() && (next_l == "e" || next_l == "\xC3\xA9")) {
                ipa += "o\xCC\x83j\xCC\x83"; // õj̃
                i += 2; continue;
            }
            ipa += "o\xCC\x83"; // õ
            i++; continue;
        }

        // --- Accented vowels ---
        if (cp == 0xE1 || cp == 0xC1) { ipa += "a";  i++; continue; } // á
        if (cp == 0xE2 || cp == 0xC2) { ipa += "a";  i++; continue; } // â
        if (cp == 0xE9 || cp == 0xC9) { ipa += "\xC9\x9B"; i++; continue; } // é → ɛ (open)
        if (cp == 0xEA || cp == 0xCA) { ipa += "e";  i++; continue; } // ê
        if (cp == 0xED || cp == 0xCD) { ipa += "i";  i++; continue; } // í
        if (cp == 0xF3 || cp == 0xD3) { ipa += "\xC9\x94"; i++; continue; } // ó → ɔ (open)
        if (cp == 0xF4 || cp == 0xD4) { ipa += "o";  i++; continue; } // ô
        if (cp == 0xFA || cp == 0xDA) { ipa += "u";  i++; continue; } // ú

        // ç → s
        if (cp == 0xE7 || cp == 0xC7) { ipa += "s";  i++; continue; }

        // Context-dependent
        if (cl == "c") {
            if (!next_l.empty() && (next_l == "e" || next_l == "i")) {
                ipa += "s";
            } else {
                ipa += "k";
            }
            i++; continue;
        }
        if (cl == "g") {
            if (!next_l.empty() && (next_l == "e" || next_l == "i")) {
                ipa += "\xCA\x92"; // ʒ
            } else {
                ipa += "g";
            }
            i++; continue;
        }
        if (cl == "r") {
            // Initial r or rr = ʁ, intervocalic = ɾ
            if (i == 0 || (i > 0 && !is_latin_vowel(chars[i - 1][0]))) {
                ipa += "\xCA\x81"; // ʁ
            } else {
                ipa += "\xC9\xBE"; // ɾ
            }
            i++; continue;
        }
        if (cl == "s") {
            // Intervocalic s = z
            if (i > 0 && i + 1 < chars.size()
                && is_latin_vowel(chars[i - 1][0]) && is_latin_vowel(chars[i + 1][0])) {
                ipa += "z";
            } else {
                ipa += "s";
            }
            i++; continue;
        }

        if (cl == "j") { ipa += "\xCA\x92"; i++; continue; } // ʒ
        if (cl == "x") { ipa += "\xCA\x83"; i++; continue; } // ʃ (most common)
        if (cl == "h") { i++; continue; } // silent

        // Passthrough
        if (cp == ' ')  { ipa += " "; i++; continue; }
        if (cp >= 'a' && cp <= 'z') { ipa += static_cast<char>(cp); i++; continue; }
        if (cp >= 'A' && cp <= 'Z') { ipa += static_cast<char>(cp + 32); i++; continue; }

        // Punctuation
        if (cp == ',' || cp == '.' || cp == '!' || cp == '?' || cp == ';'
            || cp == ':' || cp == '-') {
            ipa += static_cast<char>(cp);
            i++; continue;
        }

        i++; // skip unknown
    }

    return kokoro_postprocess(ipa);
}

// ===========================================================================
// ITALIAN
// ===========================================================================

std::string multilingual::italian_g2p(const std::string& text) {
    auto chars = utf8_split(text);
    std::string ipa;

    for (size_t i = 0; i < chars.size(); ) {
        std::string c = chars[i];
        uint32_t cp = utf8_codepoint(c);

        std::string cl;
        if (cp >= 'A' && cp <= 'Z') {
            cl = std::string(1, static_cast<char>(cp + 32));
        } else {
            cl = c;
        }

        // Lookahead
        std::string next_l, next2_l;
        if (i + 1 < chars.size()) {
            uint32_t ncp = utf8_codepoint(chars[i + 1]);
            next_l = (ncp >= 'A' && ncp <= 'Z')
                ? std::string(1, static_cast<char>(ncp + 32)) : chars[i + 1];
        }
        if (i + 2 < chars.size()) {
            uint32_t ncp = utf8_codepoint(chars[i + 2]);
            next2_l = (ncp >= 'A' && ncp <= 'Z')
                ? std::string(1, static_cast<char>(ncp + 32)) : chars[i + 2];
        }

        // --- Trigraphs ---
        if (!next_l.empty() && !next2_l.empty()) {
            std::string tri = cl + next_l + next2_l;
            // sci before e/i = ʃ + vowel
            if (tri == "sce" || tri == "sci") {
                ipa += "\xCA\x83"; // ʃ
                i += 2; continue; // consume sc, leave vowel
            }
            // gli before vowel = ʎ + vowel
            if (cl == "g" && next_l == "l" && next2_l == "i") {
                // Check if followed by a vowel
                if (i + 3 < chars.size()) {
                    uint32_t nncp = utf8_codepoint(chars[i + 3]);
                    if (nncp == 'a' || nncp == 'e' || nncp == 'i' || nncp == 'o' || nncp == 'u') {
                        ipa += "\xCA\x8E"; // ʎ
                        i += 3; continue; // consume gli
                    }
                }
                // gli at end or before consonant = ʎi
                ipa += "\xCA\x8Ei"; // ʎi
                i += 3; continue;
            }
            // ghi/ghe = g + vowel (hard g before e/i)
            if (cl == "g" && next_l == "h") {
                if (next2_l == "e" || next2_l == "i") {
                    ipa += "g";
                    i += 2; continue; // consume gh, leave vowel
                }
            }
            // chi/che = k + vowel (hard c before e/i)
            if (cl == "c" && next_l == "h") {
                if (next2_l == "e" || next2_l == "i") {
                    ipa += "k";
                    i += 2; continue;
                }
            }
        }

        // --- Digraphs ---
        if (!next_l.empty()) {
            std::string di = cl + next_l;
            if (di == "gn") { ipa += "\xC9\xB2";         i += 2; continue; } // ɲ
            if (di == "sc") {
                // sc before e/i = ʃ (already handled in trigraphs above for explicit vowel)
                if (!next2_l.empty() && (next2_l == "e" || next2_l == "i")) {
                    ipa += "\xCA\x83"; // ʃ
                    i += 2; continue;
                }
                ipa += "sk"; i += 2; continue;
            }
            if (di == "qu") { ipa += "kw";                i += 2; continue; }
            if (di == "ss") { ipa += "s";                 i += 2; continue; }
            if (di == "zz") { ipa += "ts";                i += 2; continue; }
            if (di == "cc") {
                if (!next2_l.empty() && (next2_l == "e" || next2_l == "i")) {
                    ipa += "t\xCA\x83"; // tʃ
                    i += 2; continue;
                }
                ipa += "kk"; i += 2; continue;
            }
            if (di == "gg") {
                if (!next2_l.empty() && (next2_l == "e" || next2_l == "i")) {
                    ipa += "d\xCA\x92"; // dʒ
                    i += 2; continue;
                }
                ipa += "gg"; i += 2; continue;
            }
            if (di == "gl") {
                // gl before i = ʎ (covered in trigraphs)
                // gl otherwise = gl
                ipa += "gl"; i += 2; continue;
            }
        }

        // --- Context-dependent consonants ---
        if (cl == "c") {
            if (!next_l.empty() && (next_l == "e" || next_l == "i")) {
                ipa += "t\xCA\x83"; // tʃ
            } else {
                ipa += "k";
            }
            i++; continue;
        }
        if (cl == "g") {
            if (!next_l.empty() && (next_l == "e" || next_l == "i")) {
                ipa += "d\xCA\x92"; // dʒ
            } else {
                ipa += "g";
            }
            i++; continue;
        }
        if (cl == "z") {
            // Default: ts (can be dz in some words — would need dictionary)
            ipa += "ts";
            i++; continue;
        }
        if (cl == "s") {
            // Intervocalic s = z
            if (i > 0 && i + 1 < chars.size()
                && is_latin_vowel(chars[i - 1][0]) && is_latin_vowel(chars[i + 1][0])) {
                ipa += "z";
            } else {
                ipa += "s";
            }
            i++; continue;
        }

        // Accented vowels
        if (cp == 0xE0 || cp == 0xC0) { ipa += "a";  i++; continue; } // à
        if (cp == 0xE1 || cp == 0xC1) { ipa += "a";  i++; continue; } // á
        if (cp == 0xE8 || cp == 0xC8) { ipa += "\xC9\x9B"; i++; continue; } // è → ɛ
        if (cp == 0xE9 || cp == 0xC9) { ipa += "e";  i++; continue; } // é
        if (cp == 0xEC || cp == 0xCC) { ipa += "i";  i++; continue; } // ì
        if (cp == 0xED || cp == 0xCD) { ipa += "i";  i++; continue; } // í
        if (cp == 0xF2 || cp == 0xD2) { ipa += "\xC9\x94"; i++; continue; } // ò → ɔ
        if (cp == 0xF3 || cp == 0xD3) { ipa += "o";  i++; continue; } // ó
        if (cp == 0xF9 || cp == 0xD9) { ipa += "u";  i++; continue; } // ù
        if (cp == 0xFA || cp == 0xDA) { ipa += "u";  i++; continue; } // ú

        if (cl == "h") { i++; continue; } // silent
        if (cl == "j") { ipa += "j"; i++; continue; }

        // Passthrough
        if (cp == ' ')  { ipa += " "; i++; continue; }
        if (cp >= 'a' && cp <= 'z') { ipa += static_cast<char>(cp); i++; continue; }
        if (cp >= 'A' && cp <= 'Z') { ipa += static_cast<char>(cp + 32); i++; continue; }

        // Punctuation
        if (cp == ',' || cp == '.' || cp == '!' || cp == '?' || cp == ';'
            || cp == ':' || cp == '-') {
            ipa += static_cast<char>(cp);
            i++; continue;
        }

        i++; // skip unknown
    }

    return kokoro_postprocess(ipa);
}

// ===========================================================================
// JAPANESE
// ===========================================================================

// Katakana and Hiragana → IPA tables.
// Built as static maps initialized on first use.

struct KanaEntry {
    const char* kana;
    const char* ipa;
};

static const std::unordered_map<std::string, std::string>& get_kana_map() {
    static const std::unordered_map<std::string, std::string> map = []() {
        std::unordered_map<std::string, std::string> m;

        // --- Katakana digraphs (must be checked before singles) ---
        // Stored with their UTF-8 sequences.

        // キャ行
        m["\xe3\x82\xad\xe3\x83\xa3"] = "kja"; // キャ
        m["\xe3\x82\xad\xe3\x83\xa5"] = "kju"; // キュ
        m["\xe3\x82\xad\xe3\x83\xa7"] = "kjo"; // キョ

        // シャ行
        m["\xe3\x82\xb7\xe3\x83\xa3"] = "\xca\x83" "a"; // シャ = ʃa
        m["\xe3\x82\xb7\xe3\x83\xa5"] = "\xca\x83" "u"; // シュ = ʃu
        m["\xe3\x82\xb7\xe3\x83\xa7"] = "\xca\x83" "o"; // ショ = ʃo

        // チャ行
        m["\xe3\x83\x81\xe3\x83\xa3"] = "t\xca\x83" "a"; // チャ = tʃa
        m["\xe3\x83\x81\xe3\x83\xa5"] = "t\xca\x83" "u"; // チュ = tʃu
        m["\xe3\x83\x81\xe3\x83\xa7"] = "t\xca\x83" "o"; // チョ = tʃo

        // ニャ行
        m["\xe3\x83\x8b\xe3\x83\xa3"] = "\xc9\xb2" "a"; // ニャ = ɲa
        m["\xe3\x83\x8b\xe3\x83\xa5"] = "\xc9\xb2" "u"; // ニュ = ɲu
        m["\xe3\x83\x8b\xe3\x83\xa7"] = "\xc9\xb2" "o"; // ニョ = ɲo

        // ヒャ行
        m["\xe3\x83\x92\xe3\x83\xa3"] = "\xc3\xa7" "a"; // ヒャ = ça
        m["\xe3\x83\x92\xe3\x83\xa5"] = "\xc3\xa7" "u"; // ヒュ = çu
        m["\xe3\x83\x92\xe3\x83\xa7"] = "\xc3\xa7" "o"; // ヒョ = ço

        // ミャ行
        m["\xe3\x83\x9f\xe3\x83\xa3"] = "mja"; // ミャ
        m["\xe3\x83\x9f\xe3\x83\xa5"] = "mju"; // ミュ
        m["\xe3\x83\x9f\xe3\x83\xa7"] = "mjo"; // ミョ

        // リャ行
        m["\xe3\x83\xaa\xe3\x83\xa3"] = "\xc9\xbe" "ja"; // リャ = ɾja
        m["\xe3\x83\xaa\xe3\x83\xa5"] = "\xc9\xbe" "ju"; // リュ = ɾju
        m["\xe3\x83\xaa\xe3\x83\xa7"] = "\xc9\xbe" "jo"; // リョ = ɾjo

        // ギャ行
        m["\xe3\x82\xae\xe3\x83\xa3"] = "gja"; // ギャ
        m["\xe3\x82\xae\xe3\x83\xa5"] = "gju"; // ギュ
        m["\xe3\x82\xae\xe3\x83\xa7"] = "gjo"; // ギョ

        // ジャ行
        m["\xe3\x82\xb8\xe3\x83\xa3"] = "d\xca\x92" "a"; // ジャ = dʒa
        m["\xe3\x82\xb8\xe3\x83\xa5"] = "d\xca\x92" "u"; // ジュ = dʒu
        m["\xe3\x82\xb8\xe3\x83\xa7"] = "d\xca\x92" "o"; // ジョ = dʒo

        // ビャ行
        m["\xe3\x83\x93\xe3\x83\xa3"] = "bja"; // ビャ
        m["\xe3\x83\x93\xe3\x83\xa5"] = "bju"; // ビュ
        m["\xe3\x83\x93\xe3\x83\xa7"] = "bjo"; // ビョ

        // ピャ行
        m["\xe3\x83\x94\xe3\x83\xa3"] = "pja"; // ピャ
        m["\xe3\x83\x94\xe3\x83\xa5"] = "pju"; // ピュ
        m["\xe3\x83\x94\xe3\x83\xa7"] = "pjo"; // ピョ

        // --- Katakana singles ---
        // ア行
        m["\xe3\x82\xa2"] = "a";   // ア
        m["\xe3\x82\xa4"] = "i";   // イ
        m["\xe3\x82\xa6"] = "\xc9\xb0"; // ウ = ɰ (unrounded)
        m["\xe3\x82\xa8"] = "e";   // エ
        m["\xe3\x82\xaa"] = "o";   // オ

        // カ行
        m["\xe3\x82\xab"] = "ka";  // カ
        m["\xe3\x82\xad"] = "ki";  // キ
        m["\xe3\x82\xaf"] = "k\xc9\xb0"; // ク = kɰ
        m["\xe3\x82\xb1"] = "ke";  // ケ
        m["\xe3\x82\xb3"] = "ko";  // コ

        // サ行
        m["\xe3\x82\xb5"] = "sa";  // サ
        m["\xe3\x82\xb7"] = "\xca\x83i"; // シ = ʃi
        m["\xe3\x82\xb9"] = "s\xc9\xb0"; // ス = sɰ
        m["\xe3\x82\xbb"] = "se";  // セ
        m["\xe3\x82\xbd"] = "so";  // ソ

        // タ行
        m["\xe3\x82\xbf"] = "ta";  // タ
        m["\xe3\x83\x81"] = "t\xca\x83i"; // チ = tʃi
        m["\xe3\x83\x84"] = "ts\xc9\xb0"; // ツ = tsɰ
        m["\xe3\x83\x86"] = "te";  // テ
        m["\xe3\x83\x88"] = "to";  // ト

        // ナ行
        m["\xe3\x83\x8a"] = "na";  // ナ
        m["\xe3\x83\x8b"] = "\xc9\xb2i"; // ニ = ɲi
        m["\xe3\x83\x8c"] = "n\xc9\xb0"; // ヌ = nɰ
        m["\xe3\x83\x8d"] = "ne";  // ネ
        m["\xe3\x83\x8e"] = "no";  // ノ

        // ハ行
        m["\xe3\x83\x8f"] = "ha";  // ハ
        m["\xe3\x83\x92"] = "\xc3\xa7i"; // ヒ = çi
        m["\xe3\x83\x95"] = "\xc9\xb8\xc9\xb0"; // フ = ɸɰ
        m["\xe3\x83\x98"] = "he";  // ヘ
        m["\xe3\x83\x9b"] = "ho";  // ホ

        // マ行
        m["\xe3\x83\x9e"] = "ma";  // マ
        m["\xe3\x83\x9f"] = "mi";  // ミ
        m["\xe3\x83\xa0"] = "m\xc9\xb0"; // ム = mɰ
        m["\xe3\x83\xa1"] = "me";  // メ
        m["\xe3\x83\xa2"] = "mo";  // モ

        // ヤ行
        m["\xe3\x83\xa4"] = "ja";  // ヤ
        m["\xe3\x83\xa6"] = "j\xc9\xb0"; // ユ = jɰ
        m["\xe3\x83\xa8"] = "jo";  // ヨ

        // ラ行
        m["\xe3\x83\xa9"] = "\xc9\xbe" "a"; // ラ = ɾa
        m["\xe3\x83\xaa"] = "\xc9\xbe" "i"; // リ = ɾi
        m["\xe3\x83\xab"] = "\xc9\xbe\xc9\xb0"; // ル = ɾɰ
        m["\xe3\x83\xac"] = "\xc9\xbe" "e"; // レ = ɾe
        m["\xe3\x83\xad"] = "\xc9\xbe" "o"; // ロ = ɾo

        // ワ行
        m["\xe3\x83\xaf"] = "wa";  // ワ
        m["\xe3\x83\xb2"] = "o";   // ヲ
        m["\xe3\x83\xb3"] = "\xc9\xb4"; // ン = ɴ

        // 濁音 (voiced) — ガ行
        m["\xe3\x82\xac"] = "ga";  // ガ
        m["\xe3\x82\xae"] = "gi";  // ギ
        m["\xe3\x82\xb0"] = "g\xc9\xb0"; // グ = gɰ
        m["\xe3\x82\xb2"] = "ge";  // ゲ
        m["\xe3\x82\xb4"] = "go";  // ゴ

        // ザ行
        m["\xe3\x82\xb6"] = "za";  // ザ
        m["\xe3\x82\xb8"] = "d\xca\x92i"; // ジ = dʒi
        m["\xe3\x82\xba"] = "z\xc9\xb0"; // ズ = zɰ
        m["\xe3\x82\xbc"] = "ze";  // ゼ
        m["\xe3\x82\xbe"] = "zo";  // ゾ

        // ダ行
        m["\xe3\x83\x80"] = "da";  // ダ
        m["\xe3\x83\x82"] = "d\xca\x92i"; // ヂ = dʒi
        m["\xe3\x83\x85"] = "z\xc9\xb0"; // ヅ = zɰ
        m["\xe3\x83\x87"] = "de";  // デ
        m["\xe3\x83\x89"] = "do";  // ド

        // バ行
        m["\xe3\x83\x90"] = "ba";  // バ
        m["\xe3\x83\x93"] = "bi";  // ビ
        m["\xe3\x83\x96"] = "b\xc9\xb0"; // ブ = bɰ
        m["\xe3\x83\x99"] = "be";  // ベ
        m["\xe3\x83\x9c"] = "bo";  // ボ

        // パ行
        m["\xe3\x83\x91"] = "pa";  // パ
        m["\xe3\x83\x94"] = "pi";  // ピ
        m["\xe3\x83\x97"] = "p\xc9\xb0"; // プ = pɰ
        m["\xe3\x83\x9a"] = "pe";  // ペ
        m["\xe3\x83\x9d"] = "po";  // ポ

        // Special
        m["\xe3\x83\x83"] = "\xca\x94"; // ッ (small tsu) = ʔ (glottal stop)
        m["\xe3\x83\xbc"] = "\xcb\x90"; // ー (long vowel) = ː

        // --- Hiragana (offset katakana by 0x60) ---
        // We add the same entries for hiragana.
        // Hiragana range: U+3041-U+3093
        // Katakana range: U+30A1-U+30F3
        // Offset: Hiragana = Katakana - 0x60

        // Hiragana vowels
        m["\xe3\x81\x82"] = "a";   // あ
        m["\xe3\x81\x84"] = "i";   // い
        m["\xe3\x81\x86"] = "\xc9\xb0"; // う = ɰ
        m["\xe3\x81\x88"] = "e";   // え
        m["\xe3\x81\x8a"] = "o";   // お

        // か行
        m["\xe3\x81\x8b"] = "ka";  // か
        m["\xe3\x81\x8d"] = "ki";  // き
        m["\xe3\x81\x8f"] = "k\xc9\xb0"; // く
        m["\xe3\x81\x91"] = "ke";  // け
        m["\xe3\x81\x93"] = "ko";  // こ

        // さ行
        m["\xe3\x81\x95"] = "sa";  // さ
        m["\xe3\x81\x97"] = "\xca\x83i"; // し = ʃi
        m["\xe3\x81\x99"] = "s\xc9\xb0"; // す
        m["\xe3\x81\x9b"] = "se";  // せ
        m["\xe3\x81\x9d"] = "so";  // そ

        // た行
        m["\xe3\x81\x9f"] = "ta";  // た
        m["\xe3\x81\xa1"] = "t\xca\x83i"; // ち = tʃi
        m["\xe3\x81\xa4"] = "ts\xc9\xb0"; // つ
        m["\xe3\x81\xa6"] = "te";  // て
        m["\xe3\x81\xa8"] = "to";  // と

        // な行
        m["\xe3\x81\xaa"] = "na";  // な
        m["\xe3\x81\xab"] = "\xc9\xb2i"; // に = ɲi
        m["\xe3\x81\xac"] = "n\xc9\xb0"; // ぬ
        m["\xe3\x81\xad"] = "ne";  // ね
        m["\xe3\x81\xae"] = "no";  // の

        // は行
        m["\xe3\x81\xaf"] = "ha";  // は
        m["\xe3\x81\xb2"] = "\xc3\xa7i"; // ひ = çi
        m["\xe3\x81\xb5"] = "\xc9\xb8\xc9\xb0"; // ふ = ɸɰ
        m["\xe3\x81\xb8"] = "he";  // へ
        m["\xe3\x81\xbb"] = "ho";  // ほ

        // ま行
        m["\xe3\x81\xbe"] = "ma";  // ま
        m["\xe3\x81\xbf"] = "mi";  // み
        m["\xe3\x82\x80"] = "m\xc9\xb0"; // む
        m["\xe3\x82\x81"] = "me";  // め
        m["\xe3\x82\x82"] = "mo";  // も

        // や行
        m["\xe3\x82\x84"] = "ja";  // や
        m["\xe3\x82\x86"] = "j\xc9\xb0"; // ゆ
        m["\xe3\x82\x88"] = "jo";  // よ

        // ら行
        m["\xe3\x82\x89"] = "\xc9\xbe" "a"; // ら = ɾa
        m["\xe3\x82\x8a"] = "\xc9\xbe" "i"; // り = ɾi
        m["\xe3\x82\x8b"] = "\xc9\xbe\xc9\xb0"; // る = ɾɰ
        m["\xe3\x82\x8c"] = "\xc9\xbe" "e"; // れ = ɾe
        m["\xe3\x82\x8d"] = "\xc9\xbe" "o"; // ろ = ɾo

        // わ行
        m["\xe3\x82\x8f"] = "wa";  // わ
        m["\xe3\x82\x92"] = "o";   // を
        m["\xe3\x82\x93"] = "\xc9\xb4"; // ん = ɴ

        // 濁音 — が行
        m["\xe3\x81\x8c"] = "ga";  // が
        m["\xe3\x81\x8e"] = "gi";  // ぎ
        m["\xe3\x81\x90"] = "g\xc9\xb0"; // ぐ
        m["\xe3\x81\x92"] = "ge";  // げ
        m["\xe3\x81\x94"] = "go";  // ご

        // ざ行
        m["\xe3\x81\x96"] = "za";  // ざ
        m["\xe3\x81\x98"] = "d\xca\x92i"; // じ = dʒi
        m["\xe3\x81\x9a"] = "z\xc9\xb0"; // ず
        m["\xe3\x81\x9c"] = "ze";  // ぜ
        m["\xe3\x81\x9e"] = "zo";  // ぞ

        // だ行
        m["\xe3\x81\xa0"] = "da";  // だ
        m["\xe3\x81\xa2"] = "d\xca\x92i"; // ぢ = dʒi
        m["\xe3\x81\xa5"] = "z\xc9\xb0"; // づ
        m["\xe3\x81\xa7"] = "de";  // で
        m["\xe3\x81\xa9"] = "do";  // ど

        // ば行
        m["\xe3\x81\xb0"] = "ba";  // ば
        m["\xe3\x81\xb3"] = "bi";  // び
        m["\xe3\x81\xb6"] = "b\xc9\xb0"; // ぶ
        m["\xe3\x81\xb9"] = "be";  // べ
        m["\xe3\x81\xbc"] = "bo";  // ぼ

        // ぱ行
        m["\xe3\x81\xb1"] = "pa";  // ぱ
        m["\xe3\x81\xb4"] = "pi";  // ぴ
        m["\xe3\x81\xb7"] = "p\xc9\xb0"; // ぷ
        m["\xe3\x81\xba"] = "pe";  // ぺ
        m["\xe3\x81\xbd"] = "po";  // ぽ

        // Special hiragana
        m["\xe3\x81\xa3"] = "\xca\x94"; // っ (small tsu) = ʔ

        // Hiragana digraphs (きゃ etc.)
        m["\xe3\x81\x8d\xe3\x82\x83"] = "kja"; // きゃ
        m["\xe3\x81\x8d\xe3\x82\x85"] = "kju"; // きゅ
        m["\xe3\x81\x8d\xe3\x82\x87"] = "kjo"; // きょ

        m["\xe3\x81\x97\xe3\x82\x83"] = "\xca\x83" "a"; // しゃ = ʃa
        m["\xe3\x81\x97\xe3\x82\x85"] = "\xca\x83" "u"; // しゅ = ʃu
        m["\xe3\x81\x97\xe3\x82\x87"] = "\xca\x83" "o"; // しょ = ʃo

        m["\xe3\x81\xa1\xe3\x82\x83"] = "t\xca\x83" "a"; // ちゃ = tʃa
        m["\xe3\x81\xa1\xe3\x82\x85"] = "t\xca\x83" "u"; // ちゅ = tʃu
        m["\xe3\x81\xa1\xe3\x82\x87"] = "t\xca\x83" "o"; // ちょ = tʃo

        m["\xe3\x81\xab\xe3\x82\x83"] = "\xc9\xb2" "a"; // にゃ = ɲa
        m["\xe3\x81\xab\xe3\x82\x85"] = "\xc9\xb2" "u"; // にゅ = ɲu
        m["\xe3\x81\xab\xe3\x82\x87"] = "\xc9\xb2" "o"; // にょ = ɲo

        m["\xe3\x81\xb2\xe3\x82\x83"] = "\xc3\xa7" "a"; // ひゃ = ça
        m["\xe3\x81\xb2\xe3\x82\x85"] = "\xc3\xa7" "u"; // ひゅ = çu
        m["\xe3\x81\xb2\xe3\x82\x87"] = "\xc3\xa7" "o"; // ひょ = ço

        m["\xe3\x81\xbf\xe3\x82\x83"] = "mja"; // みゃ
        m["\xe3\x81\xbf\xe3\x82\x85"] = "mju"; // みゅ
        m["\xe3\x81\xbf\xe3\x82\x87"] = "mjo"; // みょ

        m["\xe3\x82\x8a\xe3\x82\x83"] = "\xc9\xbe" "ja"; // りゃ = ɾja
        m["\xe3\x82\x8a\xe3\x82\x85"] = "\xc9\xbe" "ju"; // りゅ = ɾju
        m["\xe3\x82\x8a\xe3\x82\x87"] = "\xc9\xbe" "jo"; // りょ = ɾjo

        m["\xe3\x81\x8e\xe3\x82\x83"] = "gja"; // ぎゃ
        m["\xe3\x81\x8e\xe3\x82\x85"] = "gju"; // ぎゅ
        m["\xe3\x81\x8e\xe3\x82\x87"] = "gjo"; // ぎょ

        m["\xe3\x81\x98\xe3\x82\x83"] = "d\xca\x92" "a"; // じゃ = dʒa
        m["\xe3\x81\x98\xe3\x82\x85"] = "d\xca\x92" "u"; // じゅ = dʒu
        m["\xe3\x81\x98\xe3\x82\x87"] = "d\xca\x92" "o"; // じょ = dʒo

        m["\xe3\x81\xb3\xe3\x82\x83"] = "bja"; // びゃ
        m["\xe3\x81\xb3\xe3\x82\x85"] = "bju"; // びゅ
        m["\xe3\x81\xb3\xe3\x82\x87"] = "bjo"; // びょ

        m["\xe3\x81\xb4\xe3\x82\x83"] = "pja"; // ぴゃ
        m["\xe3\x81\xb4\xe3\x82\x85"] = "pju"; // ぴゅ
        m["\xe3\x81\xb4\xe3\x82\x87"] = "pjo"; // ぴょ

        return m;
    }();
    return map;
}

std::string multilingual::japanese_g2p(const std::string& text) {
    const auto& kana_map = get_kana_map();
    auto chars = utf8_split(text);
    std::string ipa;

    for (size_t i = 0; i < chars.size(); ) {
        // Try digraph (two characters) first
        if (i + 1 < chars.size()) {
            std::string pair = chars[i] + chars[i + 1];
            auto it = kana_map.find(pair);
            if (it != kana_map.end()) {
                ipa += it->second;
                i += 2;
                continue;
            }
        }

        // Try single character
        auto it = kana_map.find(chars[i]);
        if (it != kana_map.end()) {
            ipa += it->second;
            i++;
            continue;
        }

        uint32_t cp = utf8_codepoint(chars[i]);

        // ASCII passthrough
        if (cp == ' ')  { ipa += " "; i++; continue; }
        if ((cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z')
            || (cp >= '0' && cp <= '9')) {
            ipa += chars[i]; i++; continue;
        }
        // Punctuation
        if (cp == ',' || cp == '.' || cp == '!' || cp == '?'
            || cp == 0x3001 || cp == 0x3002) { // 、。
            ipa += ","; // normalize Japanese punctuation to comma pause
            i++; continue;
        }

        // CJK ideographs (kanji) — pass through as-is (requires JNI/dictionary for proper conversion)
        if (cp >= 0x4E00 && cp <= 0x9FFF) {
            // TODO: Kanji→reading conversion requires dictionary or JNI callback
            ipa += chars[i];
            i++; continue;
        }

        i++; // skip unknown
    }

    return kokoro_postprocess(ipa);
}

// ===========================================================================
// CHINESE (Pinyin → IPA)
// ===========================================================================

// Pinyin syllable → IPA conversion.
// This handles pre-segmented pinyin input (space-separated syllables).
// For raw Chinese text, a pinyin segmenter is needed upstream (JNI or ICU).

struct PinyinMapping {
    const char* pinyin;
    const char* ipa;
};

// Build the pinyin→IPA table on first use.
static const std::unordered_map<std::string, std::string>& get_pinyin_finals_map() {
    static const std::unordered_map<std::string, std::string> map = {
        // Complex finals first (longer match priority)
        {"iang", "ja\xc5\x8b"},     // jaŋ
        {"iong", "j\xca\x8a\xc5\x8b"}, // jʊŋ
        {"uang", "wa\xc5\x8b"},     // waŋ
        {"iao",  "jaw"},
        {"ian",  "j\xc9\x9bn"},     // jɛn
        {"ang",  "a\xc5\x8b"},      // aŋ
        {"eng",  "\xc9\x99\xc5\x8b"}, // əŋ
        {"ing",  "i\xc5\x8b"},      // iŋ
        {"ong",  "\xca\x8a\xc5\x8b"}, // ʊŋ
        {"uai",  "waj"},
        {"uan",  "wan"},
        {"ai",   "aj"},
        {"ei",   "ej"},
        {"ao",   "aw"},
        {"ou",   "ow"},
        {"an",   "an"},
        {"en",   "\xc9\x99n"},       // ən
        {"in",   "in"},
        {"un",   "\xc9\x99n"},       // ən (=uen simplified)
        {"ia",   "ja"},
        {"ie",   "je"},
        {"uo",   "wo"},
        {"ua",   "wa"},
        {"ue",   "we"},              // üe
        {"ui",   "wej"},             // =uei
        {"iu",   "jow"},             // =iou
        {"er",   "\xc9\x99\xc9\xbb"}, // əɻ
        {"a",    "a"},
        {"e",    "\xc9\xa4"},        // ɤ
        {"i",    "i"},
        {"o",    "wo"},
        {"u",    "u"},
    };
    return map;
}

static const std::unordered_map<std::string, std::string>& get_pinyin_initials_map() {
    static const std::unordered_map<std::string, std::string> map = {
        {"zh",  "\xca\x88\xca\x82"},     // ʈʂ
        {"ch",  "\xca\x88\xca\x82\xca\xb0"}, // ʈʂʰ
        {"sh",  "\xca\x82"},              // ʂ
        {"b",   "p"},
        {"p",   "p\xca\xb0"},            // pʰ
        {"m",   "m"},
        {"f",   "f"},
        {"d",   "t"},
        {"t",   "t\xca\xb0"},            // tʰ
        {"n",   "n"},
        {"l",   "l"},
        {"g",   "k"},
        {"k",   "k\xca\xb0"},            // kʰ
        {"h",   "x"},
        {"j",   "t\xc9\x95"},            // tɕ
        {"q",   "t\xc9\x95\xca\xb0"},    // tɕʰ
        {"x",   "\xc9\x95"},             // ɕ
        {"z",   "ts"},
        {"c",   "ts\xca\xb0"},           // tsʰ
        {"s",   "s"},
        {"r",   "\xc9\xbb"},             // ɻ
        {"y",   "j"},                     // glide
        {"w",   "w"},                     // glide
    };
    return map;
}

/// Convert a single pinyin syllable (with optional tone number) to IPA.
static std::string pinyin_syllable_to_ipa(const std::string& syllable) {
    if (syllable.empty()) return "";

    std::string syl = to_lower_ascii(syllable);

    // Strip tone number (1-5) at end
    if (!syl.empty() && syl.back() >= '1' && syl.back() <= '5') {
        syl.pop_back();
    }
    if (syl.empty()) return "";

    // Handle ü (written as v or ü in some pinyin systems)
    {
        size_t pos = 0;
        while ((pos = syl.find('v', pos)) != std::string::npos) {
            syl.replace(pos, 1, "\xc3\xbc"); // ü
            pos += 2;
        }
    }

    const auto& initials = get_pinyin_initials_map();
    const auto& finals = get_pinyin_finals_map();

    std::string initial_ipa;
    std::string remaining = syl;

    // Try two-char initial first, then one-char
    if (syl.size() >= 2) {
        auto it = initials.find(syl.substr(0, 2));
        if (it != initials.end()) {
            initial_ipa = it->second;
            remaining = syl.substr(2);
        }
    }
    if (initial_ipa.empty() && syl.size() >= 1) {
        auto it = initials.find(syl.substr(0, 1));
        if (it != initials.end()) {
            initial_ipa = it->second;
            remaining = syl.substr(1);
        }
    }

    // Special case: ü finals after j/q/x/y (written as u but pronounced y)
    if (!remaining.empty() && remaining[0] == 'u') {
        std::string init1 = syl.size() >= 1 ? syl.substr(0, 1) : "";
        if (init1 == "j" || init1 == "q" || init1 == "x" || init1 == "y") {
            remaining = "v" + remaining.substr(1); // treat as ü
            // Actually, for j/q/x, the u IS ü. Map to y sound.
            // Keep as-is for finals matching, the final will handle it.
        }
    }

    // Match final
    std::string final_ipa;
    // Try longest match first
    for (size_t len = std::min(remaining.size(), size_t(4)); len > 0; len--) {
        auto it = finals.find(remaining.substr(0, len));
        if (it != finals.end()) {
            final_ipa = it->second;
            break;
        }
    }

    if (final_ipa.empty() && !remaining.empty()) {
        // Fallback: just use the remaining as-is
        final_ipa = remaining;
    }

    return initial_ipa + final_ipa;
}

std::string multilingual::chinese_g2p(const std::string& text) {
    // Input is expected to be pinyin (space-separated syllables) or mixed text.
    // CJK characters are passed through (would need pinyin conversion upstream).
    auto chars = utf8_split(text);
    std::string ipa;
    std::string current_syllable;

    auto flush_syllable = [&]() {
        if (!current_syllable.empty()) {
            ipa += pinyin_syllable_to_ipa(current_syllable);
            current_syllable.clear();
        }
    };

    for (size_t i = 0; i < chars.size(); i++) {
        uint32_t cp = utf8_codepoint(chars[i]);

        // CJK ideographs — pass through (needs upstream pinyin conversion)
        if (cp >= 0x4E00 && cp <= 0x9FFF) {
            flush_syllable();
            // TODO: Character→pinyin conversion requires dictionary or JNI callback
            ipa += chars[i];
            continue;
        }

        // Space or punctuation = syllable boundary
        if (cp == ' ' || cp == ',' || cp == '.' || cp == '!' || cp == '?'
            || cp == ';' || cp == ':' || cp == '-') {
            flush_syllable();
            if (cp == ' ') ipa += " ";
            else ipa += static_cast<char>(cp);
            continue;
        }

        // ASCII letters and digits = part of pinyin syllable
        if ((cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z')
            || (cp >= '0' && cp <= '9')) {
            current_syllable += static_cast<char>(cp);
            continue;
        }

        // ü (U+00FC)
        if (cp == 0xFC) {
            current_syllable += "v"; // internal representation
            continue;
        }

        // Skip unknown
        flush_syllable();
    }
    flush_syllable();

    return kokoro_postprocess(ipa);
}

// ===========================================================================
// HINDI (Devanagari → IPA)
// ===========================================================================

// Devanagari consonants → IPA
static const std::unordered_map<uint32_t, std::string>& get_devanagari_consonants() {
    static const std::unordered_map<uint32_t, std::string> map = {
        // Velars
        {0x0915, "k"},       // क
        {0x0916, "k\xca\xb0"},  // ख = kʰ
        {0x0917, "\xc9\xa1"},    // ग = ɡ
        {0x0918, "\xc9\xa1\xca\xb1"}, // घ = ɡʱ
        {0x0919, "\xc5\x8b"},    // ङ = ŋ

        // Palatals
        {0x091A, "t\xca\x83"},      // च = tʃ
        {0x091B, "t\xca\x83\xca\xb0"}, // छ = tʃʰ
        {0x091C, "d\xca\x92"},      // ज = dʒ
        {0x091D, "d\xca\x92\xca\xb1"}, // झ = dʒʱ
        {0x091E, "\xc9\xb2"},       // ञ = ɲ

        // Retroflexes
        {0x091F, "\xca\x88"},       // ट = ʈ
        {0x0920, "\xca\x88\xca\xb0"}, // ठ = ʈʰ
        {0x0921, "\xc9\x96"},       // ड = ɖ
        {0x0922, "\xc9\x96\xca\xb1"}, // ढ = ɖʱ
        {0x0923, "\xc9\xb3"},       // ण = ɳ

        // Dentals
        {0x0924, "t\xcc\xaa"},      // त = t̪
        {0x0925, "t\xcc\xaa\xca\xb0"}, // थ = t̪ʰ
        {0x0926, "d\xcc\xaa"},      // द = d̪
        {0x0927, "d\xcc\xaa\xca\xb1"}, // ध = d̪ʱ
        {0x0928, "n"},              // न = n

        // Labials
        {0x092A, "p"},       // प
        {0x092B, "p\xca\xb0"},  // फ = pʰ
        {0x092C, "b"},       // ब
        {0x092D, "b\xca\xb1"},  // भ = bʱ
        {0x092E, "m"},       // म

        // Semi-vowels / Approximants
        {0x092F, "j"},       // य
        {0x0930, "\xc9\xbe"},   // र = ɾ
        {0x0932, "l"},       // ल
        {0x0935, "\xca\x8b"},   // व = ʋ

        // Sibilants / Fricatives
        {0x0936, "\xca\x83"},   // श = ʃ
        {0x0937, "\xca\x82"},   // ष = ʂ
        {0x0938, "s"},       // स
        {0x0939, "\xc9\xa6"},   // ह = ɦ

        // Nukta variants
        {0x0958, "k"},       // क़ → k (Urdu qaf)
        {0x0959, "x"},       // ख़ → x
        {0x095A, "\xc9\xa3"},   // ग़ → ɣ
        {0x095B, "z"},       // ज़ → z
        {0x095C, "\xc9\x96"},   // ड़ → ɖ (flap)
        {0x095D, "\xc9\x96\xca\xb1"}, // ढ़ → ɖʱ
        {0x095E, "f"},       // फ़ → f
    };
    return map;
}

// Devanagari independent vowels → IPA
static const std::unordered_map<uint32_t, std::string>& get_devanagari_vowels() {
    static const std::unordered_map<uint32_t, std::string> map = {
        {0x0905, "\xc9\x99"},       // अ = ə
        {0x0906, "a\xcb\x90"},      // आ = aː
        {0x0907, "\xc9\xaa"},       // इ = ɪ
        {0x0908, "i\xcb\x90"},      // ई = iː
        {0x0909, "\xca\x8a"},       // उ = ʊ
        {0x090A, "u\xcb\x90"},      // ऊ = uː
        {0x090B, "\xc9\xbe\xc9\xaa"}, // ऋ = ɾɪ
        {0x090F, "e\xcb\x90"},      // ए = eː
        {0x0910, "\xc9\x99j"},      // ऐ = əj (diphthong)
        {0x0913, "o\xcb\x90"},      // ओ = oː
        {0x0914, "\xc9\x99w"},      // औ = əw (diphthong)
    };
    return map;
}

// Devanagari vowel signs (matras) → IPA
static const std::unordered_map<uint32_t, std::string>& get_devanagari_matras() {
    static const std::unordered_map<uint32_t, std::string> map = {
        {0x093E, "a\xcb\x90"},      // ा = aː
        {0x093F, "\xc9\xaa"},       // ि = ɪ
        {0x0940, "i\xcb\x90"},      // ी = iː
        {0x0941, "\xca\x8a"},       // ु = ʊ
        {0x0942, "u\xcb\x90"},      // ू = uː
        {0x0943, "\xc9\xbe\xc9\xaa"}, // ृ = ɾɪ
        {0x0947, "e\xcb\x90"},      // े = eː
        {0x0948, "\xc9\x99j"},      // ै = əj
        {0x094B, "o\xcb\x90"},      // ो = oː
        {0x094C, "\xc9\x99w"},      // ौ = əw
    };
    return map;
}

std::string multilingual::hindi_g2p(const std::string& text) {
    const auto& consonants = get_devanagari_consonants();
    const auto& vowels = get_devanagari_vowels();
    const auto& matras = get_devanagari_matras();

    auto chars = utf8_split(text);
    std::string ipa;
    bool prev_was_consonant = false; // track for inherent schwa

    for (size_t i = 0; i < chars.size(); i++) {
        uint32_t cp = utf8_codepoint(chars[i]);

        // Virama (halant) — suppresses inherent vowel
        if (cp == 0x094D) {
            prev_was_consonant = false; // no schwa for previous consonant
            continue;
        }

        // Anusvara (nasalization)
        if (cp == 0x0902) {
            ipa += "\xc9\xb4"; // ɴ (generic nasal, assimilates in speech)
            prev_was_consonant = false;
            continue;
        }

        // Visarga
        if (cp == 0x0903) {
            ipa += "\xc9\xa6"; // ɦ
            prev_was_consonant = false;
            continue;
        }

        // Chandrabindu (nasalization of vowel)
        if (cp == 0x0901) {
            ipa += "\xcc\x83"; // combining tilde (nasalize previous vowel)
            continue;
        }

        // Nukta — modifies previous consonant. Skip (handled in nukta consonant entries).
        if (cp == 0x093C) {
            continue;
        }

        // Check vowel signs (matras) first
        auto matra_it = matras.find(cp);
        if (matra_it != matras.end()) {
            prev_was_consonant = false;
            ipa += matra_it->second;
            continue;
        }

        // Independent vowels
        auto vowel_it = vowels.find(cp);
        if (vowel_it != vowels.end()) {
            if (prev_was_consonant) {
                // Previous consonant had no explicit vowel — add inherent schwa
                ipa += "\xc9\x99"; // ə
            }
            prev_was_consonant = false;
            ipa += vowel_it->second;
            continue;
        }

        // Consonants
        auto cons_it = consonants.find(cp);
        if (cons_it != consonants.end()) {
            if (prev_was_consonant) {
                // Previous consonant had no explicit vowel — add inherent schwa
                ipa += "\xc9\x99"; // ə
            }
            ipa += cons_it->second;
            prev_was_consonant = true;
            continue;
        }

        // Space
        if (cp == ' ') {
            if (prev_was_consonant) {
                // Word-final consonant: add schwa for open syllables
                // (Hindi schwa deletion is complex — we add it conservatively)
                ipa += "\xc9\x99"; // ə
            }
            prev_was_consonant = false;
            ipa += " ";
            continue;
        }

        // ASCII passthrough
        if ((cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z')
            || (cp >= '0' && cp <= '9')) {
            if (prev_was_consonant) {
                ipa += "\xc9\x99"; // ə
                prev_was_consonant = false;
            }
            ipa += chars[i];
            continue;
        }

        // Punctuation
        if (cp == ',' || cp == '.' || cp == '!' || cp == '?'
            || cp == ';' || cp == ':' || cp == '-'
            || cp == 0x0964 || cp == 0x0965) { // Devanagari danda / double danda
            if (prev_was_consonant) {
                ipa += "\xc9\x99"; // ə
                prev_was_consonant = false;
            }
            if (cp == 0x0964 || cp == 0x0965) {
                ipa += ".";
            } else {
                ipa += static_cast<char>(cp);
            }
            continue;
        }

        // Devanagari digits (0x0966-0x096F) — pass through as Arabic numerals
        if (cp >= 0x0966 && cp <= 0x096F) {
            if (prev_was_consonant) {
                ipa += "\xc9\x99";
                prev_was_consonant = false;
            }
            ipa += static_cast<char>('0' + (cp - 0x0966));
            continue;
        }

        // Skip unknown
        if (prev_was_consonant) {
            ipa += "\xc9\x99";
            prev_was_consonant = false;
        }
    }

    // Handle trailing consonant
    if (prev_was_consonant) {
        ipa += "\xc9\x99"; // ə
    }

    return kokoro_postprocess(ipa);
}

// ===========================================================================
// DICTIONARY-FIRST PHONEMIZERS
// ===========================================================================

// Split text into words and punctuation tokens for dictionary lookup.
// Returns vector of strings: each is either a word (letters), whitespace, or punctuation.
static std::vector<std::string> split_into_tokens(const std::string& text) {
    std::vector<std::string> tokens;
    auto chars = utf8_split(text);
    std::string current_word;

    auto flush_word = [&]() {
        if (!current_word.empty()) {
            tokens.push_back(current_word);
            current_word.clear();
        }
    };

    for (size_t i = 0; i < chars.size(); i++) {
        uint32_t cp = utf8_codepoint(chars[i]);

        // Whitespace
        if (cp == ' ' || cp == '\t' || cp == '\n' || cp == '\r') {
            flush_word();
            tokens.push_back(" ");
            continue;
        }

        // ASCII punctuation that should be passed through
        if (cp == ',' || cp == '.' || cp == '!' || cp == '?' ||
            cp == ';' || cp == ':' || cp == '-' || cp == '\'' || cp == '"') {
            flush_word();
            tokens.push_back(chars[i]);
            continue;
        }

        // Inverted punctuation (Spanish)
        if (cp == 0xBF || cp == 0xA1) {
            flush_word();
            tokens.push_back(chars[i]);
            continue;
        }

        // Japanese/Chinese punctuation
        if (cp == 0x3001 || cp == 0x3002 || cp == 0xFF0C || cp == 0xFF0E ||
            cp == 0xFF01 || cp == 0xFF1F) {
            flush_word();
            tokens.push_back(","); // normalize CJK punctuation to comma
            continue;
        }

        // Devanagari danda
        if (cp == 0x0964 || cp == 0x0965) {
            flush_word();
            tokens.push_back(".");
            continue;
        }

        // Everything else is part of a word
        current_word += chars[i];
    }
    flush_word();
    return tokens;
}

// Map punctuation token to IPA-compatible output.
static std::string punct_to_ipa(const std::string& tok) {
    if (tok == "," || tok == "." || tok == "!" || tok == "?" ||
        tok == ";" || tok == ":" || tok == "-" || tok == "'") {
        return tok;
    }
    return "";
}

// Lowercase a UTF-8 string (handles ASCII letters and common Latin accented chars).
static std::string utf8_to_lower(const std::string& s) {
    auto chars = utf8_split(s);
    std::string result;
    for (auto& ch : chars) {
        uint32_t cp = utf8_codepoint(ch);
        if (cp >= 'A' && cp <= 'Z') {
            result += static_cast<char>(cp + 32);
        } else if (cp >= 0xC0 && cp <= 0xD6) {
            result += utf8_encode(cp + 32);
        } else if (cp >= 0xD8 && cp <= 0xDE) {
            result += utf8_encode(cp + 32);
        } else {
            result += ch;
        }
    }
    return result;
}

// Check if a token is whitespace.
static bool is_ws_token(const std::string& tok) {
    for (char c : tok) {
        if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return false;
    }
    return !tok.empty();
}

// Check if a token is punctuation.
static bool is_punct_tok(const std::string& tok) {
    if (tok.empty()) return false;
    if (tok.size() == 1) {
        char c = tok[0];
        return c == ',' || c == '.' || c == '!' || c == '?' ||
               c == ';' || c == ':' || c == '-' || c == '\'' || c == '"';
    }
    uint32_t cp = utf8_codepoint(tok);
    return cp == 0xBF || cp == 0xA1;
}

/// Generic dictionary-first phonemizer.
/// Splits text into words, looks up each in dict, falls back to g2p_fn.
static std::string dict_first_phonemize(
    const std::string& text,
    const std::unordered_map<std::string, std::string>& dict,
    std::string (*g2p_fn)(const std::string&))
{
    auto tokens = split_into_tokens(text);
    std::string result;

    for (auto& tok : tokens) {
        if (is_ws_token(tok)) {
            result += " ";
            continue;
        }
        if (is_punct_tok(tok)) {
            auto mapped = punct_to_ipa(tok);
            if (!mapped.empty()) result += mapped;
            continue;
        }

        // Try dictionary lookup (lowercase)
        auto lower = utf8_to_lower(tok);
        auto it = dict.find(lower);
        if (it != dict.end() && !it->second.empty()) {
            result += kokoro_postprocess(it->second);
            continue;
        }

        // Fallback to rule-based G2P
        result += g2p_fn(tok);
    }

    return result;
}

std::string multilingual::french_phonemize(
    const std::string& text,
    const std::unordered_map<std::string, std::string>& dict)
{
    return dict_first_phonemize(text, dict, french_g2p);
}

std::string multilingual::spanish_phonemize(
    const std::string& text,
    const std::unordered_map<std::string, std::string>& dict)
{
    return dict_first_phonemize(text, dict, spanish_g2p);
}

std::string multilingual::italian_phonemize(
    const std::string& text,
    const std::unordered_map<std::string, std::string>& dict)
{
    return dict_first_phonemize(text, dict, italian_g2p);
}

std::string multilingual::portuguese_phonemize(
    const std::string& text,
    const std::unordered_map<std::string, std::string>& dict)
{
    return dict_first_phonemize(text, dict, portuguese_g2p);
}

std::string multilingual::hindi_phonemize(
    const std::string& text,
    const std::unordered_map<std::string, std::string>& dict)
{
    return dict_first_phonemize(text, dict, hindi_g2p);
}

std::string multilingual::japanese_phonemize(const std::string& text) {
    return japanese_g2p(text);
}

std::string multilingual::chinese_phonemize(const std::string& text) {
    return chinese_g2p(text);
}
