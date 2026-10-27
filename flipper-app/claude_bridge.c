#include <furi.h>
#include <gui/gui.h>
#include <gui/view.h>
#include <gui/view_dispatcher.h>
#include <gui/modules/variable_item_list.h>
#include <gui/modules/widget.h>
#include <gui/modules/dialog_ex.h>
#include <string.h>
#include <stdio.h>

#include "chatlog.h"
#include "link.h"
#include "paths.h"
#include "store.h"
#include "suggest.h"
#include "text_fold.h"

#define INPUT_MAX   160
#define SUGGEST_MAX 64
#define BUSY_TIMEOUT_TICKS 240

typedef enum {
    ViewMenu,
    ViewChat,
    ViewSettings,
    ViewInfo,
    ViewConfirm,
} ViewId;

typedef enum {
    MenuChat,
    MenuNewChat,
    MenuSettings,
    MenuInfo,
} MenuItem;

typedef enum {
    EvtLink = 1,
    EvtSend,
    EvtMenu,
} AppEvent;

typedef struct {
    ChatLog* log;
    char input[INPUT_MAX + 4];
    char suggestion[SUGGEST_MAX];
    int8_t row;
    int8_t col;
    uint8_t layer;
    uint16_t scroll;
    bool busy;
    bool dict_ok;
    LinkKind link;
    char notice[28];
} ChatModel;

typedef struct {
    uint8_t selected;
    bool ready;
    bool starting;
    LinkKind kind;
} MenuModel;

typedef struct {
    Gui* gui;
    ViewDispatcher* dispatcher;
    View* menu_view;
    FuriThread* init_thread;
    uint8_t ping_ticks;
    View* chat_view;
    VariableItemList* settings;
    Widget* info;
    DialogEx* confirm;
    Store* store;
    Suggest* suggest;
    ChatLog* log;
    Link* link;
    FuriString* answer;
    ViewId current;
    bool synced;
    bool busy;
    uint16_t busy_ticks;
} App;

static const uint16_t KEYS[3][3][10] = {
    {
        {'q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p'},
        {'a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l', '\''},
        {'z', 'x', 'c', 'v', 'b', 'n', 'm', ',', '.', '?'},
    },
    {
        {'Q', 'W', 'E', 'R', 'T', 'Y', 'U', 'I', 'O', 'P'},
        {'A', 'S', 'D', 'F', 'G', 'H', 'J', 'K', 'L', '"'},
        {'Z', 'X', 'C', 'V', 'B', 'N', 'M', ';', ':', '!'},
    },
    {
        {0x105, 0x107, 0x119, 0x142, 0x144, 0xF3, 0x15B, 0x17A, 0x17C, '-'},
        {'1', '2', '3', '4', '5', '6', '7', '8', '9', '0'},
        {'@', '#', '&', '(', ')', '/', '+', '*', '=', '_'},
    },
};

static const char* const LAYER_LABEL[3] = {"abc", "ABC", "PL1"};
static const uint8_t ACTION_X[6] = {0, 24, 46, 86, 108, 128};
static const char* const LANG_NAMES[3] = {"English", "Polish", "Both"};
static const char* const LANG_CODES[3] = {"en", "pl", "both"};

static void draw_accent(Canvas* canvas, uint32_t cp, int gx, int by) {
    switch(cp) {
    case 0x105:
    case 0x119:
        canvas_draw_dot(canvas, gx + 4, by + 1);
        break;
    case 0x107:
    case 0x144:
    case 0xF3:
    case 0x15B:
    case 0x17A:
        canvas_draw_dot(canvas, gx + 3, by - 6);
        canvas_draw_dot(canvas, gx + 2, by - 5);
        break;
    case 0x142:
        canvas_draw_line(canvas, gx, by - 1, gx + 3, by - 4);
        break;
    case 0x17C:
        canvas_draw_dot(canvas, gx + 2, by - 6);
        break;
    default:
        break;
    }
}

