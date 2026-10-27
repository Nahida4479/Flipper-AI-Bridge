#include "text_fold.h"
#include <stdbool.h>

uint32_t utf8_decode(const char** s) {
    const uint8_t* p = (const uint8_t*)*s;
    uint32_t cp;
    int extra;
    if(p[0] < 0x80) {
        cp = p[0];
        extra = 0;
    } else if((p[0] & 0xE0) == 0xC0) {
        cp = p[0] & 0x1F;
        extra = 1;
    } else if((p[0] & 0xF0) == 0xE0) {
        cp = p[0] & 0x0F;
        extra = 2;
    } else if((p[0] & 0xF8) == 0xF0) {
        cp = p[0] & 0x07;
        extra = 3;
    } else {
        *s += 1;
        return '?';
    }
    int i = 1;
    for(; i <= extra; i++) {
        if((p[i] & 0xC0) != 0x80) {
            *s += i;
            return '?';
        }
        cp = (cp << 6) | (p[i] & 0x3F);
    }
    *s += 1 + extra;
    return cp;
}

size_t utf8_encode(uint32_t cp, char* out) {
    if(cp < 0x80) {
        out[0] = (char)cp;
        return 1;
    }
    if(cp < 0x800) {
        out[0] = (char)(0xC0 | (cp >> 6));
        out[1] = (char)(0x80 | (cp & 0x3F));
        return 2;
    }
    if(cp < 0x10000) {
        out[0] = (char)(0xE0 | (cp >> 12));
        out[1] = (char)(0x80 | ((cp >> 6) & 0x3F));
        out[2] = (char)(0x80 | (cp & 0x3F));
        return 3;
    }
    out[0] = (char)(0xF0 | (cp >> 18));
    out[1] = (char)(0x80 | ((cp >> 12) & 0x3F));
    out[2] = (char)(0x80 | ((cp >> 6) & 0x3F));
    out[3] = (char)(0x80 | (cp & 0x3F));
    return 4;
}

uint32_t cp_lower(uint32_t cp) {
    if(cp >= 'A' && cp <= 'Z') return cp + 32;
    switch(cp) {
    case 0x104:
    case 0x106:
    case 0x118:
    case 0x141:
    case 0x143:
    case 0x15A:
    case 0x179:
    case 0x17B:
        return cp + 1;
    case 0xD3:
        return 0xF3;
    default:
        return cp;
    }
}

char cp_fold_char(uint32_t cp) {
    if(cp < 0x80) return (char)cp;
    switch(cp_lower(cp)) {
    case 0x105:
        return 'a';
    case 0x107:
        return 'c';
    case 0x119:
        return 'e';
    case 0x142:
        return 'l';
    case 0x144:
        return 'n';
    case 0xF3:
        return 'o';
    case 0x15B:
        return 's';
    case 0x17A:
    case 0x17C:
        return 'z';
    default:
        return 0;
    }
}

void text_fold(const char* in, char* out, size_t cap) {
    size_t n = 0;
    if(cap == 0) return;
    while(*in && n + 4 < cap) {
        uint32_t cp = utf8_decode(&in);
        if(cp < 0x80) {
            out[n++] = (char)cp;
            continue;
        }
        char f = cp_fold_char(cp);
        if(f) {
            bool upper = cp == 0x104 || cp == 0x106 || cp == 0x118 || cp == 0x141 ||
                         cp == 0x143 || cp == 0xD3 || cp == 0x15A || cp == 0x179 || cp == 0x17B;
            out[n++] = upper ? (char)(f - 32) : f;
            continue;
        }
        switch(cp) {
        case 0x2018:
        case 0x2019:
            out[n++] = '\'';
            break;
        case 0x201C:
        case 0x201D:
            out[n++] = '"';
            break;
        case 0x2013:
        case 0x2014:
            out[n++] = '-';
            break;
        case 0x2026:
            out[n++] = '.';
            out[n++] = '.';
            out[n++] = '.';
            break;
        case 0xA0:
            out[n++] = ' ';
            break;
        default:
            out[n++] = '?';
            break;
        }
    }
    out[n] = 0;
}

size_t utf8_prev_start(const char* s, size_t len) {
    if(len == 0) return 0;
    size_t i = len - 1;
    while(i > 0 && ((uint8_t)s[i] & 0xC0) == 0x80) i--;
    return i;
}
