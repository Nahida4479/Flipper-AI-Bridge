#include "store.h"
#include "paths.h"
#include "text_fold.h"
#include <string.h>
#include <stdio.h>

#define TAIL_BYTES 10240

static void chat_path(int slot, char* out, size_t cap) {
    snprintf(out, cap, CHAT_PATH_FMT, slot);
}

static void save_meta(Store* st) {
    File* file = storage_file_alloc(st->storage);
    if(storage_file_open(file, META_PATH, FSAM_WRITE, FSOM_CREATE_ALWAYS)) {
        char buf[96];
        int n = snprintf(
            buf,
            sizeof(buf),
            "active=%d\nseq0=%lu\nseq1=%lu\nnext=%lu\n",
            st->active,
            (unsigned long)st->seq[0],
            (unsigned long)st->seq[1],
            (unsigned long)st->next_seq);
        storage_file_write(file, buf, (uint16_t)n);
    }
    storage_file_close(file);
    storage_file_free(file);
}

static bool read_small(Storage* storage, const char* path, char* buf, size_t cap) {
    File* file = storage_file_alloc(storage);
    bool ok = false;
    if(storage_file_open(file, path, FSAM_READ, FSOM_OPEN_EXISTING)) {
        size_t n = storage_file_read(file, buf, (uint16_t)(cap - 1));
        buf[n] = 0;
        ok = true;
    }
    storage_file_close(file);
    storage_file_free(file);
    return ok;
}

Store* store_alloc(void) {
    Store* st = malloc(sizeof(Store));
    memset(st, 0, sizeof(Store));
    st->storage = furi_record_open(RECORD_STORAGE);
    st->next_seq = 1;
    st->active = -1;
    st->lang = 2;
    storage_simply_mkdir(st->storage, "/ext/apps_data");
    storage_common_migrate(st->storage, "/ext/apps_data/claude_bridge", APP_DATA_DIR);
    storage_simply_mkdir(st->storage, APP_DATA_DIR);

    char buf[160];
    if(read_small(st->storage, SETTINGS_PATH, buf, sizeof(buf))) {
        const char* p = strstr(buf, "lang=");
        if(p) {
            int v = atoi(p + 5);
            if(v >= 0 && v <= 2) st->lang = (uint8_t)v;
        }
    }
    if(read_small(st->storage, META_PATH, buf, sizeof(buf))) {
        const char* p;
        if((p = strstr(buf, "active="))) st->active = atoi(p + 7);
        if((p = strstr(buf, "seq0="))) st->seq[0] = (uint32_t)atoi(p + 5);
        if((p = strstr(buf, "seq1="))) st->seq[1] = (uint32_t)atoi(p + 5);
        if((p = strstr(buf, "next="))) st->next_seq = (uint32_t)atoi(p + 5);
        if(st->active < 0 || st->active > 1 || st->seq[st->active] == 0) st->active = -1;
    }
    return st;
}

void store_free(Store* st) {
    furi_record_close(RECORD_STORAGE);
    free(st);
}

void store_save_settings(Store* st) {
    File* file = storage_file_alloc(st->storage);
    if(storage_file_open(file, SETTINGS_PATH, FSAM_WRITE, FSOM_CREATE_ALWAYS)) {
        char buf[24];
        int n = snprintf(buf, sizeof(buf), "lang=%d\n", st->lang);
        storage_file_write(file, buf, (uint16_t)n);
    }
    storage_file_close(file);
    storage_file_free(file);
}

bool store_has_chat(Store* st) {
    return st->active >= 0;
}

void store_new_chat(Store* st) {
    int slot;
    if(st->seq[0] == 0) {
        slot = 0;
    } else if(st->seq[1] == 0) {
        slot = 1;
    } else {
        slot = st->seq[0] < st->seq[1] ? 0 : 1;
    }
    char path[64];
    chat_path(slot, path, sizeof(path));
    storage_simply_remove(st->storage, path);
    st->seq[slot] = st->next_seq++;
    st->active = slot;
    save_meta(st);
}

static void trim_file(Store* st, const char* path) {
    char tmp[72];
    snprintf(tmp, sizeof(tmp), "%s.tmp", path);
    File* in = storage_file_alloc(st->storage);
    File* out = storage_file_alloc(st->storage);
    if(storage_file_open(in, path, FSAM_READ, FSOM_OPEN_EXISTING) &&
       storage_file_open(out, tmp, FSAM_WRITE, FSOM_CREATE_ALWAYS)) {
        uint64_t size = storage_file_size(in);
        uint32_t start = (uint32_t)(size - CHAT_MAX_BYTES / 2);
        storage_file_seek(in, start, true);
        uint8_t buf[256];
        bool skipping = true;
        size_t n;
        while((n = storage_file_read(in, buf, sizeof(buf))) > 0) {
            size_t off = 0;
            if(skipping) {
                while(off < n && buf[off] != '\n') off++;
                if(off < n) {
                    off++;
                    skipping = false;
                }
            }
            if(off < n) storage_file_write(out, buf + off, (uint16_t)(n - off));
        }
        storage_file_close(in);
        storage_file_close(out);
        storage_simply_remove(st->storage, path);
        storage_common_rename(st->storage, tmp, path);
    } else {
        storage_file_close(in);
        storage_file_close(out);
    }
    storage_file_free(in);
    storage_file_free(out);
}