static void draw_cell(Canvas* canvas, int x, int top, int w, bool selected) {
    if(selected) {
        canvas_draw_box(canvas, x, top, w, 9);
        canvas_set_color(canvas, ColorWhite);
    }
}

static void draw_label(Canvas* canvas, int x, int top, int w, const char* text) {
    int tw = canvas_string_width(canvas, text);
    canvas_draw_str(canvas, x + (w - tw) / 2, top + 7, text);
}

static void draw_key(Canvas* canvas, int x, int top, int w, uint32_t cp, bool selected) {
    draw_cell(canvas, x, top, w, selected);
    char s[2] = {cp_fold_char(cp), 0};
    int tw = canvas_string_width(canvas, s);
    int gx = x + (w - tw) / 2;
    canvas_draw_str(canvas, gx, top + 7, s);
    if(cp >= 0x80) draw_accent(canvas, cp, gx, top + 7);
    canvas_set_color(canvas, ColorBlack);
}

static void draw_input(Canvas* canvas, ChatModel* m) {
    char folded[INPUT_MAX + 8];
    char sug[SUGGEST_MAX + 4];
    text_fold(m->input, folded, sizeof(folded));
    text_fold(m->suggestion, sug, sizeof(sug));
    const char* start = folded;
    int sug_w = m->suggestion[0] ? canvas_string_width(canvas, sug) + 2 : 0;
    while(*start && canvas_string_width(canvas, start) + sug_w + 8 > 126) start++;
    int x = 1;
    canvas_draw_str(canvas, x, 25, start);
    x += canvas_string_width(canvas, start);
    if(m->suggestion[0]) {
        canvas_draw_box(canvas, x, 18, sug_w, 9);
        canvas_set_color(canvas, ColorWhite);
        canvas_draw_str(canvas, x + 1, 25, sug);
        canvas_set_color(canvas, ColorBlack);
    } else {
        canvas_draw_str(canvas, x, 25, "_");
    }
}

static void draw_strip(Canvas* canvas, ChatModel* m) {
    ChatLog* log = m->log;
    canvas_set_font(canvas, FontSecondary);
    if(log->count == 0) {
        char line[32];
        const char* kind = m->link == LinkKindUsb ? "USB" : m->link == LinkKindBle ? "Bluetooth" :
                                                                                       "none";
        snprintf(line, sizeof(line), "Link: %s", kind);
        canvas_draw_str(canvas, 1, 7, line);
        canvas_draw_str(canvas, 1, 15, m->notice[0] ? m->notice : "Type a message");
    } else {
        int total = log->count;
        int last = total - 1 - (int)m->scroll;
        for(int i = 0; i < 2; i++) {
            int idx = last - 1 + i;
            if(idx >= 0 && idx < total) canvas_draw_str(canvas, 1, 7 + i * 8, log->lines[idx]);
        }
        if(m->busy && m->scroll == 0 && m->notice[0]) {
            canvas_draw_str(canvas, 1, 15, m->notice);
        }
        if(last - 1 > 0) canvas_draw_str(canvas, 122, 7, "^");
        if(m->scroll > 0) canvas_draw_str(canvas, 122, 15, "v");
    }
    if(m->row < 0) canvas_draw_frame(canvas, 0, 0, 128, 18);
}

