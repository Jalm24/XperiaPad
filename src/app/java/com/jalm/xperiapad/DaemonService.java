/* Copyright (C) 2026 Jalm24
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Additional GPLv3 §7(b) term: this author attribution must be
 * preserved in modified source files and in the project's legal notices. */

package com.jalm.xperiapad;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Servicio ancla (E31): hace visible el daemon en Ajustes → Servicios en
 * ejecución, mantiene una notificación permanente con acción "detener" y
 * apaga el daemon si se queda sin cliente 120 s (cold-close real). */
public class DaemonService extends Service {
    public static final String ACTION_STOP = "com.jalm.xperiapad.DAEMON_STOP";
    public static final String ACTION_STOPPED = "com.jalm.xperiapad.DAEMON_STOPPED";
    /** Permiso signature: ninguna otra app puede stops/falsificar ACTION_STOPPED. */
    public static final String PERM = "com.jalm.xperiapad.permission.DAEMON_CONTROL";
    private static final int NID = 2026;

    private NotificationManager nm;
    private volatile boolean stopping = false;
    private boolean receiverOn = false;

    private final BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) { stopAll(); }
    };

    @Override
    public void onCreate() {
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        registerReceiver(stopReceiver, new IntentFilter(ACTION_STOP), PERM, null);
        receiverOn = true;
        showNotification();
        startForeground(NID, buildNotification());
        new Thread(monitor).start();
    }

    private Notification buildNotification() {
        Intent stop = new Intent(ACTION_STOP);
        stop.setClass(this, DaemonService.class);
        PendingIntent pi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification(android.R.drawable.stat_sys_upload,
                "XperiaPad", System.currentTimeMillis());
        n.setLatestEventInfo(this, "XperiaPad", getString(R.string.notif_daemon_active), pi);
        n.flags |= Notification.FLAG_ONGOING_EVENT; // siempre visible en GB
        return n;
    }

    private void showNotification() { nm.notify(NID, buildNotification()); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) stopAll();
        return START_NOT_STICKY; // no resucitar el daemon cuando la app muere
    }

    private void stopAll() {
        if (stopping) return;
        stopping = true;
        // primero STOP por UDS: el servicio corre con el mismo UID que la app
        try {
            LocalSocket s = new LocalSocket();
            s.connect(new LocalSocketAddress("xperiapad", LocalSocketAddress.Namespace.ABSTRACT));
            s.getOutputStream().write("STOP\n".getBytes());
            s.getOutputStream().flush();
            s.close();
            Thread.sleep(250);
        } catch (Exception ignored) {}
        // fallback con root solo si el daemon sigue respondiendo
        if (daemonAlive()) {
            try {
                Runtime.getRuntime().exec(new String[]{"su", "-c",
                        "killall xpd 2>/dev/null; killall libxpd.so 2>/dev/null; true"}).waitFor();
            } catch (Exception ignored) {}
        }
        SharedPreferences sp = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        sp.edit().putBoolean(MainActivity.PREF_DAEMON, false).commit();
        sendBroadcast(new Intent(ACTION_STOPPED), PERM);
        nm.cancel(NID);
        stopForeground(true);
        stopSelf();
    }

    private boolean daemonAlive() {
        try {
            LocalSocket s = new LocalSocket();
            s.connect(new LocalSocketAddress("xperiapadctl", LocalSocketAddress.Namespace.ABSTRACT));
            s.getOutputStream().write("PING\n".getBytes());
            s.getOutputStream().flush();
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String reply = r.readLine();
            s.close();
            return reply != null;
        } catch (Exception e) { return false; }
    }

    /** Detecta que el daemon ya no existe (autostop de 120 s) y retira el ancla. */
    private final Runnable monitor = new Runnable() {
        public void run() {
            int misses = 0;
            long lastKeepalive = 0;
            while (!stopping) {
                try {
                    Thread.sleep(2000);
                    long now = System.currentTimeMillis();
                    if (now - lastKeepalive >= 20000) {
                        lastKeepalive = now;
                        keepDaemonAlive();
                    }
                    boolean alive = false;
                    java.io.File[] procs = new java.io.File("/proc").listFiles();
                    if (procs != null) {
                        for (java.io.File p : procs) {
                            if (!p.isDirectory()) continue;
                            java.io.File cmd = new java.io.File(p, "cmdline");
                            if (!cmd.exists()) continue;
                            java.io.FileInputStream f = new java.io.FileInputStream(cmd);
                            byte[] b = new byte[256];
                            int n = f.read(b);
                            f.close();
                            if (n > 0 && new String(b, 0, n).contains("libxpd.so")) { alive = true; break; }
                        }
                    }
                    misses = alive ? 0 : misses + 1;
                    if (misses >= 3) { stopAll(); return; } // 6 s sin daemon
                } catch (Throwable t) { /* sigue monitorizando */ }
            }
        }
    };

    /** Mantiene el perfil HID disponible mientras el ancla este activa, sin
     * ocupar el socket principal: la app puede seguir enviando ARM/R3. */
    private void keepDaemonAlive() {
        LocalSocket s = new LocalSocket();
        try {
            s.connect(new LocalSocketAddress("xperiapadctl", LocalSocketAddress.Namespace.ABSTRACT), 1000);
            s.setSoTimeout(1000);
            s.getOutputStream().write("PING\n".getBytes());
            s.getOutputStream().flush();
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
            r.readLine(); // PONG; si falla, el monitor retirara el ancla
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    @Override
    public void onDestroy() {
        if (receiverOn) { unregisterReceiver(stopReceiver); receiverOn = false; }
        nm.cancel(NID);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}
