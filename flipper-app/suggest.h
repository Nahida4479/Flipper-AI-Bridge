#pragma once

#include <furi.h>
#include <storage/storage.h>

#define SUGGEST_MAX_WORDS   300
#define SUGGEST_MAX_PHRASES 80
#define SUGGEST_WORD_LEN    24
#define SUGGEST_PHRASE_LEN  96

typedef enum {
    SuggestLangEn = 0,
    SuggestLangPl = 1,
    SuggestLangBoth = 2,
} SuggestLang;

typedef struct Suggest Suggest;

Suggest* suggest_alloc(Storage* storage);
void suggest_free(Suggest* s);
void suggest_set_lang(Suggest* s, SuggestLang lang);
bool suggest_dict_available(Suggest* s);
bool suggest_find(Suggest* s, const char* text, char* out, size_t out_cap);
void suggest_learn(Suggest* s, const char* text);
void suggest_save(Suggest* s);