static void chat_draw(Canvas* canvas, void* model) {
    ChatModel* m = model;
    canvas_clear(canvas);
    canvas_set_color(canvas, ColorBlack);
    draw_strip(canvas, m);
    canvas_draw_line(canvas, 0, 18, 127, 18);
    draw_input(canvas, m);
    canvas_draw_line(canvas, 0, 27, 127, 27);
    canvas_set_font(canvas, FontSecondary);
    for(int r = 0; r < 3; r++) {
        for(int c = 0; c < 10; c++) {
            draw_key(canvas, 4 + c * 12, 28 + r * 9, 12, KEYS[m->layer][r][c], m->row == r && m->col == c);
        }
    }
    for(int c = 0; c < 5; c++) {
        int x = ACTION_X[c];
        int w = ACTION_X[c + 1] - x;
        bool sel = m->row == 3 && m->col == c;
        draw_cell(canvas, x, 55, w, sel);
        switch(c) {
        case 0:
            draw_label(canvas, x, 55, w, LAYER_LABEL[m->layer]);
            break;
        case 1:
            draw_label(canvas, x, 55, w, "Del");
            break;
        case 2:
            draw_label(canvas, x, 55, w, "Space");
            break;
        case 3:
            draw_label(canvas, x, 55, w, m->suggestion[0] ? "Tab" : "-");
            break;
        default:
            draw_label(canvas, x, 55, w, m->busy ? "..." : "Send");
            break;
        }
        canvas_set_color(canvas, ColorBlack);
    }
}

static void refresh_suggestion(App* app, ChatModel* m) {
    char out[SUGGEST_MAX];
    if(suggest_find(app->suggest, m->input, out, sizeof(out))) {
        snprintf(m->suggestion, sizeof(m->suggestion), "%s", out);
    } else {
        m->suggestion[0] = 0;
    }
}

static void input_append(App* app, ChatModel* m, uint32_t cp) {
    size_t len = strlen(m->input);
    char enc[4];
    size_t n = utf8_encode(cp, enc);
    if(len + n > INPUT_MAX) return;
    memcpy(m->input + len, enc, n);
    m->input[len + n] = 0;
    if(m->layer == 1) m->layer = 0;
    refresh_suggestion(app, m);
}

static void input_backspace(App* app, ChatModel* m) {
    size_t len = strlen(m->input);
    if(len == 0) return;
    m->input[utf8_prev_start(m->input, len)] = 0;
    refresh_suggestion(app, m);
}

static void accept_suggestion(App* app, ChatModel* m) {
    if(!m->suggestion[0]) return;
    size_t len = strlen(m->input);
    size_t sl = strlen(m->suggestion);
    if(len + sl + 1 > INPUT_MAX) return;
    memcpy(m->input + len, m->suggestion, sl);
    m->input[len + sl] = ' ';
    m->input[len + sl + 1] = 0;
    m->suggestion[0] = 0;
    m->layer = 0;
    refresh_suggestion(app, m);
}

static void move_cursor(ChatModel* m, InputKey key) {
    int maxcol = m->row == 3 ? 4 : 9;
    switch(key) {
    case InputKeyLeft:
        if(m->row >= 0) m->col = m->col == 0 ? maxcol : m->col - 1;
        break;
    case InputKeyRight:
        if(m->row >= 0) m->col = m->col == maxcol ? 0 : m->col + 1;
        break;
    case InputKeyUp:
        if(m->row < 0) {
            if(m->log->count > 2 && m->scroll < m->log->count - 2) m->scroll++;
        } else if(m->row == 0) {
            m->row = -1;
        } else {
            m->row--;
            if(m->row == 3) m->col = m->col > 4 ? 4 : m->col;
        }
        break;
    case InputKeyDown:
        if(m->row < 0) {
            if(m->scroll > 0) {
                m->scroll--;
            } else {
                m->row = 0;
            }
        } else if(m->row < 3) {
            m->row++;
            if(m->row == 3) m->col = m->col * 5 / 10;
        }
        break;
    default:
        break;
    }
}

static bool press_key(App* app, ChatModel* m) {
    if(m->row < 0) return false;
    if(m->row < 3) {
        input_append(app, m, KEYS[m->layer][m->row][m->col]);
        return false;
    }
    switch(m->col) {
    case 0:
        m->layer = (m->layer + 1) % 3;
        break;
    case 1:
        input_backspace(app, m);
        break;
    case 2:
        input_append(app, m, ' ');
        break;
    case 3:
        accept_suggestion(app, m);
        break;
    default:
        return !m->busy && m->input[0] != 0;
    }
    return false;
}

