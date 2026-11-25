/* Copyright (C) 2026 Jalm24
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Additional GPLv3 §7(b) term: this author attribution must be
 * preserved in modified source files and in the project's legal notices. */

#include "cleanup.h"
#include "hci_class.h"
#include "sdp_register.h"
#include "hidp_server.h"
#include "uds.h"
#include "log.h"

void xpd_cleanup(xpd_state_t *st) {
    xpd_log("INFO", "cleanup: restoring state...");
    st->active = 0;
    hidp_close_all(st);
    /* borrar nuestros registros SDP (y dejar que el sistema republique el suyo al reiniciar si aplica) */
    sdp_cleanup_handles(st);
    if (st->have_original_cod && st->bt_fd >= 0) {
        if (hci_write_cod(st, st->original_cod) == 0) {
            xpd_log("INFO", "CoD restored to 0x%06x", st->original_cod);
        } else {
            xpd_log("WARN", "could not restore CoD");
        }
        if (hci_write_eir_clear(st) == 0) {
            xpd_log("INFO", "EIR cleared");
        } else {
            xpd_log("WARN", "could not clear EIR");
        }
    }
    uds_close(st);
    hci_close_dev(st);
    st->running = 0;
}
