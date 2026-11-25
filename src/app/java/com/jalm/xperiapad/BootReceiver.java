/* Copyright (C) 2026 Jalm24
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Additional GPLv3 §7(b) term: this author attribution must be
 * preserved in modified source files and in the project's legal notices. */

package com.jalm.xperiapad;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.io.File;

/** H3: el perfil HID debe existir antes de que un host conecte. Arranca el
 * daemon al boot y cada vez que el BT vuelve a encenderse. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            launch(ctx);
        } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            int st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (st == BluetoothAdapter.STATE_ON) launch(ctx);
        }
    }

    private void launch(Context ctx) {
        // El stack Bluetooth se ha reiniciado: los records SDP pueden estar
        // caidos aunque la identidad guardada coincida. Forzar re-registro.
        new File(ctx.getFilesDir(), "xpd.sdp.state").delete();
        if (!DaemonStarter.start(ctx)) return;
        ctx.startService(new Intent(ctx, DaemonService.class));
    }
}
