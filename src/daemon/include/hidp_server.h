#ifndef XPD_HIDP_SERVER_H
#define XPD_HIDP_SERVER_H

#include "xpd.h"

int hidp_listen(xpd_state_t *st);
void hidp_close_all(xpd_state_t *st);
int hidp_handle_fds(xpd_state_t *st, int ctrl_l_ready, int intr_l_ready, int ctrl_ready, int intr_ready);
int hidp_send_report(xpd_state_t *st, uint8_t report_id, const uint8_t *data, size_t len);
unsigned long hidp_bp_count(void);
void hidp_bp_reset(void);

#endif
