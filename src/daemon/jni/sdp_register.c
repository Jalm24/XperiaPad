/* Copyright (C) 2026 Jalm24
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Additional GPLv3 §7(b) term: this author attribution must be
 * preserved in modified source files and in the project's legal notices. */

#include "sdp_register.h"
#include "log.h"

#include <stdint.h>
#include <string.h>
#include <errno.h>

#include "bluetooth.h"
#include "sdp.h"
#include "sdp_lib.h"

/* Descriptor HID: Report 0x03 gamepad con Hat Switch (d-pad) para mejor compatibilidad web/estándar.
 * Layout 0x03 (10 bytes payload): [btn1-4+pad(1), X(1), Y(1), Rx(1), Ry(1), Hat+pad(1), pad(1), btn5-12(1), btn13-16+pad(1), pad(1)]
 * Hat: 4 bits, 0=N,1=NE,2=E,3=SE,4=S,5=SW,6=W,7=NW, 8=null (reposo).
 */
static const uint8_t hid_desc[] = {
    0x05, 0x01,       /* Usage Page (Generic Desktop) */
    0x09, 0x05,       /* Usage (Game Pad) */
    0xA1, 0x01,       /* Collection (Application) */
    0x85, 0x03,       /*   Report ID (3) */

    0x05, 0x09,       /*   Usage Page (Button) */
    0x19, 0x01,       /*   Usage Minimum (1) */
    0x29, 0x04,       /*   Usage Maximum (4) */
    0x15, 0x00, 0x25, 0x01,
    0x75, 0x01, 0x95, 0x04,
    0x81, 0x02,       /*   Input (Data,Var,Abs) - btn1-4 */
    0x75, 0x04, 0x95, 0x01,
    0x81, 0x01,       /*   Input (Const) - pad */

    0x05, 0x01,       /*   Usage Page (Generic Desktop) */
    0x09, 0x30,       /*   Usage (X) */
    0x09, 0x31,       /*   Usage (Y) */
    0x09, 0x33,       /*   Usage (Rx) */
    0x09, 0x34,       /*   Usage (Ry) */
    0x15, 0x00, 0x26, 0xFF, 0x00,
    0x75, 0x08, 0x95, 0x04,
    0x81, 0x02,       /*   Input (Data,Var,Abs) - 4 axes */

    0x09, 0x39,       /*   Usage (Hat Switch) */
    0x15, 0x00, 0x25, 0x07,
    0x35, 0x00, 0x46, 0x3B, 0x01, /* Physical 0..315 */
    0x65, 0x14,       /*   Unit (Eng Rot: Degrees) */
    0x75, 0x04, 0x95, 0x01,
    0x81, 0x42,       /*   Input (Data,Var,Abs,Null) - hat */
    0x65, 0x00,       /*   Unit (None) */
    0x75, 0x04, 0x95, 0x01,
    0x81, 0x01,       /*   Input (Const) - pad */

    0x75, 0x08, 0x95, 0x01,
    0x81, 0x01,       /*   Input (Const) - pad byte */

    0x05, 0x09,       /*   Usage Page (Button) */
    0x19, 0x05,       /*   Usage Minimum (5) */
    0x29, 0x0C,       /*   Usage Maximum (12) */
    0x15, 0x00, 0x25, 0x01,
    0x75, 0x01, 0x95, 0x08,
    0x81, 0x02,       /*   Input (Data,Var,Abs) - btn5-12 */

    0x19, 0x0D,       /*   Usage Minimum (13) */
    0x29, 0x10,       /*   Usage Maximum (16) */
    0x75, 0x01, 0x95, 0x04,
    0x81, 0x02,       /*   Input (Data,Var,Abs) - btn13-16 */
    0x75, 0x04, 0x95, 0x01,
    0x81, 0x01,       /*   Input (Const) - pad */

    0x75, 0x08, 0x95, 0x01,
    0x81, 0x01,       /*   Input (Const) - pad byte */

    0xC0,             /* End Collection */

    0x05, 0x0C,       /* Usage Page (Consumer) */
    0x09, 0x01,       /* Usage (Consumer Control) */
    0xA1, 0x01,       /* Collection (Application) */
    0x85, 0x01,       /*   Report ID (1) */
    0x75, 0x08, 0x95, 0x01,
    0x81, 0x01,       /*   Input (Const) */
    0x05, 0x0C,       /*   Usage Page (Consumer) */
    0x09, 0x23,       /*   Usage (Home) */
    0x09, 0x24,       /*   Usage (Back) */
    0x15, 0x00, 0x25, 0x01,
    0x75, 0x01, 0x95, 0x02,
    0x81, 0x02,       /*   Input (Data,Var,Abs) */
    0x75, 0x01, 0x95, 0x06,
    0x81, 0x01,       /*   Input (Const) */
    0x75, 0x08, 0x95, 0x06,
    0x81, 0x01,       /*   Input (Const) */
    0xC0              /* End Collection */
};

