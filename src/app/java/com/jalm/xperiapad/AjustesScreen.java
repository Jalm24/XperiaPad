/* Copyright (C) 2026 Jalm24
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Additional GPLv3 §7(b) term: this author attribution must be
 * preserved in modified source files and in the project's legal notices. */

package com.jalm.xperiapad;

import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Ajustes persistentes de la app (no de la sesion): timeout de descubrimiento,
 * pantalla encendida, y acceso a la configuracion del sistema.
 */
public class AjustesScreen extends Screen {

    private TextView labelDisc;
    private SeekBar discSeek;
    private CheckBox keepCheck;
    private RadioGroup langGroup;
    private boolean updatingUi = false;
    private int pendingDiscSec = -1;
    private boolean pendingKeep = false;
    private String pendingLocale = null;

    public AjustesScreen(MainActivity app) { super(app); }

    public String title() { return ctx.getString(R.string.title_settings); }

    public void onShow() { sync(); }

    @Override
    protected void onInvalidate() {
        labelDisc = null;
        discSeek = null;
        keepCheck = null;
        langGroup = null;
        updatingUi = false;
    }

    private void sync() {
        if (labelDisc == null) return;
        updatingUi = true;
        labelDisc.setText(ctx.getString(R.string.settings_disc_fmt, pendingDiscSec));
        if (discSeek != null) discSeek.setProgress(pendingDiscSec - 15);
        if (keepCheck != null) keepCheck.setChecked(pendingKeep);
        if (langGroup != null) {
            langGroup.check("es".equals(pendingLocale) ? 102 : "en".equals(pendingLocale) ? 103 : 101);
        }
        updatingUi = false;
    }

    private void savePending() {
        if (pendingDiscSec != app.discoverableSec) app.setDiscoverableSec(pendingDiscSec);
        if (pendingKeep != app.keepScreenOn) app.setKeepScreenOn(pendingKeep);
        boolean langChanged = pendingLocale != null && !pendingLocale.equals(app.localeMode);
        if (langChanged) {
            app.setLocaleMode(pendingLocale); // retranslateUi reconstruye esta pantalla
            return;
        }
        Toast.makeText(ctx, ctx.getString(R.string.settings_saved), Toast.LENGTH_SHORT).show();
    }

    private void revertPending() {
        pendingDiscSec = app.discoverableSec;
        pendingKeep = app.keepScreenOn;
        pendingLocale = app.localeMode;
        sync();
    }

    public View build() {
        ScrollView scroll = new ScrollView(ctx);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121216);
        int p = app.dp(14);
        root.setPadding(p, p, p, p);
        scroll.addView(root);

        pendingDiscSec = app.discoverableSec;
        pendingKeep = app.keepScreenOn;
        pendingLocale = app.localeMode;

        TextView h = new TextView(ctx);
        h.setText(ctx.getString(R.string.title_settings));
        h.setTextColor(0xFF9BA0AE);
        h.setTextSize(13f);
        root.addView(h);

        TextView hint = new TextView(ctx);
        hint.setText(ctx.getString(R.string.settings_save_hint));
        hint.setTextColor(0xFF7C8290);
        hint.setTextSize(12f);
        root.addView(hint);

        TextView t1 = new TextView(ctx);
        t1.setText(ctx.getString(R.string.settings_bt_discovery));
        t1.setTextColor(0xFFEDEDF2);
        t1.setTextSize(15f);
        t1.setPadding(0, app.dp(10), 0, 0);
        root.addView(t1);

        labelDisc = new TextView(ctx);
        labelDisc.setTextColor(0xFF9BA0AE);
        labelDisc.setTextSize(13f);
        labelDisc.setPadding(0, app.dp(4), 0, 0);
        root.addView(labelDisc);

