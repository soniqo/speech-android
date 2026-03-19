#pragma once

#include <fstream>
#include <sstream>
#include <string>
#include <unordered_map>
#include <variant>
#include <vector>

/// Minimal JSON parser for our specific model config files.
/// Handles flat objects with string/int values and one level of nesting.
namespace json {

using Dict = std::unordered_map<std::string, std::string>;

inline std::string read_file(const std::string& path) {
    std::ifstream f(path);
    if (!f.is_open()) return "";
    std::ostringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

inline void skip_ws(const std::string& s, size_t& i) {
    while (i < s.size() && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++;
}

inline std::string parse_string(const std::string& s, size_t& i) {
    if (i >= s.size() || s[i] != '"') return "";
    i++; // skip opening quote
    std::string result;
    while (i < s.size() && s[i] != '"') {
        if (s[i] == '\\' && i + 1 < s.size()) {
            i++;
            switch (s[i]) {
                case '"': result += '"'; break;
                case '\\': result += '\\'; break;
                case 'n': result += '\n'; break;
                case 't': result += '\t'; break;
                case 'u': {
                    // Parse \uXXXX → UTF-8
                    if (i + 4 < s.size()) {
                        std::string hex = s.substr(i + 1, 4);
                        unsigned long cp = std::stoul(hex, nullptr, 16);
                        i += 4;
                        if (cp < 0x80) {
                            result += static_cast<char>(cp);
                        } else if (cp < 0x800) {
                            result += static_cast<char>(0xC0 | (cp >> 6));
                            result += static_cast<char>(0x80 | (cp & 0x3F));
                        } else {
                            result += static_cast<char>(0xE0 | (cp >> 12));
                            result += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
                            result += static_cast<char>(0x80 | (cp & 0x3F));
                        }
                    }
                    break;
                }
                default: result += s[i]; break;
            }
        } else {
            result += s[i];
        }
        i++;
    }
    if (i < s.size()) i++; // skip closing quote
    return result;
}

inline std::string parse_value_raw(const std::string& s, size_t& i) {
    skip_ws(s, i);
    if (i >= s.size()) return "";

    if (s[i] == '"') return parse_string(s, i);

    // Number, bool, null
    std::string val;
    while (i < s.size() && s[i] != ',' && s[i] != '}' && s[i] != ']'
           && s[i] != ' ' && s[i] != '\n' && s[i] != '\r') {
        val += s[i++];
    }
    return val;
}

/// Skip a JSON value (string, number, object, array)
inline void skip_value(const std::string& s, size_t& i) {
    skip_ws(s, i);
    if (i >= s.size()) return;
    if (s[i] == '"') { parse_string(s, i); return; }
    if (s[i] == '{') {
        int depth = 1; i++;
        while (i < s.size() && depth > 0) {
            if (s[i] == '{') { depth++; i++; }
            else if (s[i] == '}') { depth--; i++; }
            else if (s[i] == '"') { parse_string(s, i); }
            else { i++; }
        }
        return;
    }
    if (s[i] == '[') {
        int depth = 1; i++;
        while (i < s.size() && depth > 0) {
            if (s[i] == '[') { depth++; i++; }
            else if (s[i] == ']') { depth--; i++; }
            else if (s[i] == '"') { parse_string(s, i); }
            else { i++; }
        }
        return;
    }
    parse_value_raw(s, i);
}

/// Parse {"key": "value", ...} → map<string, string>
/// Works for string and integer values (ints stored as strings).
inline Dict parse_flat_object(const std::string& text) {
    Dict result;
    size_t i = 0;
    skip_ws(text, i);
    if (i >= text.size() || text[i] != '{') return result;
    i++;

    while (i < text.size()) {
        skip_ws(text, i);
        if (text[i] == '}') break;
        if (text[i] == ',') { i++; continue; }

        auto key = parse_string(text, i);
        skip_ws(text, i);
        if (i < text.size() && text[i] == ':') i++;
        skip_ws(text, i);

        if (i < text.size() && text[i] == '{') {
            // Nested object — skip it for flat parsing
            skip_value(text, i);
        } else {
            auto val = parse_value_raw(text, i);
            result[key] = val;
        }
    }
    return result;
}

/// Heteronym entry: either a simple string or POS-tagged map.
struct DictEntry {
    std::string simple;
    std::unordered_map<std::string, std::string> pos_map;  // empty if simple
    bool is_heteronym() const { return !pos_map.empty(); }
};

/// Parse pronunciation dictionary: {"word": "phonemes", "word2": {"VERB": "p1", "DEFAULT": "p2"}}
inline std::unordered_map<std::string, DictEntry> parse_dictionary(const std::string& text) {
    std::unordered_map<std::string, DictEntry> result;
    size_t i = 0;
    skip_ws(text, i);
    if (i >= text.size() || text[i] != '{') return result;
    i++;

    while (i < text.size()) {
        skip_ws(text, i);
        if (text[i] == '}') break;
        if (text[i] == ',') { i++; continue; }

        auto key = parse_string(text, i);
        skip_ws(text, i);
        if (i < text.size() && text[i] == ':') i++;
        skip_ws(text, i);

        DictEntry entry;
        if (i < text.size() && text[i] == '"') {
            entry.simple = parse_string(text, i);
        } else if (i < text.size() && text[i] == '{') {
            // Nested POS map
            i++; // skip {
            while (i < text.size()) {
                skip_ws(text, i);
                if (text[i] == '}') { i++; break; }
                if (text[i] == ',') { i++; continue; }
                auto pos = parse_string(text, i);
                skip_ws(text, i);
                if (i < text.size() && text[i] == ':') i++;
                skip_ws(text, i);
                if (i < text.size() && text[i] == 'n') {
                    // null value
                    skip_value(text, i);
                } else {
                    auto pron = parse_value_raw(text, i);
                    entry.pos_map[pos] = pron;
                }
            }
        } else {
            skip_value(text, i);
        }
        result[key] = std::move(entry);
    }
    return result;
}

/// Parse vocab_index.json: {"vocab": {"sym": id, ...}} or flat {"sym": id, ...}
inline std::unordered_map<std::string, int> parse_vocab_index(const std::string& text) {
    std::unordered_map<std::string, int> result;
    size_t i = 0;
    skip_ws(text, i);
    if (i >= text.size() || text[i] != '{') return result;
    i++;

    // Check if nested under "vocab" key
    size_t save = i;
    skip_ws(text, i);
    auto first_key = parse_string(text, i);
    skip_ws(text, i);
    if (i < text.size() && text[i] == ':') i++;
    skip_ws(text, i);

    size_t obj_start;
    if (first_key == "vocab" && i < text.size() && text[i] == '{') {
        obj_start = i + 1;
    } else {
        // Flat format — restart
        i = save;
        obj_start = i;
    }

    i = obj_start;
    while (i < text.size()) {
        skip_ws(text, i);
        if (text[i] == '}') break;
        if (text[i] == ',') { i++; continue; }

        auto sym = parse_string(text, i);
        skip_ws(text, i);
        if (i < text.size() && text[i] == ':') i++;
        auto val = parse_value_raw(text, i);

        try {
            result[sym] = std::stoi(val);
        } catch (...) {}
    }
    return result;
}

} // namespace json
