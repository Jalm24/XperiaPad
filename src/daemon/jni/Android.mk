# Copyright (C) 2026 Jalm24
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Additional GPLv3 §7(b) term: this author attribution must be
# preserved in modified source files and in the project's legal notices.

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := xpd
LOCAL_SRC_FILES := xpd.c log.c uds.c hci_class.c cleanup.c sdp_register.c hidp_server.c \
                   trusted.c dbus_trusted.c sdp.c bluetooth.c sdp-xml.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/../include $(LOCAL_PATH)
LOCAL_CFLAGS    := -Wall -Wextra -O2 -std=gnu99 -D_GNU_SOURCE -Wno-unused-parameter -Wno-sign-compare -Wno-pointer-sign -Wno-missing-field-initializers
LOCAL_LDLIBS    := -llog

# Bluetooth/HCI: enlazaremos con libbluetooth del sistema o funciones propias según haga falta.
# Por ahora el daemon es un esqueleto.

include $(BUILD_EXECUTABLE)

include $(CLEAR_VARS)
LOCAL_MODULE    := xpdctl
LOCAL_SRC_FILES := xpdctl.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/../include $(LOCAL_PATH)
LOCAL_CFLAGS    := -Wall -Wextra -O2 -std=gnu99 -D_GNU_SOURCE
include $(BUILD_EXECUTABLE)
