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
void link_free(Link* link);
FuriMessageQueue* link_queue(Link* link);
LinkKind link_kind(Link* link);
bool link_ble_connected(Link* link);
bool link_usb_active(Link* link);
bool link_send_line(Link* link, const char* text);
