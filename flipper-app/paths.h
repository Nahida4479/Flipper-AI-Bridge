#pragma once

#include <storage/storage.h>

#define APP_ASSETS_DIR "/ext/apps_assets/ai_bridge"
#define APP_DATA_DIR   "/ext/apps_data/ai_bridge"
#define LEARNED_PATH   APP_DATA_DIR "/learned.txt"
#define SETTINGS_PATH  APP_DATA_DIR "/settings.txt"
#define META_PATH      APP_DATA_DIR "/chats.txt"
#define CHAT_PATH_FMT  APP_DATA_DIR "/chat%d.txt"
#define CHAT_MAX_BYTES (500 * 1024)
