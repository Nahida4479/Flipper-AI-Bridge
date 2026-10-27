#include "link.h"
#include "paths.h"
#include <furi_hal_bt.h>
#include <furi_hal_usb.h>
#include <furi_hal_usb_cdc.h>
#include <cli/cli_vcp.h>
#include <bt/bt_service/bt.h>
#include <profiles/serial_profile.h>
#include <string.h>

#define BLE_RX_BUFFER 1024
#define BLE_TX_CHUNK  160
#define USB_CHUNK     CDC_DATA_SZ

typedef enum {
    EvtStop = (1 << 0),
    EvtBle = (1 << 1),
    EvtUsb = (1 << 2),
} LinkEvt;

typedef struct {
    char buf[LINK_LINE_MAX];
    size_t n;
} Assembler;

struct Link {
    LinkNotify notify;
    void* context;
    Bt* bt;
    CliVcp* cli;
    FuriHalBleProfileBase* profile;
    FuriThread* thread;
    FuriStreamBuffer* ble_rx;
    FuriMessageQueue* queue;
    Assembler ble_asm;
    Assembler usb_asm;
    volatile bool ble_connected;
    volatile bool usb_dtr;
    volatile bool usb_connected;
};

static void notify(Link* link) {
    if(link->notify) link->notify(link->context);
}

static void feed(Link* link, Assembler* a, const uint8_t* data, size_t size) {
    for(size_t i = 0; i < size; i++) {
        char c = (char)data[i];
        if(c == '\r') continue;
        if(c == '\n') {
            if(a->n > 0) {
                LinkMsg msg;
                memcpy(msg.line, a->buf, a->n);
                msg.line[a->n] = 0;
                furi_message_queue_put(link->queue, &msg, 200);
                notify(link);
            }
            a->n = 0;
        } else if(a->n < LINK_LINE_MAX - 1) {
            a->buf[a->n++] = c;
        }
    }
}

static int32_t link_worker(void* context) {
    Link* link = context;
    uint8_t buf[USB_CHUNK];
    for(;;) {
        uint32_t flags =
            furi_thread_flags_wait(EvtStop | EvtBle | EvtUsb, FuriFlagWaitAny, FuriWaitForever);
        if(flags & EvtStop) break;
        if(flags & EvtBle) {
            size_t n;
            while((n = furi_stream_buffer_receive(link->ble_rx, buf, sizeof(buf), 0)) > 0) {
                feed(link, &link->ble_asm, buf, n);
            }
        }
        if(flags & EvtUsb) {
            int32_t n;
            while((n = furi_hal_cdc_receive(0, buf, sizeof(buf))) > 0) {
                feed(link, &link->usb_asm, buf, (size_t)n);
            }
        }
    }
    return 0;
}

static uint16_t ble_event(SerialServiceEvent event, void* context) {
    Link* link = context;
    if(event.event == SerialServiceEventTypeDataReceived) {
        furi_stream_buffer_send(link->ble_rx, event.data.buffer, event.data.size, 0);
        furi_thread_flags_set(furi_thread_get_id(link->thread), EvtBle);
    }
    return (uint16_t)furi_stream_buffer_spaces_available(link->ble_rx);
}

static void bt_status(BtStatus status, void* context) {
    Link* link = context;
    link->ble_connected = status == BtStatusConnected;
    notify(link);
}

static void cdc_rx(void* context) {
    Link* link = context;
    furi_thread_flags_set(furi_thread_get_id(link->thread), EvtUsb);
}

static void cdc_tx(void* context) {
    UNUSED(context);
}

static void cdc_state(void* context, CdcState state) {
    Link* link = context;
    link->usb_connected = state == CdcStateConnected;
    if(!link->usb_connected) link->usb_dtr = false;
    notify(link);
}

static void cdc_ctrl(void* context, CdcCtrlLine lines) {
    Link* link = context;
    link->usb_dtr = (lines & CdcCtrlLineDTR) != 0;
    notify(link);
}

static void cdc_config(void* context, struct usb_cdc_line_coding* config) {
    UNUSED(context);
    UNUSED(config);
}

static CdcCallbacks cdc_callbacks = {
    .tx_ep_callback = cdc_tx,
    .rx_ep_callback = cdc_rx,
    .state_callback = cdc_state,
    .ctrl_line_callback = cdc_ctrl,
    .config_callback = cdc_config,
};

