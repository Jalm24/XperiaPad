#ifndef XPD_HCI_CLASS_H
#define XPD_HCI_CLASS_H

#include "xpd.h"

int hci_open_dev(xpd_state_t *st);
void hci_close_dev(xpd_state_t *st);
int hci_read_cod(xpd_state_t *st, uint32_t *cod);
int hci_write_cod(xpd_state_t *st, uint32_t cod);
int hci_write_eir_gamepad(xpd_state_t *st, const char *name);
int hci_write_eir_clear(xpd_state_t *st);
int hci_read_scan_enable(xpd_state_t *st, uint8_t *out);
int hci_write_scan_enable(xpd_state_t *st, uint8_t scan);
int hci_disconnect_acl(xpd_state_t *st, uint16_t handle, uint8_t reason);

#endif
