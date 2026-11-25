#ifndef XPD_SDP_REGISTER_H
#define XPD_SDP_REGISTER_H

#include "xpd.h"

int sdp_cleanup_handles(xpd_state_t *st);
int sdp_register_all(xpd_state_t *st);
int sdp_our_records_present(void);

#endif