Link* link_alloc(LinkNotify notify_cb, void* context) {
    Link* link = malloc(sizeof(Link));
    memset(link, 0, sizeof(Link));
    link->notify = notify_cb;
    link->context = context;
    link->ble_rx = furi_stream_buffer_alloc(BLE_RX_BUFFER, 1);
    link->queue = furi_message_queue_alloc(8, sizeof(LinkMsg));
    link->thread = furi_thread_alloc_ex("ClaudeLink", 1536, link_worker, link);
    furi_thread_start(link->thread);

    link->bt = furi_record_open(RECORD_BT);
    bt_disconnect(link->bt);
    furi_delay_ms(200);
    bt_keys_storage_set_storage_path(link->bt, BT_KEYS_PATH);
    link->profile = bt_profile_start(link->bt, ble_profile_serial, NULL);
    if(link->profile) {
        ble_profile_serial_set_event_callback(link->profile, BLE_RX_BUFFER, ble_event, link);
        furi_hal_bt_start_advertising();
        bt_set_status_changed_callback(link->bt, bt_status, link);
    }

    link->cli = furi_record_open(RECORD_CLI_VCP);
    cli_vcp_disable(link->cli);
    furi_hal_usb_unlock();
    furi_hal_usb_set_config(&usb_cdc_single, NULL);
    furi_hal_cdc_set_callbacks(0, &cdc_callbacks, link);
    link->usb_dtr = (furi_hal_cdc_get_ctrl_line_state(0) & CdcCtrlLineDTR) != 0;
    return link;
}

void link_free(Link* link) {
    furi_hal_cdc_set_callbacks(0, NULL, NULL);
    furi_hal_usb_unlock();
    furi_hal_usb_set_config(&usb_cdc_single, NULL);
    cli_vcp_enable(link->cli);
    furi_record_close(RECORD_CLI_VCP);

    bt_set_status_changed_callback(link->bt, NULL, NULL);
    bt_disconnect(link->bt);
    furi_delay_ms(200);
    bt_keys_storage_set_default_path(link->bt);
    bt_profile_restore_default(link->bt);
    furi_record_close(RECORD_BT);

    furi_thread_flags_set(furi_thread_get_id(link->thread), EvtStop);
    furi_thread_join(link->thread);
    furi_thread_free(link->thread);
    furi_message_queue_free(link->queue);
    furi_stream_buffer_free(link->ble_rx);
    free(link);
}

FuriMessageQueue* link_queue(Link* link) {
    return link->queue;
}

bool link_ble_connected(Link* link) {
    return link->ble_connected;
}

bool link_usb_active(Link* link) {
    return link->usb_connected && link->usb_dtr;
}

LinkKind link_kind(Link* link) {
    if(link_usb_active(link)) return LinkKindUsb;
    if(link->ble_connected) return LinkKindBle;
    return LinkKindNone;
}

static bool send_usb(const uint8_t* data, size_t size) {
    size_t off = 0;
    while(off < size) {
        size_t n = size - off;
        if(n > USB_CHUNK) n = USB_CHUNK;
        furi_hal_cdc_send(0, (uint8_t*)data + off, (uint16_t)n);
        off += n;
        furi_delay_ms(2);
    }
    return true;
}

static bool send_ble(Link* link, const uint8_t* data, size_t size) {
    size_t off = 0;
    while(off < size) {
        size_t n = size - off;
        if(n > BLE_TX_CHUNK) n = BLE_TX_CHUNK;
        bool ok = false;
        for(int attempt = 0; attempt < 100; attempt++) {
            if(ble_profile_serial_tx(link->profile, (uint8_t*)data + off, (uint16_t)n)) {
                ok = true;
                break;
            }
            furi_delay_ms(10);
        }
        if(!ok) return false;
        off += n;
        furi_delay_ms(5);
    }
    return true;
}

bool link_send_line(Link* link, const char* text) {
    size_t len = strlen(text);
    if(link_usb_active(link)) return send_usb((const uint8_t*)text, len);
    if(link->ble_connected && link->profile) return send_ble(link, (const uint8_t*)text, len);
    return false;
}