void store_append(Store* st, char role, const char* text) {
    if(st->active < 0) store_new_chat(st);
    char path[64];
    chat_path(st->active, path, sizeof(path));
    FuriString* line = furi_string_alloc();
    furi_string_push_back(line, role);
    furi_string_push_back(line, '\t');
    for(const char* p = text; *p; p++) {
        if(*p == '\n') {
            furi_string_cat(line, "\\n");
        } else if(*p == '\\') {
            furi_string_cat(line, "\\\\");
        } else if(*p != '\r') {
            furi_string_push_back(line, *p);
        }
    }
    furi_string_push_back(line, '\n');
    File* file = storage_file_alloc(st->storage);
    uint64_t size = 0;
    if(storage_file_open(file, path, FSAM_WRITE, FSOM_OPEN_APPEND)) {
        storage_file_write(file, furi_string_get_cstr(line), (uint16_t)furi_string_size(line));
        size = storage_file_size(file);
    }
    storage_file_close(file);
    storage_file_free(file);
    furi_string_free(line);
    if(size > CHAT_MAX_BYTES) trim_file(st, path);
}

void store_load_chat(Store* st, ChatLog* log) {
    chatlog_clear(log);
    if(st->active < 0) return;
    char path[64];
    chat_path(st->active, path, sizeof(path));
    File* file = storage_file_alloc(st->storage);
    if(storage_file_open(file, path, FSAM_READ, FSOM_OPEN_EXISTING)) {
        uint64_t size = storage_file_size(file);
        uint32_t start = size > TAIL_BYTES ? (uint32_t)(size - TAIL_BYTES) : 0;
        storage_file_seek(file, start, true);
        char* buf = malloc(TAIL_BYTES + 1);
        size_t n = storage_file_read(file, buf, TAIL_BYTES);
        buf[n] = 0;
        char* p = buf;
        if(start > 0) {
            char* nl = strchr(p, '\n');
            p = nl ? nl + 1 : buf + n;
        }
        FuriString* text = furi_string_alloc();
        while(*p) {
            char* nl = strchr(p, '\n');
            if(!nl) break;
            *nl = 0;
            if(p[0] && p[1] == '\t') {
                furi_string_reset(text);
                for(const char* q = p + 2; *q; q++) {
                    if(*q == '\\' && q[1] == 'n') {
                        furi_string_push_back(text, '\n');
                        q++;
                    } else if(*q == '\\' && q[1] == '\\') {
                        furi_string_push_back(text, '\\');
                        q++;
                    } else {
                        furi_string_push_back(text, *q);
                    }
                }
                chatlog_push(log, p[0], furi_string_get_cstr(text));
                chatlog_replay_add(log, p[0], furi_string_get_cstr(text));
            }
            p = nl + 1;
        }
        furi_string_free(text);
        free(buf);
    }
    storage_file_close(file);
    storage_file_free(file);
}

int store_chat_count(Store* st) {
    return (st->seq[0] > 0 ? 1 : 0) + (st->seq[1] > 0 ? 1 : 0);
}

bool store_slot_used(Store* st, int slot) {
    return slot >= 0 && slot <= 1 && st->seq[slot] > 0;
}

void store_open_slot(Store* st, int slot) {
    if(!store_slot_used(st, slot)) return;
    st->active = slot;
    st->seq[slot] = st->next_seq++;
    save_meta(st);
}

void store_last_question(Store* st, int slot, char* out, size_t cap) {
    snprintf(out, cap, "(empty chat)");
    if(!store_slot_used(st, slot)) return;
    char path[64];
    chat_path(slot, path, sizeof(path));
    File* file = storage_file_alloc(st->storage);
    if(storage_file_open(file, path, FSAM_READ, FSOM_OPEN_EXISTING)) {
        uint64_t size = storage_file_size(file);
        const uint32_t tail = 6144;
        uint32_t start = size > tail ? (uint32_t)(size - tail) : 0;
        storage_file_seek(file, start, true);
        char* buf = malloc(tail + 1);
        size_t n = storage_file_read(file, buf, tail);
        buf[n] = 0;
        char* p = buf;
        if(start > 0) {
            char* nl = strchr(p, '\n');
            p = nl ? nl + 1 : buf + n;
        }
        char* last = NULL;
        while(*p) {
            char* nl = strchr(p, '\n');
            if(!nl) break;
            *nl = 0;
            if(p[0] == 'U' && p[1] == '\t') last = p + 2;
            p = nl + 1;
        }
        if(last) {
            char* raw = malloc(strlen(last) + 4);
            size_t r = 0;
            for(const char* q = last; *q; q++) {
                if(*q == '\\' && q[1] == 'n') {
                    raw[r++] = ' ';
                    q++;
                } else if(*q == '\\' && q[1] == '\\') {
                    raw[r++] = '\\';
                    q++;
                } else {
                    raw[r++] = *q;
                }
            }
            raw[r] = 0;
            char* folded = malloc(r + 8);
            text_fold(raw, folded, r + 8);
            size_t flen = strlen(folded);
            const size_t max = 17;
            if(flen > max) {
                folded[max] = 0;
                snprintf(out, cap, "%s...", folded);
            } else if(flen > 0) {
                snprintf(out, cap, "%s", folded);
            }
            free(folded);
            free(raw);
        }
        free(buf);
    }
    storage_file_close(file);
    storage_file_free(file);
}
