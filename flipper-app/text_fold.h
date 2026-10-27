#pragma once

#include <stddef.h>
#include <stdint.h>

uint32_t utf8_decode(const char** s);
size_t utf8_encode(uint32_t cp, char* out);
uint32_t cp_lower(uint32_t cp);
void text_fold(const char* in, char* out, size_t cap);
char cp_fold_char(uint32_t cp);
size_t utf8_prev_start(const char* s, size_t len);
