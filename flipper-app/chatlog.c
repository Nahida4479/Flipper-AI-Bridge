#include "chatlog.h"
#include "text_fold.h"
#include <string.h>

ChatLog* chatlog_alloc(void) {
    ChatLog* log = malloc(sizeof(ChatLog));
    memset(log, 0, sizeof(ChatLog));
    return log;
}

static void replay_clear(ChatLog* log) {
    for(uint8_t i = 0; i < log->replay_count; i++) {
        free(log->replay_text[i]);
        log->replay_text[i] = NULL;
    }
    log->replay_count = 0;
}

void chatlog_free(ChatLog* log) {
    replay_clear(log);
    free(log);
}

void chatlog_clear(ChatLog* log) {
    replay_clear(log);
    log->count = 0;
    log->base_count = 0;
}

static void append_line(ChatLog* log, const char* s, size_t n) {
    if(log->count >= CHAT_MAX_LINES) {
        memmove(log->lines[0], log->lines[1], (CHAT_MAX_LINES - 1) * (CHAT_LINE_W + 1));
        log->count = CHAT_MAX_LINES - 1;
        if(log->base_count > 0) log->base_count--;
    }
    if(n > CHAT_LINE_W) n = CHAT_LINE_W;
    memcpy(log->lines[log->count], s, n);
    log->lines[log->count][n] = 0;
    log->count++;
}

static void wrap_into(ChatLog* log, const char* prefix_first, const char* prefix_next, const char* utf8) {
    size_t cap = strlen(utf8) + 8;
    char* folded = malloc(cap);
    text_fold(utf8, folded, cap);
    size_t plen = strlen(prefix_first);
    const char* p = folded;
    bool first = true;
    for(;;) {
        const char* prefix = first ? prefix_first : prefix_next;
        size_t room = CHAT_LINE_W - plen;
        while(*p == ' ') p++;
        if(*p == 0) {
            if(first) append_line(log, prefix, plen);
            break;
        }
        size_t take = 0;
        const char* nl = p;
        while(*nl && *nl != '\n' && take < room) {
            nl++;
            take++;
        }
        size_t cut = take;
        if(*nl && *nl != '\n' && *nl != ' ') {
            size_t back = take;
            while(back > 0 && p[back - 1] != ' ') back--;
            if(back > 0) cut = back;
        }
        char line[CHAT_LINE_W + 2];
        memcpy(line, prefix, plen);
        size_t body = cut;
        while(body > 0 && p[body - 1] == ' ') body--;
        memcpy(line + plen, p, body);
        append_line(log, line, plen + body);
        p += cut;
        if(*p == '\n') p++;
        first = false;
    }
    free(folded);
}

void chatlog_push(ChatLog* log, char role, const char* utf8) {
    log->count = log->base_count;
    if(role == 'U') {
        wrap_into(log, "> ", "  ", utf8);
    } else {
        wrap_into(log, "", "", utf8);
    }
    log->base_count = log->count;
}

void chatlog_set_pending(ChatLog* log, const char* utf8) {
    log->count = log->base_count;
    wrap_into(log, "", "", utf8);
}

void chatlog_commit(ChatLog* log) {
    log->base_count = log->count;
}

void chatlog_replay_add(ChatLog* log, char role, const char* utf8) {
    if(log->replay_count == REPLAY_MAX) {
        free(log->replay_text[0]);
        for(uint8_t i = 1; i < REPLAY_MAX; i++) {
            log->replay_text[i - 1] = log->replay_text[i];
            log->replay_role[i - 1] = log->replay_role[i];
        }
        log->replay_count--;
    }
    size_t len = strlen(utf8);
    if(len > REPLAY_TEXT) {
        len = REPLAY_TEXT;
        while(len > 0 && ((uint8_t)utf8[len] & 0xC0) == 0x80) len--;
    }
    char* copy = malloc(len + 1);
    memcpy(copy, utf8, len);
    copy[len] = 0;
    log->replay_text[log->replay_count] = copy;
    log->replay_role[log->replay_count] = role;
    log->replay_count++;
}
