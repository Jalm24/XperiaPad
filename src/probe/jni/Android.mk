LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := probe
LOCAL_SRC_FILES := probe.c
LOCAL_CFLAGS := -Wall -O2 -std=gnu99 -D_GNU_SOURCE
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
