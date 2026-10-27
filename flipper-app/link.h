#pragma once

#include <furi.h>

#define LINK_LINE_MAX 400

typedef enum {
    LinkKindNone,
    LinkKindBle,
    LinkKindUsb,
} LinkKind;

typedef struct {
    char line[LINK_LINE_MAX];
} LinkMsg;

typedef void (*LinkNotify)(void* context);

typedef struct Link Link;

Link* link_alloc(LinkNotify notify, void* context);
void link_start(Link* link);
bool link_started(Link* link);
bool link_phone_ready(Link* link);
void link_free(Link* link);
FuriMessageQueue* link_queue(Link* link);
LinkKind link_kind(Link* link);
bool link_ble_connected(Link* link);
bool link_usb_active(Link* link);
bool link_send_line(Link* link, const char* text);
void link_ping(Link* link);
void link_stats(Link* link, uint32_t* rx_bytes, uint32_t* pings, uint32_t* pings_ok);