static void add_lang_attr(sdp_record_t *rec) {
    sdp_lang_attr_t base_lang;
    sdp_list_t *langs = NULL;
    base_lang.code_ISO639 = (0x65 << 8) | 0x6e; /* en */
    base_lang.encoding = 106; /* UTF-8 */
    base_lang.base_offset = SDP_PRIMARY_LANG_BASE;
    langs = sdp_list_append(NULL, &base_lang);
    sdp_set_lang_attr(rec, langs);
    sdp_list_free(langs, NULL);
}

static int unregister_handle(sdp_session_t *sess, uint32_t handle) {
    return sdp_device_record_unregister_binary(sess, BDADDR_ANY, handle);
}

static int register_hid(sdp_session_t *sess) {
    sdp_record_t record;
    memset(&record, 0, sizeof(record));
    record.handle = SDP_HANDLE_HID;

    sdp_list_t *svclass = NULL, *pfseq = NULL, *apseq = NULL, *aproto = NULL, *root = NULL;
    uuid_t root_uuid, hid_uuid, l2cap_uuid, hidp_uuid;
    sdp_profile_desc_t profile;
    sdp_data_t *psm16 = NULL;

    sdp_uuid16_create(&root_uuid, PUBLIC_BROWSE_GROUP);
    root = sdp_list_append(NULL, &root_uuid);
    sdp_set_browse_groups(&record, root);

    add_lang_attr(&record);

    sdp_uuid16_create(&hid_uuid, HID_SVCLASS_ID);
    svclass = sdp_list_append(NULL, &hid_uuid);
    sdp_set_service_classes(&record, svclass);

    sdp_uuid16_create(&profile.uuid, HID_PROFILE_ID);
    profile.version = 0x0111;
    pfseq = sdp_list_append(NULL, &profile);
    sdp_set_profile_descs(&record, pfseq);

    uint16_t ctrl = 0x11, intr = 0x13;
    /* Control: L2CAP(PSM 0x11) + HIDP */
    sdp_uuid16_create(&l2cap_uuid, L2CAP_UUID);
    sdp_list_t *proto_c = sdp_list_append(NULL, &l2cap_uuid);
    psm16 = sdp_data_alloc(SDP_UINT16, &ctrl);
    proto_c = sdp_list_append(proto_c, psm16);
    apseq = sdp_list_append(NULL, proto_c);
    sdp_uuid16_create(&hidp_uuid, HIDP_UUID);
    sdp_list_t *proto_h = sdp_list_append(NULL, &hidp_uuid);
    apseq = sdp_list_append(apseq, proto_h);
    aproto = sdp_list_append(NULL, apseq);
    sdp_set_access_protos(&record, aproto);

    /* Interrupt: additional */
    sdp_list_t *proto_i = sdp_list_append(NULL, &l2cap_uuid);
    sdp_data_t *psm16_i = sdp_data_alloc(SDP_UINT16, &intr);
    proto_i = sdp_list_append(proto_i, psm16_i);
    sdp_list_t *apseq_i = sdp_list_append(NULL, proto_i);
    sdp_list_t *proto_h_i = sdp_list_append(NULL, &hidp_uuid);
    apseq_i = sdp_list_append(apseq_i, proto_h_i);
    sdp_list_t *aproto_i = sdp_list_append(NULL, apseq_i);
    sdp_set_add_access_protos(&record, aproto_i);

    sdp_set_info_attr(&record, "Xperia PLAY Gamepad", "XperiaPad", "Bluetooth HID gamepad");

    uint16_t rel = 0x0100, parser = 0x0111, profv = 0x0111, superv = 0x0C80;
    uint8_t subclass = 0x08, country = 0x00;
    uint8_t vtrue = 1, vfalse = 0;
    sdp_attr_add_new(&record, SDP_ATTR_HID_DEVICE_RELEASE_NUMBER, SDP_UINT16, &rel);
    sdp_attr_add_new(&record, SDP_ATTR_HID_PARSER_VERSION, SDP_UINT16, &parser);
    sdp_attr_add_new(&record, SDP_ATTR_HID_DEVICE_SUBCLASS, SDP_UINT8, &subclass);
    sdp_attr_add_new(&record, SDP_ATTR_HID_COUNTRY_CODE, SDP_UINT8, &country);
    sdp_attr_add_new(&record, SDP_ATTR_HID_VIRTUAL_CABLE, SDP_BOOL, &vfalse);
    sdp_attr_add_new(&record, SDP_ATTR_HID_RECONNECT_INITIATE, SDP_BOOL, &vtrue);

    /* Descriptor list */
    uint8_t dtd2 = SDP_UINT8, dtd_data = SDP_TEXT_STR8;
    uint8_t hid_spec_type = 0x22;
    void *dtds[2] = { &dtd2, &dtd_data };
    void *vals[2] = { &hid_spec_type, (void *)hid_desc };
    int lens[2] = { 0, (int)sizeof(hid_desc) };
    sdp_data_t *hid_spec_lst = sdp_seq_alloc_with_length(dtds, vals, lens, 2);
    sdp_data_t *hid_spec_lst2 = sdp_data_alloc(SDP_SEQ8, hid_spec_lst);
    sdp_attr_add(&record, SDP_ATTR_HID_DESCRIPTOR_LIST, hid_spec_lst2);

    /* LANGID base */
    uint8_t dtd16 = SDP_UINT16;
    uint16_t lang0 = 0x0409, lang1 = 0x0100;
    void *ld[2] = { &dtd16, &dtd16 };
    void *lv[2] = { &lang0, &lang1 };
    sdp_data_t *lang_lst = sdp_seq_alloc(ld, lv, 2);
    sdp_data_t *lang_lst2 = sdp_data_alloc(SDP_SEQ8, lang_lst);
    sdp_attr_add(&record, SDP_ATTR_HID_LANG_ID_BASE_LIST, lang_lst2);

    sdp_attr_add_new(&record, SDP_ATTR_HID_BATTERY_POWER, SDP_BOOL, &vtrue);
    sdp_attr_add_new(&record, SDP_ATTR_HID_REMOTE_WAKEUP, SDP_BOOL, &vtrue);
    sdp_attr_add_new(&record, SDP_ATTR_HID_PROFILE_VERSION, SDP_UINT16, &profv);
    sdp_attr_add_new(&record, SDP_ATTR_HID_SUPERVISION_TIMEOUT, SDP_UINT16, &superv);
    sdp_attr_add_new(&record, SDP_ATTR_HID_NORMALLY_CONNECTABLE, SDP_BOOL, &vtrue);
    sdp_attr_add_new(&record, SDP_ATTR_HID_BOOT_DEVICE, SDP_BOOL, &vfalse);

    if (sdp_device_record_register(sess, BDADDR_ANY, &record, 0) < 0) {
        xpd_log("ERR", "HID registration failed: %s", strerror(errno));
        return -1;
    }
    xpd_log("INFO", "HID registration 0x%05x OK", SDP_HANDLE_HID);
    return 0;
}