static bool chat_input(InputEvent* event, void* context) {
    App* app = context;
    bool send = false;
    bool consumed = true;
    if(event->type == InputTypeLong && event->key == InputKeyBack) {
        view_dispatcher_send_custom_event(app->dispatcher, EvtMenu);
        return true;
    }
    if(event->type == InputTypeLong && event->key == InputKeyOk) {
        with_view_model(app->chat_view, ChatModel * m, { accept_suggestion(app, m); }, true);
        return true;
    }
    if(event->type != InputTypeShort && event->type != InputTypeRepeat) return true;
    if(event->key == InputKeyBack) {
        if(event->type != InputTypeShort) return true;
        bool empty = false;
        with_view_model(
            app->chat_view,
            ChatModel * m,
            {
                empty = m->input[0] == 0;
                if(!empty) input_backspace(app, m);
            },
            true);
        return !empty ? true : false;
    }
    if(event->key == InputKeyOk) {
        if(event->type != InputTypeShort) return true;
        with_view_model(app->chat_view, ChatModel * m, { send = press_key(app, m); }, true);
        if(send) view_dispatcher_send_custom_event(app->dispatcher, EvtSend);
        return consumed;
    }
    with_view_model(app->chat_view, ChatModel * m, { move_cursor(m, event->key); }, true);
    return consumed;
}

static void sync_menu(App* app);

static void sync_link_state(App* app) {
    LinkKind kind = link_kind(app->link);
    with_view_model(app->chat_view, ChatModel * m, { m->link = kind; }, true);
    sync_menu(app);
}

static void set_notice(App* app, const char* text) {
    with_view_model(app->chat_view, ChatModel * m, { snprintf(m->notice, sizeof(m->notice), "%s", text); }, true);
}

static void escape_into(FuriString* out, const char* text) {
    for(const char* p = text; *p; p++) {
        if(*p == '\n') {
            furi_string_cat(out, "\\n");
        } else if(*p == '\\') {
            furi_string_cat(out, "\\\\");
        } else if(*p != '\r') {
            furi_string_push_back(out, *p);
        }
    }
}

static void unescape_into(FuriString* out, const char* text) {
    for(const char* p = text; *p; p++) {
        if(*p == '\\' && p[1] == 'n') {
            furi_string_push_back(out, '\n');
            p++;
        } else if(*p == '\\' && p[1] == '\\') {
            furi_string_push_back(out, '\\');
            p++;
        } else {
            furi_string_push_back(out, *p);
        }
    }
}

static bool do_send(App* app, const char* text) {
    if(!link_phone_ready(app->link)) {
        set_notice(app, "Phone app not detected");
        return false;
    }
    FuriString* line = furi_string_alloc();
    bool ok = true;
    if(!app->synced) {
        ok = link_send_line(app->link, "N\n");
        for(uint8_t i = 0; ok && i < app->log->replay_count; i++) {
            furi_string_printf(line, "%c:", app->log->replay_role[i] == 'U' ? 'U' : 'B');
            escape_into(line, app->log->replay_text[i]);
            furi_string_push_back(line, '\n');
            ok = link_send_line(app->link, furi_string_get_cstr(line));
        }
        if(ok) app->synced = true;
    }
    if(ok) {
        furi_string_printf(line, "Q:%s:", LANG_CODES[app->store->lang]);
        escape_into(line, text);
        furi_string_push_back(line, '\n');
        ok = link_send_line(app->link, furi_string_get_cstr(line));
    }
    furi_string_free(line);
    if(!ok) set_notice(app, "Send failed");
    return ok;
}

