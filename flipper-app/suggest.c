#include "suggest.h"
#include "text_fold.h"
#include "paths.h"
#include <string.h>

#define DICT_RECORD 20

typedef struct {
    char text[SUGGEST_WORD_LEN];
    uint16_t count;
} LearnedWord;

typedef struct {
    char text[SUGGEST_PHRASE_LEN];
    uint16_t count;
} LearnedPhrase;

struct Suggest {
    Storage* storage;
    SuggestLang lang;
    LearnedWord words[SUGGEST_MAX_WORDS];
    size_t word_count;
    LearnedPhrase phrases[SUGGEST_MAX_PHRASES];
    size_t phrase_count;
    bool dirty;
};

static void lower_utf8(const char* in, char* out, size_t cap) {
    size_t n = 0;
    while(*in) {
        uint32_t cp = cp_lower(utf8_decode(&in));
        char buf[4];
        size_t len = utf8_encode(cp, buf);
        if(n + len + 1 > cap) break;
        memcpy(out + n, buf, len);
        n += len;
    }
    out[n] = 0;
}

static bool is_word_char(uint32_t cp) {
    if(cp >= 'a' && cp <= 'z') return true;
    if(cp >= 'A' && cp <= 'Z') return true;
    if(cp >= '0' && cp <= '9') return false;
    if(cp == '\'') return true;
    return cp >= 0x80 && cp_fold_char(cp) != 0;
}

static void dict_path_in(Storage* storage, SuggestLang lang, char* out, size_t cap) {
    UNUSED(storage);
    snprintf(out, cap, "%s/dict/%s.dict", APP_ASSETS_DIR, lang == SuggestLangPl ? "pl" : "en");
}

