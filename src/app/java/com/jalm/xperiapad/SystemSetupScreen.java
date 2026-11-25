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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Configuracion del sistema: aplica P2 (DisablePlugins=input) y revierte.
 * El framework nunca se toca: P1 fue retirado (E27) tras la cascada SIGBUS
 * de E26; el gate HID se esquiva con P2 + trusted del host.
 * Se abre sola al arrancar si falta P2; accesible desde Ajustes/Diagnostico.
 */
public class SystemSetupScreen extends Screen {

    private TextView dotRoot, txtRoot, dotStack, txtStack, dotInput, txtInput, dotP2, txtP2;
    private TextView warnView, logView;
    private Button bApply, bRevert, bWipe;
    private Button bContinue;

    private static final int GREEN = 0xFF66DD66;
    private static final int RED = 0xFFDD5555;
    private static final int GREY = 0xFF9BA0AE;

    public SystemSetupScreen(MainActivity app) { super(app); }

    public String title() { return ctx.getString(R.string.title_setup); }

    public void onShow() {
        app.logListener = new Runnable() {
            public void run() { refreshLog(); }
        };
        // root se comprueba solo al abrir esta pantalla (evita avisos al abrir la app)
        app.ensureRootCheck(new Runnable() {
            public void run() { detect(); }
        });
    }

    public void onHide() {
        app.logListener = null;
    }

    private final Runnable refresh = new Runnable() {
        public void run() {
            paint(); // sin reboot: P2 se aplica en vivo (respawn de bluetoothd)
        }
    };

    private final Runnable refreshAfterRevert = new Runnable() {
        public void run() {
            paint();
        }
    };

    private void detect() {
        app.runSetupDetection(new Runnable() {
            public void run() { paint(); }
        });
    }

    private void setDot(TextView dot, TextView label, int state, String text) {
        dot.setTextColor(state == 1 ? GREEN : state == -1 ? RED : GREY);
        label.setText(text);
    }

    /** Marcador corto y neutral: "+" presente, "!" ausente, "?" no comprobable. */
    private String mark(boolean ok) { return ok ? "+" : "!"; }

    private void paint() {
        if (dotRoot == null) return;

        setDot(dotRoot, txtRoot, app.setupRoot ? 1 : -1,
                ctx.getString(R.string.setup_root, (app.setupRoot ? ctx.getString(R.string.state_ok) : ctx.getString(R.string.state_no_upper))));

        // Stack: informativo. Nunca bloquea, aunque la ROM/kernel/stack difieran.
        String stack = mark(app.stackDbus) + "dbus " + mark(app.stackSdp) + "sdp "
                + mark(app.stackBluez4) + "bluez4 " + mark(app.stackConf) + "conf";
        setDot(dotStack, txtStack, 0, ctx.getString(R.string.setup_stack, stack));
        String input = app.stackTouchpadChecked ? mark(app.stackTouchpad) : "?";
        setDot(dotInput, txtInput, 0, ctx.getString(R.string.setup_input, input));

        String[] w = app.stackWarnings;
        if (w == null || w.length == 0) {
            warnView.setText(ctx.getString(R.string.setup_stack_ok, app.stackDetected));
            warnView.setTextColor(0xFFB8E6B8);
        } else {
            warnView.setText(ctx.getString(R.string.setup_stack_warn,
                    android.text.TextUtils.join(", ", w), app.stackDetected));
            warnView.setTextColor(0xFFFFCC66);
        }

        boolean p2 = "si".equals(app.setupBtPatch);
        setDot(dotP2, txtP2, "si".equals(app.setupBtPatch) ? 1 : "no".equals(app.setupBtPatch) ? -1 : 0,
                ctx.getString(R.string.setup_patch, (p2 ? ctx.getString(R.string.setup_applied) : ctx.getString(R.string.setup_not_applied))));

        boolean canApply = app.setupRoot && !p2;
        bApply.setEnabled(canApply && !app.setupBusy);
        bApply.setText(app.setupBusy ? ctx.getString(R.string.setup_working) :
                (canApply ? ctx.getString(R.string.setup_apply) : ctx.getString(R.string.setup_already)));

        bRevert.setEnabled(app.setupRoot && !app.setupBusy);
        bWipe.setEnabled(app.setupRoot && !app.setupBusy);

        // el boton de continuar solo tiene sentido si falta algo
        bContinue.setVisibility(p2 ? View.GONE : View.VISIBLE);
    }