static void handle_send(App* app) {
    char text[INPUT_MAX + 4];
    with_view_model(app->chat_view, ChatModel * m, { snprintf(text, sizeof(text), "%s", m->input); }, false);
    size_t len = strlen(text);
    while(len > 0 && text[len - 1] == ' ') text[--len] = 0;
    if(len == 0 || app->busy) return;
    if(!do_send(app, text)) return;

    app->busy = true;
    app->busy_ticks = 0;
    furi_string_reset(app->answer);
    suggest_learn(app->suggest, text);
    suggest_save(app->suggest);
    store_append(app->store, 'U', text);
    chatlog_replay_add(app->log, 'U', text);
    with_view_model(
        app->chat_view,
        ChatModel * m,
        {
            chatlog_push(m->log, 'U', text);
            m->input[0] = 0;
            m->suggestion[0] = 0;
            m->scroll = 0;
            m->busy = true;
            snprintf(m->notice, sizeof(m->notice), "Claude is thinking...");
        },
        true);
}

static void finish_answer(App* app, bool save) {
    if(save && furi_string_size(app->answer) > 0) {
        store_append(app->store, 'A', furi_string_get_cstr(app->answer));
        chatlog_replay_add(app->log, 'A', furi_string_get_cstr(app->answer));
    }
    furi_string_reset(app->answer);
    app->busy = false;
    with_view_model(
        app->chat_view,
        ChatModel * m,
        {
            chatlog_commit(m->log);
            m->busy = false;
            m->notice[0] = 0;
        },
        true);
}

static void handle_line(App* app, const char* line) {
    if(line[0] == 'A' && line[1] == ':') {
        if(!app->busy) return;
        unescape_into(app->answer, line + 2);
        with_view_model(
            app->chat_view,
            ChatModel * m,
            {
                chatlog_set_pending(m->log, furi_string_get_cstr(app->answer));
                m->scroll = 0;
                m->notice[0] = 0;
            },
            true);
    } else if(line[0] == 'E') {
        if(app->busy) finish_answer(app, true);
    } else if(line[0] == 'X' && line[1] == ':') {
        if(!app->busy) return;
        furi_string_reset(app->answer);
        furi_string_cat(app->answer, "[!] ");
        unescape_into(app->answer, line + 2);
        with_view_model(
            app->chat_view,
            ChatModel * m,
            {
                chatlog_set_pending(m->log, furi_string_get_cstr(app->answer));
                m->scroll = 0;
            },
            true);
        finish_answer(app, false);
    } else if(line[0] == 'S' && line[1] == ':') {
        char note[28];
        snprintf(note, sizeof(note), "%s", line + 2);
        if(app->busy) set_notice(app, note);
    }
}

static void drain_link(App* app) {
    LinkMsg* msg = malloc(sizeof(LinkMsg));
    while(furi_message_queue_get(link_queue(app->link), msg, 0) == FuriStatusOk) {
        handle_line(app, msg->line);
    }
    free(msg);
    sync_link_state(app);
}

static void enter_chat(App* app) {
    if(!store_has_chat(app->store)) store_new_chat(app->store);
    store_load_chat(app->store, app->log);
    suggest_set_lang(app->suggest, (SuggestLang)app->store->lang);
    app->synced = false;
    app->busy = false;
    furi_string_reset(app->answer);
    LinkKind kind = link_kind(app->link);
    bool dict_ok = suggest_dict_available(app->suggest);
    with_view_model(
        app->chat_view,
        ChatModel * m,
        {
            m->input[0] = 0;
            m->suggestion[0] = 0;
            m->row = 1;
            m->col = 4;
            m->layer = 0;
            m->scroll = 0;
            m->busy = false;
            m->link = kind;
            m->dict_ok = dict_ok;
            snprintf(m->notice, sizeof(m->notice), "%s", dict_ok ? "Type a message" : "Dictionary missing");
        },
        true);
    app->current = ViewChat;
    view_dispatcher_switch_to_view(app->dispatcher, ViewChat);
}

static void show_menu(App* app) {
    suggest_save(app->suggest);
    sync_menu(app);
    app->current = ViewMenu;
    view_dispatcher_switch_to_view(app->dispatcher, ViewMenu);
}