static int register_did(sdp_session_t *sess, xpd_state_t *st) {
    sdp_record_t record;
    memset(&record, 0, sizeof(record));
    record.handle = SDP_HANDLE_DI;

    sdp_list_t *svclass = NULL, *pfseq = NULL, *root = NULL;
    uuid_t root_uuid, pnp_uuid;
    sdp_profile_desc_t profile;

    sdp_uuid16_create(&root_uuid, PUBLIC_BROWSE_GROUP);
    root = sdp_list_append(NULL, &root_uuid);
    sdp_set_browse_groups(&record, root);

    sdp_uuid16_create(&pnp_uuid, PNP_INFO_SVCLASS_ID);
    svclass = sdp_list_append(NULL, &pnp_uuid);
    sdp_set_service_classes(&record, svclass);

    sdp_uuid16_create(&profile.uuid, PNP_INFO_PROFILE_ID);
    profile.version = 0x0100;
    pfseq = sdp_list_append(NULL, &profile);
    sdp_set_profile_descs(&record, pfseq);

    uint16_t spec = 0x0103; /* 1.3 */
    /* Identidad por perfil (E20/E27): generica 0001:0001 para evitar mapeos
     * cacheados en navegadores; GameStick 0f0d:1011 para MissionControl.
     * El host la guarda al emparejar, asi que el modo debe elegirse ANTES. */
    uint16_t vendor = st->di_vid, product = st->di_pid, version = 0x0100;
    uint16_t source = 0x0002; /* USB */
    uint8_t primary = 1;
    sdp_attr_add_new(&record, 0x0200, SDP_UINT16, &spec);
    sdp_attr_add_new(&record, 0x0201, SDP_UINT16, &vendor);
    sdp_attr_add_new(&record, 0x0202, SDP_UINT16, &product);
    sdp_attr_add_new(&record, 0x0203, SDP_UINT16, &version);
    sdp_attr_add_new(&record, 0x0204, SDP_BOOL, &primary);
    sdp_attr_add_new(&record, 0x0205, SDP_UINT16, &source);

    if (sdp_device_record_register(sess, BDADDR_ANY, &record, 0) < 0) {
        xpd_log("ERR", "DI registration failed: %s", strerror(errno));
        return -1;
    }
    xpd_log("INFO", "DI registration 0x%05x OK (0x%04x:0x%04x)", SDP_HANDLE_DI, vendor, product);
    return 0;
}