        SeekBar seek = new SeekBar(ctx);
        discSeek = seek;
        seek.setMax(285); // 15..300 s
        seek.setProgress(pendingDiscSec - 15);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = app.dp(6);
        seek.setLayoutParams(slp);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int v, boolean fromUser) {
                if (fromUser && !updatingUi) pendingDiscSec = v + 15;
                sync();
            }
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) {}
        });
        root.addView(seek);

        TextView d1 = new TextView(ctx);
        d1.setText(ctx.getString(R.string.settings_disc_desc));
        d1.setTextColor(0xFF7C8290);
        d1.setTextSize(12f);
        d1.setPadding(0, app.dp(4), 0, 0);
        root.addView(d1);

        TextView t2 = new TextView(ctx);
        t2.setText(ctx.getString(R.string.settings_screen));
        t2.setTextColor(0xFFEDEDF2);
        t2.setTextSize(15f);
        t2.setPadding(0, app.dp(14), 0, 0);
        root.addView(t2);

        CheckBox cbKeep = new CheckBox(ctx);
        cbKeep.setText(ctx.getString(R.string.settings_keep_screen));
        cbKeep.setTextColor(0xFFEDEDF2);
        keepCheck = cbKeep;
        cbKeep.setChecked(pendingKeep);
        cbKeep.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (updatingUi) return;
                pendingKeep = on;
            }
        });
        root.addView(cbKeep);

        // --- idioma: seguir el sistema o forzar uno ---
        TextView tLang = new TextView(ctx);
        tLang.setText(ctx.getString(R.string.settings_language));
        tLang.setTextColor(0xFFEDEDF2);
        tLang.setTextSize(15f);
        tLang.setPadding(0, app.dp(14), 0, 0);
        root.addView(tLang);

        RadioGroup rgLang = new RadioGroup(ctx);
        langGroup = rgLang;
        rgLang.setOrientation(RadioGroup.VERTICAL);
        int[] langIds = { 101, 102, 103 };
        int[] langLabels = { R.string.lang_system, R.string.lang_es, R.string.lang_en };
        for (int i = 0; i < 3; i++) {
            RadioButton rb = new RadioButton(ctx);
            rb.setId(langIds[i]);
            rb.setText(ctx.getString(langLabels[i]));
            rb.setTextColor(0xFFEDEDF2);
            rgLang.addView(rb);
        }
        root.addView(rgLang);
        updatingUi = true;
        int checked = "es".equals(pendingLocale) ? 102 : "en".equals(pendingLocale) ? 103 : 101;
        rgLang.check(checked);
        updatingUi = false;
        rgLang.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(RadioGroup g, int id) {
                if (updatingUi) return;
                pendingLocale = id == 102 ? "es" : id == 103 ? "en" : "system";
            }
        });

        // --- configuracion del sistema (subpagina) ---
        TextView t3 = new TextView(ctx);
        t3.setText(ctx.getString(R.string.settings_system));
        t3.setTextColor(0xFFEDEDF2);
        t3.setTextSize(15f);
        t3.setPadding(0, app.dp(14), 0, 0);
        root.addView(t3);

        Button bSetup = new Button(ctx);
        bSetup.setText(ctx.getString(R.string.settings_system_config));
        bSetup.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { app.setScreen(MainActivity.IDX_SETUP); }
        });
        root.addView(bSetup);

        TextView d2 = new TextView(ctx);
        d2.setText(ctx.getString(R.string.settings_system_desc));
        d2.setTextColor(0xFF7C8290);
        d2.setTextSize(12f);
        d2.setPadding(0, app.dp(4), 0, 0);
        root.addView(d2);

        LinearLayout rowButtons = new LinearLayout(ctx);
        rowButtons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bslp = new LinearLayout.LayoutParams(-1, -2);
        bslp.topMargin = app.dp(14);
        rowButtons.setLayoutParams(bslp);

        Button bSave = new Button(ctx);
        bSave.setText(ctx.getString(R.string.action_save));
        bSave.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        bSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { savePending(); }
        });
        rowButtons.addView(bSave);

        Button bReset = new Button(ctx);
        bReset.setText(ctx.getString(R.string.action_reset));
        bReset.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        bReset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { revertPending(); }
        });
        rowButtons.addView(bReset);
        root.addView(rowButtons);

        return scroll;
    }
}