    private void refreshLog() {
        if (logView == null) return;
        java.util.ArrayList<String> lines = app.snapshotLog();
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, lines.size() - 10);
        for (int i = start; i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
        logView.setText(sb.toString());
    }

    public View build() {
        ScrollView sc = new ScrollView(ctx);
        sc.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
        sc.setFillViewport(true);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121216);
        int p = app.dp(12);
        root.setPadding(p, p, p, p);
        sc.addView(root);

        TextView t = new TextView(ctx);
        t.setText(ctx.getString(R.string.title_setup));
        t.setTextColor(0xFFEDEDF2);
        t.setTextSize(18f);
        root.addView(t);

        TextView d = new TextView(ctx);
        d.setText(ctx.getString(R.string.setup_desc));
        d.setTextColor(0xFF9BA0AE);
        d.setTextSize(12f);
        d.setPadding(0, app.dp(4), 0, app.dp(6));
        root.addView(d);

        LinearLayout st = new LinearLayout(ctx);
        st.setOrientation(LinearLayout.VERTICAL);
        st.addView(statusRow()); // root
        st.addView(statusRow()); // stack bt
        st.addView(statusRow()); // input
        st.addView(statusRow()); // bt-patch
        root.addView(st);

        warnView = new TextView(ctx);
        warnView.setTextSize(11f);
        warnView.setPadding(0, app.dp(6), 0, app.dp(2));
        root.addView(warnView);

        bContinue = new Button(ctx);
        bContinue.setText(ctx.getString(R.string.setup_continue));
        bContinue.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { app.setScreen(MainActivity.IDX_HOME); }
        });
        root.addView(bContinue);

        bApply = new Button(ctx);
        bApply.setText(ctx.getString(R.string.setup_apply));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, app.dp(52));
        alp.topMargin = app.dp(8);
        bApply.setLayoutParams(alp);
        bApply.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirm(ctx.getString(R.string.setup_apply),
                        ctx.getString(R.string.setup_apply_confirm),
                        new Runnable() {
                            public void run() { app.applyPatchSequence(refresh); }
                        });
            }
        });
        root.addView(bApply);

        bRevert = new Button(ctx);
        bRevert.setText(ctx.getString(R.string.setup_revert_all));
        bRevert.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirm(ctx.getString(R.string.setup_revert_all),
                        ctx.getString(R.string.setup_revert_confirm),
                        new Runnable() {
                            public void run() { app.revertSystemSequence(refreshAfterRevert); }
                        });
            }
        });
        root.addView(bRevert);

        bWipe = new Button(ctx);
        bWipe.setText(ctx.getString(R.string.setup_wipe));
        bWipe.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirm(ctx.getString(R.string.setup_wipe),
                        ctx.getString(R.string.setup_wipe_confirm), new Runnable() {
                            public void run() {
                                app.appendLog("clearing app data...");
                                // Sin esto el daemon queda huerfano hasta el autostop de 120 s.
                                app.setDaemonEnabled(false);
                                app.suRC("pm clear com.jalm.xperiapad");
                            }
                        });
            }
        });
        root.addView(bWipe);

        TextView lh = new TextView(ctx);
        lh.setText(ctx.getString(R.string.setup_recent_log));
        lh.setTextColor(0xFF9BA0AE);
        lh.setTextSize(12f);
        lh.setPadding(0, app.dp(10), 0, 0);
        root.addView(lh);

        logView = new TextView(ctx);
        logView.setTextColor(0xFFB8E6B8);
        logView.setTextSize(10f);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(0, app.dp(4), 0, 0);
        root.addView(logView);

        paint();
        refreshLog();
        return sc;
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
        label.setTextSize(14f);
        label.setTypeface(Typeface.MONOSPACE);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(dot);
        row.addView(label);
        if (dotRoot == null) { dotRoot = dot; txtRoot = label; }
        else if (dotStack == null) { dotStack = dot; txtStack = label; }
        else if (dotInput == null) { dotInput = dot; txtInput = label; }
        else { dotP2 = dot; txtP2 = label; }
        return row;
    }

    /** Mini dialogo de confirmacion (Si/No). */
    private void confirm(String title, String msg, final Runnable onOk) {
        new android.app.AlertDialog.Builder(ctx)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(ctx.getString(R.string.action_yes), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { onOk.run(); }
                })
                .setNegativeButton(ctx.getString(R.string.action_no), null)
                .show();
    }
}
