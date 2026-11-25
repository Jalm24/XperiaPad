#ifndef XPD_UDS_H
#define XPD_UDS_H

#include "xpd.h"
#include <sys/types.h>

int uds_listen(xpd_state_t *st);
int uds_accept(xpd_state_t *st);
int uds_handle(xpd_state_t *st);
void uds_close(xpd_state_t *st);
/* C2: restringe quien puede conectarse al UDS. Si allow_shell=1 se aceptan
 * tambien root(0) y shell(2000) para depuracion por adb. */
void uds_set_allowed_uid(uid_t uid, int allow_shell);
int uds_listen_ctl(xpd_state_t *st);
int uds_accept_ctl(xpd_state_t *st);
void uds_ctl_close(xpd_state_t *st);

#endif