static void show_info(App* app) {
    widget_reset(app->info);
    LinkKind kind = link_kind(app->link);
    FuriString* text = furi_string_alloc();
    furi_string_printf(
        text,
        "\e#Claude Bridge\n\e*Link:\e* %s\n"
        "1. Install the Android app\n"
        "2. Pair it with this Flipper (Bluetooth) or plug in USB\n"
        "3. USB is used automatically when connected\n\n"
        "Chats: only 2 are kept. Starting a new chat when both slots are used replaces the "
        "oldest one. Max 500 KB per chat.\n\n"
        "Hints: Hold OK = accept. Hold Back = menu.\n"
        "Dictionary: %s",
        kind == LinkKindUsb ? "USB" : kind == LinkKindBle ? "Bluetooth" : "not connected",
        suggest_dict_available(app->suggest) ? "ok" : "missing - reinstall app");
    widget_add_text_scroll_element(app->info, 0, 0, 128, 64, furi_string_get_cstr(text));
    furi_string_free(text);
    app->current = ViewInfo;
    view_dispatcher_switch_to_view(app->dispatcher, ViewInfo);
}

static const char* const MENU_LABELS[4] = {"Chat", "New chat", "Settings", "Info"};

static bool menu_enabled(const MenuModel* m, int index) {
    return index >= MenuSettings || m->ready;
}

static void menu_draw(Canvas* canvas, void* model) {
    MenuModel* m = model;
    canvas_clear(canvas);
    canvas_set_color(canvas, ColorBlack);
    canvas_set_font(canvas, FontPrimary);
    canvas_draw_str(canvas, 2, 9, "Claude Bridge");
    canvas_set_font(canvas, FontSecondary);
    if(m->ready) {
        canvas_draw_str_aligned(canvas, 126, 1, AlignRight, AlignTop, m->kind == LinkKindUsb ? "USB" : "BLE");
    }
    canvas_draw_line(canvas, 0, 11, 127, 11);
    for(int i = 0; i < 4; i++) {
        int y = 13 + i * 10;
        bool sel = m->selected == i;
        bool enabled = menu_enabled(m, i);
        int tw = canvas_string_width(canvas, MENU_LABELS[i]);
        if(sel && enabled) {
            canvas_draw_box(canvas, 0, y, 128, 10);
            canvas_set_color(canvas, ColorWhite);
        } else if(sel) {
            canvas_draw_frame(canvas, 0, y, 128, 10);
        }
        canvas_draw_str(canvas, 4, y + 8, MENU_LABELS[i]);
        canvas_set_color(canvas, ColorBlack);
        if(!enabled) {
            canvas_set_color(canvas, ColorWhite);
            for(int dy = 0; dy < 10; dy++) {
                for(int dx = 0; dx < tw + 2; dx++) {
                    if(((dx + dy) & 1) == 0) canvas_draw_dot(canvas, 4 + dx, y + dy);
                }
            }
            canvas_set_color(canvas, ColorBlack);
        }
    }
    canvas_draw_line(canvas, 0, 54, 127, 54);
    const char* msg = m->starting ? "Starting..." :
                      m->ready    ? "Phone app connected" :
                                    "Phone app not detected";
    canvas_draw_str_aligned(canvas, 64, 59, AlignCenter, AlignCenter, msg);
}

static void menu_cb(void* context, uint32_t index);

static bool menu_input(InputEvent* event, void* context) {
    App* app = context;
    if(event->type != InputTypeShort && event->type != InputTypeRepeat) return false;
    if(event->key == InputKeyUp || event->key == InputKeyDown) {
        with_view_model(
            app->menu_view,
            MenuModel * m,
            { m->selected = event->key == InputKeyUp ? (m->selected + 3) % 4 : (m->selected + 1) % 4; },
            true);
        return true;
    }
    if(event->key == InputKeyOk && event->type == InputTypeShort) {
        uint8_t index = 0;
        bool enabled = false;
        with_view_model(
            app->menu_view,
            MenuModel * m,
            {
                index = m->selected;
                enabled = menu_enabled(m, index);
            },
            false);
        if(enabled) menu_cb(app, index);
        return true;
    }
    return false;
}

