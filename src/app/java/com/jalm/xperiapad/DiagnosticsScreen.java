/* Copyright (C) 2026 Jalm24
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Additional GPLv3 §7(b) term: this author attribution must be
 * preserved in modified source files and in the project's legal notices. */

package com.jalm.xperiapad;

import android.graphics.Typeface;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Pantalla de diagnostico: log acotado, comandos de prueba e info del dispositivo.
 */
public class DiagnosticsScreen extends Screen {

    private TextView logView, statusView;
    private ScrollView logScroll;
    private boolean autoScroll = true;

    public DiagnosticsScreen(MainActivity app) { super(app); }

    public String title() { return ctx.getString(R.string.title_diagnostics); }

    public String actionLabel() { return ctx.getString(R.string.diag_clear); }

    public void onAction() {
        app.clearLog();
        refreshLog();
    }

    private final Runnable refresh = new Runnable() {
        public void run() { refreshLog(); }
    };

    public void onShow() {
        app.logListener = refresh;
        refreshLog();
    }

    public void onHide() {
        app.logListener = null;
    }

    private void refreshLog() {
        if (logView == null) return;
        if (statusView != null) {
            statusView.setText(ctx.getString(R.string.diag_status,
                        (app.daemonRunning ? ctx.getString(R.string.state_active) : ctx.getString(R.string.state_stopped)),
                        (app.connected ? ctx.getString(R.string.state_connected) : ctx.getString(R.string.state_no_link)),
                        (app.armEnabled ? ctx.getString(R.string.state_active) : ctx.getString(R.string.state_no))));
        }
        StringBuilder sb = new StringBuilder();
        java.util.ArrayList<String> lines = app.snapshotLog();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i)).append('\n');
        }
        logView.setText(sb.toString());
        if (autoScroll) {
            logScroll.post(new Runnable() {
                public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
            });
        }
    }

    public View build() {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121216);
        int p = app.dp(10);
        root.setPadding(p, p, p, p);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button bPing = new Button(ctx);
        bPing.setText("PING");
        bPing.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        bPing.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { app.sendCmd("PING"); }
        });
        row.addView(bPing);

        Button bNeutral = new Button(ctx);
        bNeutral.setText("NEUTRAL");
        bNeutral.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        bNeutral.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { app.sendCmd("NEUTRAL"); }
        });
        row.addView(bNeutral);

        Button bStart = new Button(ctx);
        bStart.setText(ctx.getString(R.string.diag_start));
        bStart.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        bStart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { app.startDaemon(); }
        });
        row.addView(bStart);

        Button bStop = new Button(ctx);
        bStop.setText(ctx.getString(R.string.diag_stop));
        bStop.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        bStop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { app.stopDaemon(); }
        });
        row.addView(bStop);

        Button bSetup = new Button(ctx);
        bSetup.setText(ctx.getString(R.string.diag_system));
        bSetup.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        bSetup.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { app.setScreen(MainActivity.IDX_SETUP); }
        });
        row.addView(bSetup);
        root.addView(row);

        statusView = new TextView(ctx);
        statusView.setTextColor(0xFFEDEDF2);
        statusView.setTextSize(13f);
        statusView.setTypeface(android.graphics.Typeface.MONOSPACE);
        statusView.setPadding(0, app.dp(6), 0, 0);
        root.addView(statusView);

        TextView info = new TextView(ctx);
        info.setText("XperiaPad " + MainActivity.APP_VERSION + "  |  " + app.deviceInfo());
        info.setTextColor(0xFF7C8290);
        info.setTextSize(11f);
        info.setPadding(0, app.dp(4), 0, app.dp(4));
        root.addView(info);

        android.widget.CheckBox cbScroll = new android.widget.CheckBox(ctx);
        cbScroll.setText(ctx.getString(R.string.diag_autoscroll));
        cbScroll.setTextColor(0xFFEDEDF2);
        cbScroll.setTextSize(12f);
        cbScroll.setChecked(autoScroll);
        cbScroll.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) { autoScroll = on; }
        });
        root.addView(cbScroll);

        logScroll = new ScrollView(ctx);
        logScroll.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        logView = new TextView(ctx);
        logView.setTextColor(0xFFB8E6B8);
        logView.setTextSize(10f);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setGravity(Gravity.LEFT);
        logScroll.addView(logView);
        root.addView(logScroll);

        return root;
    }

}
