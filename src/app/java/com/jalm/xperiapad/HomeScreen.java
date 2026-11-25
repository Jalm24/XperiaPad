/* Copyright (C) 2026 Jalm24
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Additional GPLv3 §7(b) term: this author attribution must be
 * preserved in modified source files and in the project's legal notices. */

package com.jalm.xperiapad;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Pantalla de inicio. Dos columnas: estado a la izquierda, controles a la
 * derecha; el modo juego ocupa el ancho completo abajo.
 */
public class HomeScreen extends Screen {

    private static final int GREEN = 0xFF66DD66;
    private static final int RED = 0xFFDD5555;
    private static final int GREY = 0xFF9BA0AE;

    // estado (izquierda)
    private TextView dotBt, txtBt, dotDaemon, txtDaemon, dotLink, txtLink, dotHid, txtHid, dotBat, txtBat;
    // controles (derecha)
    private SwitchView swBt, swDaemon;
    private int statusRowsBuilt = 0;
    private boolean updatingUi = false; // evita que setChecked() dispare acciones de usuario
    private CheckBox cbDisc;
    private TextView discLabel;

    private final Runnable refresh = new Runnable() {
        public void run() { update(); }
    };

    public HomeScreen(MainActivity app) { super(app); }

    public String title() { return ctx.getString(R.string.title_home); }

    public String actionLabel() { return ctx.getString(R.string.action_game); }

    public void onAction() { app.enterGameMode(); }

    public void onShow() {
        app.statusListener = refresh;
        refresh.run();
    }

    public void onHide() {
        app.statusListener = null;
    }

    private void update() {
        int pending = app.btPending; // 1 encendiendo, -1 apagando
        boolean bt = (pending == 1) ? true : (pending == -1) ? false : app.btEnabled();

        if (txtBt != null) {
            if (pending != 0) {
                dotBt.setTextColor(GREY);
                txtBt.setText(ctx.getString(R.string.home_bt_state, (pending == 1 ? ctx.getString(R.string.home_turning_on) : ctx.getString(R.string.home_turning_off))));
            } else {
                setDot(dotBt, txtBt, bt, ctx.getString(R.string.home_bt_state, (bt ? ctx.getString(R.string.state_on) : ctx.getString(R.string.state_off))));
            }
        }
        if (txtDaemon != null) {
            setDot(dotDaemon, txtDaemon, app.daemonRunning,
                    ctx.getString(R.string.home_daemon, (app.daemonRunning ? ctx.getString(R.string.state_active) : ctx.getString(R.string.state_stopped))));
        }
        if (txtLink != null) {
            setDot(dotLink, txtLink, app.connected,
                    ctx.getString(R.string.home_link, (app.connected ? ctx.getString(R.string.state_connected) : ctx.getString(R.string.state_no_link))));
        }
        if (txtHid != null) {
            setDot(dotHid, txtHid, app.connected && app.armEnabled,
                    ctx.getString(R.string.home_hid, (app.connected && app.armEnabled ? ctx.getString(R.string.state_armed) : ctx.getString(R.string.state_disarmed))));
        }
        if (txtBat != null) {
            int b = app.batteryPct();
            dotBat.setTextColor(GREY);
            txtBat.setText(ctx.getString(R.string.home_battery, (b < 0 ? "?" : (b + "%"))));
        }

        if (swBt != null) {
            updatingUi = true;
            swBt.setChecked(bt);
            swDaemon.setChecked(app.daemonEnabled);
            // habilitado solo con BT encendido Y parche aplicado
            swDaemon.setEnabled(bt && app.patchedEnough());
            cbDisc.setEnabled(bt);
            cbDisc.setChecked(app.discoverable);
            if (discLabel != null) {
                discLabel.setText(app.discoverable
                        ? ctx.getString(R.string.home_disc_remaining, app.discoverableRemain)
                        : ctx.getString(R.string.home_discoverable));
            }
            updatingUi = false;
        }
    }
    private void setDot(TextView dot, TextView label, boolean ok, String text) {
        dot.setTextColor(ok ? GREEN : RED);
        label.setText(text);
    }