static void sync_menu(App* app) {
    bool ready = link_started(app->link) && link_phone_ready(app->link);
    bool starting = !link_started(app->link);
    LinkKind kind = link_kind(app->link);
    with_view_model(
        app->menu_view,
        MenuModel * m,
        {
            m->ready = ready;
            m->starting = starting;
            m->kind = kind;
        },
        true);
}

static void menu_cb(void* context, uint32_t index) {
    App* app = context;
    switch(index) {
    case MenuChat:
        enter_chat(app);
        break;
    case MenuNewChat:
        dialog_ex_set_header(app->confirm, "New chat", 64, 0, AlignCenter, AlignTop);
        dialog_ex_set_text(
            app->confirm,
            "Only 2 chats are kept.\nIf both are used, the\noldest one is replaced.",
            64,
            30,
            AlignCenter,
            AlignCenter);
        dialog_ex_set_left_button_text(app->confirm, "Cancel");
        dialog_ex_set_right_button_text(app->confirm, "Start");
        app->current = ViewConfirm;
        view_dispatcher_switch_to_view(app->dispatcher, ViewConfirm);
        break;
    case MenuSettings:
        app->current = ViewSettings;
        view_dispatcher_switch_to_view(app->dispatcher, ViewSettings);
        break;
    default:
        show_info(app);
        break;
    }
}

static void confirm_cb(DialogExResult result, void* context) {
    App* app = context;
    if(result == DialogExResultRight) {
        store_new_chat(app->store);
        enter_chat(app);
    } else {
        show_menu(app);
    }
}

static void lang_changed(VariableItem* item) {
    App* app = variable_item_get_context(item);
    uint8_t idx = variable_item_list_get_selected_item_index(app->settings);
    UNUSED(idx);
    uint8_t value = variable_item_get_current_value_index(item);
    variable_item_set_current_value_text(item, LANG_NAMES[value]);
    app->store->lang = value;
    store_save_settings(app->store);
    suggest_set_lang(app->suggest, (SuggestLang)value);
}

static bool custom_event_cb(void* context, uint32_t event) {
    App* app = context;
    switch(event) {
    case EvtLink:
        drain_link(app);
        break;
    case EvtSend:
        handle_send(app);
        break;
    case EvtMenu:
        show_menu(app);
        break;
    default:
        break;
    }
    return true;
}

static void tick_cb(void* context) {
    App* app = context;
    if(app->busy) {
        app->busy_ticks++;
        if(app->busy_ticks > BUSY_TIMEOUT_TICKS) {
            furi_string_reset(app->answer);
            furi_string_cat(app->answer, "[!] Timeout");
            with_view_model(
                app->chat_view,
                ChatModel * m,
                { chatlog_set_pending(m->log, furi_string_get_cstr(app->answer)); },
                true);
            finish_answer(app, false);
        }
    }
    if(app->current == ViewChat) sync_link_state(app);
    if(link_started(app->link) && link_kind(app->link) != LinkKindNone &&
       !link_phone_ready(app->link)) {
        if(app->ping_ticks++ % 4 == 0) link_ping(app->link);
    } else {
        app->ping_ticks = 0;
    }
}

static bool nav_cb(void* context) {
    App* app = context;
    if(app->current == ViewMenu) {
        view_dispatcher_stop(app->dispatcher);
    } else {
        show_menu(app);
    }
    return true;
}

static void link_notify(void* context) {
    App* app = context;
    view_dispatcher_send_custom_event(app->dispatcher, EvtLink);
}

static int32_t init_worker(void* context) {
    App* app = context;
    link_start(app->link);
    return 0;
}

