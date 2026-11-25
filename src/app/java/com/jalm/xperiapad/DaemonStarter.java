/* Copyright (C) 2026 Jalm24
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Additional GPLv3 §7(b) term: this author attribution must be
 * preserved in modified source files and in the project's legal notices. */

package com.jalm.xperiapad;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;

/** H3: arranque del daemon fuera de la Activity (boot / BT encendido).
 * Respeta los mismos prefs que el toggle de la app. */
final class DaemonStarter {
    private DaemonStarter() {}

    static boolean start(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        if (!sp.getBoolean(MainActivity.PREF_DAEMON, true)) return false;
        if (!"si".equals(sp.getString("last_patched_state", ""))) return false;

        String bin = ctx.getApplicationInfo().nativeLibraryDir + "/libxpd.so";
        File log = new File(ctx.getFilesDir(), "xpd.log");
        String cmd = "killall xpd 2>/dev/null; killall libxpd.so 2>/dev/null; "
                + bin + " --uid " + ctx.getApplicationInfo().uid
                + " --log " + log.getAbsolutePath() + " >/dev/null 2>&1 &";
        try {
            return Runtime.getRuntime().exec(new String[]{"su", "-c", cmd}).waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