static bool dict_lookup(
    Suggest* s,
    SuggestLang lang,
    const char* prefix,
    char* out,
    size_t out_cap) {
    char path[96];
    dict_path_in(s->storage, lang, path, sizeof(path));
    File* file = storage_file_alloc(s->storage);
    bool found = false;
    if(storage_file_open(file, path, FSAM_READ, FSOM_OPEN_EXISTING)) {
        uint64_t size = storage_file_size(file);
        int64_t total = (int64_t)(size / DICT_RECORD);
        size_t plen = strlen(prefix);
        uint8_t rec[DICT_RECORD];
        int64_t lo = 0;
        int64_t hi = total;
        while(lo < hi) {
            int64_t mid = (lo + hi) / 2;
            storage_file_seek(file, (uint32_t)(mid * DICT_RECORD), true);
            if(storage_file_read(file, rec, DICT_RECORD) != DICT_RECORD) break;
            if(strncmp((const char*)rec + 1, prefix, plen) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        uint8_t best_freq = 0;
        size_t best_len = 0;
        char best[DICT_RECORD];
        int scanned = 0;
        storage_file_seek(file, (uint32_t)(lo * DICT_RECORD), true);
        for(int64_t i = lo; i < total && scanned < 600; i++, scanned++) {
            if(storage_file_read(file, rec, DICT_RECORD) != DICT_RECORD) break;
            const char* w = (const char*)rec + 1;
            if(strncmp(w, prefix, plen) != 0) break;
            size_t wl = strlen(w);
            if(wl <= plen) continue;
            if(!found || rec[0] > best_freq || (rec[0] == best_freq && wl < best_len)) {
                found = true;
                best_freq = rec[0];
                best_len = wl;
                memcpy(best, w, wl + 1);
            }
        }
        if(found) {
            snprintf(out, out_cap, "%s", best + plen);
        }
    }
    storage_file_close(file);
    storage_file_free(file);
    return found;
}

Suggest* suggest_alloc(Storage* storage) {
    Suggest* s = malloc(sizeof(Suggest));
    memset(s, 0, sizeof(Suggest));
    s->storage = storage;
    File* file = storage_file_alloc(storage);
    if(storage_file_open(file, LEARNED_PATH, FSAM_READ, FSOM_OPEN_EXISTING)) {
        char line[SUGGEST_PHRASE_LEN + 16];
        size_t n = 0;
        uint8_t ch;
        while(storage_file_read(file, &ch, 1) == 1) {
            if(ch != '\n') {
                if(n < sizeof(line) - 1) line[n++] = (char)ch;
                continue;
            }
            line[n] = 0;
            n = 0;
            if(line[0] != 'W' && line[0] != 'P') continue;
            if(line[1] != '\t') continue;
            char* sep = strchr(line + 2, '\t');
            if(!sep) continue;
            *sep = 0;
            unsigned count = (unsigned)atoi(line + 2);
            const char* text = sep + 1;
            if(line[0] == 'W' && s->word_count < SUGGEST_MAX_WORDS && strlen(text) < SUGGEST_WORD_LEN) {
                strcpy(s->words[s->word_count].text, text);
                s->words[s->word_count].count = (uint16_t)count;
                s->word_count++;
            } else if(
                line[0] == 'P' && s->phrase_count < SUGGEST_MAX_PHRASES &&
                strlen(text) < SUGGEST_PHRASE_LEN) {
                strcpy(s->phrases[s->phrase_count].text, text);
                s->phrases[s->phrase_count].count = (uint16_t)count;
                s->phrase_count++;
            }
        }
    }
    storage_file_close(file);
    storage_file_free(file);
    return s;
}

void suggest_free(Suggest* s) {
    free(s);
}

void suggest_set_lang(Suggest* s, SuggestLang lang) {
    s->lang = lang;
}

bool suggest_dict_available(Suggest* s) {
    char path[96];
    dict_path_in(s->storage, SuggestLangEn, path, sizeof(path));
    return storage_file_exists(s->storage, path);
}

static bool last_token(const char* lower, char* tok, size_t cap) {
    size_t len = strlen(lower);
    size_t start = len;
    while(start > 0) {
        size_t p = utf8_prev_start(lower, start);
        const char* q = lower + p;
        uint32_t cp = utf8_decode(&q);
        if(!is_word_char(cp)) break;
        start = p;
    }
    if(start == len) return false;
    snprintf(tok, cap, "%s", lower + start);
    return true;
}

bool suggest_find(Suggest* s, const char* text, char* out, size_t out_cap) {
    out[0] = 0;
    size_t len = strlen(text);
    if(len == 0) return false;
    char lower[SUGGEST_PHRASE_LEN + 8];
    while(*text == ' ') text++;
    len = strlen(text);
    if(len > SUGGEST_PHRASE_LEN) {
        text += len - SUGGEST_PHRASE_LEN;
        while(((uint8_t)*text & 0xC0) == 0x80) text++;
    }
    lower_utf8(text, lower, sizeof(lower));
    size_t llen = strlen(lower);
    if(llen < 2) return false;

    if(lower[llen - 1] != ' ') {
        const LearnedPhrase* best_phrase = NULL;
        for(size_t i = 0; i < s->phrase_count; i++) {
            const LearnedPhrase* p = &s->phrases[i];
            if(p->count < 2) continue;
            if(strlen(p->text) <= llen) continue;
            if(strncmp(p->text, lower, llen) != 0) continue;
            if(!best_phrase || p->count > best_phrase->count) best_phrase = p;
        }
        if(best_phrase) {
            snprintf(out, out_cap, "%s", best_phrase->text + llen);
            return true;
        }
    }

    char tok[SUGGEST_WORD_LEN];
    if(lower[llen - 1] == ' ' || !last_token(lower, tok, sizeof(tok))) return false;
    size_t tlen = strlen(tok);
    if(tlen < 2) return false;

    const LearnedWord* best_word = NULL;
    for(size_t i = 0; i < s->word_count; i++) {
        const LearnedWord* w = &s->words[i];
        if(strlen(w->text) <= tlen) continue;
        if(strncmp(w->text, tok, tlen) != 0) continue;
        if(!best_word || w->count > best_word->count) best_word = w;
    }
    if(best_word && best_word->count >= 2) {
        snprintf(out, out_cap, "%s", best_word->text + tlen);
        return true;
    }

    char cand_en[SUGGEST_WORD_LEN];
    char cand_pl[SUGGEST_WORD_LEN];
    bool en = false;
    bool pl = false;
    if(s->lang != SuggestLangPl) en = dict_lookup(s, SuggestLangEn, tok, cand_en, sizeof(cand_en));
    if(s->lang != SuggestLangEn) pl = dict_lookup(s, SuggestLangPl, tok, cand_pl, sizeof(cand_pl));
    if(en && pl) {
        snprintf(out, out_cap, "%s", strlen(cand_pl) <= strlen(cand_en) ? cand_pl : cand_en);
        return true;
    }
    if(en || pl) {
        snprintf(out, out_cap, "%s", en ? cand_en : cand_pl);
        return true;
    }
    if(best_word) {
        snprintf(out, out_cap, "%s", best_word->text + tlen);
        return true;
    }
    return false;
}

static void bump_word(Suggest* s, const char* word) {
    for(size_t i = 0; i < s->word_count; i++) {
        if(strcmp(s->words[i].text, word) == 0) {
            if(s->words[i].count < 60000) s->words[i].count++;
            return;
        }
    }
    size_t slot;
    if(s->word_count < SUGGEST_MAX_WORDS) {
        slot = s->word_count++;
    } else {
        slot = 0;
        for(size_t i = 1; i < s->word_count; i++) {
            if(s->words[i].count < s->words[slot].count) slot = i;
        }
    }
    snprintf(s->words[slot].text, SUGGEST_WORD_LEN, "%s", word);
    s->words[slot].count = 1;
}

static void bump_phrase(Suggest* s, const char* phrase) {
    for(size_t i = 0; i < s->phrase_count; i++) {
        if(strcmp(s->phrases[i].text, phrase) == 0) {
            if(s->phrases[i].count < 60000) s->phrases[i].count++;
            return;
        }
    }
    size_t slot;
    if(s->phrase_count < SUGGEST_MAX_PHRASES) {
        slot = s->phrase_count++;
    } else {
        slot = 0;
        for(size_t i = 1; i < s->phrase_count; i++) {
            if(s->phrases[i].count < s->phrases[slot].count) slot = i;
        }
    }
    snprintf(s->phrases[slot].text, SUGGEST_PHRASE_LEN, "%s", phrase);
    s->phrases[slot].count = 1;
}

void suggest_learn(Suggest* s, const char* text) {
    char lower[SUGGEST_PHRASE_LEN * 3];
    lower_utf8(text, lower, sizeof(lower));

    char word[SUGGEST_WORD_LEN];
    size_t wn = 0;
    char sentence[SUGGEST_PHRASE_LEN];
    size_t sn = 0;
    bool sentence_ok = true;
    const char* p = lower;
    for(;;) {
        const char* before = p;
        uint32_t cp = *p ? utf8_decode(&p) : 0;
        size_t clen = (size_t)(p - before);
        bool end = cp == 0 || cp == '.' || cp == '!' || cp == '?';
        if(cp != 0 && is_word_char(cp)) {
            if(wn + clen < sizeof(word)) {
                memcpy(word + wn, before, clen);
                wn += clen;
            }
        } else {
            if(wn >= 2) {
                word[wn] = 0;
                bump_word(s, word);
            }
            wn = 0;
        }
        if(end) {
            while(sn > 0 && sentence[sn - 1] == ' ') sn--;
            sentence[sn] = 0;
            if(sentence_ok && sn >= 8 && strchr(sentence, ' ')) bump_phrase(s, sentence);
            sn = 0;
            sentence_ok = true;
            if(cp == 0) break;
            continue;
        }
        if(sn + clen < sizeof(sentence)) {
            if(!(cp == ' ' && sn == 0)) {
                memcpy(sentence + sn, before, clen);
                sn += clen;
            }
        } else {
            sentence_ok = false;
        }
    }
    s->dirty = true;
}

void suggest_save(Suggest* s) {
    if(!s->dirty) return;
    File* file = storage_file_alloc(s->storage);
    if(storage_file_open(file, LEARNED_PATH, FSAM_WRITE, FSOM_CREATE_ALWAYS)) {
        char line[SUGGEST_PHRASE_LEN + 16];
        for(size_t i = 0; i < s->word_count; i++) {
            int n = snprintf(line, sizeof(line), "W\t%u\t%s\n", s->words[i].count, s->words[i].text);
            storage_file_write(file, line, (uint16_t)n);
        }
        for(size_t i = 0; i < s->phrase_count; i++) {
            int n =
                snprintf(line, sizeof(line), "P\t%u\t%s\n", s->phrases[i].count, s->phrases[i].text);
            storage_file_write(file, line, (uint16_t)n);
        }
    }
    storage_file_close(file);
    storage_file_free(file);
    s->dirty = false;
}