int32_t claude_bridge_app(void* p) {
    UNUSED(p);
    App* app = malloc(sizeof(App));
    memset(app, 0, sizeof(App));
    app->store = store_alloc();
    app->suggest = suggest_alloc(app->store->storage);
    suggest_set_lang(app->suggest, (SuggestLang)app->store->lang);
    app->log = chatlog_alloc();
    app->answer = furi_string_alloc();

    app->gui = furi_record_open(RECORD_GUI);
    app->dispatcher = view_dispatcher_alloc();
    view_dispatcher_set_event_callback_context(app->dispatcher, app);
    view_dispatcher_set_custom_event_callback(app->dispatcher, custom_event_cb);
    view_dispatcher_set_navigation_event_callback(app->dispatcher, nav_cb);
    view_dispatcher_set_tick_event_callback(app->dispatcher, tick_cb, 500);

    app->menu_view = view_alloc();
    view_allocate_model(app->menu_view, ViewModelTypeLocking, sizeof(MenuModel));
    view_set_context(app->menu_view, app);
    view_set_draw_callback(app->menu_view, menu_draw);
    view_set_input_callback(app->menu_view, menu_input);
    with_view_model(app->menu_view, MenuModel * m, { m->starting = true; }, false);
    view_dispatcher_add_view(app->dispatcher, ViewMenu, app->menu_view);

    app->chat_view = view_alloc();
    view_allocate_model(app->chat_view, ViewModelTypeLocking, sizeof(ChatModel));
    view_set_context(app->chat_view, app);
    view_set_draw_callback(app->chat_view, chat_draw);
    view_set_input_callback(app->chat_view, chat_input);
    with_view_model(app->chat_view, ChatModel * m, { m->log = app->log; }, false);
    view_dispatcher_add_view(app->dispatcher, ViewChat, app->chat_view);

    app->settings = variable_item_list_alloc();
    VariableItem* item =
        variable_item_list_add(app->settings, "Hints language", 3, lang_changed, app);
    variable_item_set_current_value_index(item, app->store->lang);
    variable_item_set_current_value_text(item, LANG_NAMES[app->store->lang]);
    view_dispatcher_add_view(app->dispatcher, ViewSettings, variable_item_list_get_view(app->settings));

    app->info = widget_alloc();
    view_dispatcher_add_view(app->dispatcher, ViewInfo, widget_get_view(app->info));

    app->confirm = dialog_ex_alloc();
    dialog_ex_set_context(app->confirm, app);
    dialog_ex_set_result_callback(app->confirm, confirm_cb);
    view_dispatcher_add_view(app->dispatcher, ViewConfirm, dialog_ex_get_view(app->confirm));

    view_dispatcher_attach_to_gui(app->dispatcher, app->gui, ViewDispatcherTypeFullscreen);

    app->link = link_alloc(link_notify, app);
    app->init_thread = furi_thread_alloc_ex("ClaudeInit", 3072, init_worker, app);
    furi_thread_start(app->init_thread);

    app->current = ViewMenu;
    view_dispatcher_switch_to_view(app->dispatcher, ViewMenu);
    view_dispatcher_run(app->dispatcher);

    furi_thread_join(app->init_thread);
    furi_thread_free(app->init_thread);
    link_free(app->link);

    suggest_save(app->suggest);
    view_dispatcher_remove_view(app->dispatcher, ViewMenu);
    view_dispatcher_remove_view(app->dispatcher, ViewChat);
    view_dispatcher_remove_view(app->dispatcher, ViewSettings);
    view_dispatcher_remove_view(app->dispatcher, ViewInfo);
    view_dispatcher_remove_view(app->dispatcher, ViewConfirm);
    view_free_model(app->menu_view);
    view_free(app->menu_view);
    view_free_model(app->chat_view);
    view_free(app->chat_view);
    variable_item_list_free(app->settings);
    widget_free(app->info);
    dialog_ex_free(app->confirm);
    view_dispatcher_free(app->dispatcher);
    furi_record_close(RECORD_GUI);
    furi_string_free(app->answer);
    chatlog_free(app->log);
    suggest_free(app->suggest);
    store_free(app->store);
    free(app);
    return 0;
}
