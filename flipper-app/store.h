#pragma once

#include <furi.h>
#include <storage/storage.h>
#include "chatlog.h"

typedef struct {
    Storage* storage;
    uint8_t lang;
    int active;
    uint32_t seq[2];
    uint32_t next_seq;
} Store;

Store* store_alloc(void);
void store_free(Store* st);
void store_save_settings(Store* st);
bool store_has_chat(Store* st);
void store_new_chat(Store* st);
void store_append(Store* st, char role, const char* text);
void store_load_chat(Store* st, ChatLog* log);
