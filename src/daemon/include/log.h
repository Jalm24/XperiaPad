#ifndef XPD_LOG_H
#define XPD_LOG_H

void xpd_log_init(const char *path);
void xpd_log(const char *level, const char *fmt, ...);
void xpd_log_close(void);

#endif
