#include "dictpack.h"
#include "paths.h"
#include <string.h>

#define PACK_MAGIC "CBDICT01"
#define FOOTER_SIZE 16
#define FAP_NAME    "claude_bridge.fap"

static bool both_present(Storage* storage) {
    return storage_file_exists(storage, DICT_DATA_DIR "/en.dict") &&
           storage_file_exists(storage, DICT_DATA_DIR "/pl.dict");
}

static bool find_fap(Storage* storage, FuriString* path) {
    furi_string_set(path, "/ext/apps/" FAP_NAME);
    if(storage_file_exists(storage, furi_string_get_cstr(path))) return true;
    File* dir = storage_file_alloc(storage);
    bool found = false;
    if(storage_dir_open(dir, "/ext/apps")) {
        FileInfo info;
        char name[64];
        while(!found && storage_dir_read(dir, &info, name, sizeof(name))) {
            if(!file_info_is_dir(&info)) continue;
            furi_string_printf(path, "/ext/apps/%s/" FAP_NAME, name);
            found = storage_file_exists(storage, furi_string_get_cstr(path));
        }
    }
    storage_dir_close(dir);
    storage_file_free(dir);
    return found;
}

static bool copy_range(File* in, uint32_t offset, uint32_t len, Storage* storage, const char* out_path) {
    File* out = storage_file_alloc(storage);
    bool ok = false;
    if(storage_file_open(out, out_path, FSAM_WRITE, FSOM_CREATE_ALWAYS)) {
        ok = storage_file_seek(in, offset, true);
        uint8_t buf[256];
        uint32_t left = len;
        while(ok && left > 0) {
            uint16_t n = left > sizeof(buf) ? (uint16_t)sizeof(buf) : (uint16_t)left;
            if(storage_file_read(in, buf, n) != n || storage_file_write(out, buf, n) != n) ok = false;
            left -= n;
        }
    }
    storage_file_close(out);
    storage_file_free(out);
    if(!ok) storage_simply_remove(storage, out_path);
    return ok;
}

static bool extract_from_fap(Storage* storage, const char* fap_path) {
    File* in = storage_file_alloc(storage);
    bool ok = false;
    if(storage_file_open(in, fap_path, FSAM_READ, FSOM_OPEN_EXISTING)) {
        uint64_t size = storage_file_size(in);
        uint8_t footer[FOOTER_SIZE];
        if(size > FOOTER_SIZE && storage_file_seek(in, (uint32_t)(size - FOOTER_SIZE), true) &&
           storage_file_read(in, footer, FOOTER_SIZE) == FOOTER_SIZE &&
           memcmp(footer, PACK_MAGIC, 8) == 0) {
            uint32_t en_len;
            uint32_t pl_len;
            memcpy(&en_len, footer + 8, 4);
            memcpy(&pl_len, footer + 12, 4);
            if((uint64_t)en_len + pl_len + FOOTER_SIZE <= size) {
                uint32_t en_off = (uint32_t)(size - FOOTER_SIZE - pl_len - en_len);
                storage_simply_mkdir(storage, DICT_DATA_DIR);
                ok = copy_range(in, en_off, en_len, storage, DICT_DATA_DIR "/en.dict") &&
                     copy_range(in, en_off + en_len, pl_len, storage, DICT_DATA_DIR "/pl.dict");
            }
        }
    }
    storage_file_close(in);
    storage_file_free(in);
    return ok;
}

bool dict_ensure(Storage* storage) {
    if(both_present(storage)) return true;
    FuriString* path = furi_string_alloc();
    bool ok = false;
    if(find_fap(storage, path)) ok = extract_from_fap(storage, furi_string_get_cstr(path));
    furi_string_free(path);
    return ok && both_present(storage);
}