/* Comprobar los records en el SDP server local, no solo el archivo de estado.
 * Tras STOP los records se borran pero el archivo puede seguir intacto: fiarse
 * solo de el deja al host sin HID SDP ("Could not parse HID SDP record"). */
static int search_handle(sdp_session_t *sess, uint16_t svclass, uint32_t want) {
    uuid_t svc;
    sdp_uuid16_create(&svc, svclass);
    sdp_list_t *search = sdp_list_append(NULL, &svc);
    sdp_list_t *rsp = NULL;
    int found = 0;
    if (!search) return 0;
    if (sdp_service_search_req(sess, search, 16, &rsp) == 0 && rsp) {
        for (sdp_list_t *l = rsp; l; l = l->next) {
            if ((uint32_t)(uintptr_t)l->data == want) { found = 1; break; }
        }
        sdp_list_free(rsp, NULL);
    }
    sdp_list_free(search, NULL);
    return found;
}

int sdp_our_records_present(void) {
    sdp_session_t *sess = sdp_connect(BDADDR_ANY, BDADDR_LOCAL, SDP_RETRY_IF_BUSY);
    if (!sess) return 0;
    int hid = search_handle(sess, HID_SVCLASS_ID, SDP_HANDLE_HID);
    int di = search_handle(sess, PNP_INFO_SVCLASS_ID, SDP_HANDLE_DI);
    sdp_close(sess);
    return hid && di;
}

int sdp_cleanup_handles(xpd_state_t *st) {
    (void)st;
    sdp_session_t *sess = sdp_connect(BDADDR_ANY, BDADDR_LOCAL, SDP_RETRY_IF_BUSY);
    if (!sess) { xpd_log("WARN", "sdp_connect (cleanup): %s", strerror(errno)); return -1; }
    uint32_t hs[] = { SDP_HANDLE_SYS_DI, SDP_HANDLE_HID, SDP_HANDLE_DI, 0 };
    for (int i = 0; hs[i]; i++) {
        if (unregister_handle(sess, hs[i]) == 0) xpd_log("INFO", "SDP removed 0x%x", hs[i]);
    }
    sdp_close(sess);
    return 0;
}

int sdp_register_all(xpd_state_t *st) {
    sdp_session_t *sess = sdp_connect(BDADDR_ANY, BDADDR_LOCAL, SDP_RETRY_IF_BUSY);
    if (!sess) { xpd_log("ERR", "sdp_connect: %s", strerror(errno)); return -1; }
    int ok = 0;
    if (register_hid(sess) != 0) ok = -1;
    if (register_did(sess, st) != 0) ok = -1;
    sdp_close(sess);
    return ok;
}