    private LinearLayout statusRow() {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView dot = new TextView(ctx);
        dot.setTypeface(Typeface.DEFAULT_BOLD);
        dot.setTextSize(15f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.rightMargin = app.dp(8);
        dot.setLayoutParams(dlp);
        TextView label = new TextView(ctx);
        label.setTextColor(0xFFEDEDF2);
        label.setTextSize(13f);
        label.setTypeface(Typeface.MONOSPACE);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(dot);
        row.addView(label);
        switch (statusRowsBuilt++) {
            case 0: dotBt = dot; txtBt = label; break;
            case 1: dotDaemon = dot; txtDaemon = label; break;
            case 2: dotLink = dot; txtLink = label; break;
            case 3: dotHid = dot; txtHid = label; break;
            default: dotBat = dot; txtBat = label; break;
        }
        return row;
    }

    private LinearLayout controlRow(View control, String label) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(0xFFEDEDF2);
        tv.setTextSize(14f);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(tv);
        control.setLayoutParams(new LinearLayout.LayoutParams(app.dp(56), app.dp(30)));
        row.addView(control);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = app.dp(6);
        row.setLayoutParams(rlp);
        return row;
    }

    @Override
    protected void onInvalidate() {
        dotBt = null; txtBt = null;
        dotDaemon = null; txtDaemon = null;
        dotLink = null; txtLink = null;
        dotHid = null; txtHid = null;
        dotBat = null; txtBat = null;
        swBt = null; swDaemon = null;
        cbDisc = null; discLabel = null;
        statusRowsBuilt = 0;
        updatingUi = false;
    }

    public View build() {
        statusRowsBuilt = 0;
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121216);
        int p = app.dp(12);
        root.setPadding(p, p, p, p);

        LinearLayout cols = new LinearLayout(ctx);
        cols.setOrientation(LinearLayout.HORIZONTAL);

        // --- izquierda: estado ---
        LinearLayout left = new LinearLayout(ctx);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView h1 = new TextView(ctx);
        h1.setText(ctx.getString(R.string.home_status));
        h1.setTextColor(GREY);
        h1.setTextSize(12f);
        left.addView(h1);
        LinearLayout st = new LinearLayout(ctx);
        st.setOrientation(LinearLayout.VERTICAL);
        st.setPadding(0, app.dp(6), 0, 0);
        st.addView(statusRow());
        st.addView(statusRow());
        st.addView(statusRow());
        st.addView(statusRow());
        st.addView(statusRow());
        left.addView(st);
        cols.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        // --- derecha: servicios ---
        LinearLayout right = new LinearLayout(ctx);
        right.setOrientation(LinearLayout.VERTICAL);

        TextView h2 = new TextView(ctx);
        h2.setText(ctx.getString(R.string.home_services));
        h2.setTextColor(GREY);
        h2.setTextSize(12f);
        h2.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        h2.setPadding(0, 0, 0, app.dp(6));
        right.addView(h2);

        swBt = new SwitchView(ctx);
        right.addView(controlRow(swBt, ctx.getString(R.string.home_bluetooth)));

        swDaemon = new SwitchView(ctx);
        right.addView(controlRow(swDaemon, ctx.getString(R.string.home_daemon_gamepad)));

        cbDisc = new CheckBox(ctx);
        discLabel = new TextView(ctx);
        discLabel.setTextColor(0xFFEDEDF2);
        discLabel.setTextSize(14f);
        discLabel.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        cbDisc.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        LinearLayout rowDisc = new LinearLayout(ctx);
        rowDisc.setOrientation(LinearLayout.HORIZONTAL);
        rowDisc.setGravity(Gravity.CENTER_VERTICAL);
        rowDisc.addView(discLabel);
        rowDisc.addView(cbDisc);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = app.dp(6);
        rowDisc.setLayoutParams(rl);
        right.addView(rowDisc);

        Button bReconnect = new Button(ctx);
        bReconnect.setText(ctx.getString(R.string.action_reconnect_hid));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = app.dp(8);
        bReconnect.setLayoutParams(rlp);
        bReconnect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { app.reconnectHid(); }
        });
        right.addView(bReconnect);

        cols.addView(right, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(cols);

        // --- listeners ---
        swBt.setOnCheckedChangeListener(new SwitchView.OnCheckedChangeListener() {
            public void onCheckedChanged(SwitchView v, boolean checked) {
                if (updatingUi) return;
                app.setBtEnabled(checked);
            }
        });
        swDaemon.setOnCheckedChangeListener(new SwitchView.OnCheckedChangeListener() {
            public void onCheckedChanged(SwitchView v, boolean checked) {
                if (updatingUi) return;
                app.setDaemonEnabled(checked);
            }
        });
        cbDisc.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (updatingUi) return;
                app.setDiscoverable(on);
            }
        });

        // --- modo juego ---
        Button bGame = new Button(ctx);
        bGame.setText(ctx.getString(R.string.action_game));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, app.dp(50));
        glp.topMargin = app.dp(10);
        bGame.setLayoutParams(glp);
        bGame.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { app.enterGameMode(); }
        });
        root.addView(bGame);

        refresh.run();
        return root;
    }
}
