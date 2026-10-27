#pragma once

#include <storage/storage.h>

#define APP_ASSETS_DIR "/ext/apps_assets/claude_bridge"
#define APP_DATA_DIR   "/ext/apps_data/claude_bridge"
#define LEARNED_PATH   APP_DATA_DIR "/learned.txt"
#define SETTINGS_PATH  APP_DATA_DIR "/settings.txt"
#define META_PATH      APP_DATA_DIR "/chats.txt"
#define BT_KEYS_PATH   APP_DATA_DIR "/.bt_keys"
#define CHAT_PATH_FMT  APP_DATA_DIR "/chat%d.txt"
#define CHAT_MAX_BYTES (500 * 1024)
