#pragma once

#include <furi.h>

#define CHAT_LINE_W    20
#define CHAT_MAX_LINES 220
#define REPLAY_MAX     8
#define REPLAY_TEXT    600

typedef struct {
    char lines[CHAT_MAX_LINES][CHAT_LINE_W + 1];
    uint16_t count;
    uint16_t base_count;
    char replay_role[REPLAY_MAX];
    char* replay_text[REPLAY_MAX];
    uint8_t replay_count;
} ChatLog;

ChatLog* chatlog_alloc(void);
void chatlog_free(ChatLog* log);
void chatlog_clear(ChatLog* log);
void chatlog_push(ChatLog* log, char role, const char* utf8);
void chatlog_set_pending(ChatLog* log, const char* utf8);
void chatlog_commit(ChatLog* log);
void chatlog_replay_add(ChatLog* log, char role, const char* utf8);
