/* Copyright (C) 2026 Jalm24
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Additional GPLv3 §7(b) term: this author attribution must be
 * preserved in modified source files and in the project's legal notices. */

package com.jalm.xperiapad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Pantalla de controles: editor de las zonas del touchpad (sticks).
 * El editor ocupa el espacio disponible y NO esta dentro de un ScrollView,
 * por eso arrastrar hacia abajo no desplaza la pagina.
 */
public class ControlsScreen extends Screen {

    private StickEditorView editor;
    private TextView info, dzLabel, sensLabel;
    private SeekBar dzBar, sensBar;
    private final ArrayList<MainActivity.StickConfig> draftSticks = new ArrayList<MainActivity.StickConfig>();
    private CheckBox invCheck;
    private final Runnable selChanged = new Runnable() {
        public void run() { syncControls(); }
    };

    public ControlsScreen(MainActivity app) { super(app); }

    public String title() { return ctx.getString(R.string.title_controls); }

    public String actionLabel() { return null; }

    public void onAction() { }

    private static MainActivity.StickConfig copyStick(MainActivity.StickConfig s) {
        MainActivity.StickConfig c = new MainActivity.StickConfig();
        c.id = s.id; c.output = s.output;
        c.x = s.x; c.y = s.y; c.w = s.w; c.h = s.h;
        c.deadzone = s.deadzone; c.sensitivity = s.sensitivity; c.invertY = s.invertY;
        return c;
    }

    private void resetDraft() {
        draftSticks.clear();
        for (int i = 0; i < app.sticks.size(); i++) draftSticks.add(copyStick(app.sticks.get(i)));
    }

    private void confirmResetZones() {
        new android.app.AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.controls_reset_title))
                .setMessage(ctx.getString(R.string.controls_reset_msg))
                .setPositiveButton(ctx.getString(R.string.action_reset), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        draftSticks.clear();
                        app.fillDefaultSticks(draftSticks); // no aplica hasta Guardar
                        if (editor != null) editor.invalidate();
                        syncControls();
                    }
                })
                .setNegativeButton(ctx.getString(R.string.action_cancel), null)
                .show();
    }

    /** UNICO punto que persiste y aplica las zonas: el boton Guardar. */
    private void saveZones() {
        app.sticks.clear();
        for (int i = 0; i < draftSticks.size(); i++) app.sticks.add(copyStick(draftSticks.get(i)));
        app.saveSticks();
        android.widget.Toast.makeText(ctx, ctx.getString(R.string.controls_saved), android.widget.Toast.LENGTH_SHORT).show();
    }

    public void onShow() { resetDraft(); syncControls(); }

    /** Altura de la vista del editor: ampliada (ancho completo) o escala real (~42%). */
    private int editorHeight() {
        float w = app.displayW();
        float factor = app.stickEditorZoom ? 1f : 0.55f; // ancho fisico estimado del pad
        return (int) (factor * w * MainActivity.SURF_H / MainActivity.SURF_W);
    }

    private void syncControls() {
        if (editor == null) return;
        int sel = editor.getSel();
        if (sel < 0 || sel >= draftSticks.size()) return;
        MainActivity.StickConfig s = draftSticks.get(sel);
        info.setText(ctx.getString(R.string.controls_editing, s.id, (int) s.w, (int) s.h, (int) s.x, (int) s.y));
        dzLabel.setText(ctx.getString(R.string.controls_deadzone, String.format("%.2f", Float.valueOf(s.deadzone))));
        sensLabel.setText(ctx.getString(R.string.controls_sensitivity, String.format("%.2f", Float.valueOf(s.sensitivity))));
        dzBar.setProgress((int) (s.deadzone * 100f));
        sensBar.setProgress((int) (s.sensitivity * 100f));
        invCheck.setChecked(s.invertY);
    }

    public View build() {
        // pagina scrolleable: el editor arriba a tamano real, controles debajo
        android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121216);
        int p = app.dp(10);
        root.setPadding(p, p, p, p);
        scroll.addView(root);
        resetDraft();

        LinearLayout rowZoom = new LinearLayout(ctx);
        rowZoom.setOrientation(LinearLayout.HORIZONTAL);
        rowZoom.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView zLabel = new TextView(ctx);
        zLabel.setText(ctx.getString(R.string.controls_zoom));
        zLabel.setTextColor(0xFFEDEDF2);
        zLabel.setTextSize(14f);
        zLabel.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        rowZoom.addView(zLabel);
        SwitchView swZoom = new SwitchView(ctx);
        swZoom.setLayoutParams(new LinearLayout.LayoutParams(app.dp(56), app.dp(30)));
        swZoom.setChecked(app.stickEditorZoom);
        swZoom.setOnCheckedChangeListener(new SwitchView.OnCheckedChangeListener() {
            public void onCheckedChanged(SwitchView v, boolean checked) {
                app.setStickEditorZoom(checked);
                editor.setLayoutParams(new LinearLayout.LayoutParams(-1, editorHeight()));
                editor.invalidate();
            }
        });
        rowZoom.addView(swZoom);
        root.addView(rowZoom);

        // altura de la vista del editor segun la escala elegida
        editor = new StickEditorView(ctx);
        editor.onSelChanged = selChanged;
        editor.setLayoutParams(new LinearLayout.LayoutParams(-1, editorHeight()));
        root.addView(editor);

        info = new TextView(ctx);
        info.setTextColor(0xFFEDEDF2);
        info.setTextSize(13f);
        info.setPadding(0, app.dp(6), 0, app.dp(2));
        root.addView(info);

        // Guardar / Reiniciar, debajo del editor y antes de los sliders
        TextView hint = new TextView(ctx);
        hint.setText(ctx.getString(R.string.settings_save_hint));
        hint.setTextColor(0xFF7C8290);
        hint.setTextSize(12f);
        root.addView(hint);

        LinearLayout rowBtns = new LinearLayout(ctx);
        rowBtns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = app.dp(10);
        rowBtns.setLayoutParams(rlp);

        Button bSave = new Button(ctx);
        bSave.setText(ctx.getString(R.string.action_save));
        bSave.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        bSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { saveZones(); }
        });
        rowBtns.addView(bSave);

        Button bReset = new Button(ctx);
        bReset.setText(ctx.getString(R.string.action_reset));
        bReset.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        bReset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmResetZones(); }
        });
        rowBtns.addView(bReset);
        root.addView(rowBtns);

        LinearLayout rowDz = new LinearLayout(ctx);
        rowDz.setOrientation(LinearLayout.HORIZONTAL);
        rowDz.setGravity(android.view.Gravity.CENTER_VERTICAL);
        dzLabel = new TextView(ctx);
        dzLabel.setTextColor(0xFFEDEDF2);
        dzLabel.setTextSize(13f);
        dzLabel.setWidth(app.dp(150));
        rowDz.addView(dzLabel);
        dzBar = new SeekBar(ctx);
        dzBar.setMax(90);
        dzBar.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        dzBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int v, boolean fromUser) {
                MainActivity.StickConfig s = cur();
                if (s == null) return;
                s.deadzone = v / 100f;
                dzLabel.setText(ctx.getString(R.string.controls_deadzone, String.format("%.2f", Float.valueOf(s.deadzone))));
                editor.invalidate();
            }
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) { } // se guarda con el boton Guardar
        });
        rowDz.addView(dzBar);
        root.addView(rowDz);

        LinearLayout rowSens = new LinearLayout(ctx);
        rowSens.setOrientation(LinearLayout.HORIZONTAL);
        rowSens.setGravity(android.view.Gravity.CENTER_VERTICAL);
        sensLabel = new TextView(ctx);
        sensLabel.setTextColor(0xFFEDEDF2);
        sensLabel.setTextSize(13f);
        sensLabel.setWidth(app.dp(150));
        rowSens.addView(sensLabel);
        sensBar = new SeekBar(ctx);
        sensBar.setMax(300);
        sensBar.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        sensBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int v, boolean fromUser) {
                MainActivity.StickConfig s = cur();
                if (s == null) return;
                s.sensitivity = Math.max(0.1f, v / 100f);
                sensLabel.setText(ctx.getString(R.string.controls_sensitivity, String.format("%.2f", Float.valueOf(s.sensitivity))));
            }
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) { } // se guarda con el boton Guardar
        });
        rowSens.addView(sensBar);
        root.addView(rowSens);

        invCheck = new CheckBox(ctx);
        invCheck.setText(ctx.getString(R.string.controls_invert_y));
        invCheck.setTextColor(0xFFEDEDF2);
        invCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                MainActivity.StickConfig s = cur();
                if (s == null) return;
                s.invertY = on;
            }
        });
        root.addView(invCheck);

        Button bEditor = new Button(ctx);
        bEditor.setText(ctx.getString(R.string.controls_edit_layout));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
        elp.topMargin = app.dp(8);
        bEditor.setLayoutParams(elp);
        bEditor.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { app.enterEditorMode(); }
        });
        root.addView(bEditor);

        return scroll;
    }

    private MainActivity.StickConfig cur() {
        if (editor == null) return null;
        int sel = editor.getSel();
        if (sel < 0 || sel >= draftSticks.size()) return null;
        return draftSticks.get(sel);
    }

    /** Editor visual de las zonas de stick sobre la superficie del touchpad (966x360). */
    class StickEditorView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float scale = 1f;
        private float offX = 0f, offY = 0f;
        private int sel = 0;
        private int mode = 0; // 0=nada 1=mover 2=redimensionar
        private float grabDX = 0f, grabDY = 0f;
        private final float HANDLE = 20f;
        Runnable onSelChanged;

        StickEditorView(Context c) { super(c); }

        private void computeScale() {
            float w = getWidth(), h = getHeight();
            if (w <= 0f || h <= 0f) return;
            scale = Math.min(w / MainActivity.SURF_W, h / MainActivity.SURF_H);
            offX = (w - MainActivity.SURF_W * scale) / 2f;
            offY = (h - MainActivity.SURF_H * scale) / 2f;
        }

        private float vx(float x) { return offX + x * scale; }
        private float vy(float y) { return offY + y * scale; }
        private float toSx(float x) { return (x - offX) / scale; }
        private float toSy(float y) { return (y - offY) / scale; }

        int getSel() { return sel; }

        @Override
        protected void onDraw(Canvas cv) {
            computeScale();
            cv.drawColor(Color.rgb(22, 22, 26));

            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(46, 46, 54));
            cv.drawRect(vx(0), vy(0), vx(MainActivity.SURF_W), vy(MainActivity.SURF_H), p);

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setColor(Color.rgb(130, 130, 140));
            cv.drawRect(vx(0), vy(0), vx(MainActivity.SURF_W), vy(MainActivity.SURF_H), p);

            for (int i = 0; i < draftSticks.size(); i++) {
                MainActivity.StickConfig s = draftSticks.get(i);
                boolean selected = (i == sel);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(selected ? 4f : 2f);
                p.setColor(selected ? Color.rgb(120, 230, 120) : Color.rgb(200, 200, 210));
                cv.drawRect(vx(s.x), vy(s.y), vx(s.x + s.w), vy(s.y + s.h), p);

                float cx = vx(s.x + s.w / 2f);
                float cy = vy(s.y + s.h / 2f);
                p.setStrokeWidth(2f);
                p.setColor(Color.rgb(110, 160, 230));
                float dzR = Math.min(s.w, s.h) / 2f * s.deadzone;
                cv.drawCircle(cx, cy, dzR * scale, p);

                float cross = Math.min(s.w, s.h) * 0.18f * scale;
                p.setColor(Color.rgb(255, 210, 90));
                p.setStrokeWidth(3f);
                cv.drawLine(cx - cross, cy, cx + cross, cy, p);
                cv.drawLine(cx, cy - cross, cx, cy + cross, p);

                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.WHITE);
                p.setTextSize(Math.max(14f, 10f * scale + 10f));
                cv.drawText(s.id, vx(s.x) + 6f, vy(s.y) + 18f, p);

                if (selected) {
                    p.setColor(Color.rgb(255, 110, 110));
                    cv.drawRect(vx(s.x + s.w) - 10f, vy(s.y + s.h) - 10f,
                                vx(s.x + s.w) + 10f, vy(s.y + s.h) + 10f, p);
                }
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            computeScale();
            float x = ev.getX(), y = ev.getY();
            // Scroll selectivo: solo consumimos el gesto si empieza DENTRO del marco
            // del touchpad; fuera, la pagina scrollea con normalidad.
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                boolean inside = x >= offX && x <= offX + MainActivity.SURF_W * scale
                        && y >= offY && y <= offY + MainActivity.SURF_H * scale;
                if (!inside) return false; // el ScrollView se queda con el arrastre
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    MainActivity.StickConfig s = cur();
                    if (s != null) {
                        float hx = vx(s.x + s.w), hy = vy(s.y + s.h);
                        if (Math.abs(x - hx) <= HANDLE && Math.abs(y - hy) <= HANDLE) {
                            mode = 2;
                            return true;
                        }
                    }
                    float sx = toSx(x), sy = toSy(y);
                    for (int i = 0; i < draftSticks.size(); i++) {
                        MainActivity.StickConfig c = draftSticks.get(i);
                        if (sx >= c.x && sx <= c.x + c.w && sy >= c.y && sy <= c.y + c.h) {
                            sel = i;
                            mode = 1;
                            grabDX = sx - c.x;
                            grabDY = sy - c.y;
                            if (onSelChanged != null) onSelChanged.run();
                            invalidate();
                            return true;
                        }
                    }
                    mode = 0;
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    MainActivity.StickConfig s = cur();
                    if (s == null) return true;
                    if (mode == 1) {
                        s.x = MainActivity.clampf(toSx(x) - grabDX, 0f, MainActivity.SURF_W - s.w);
                        s.y = MainActivity.clampf(toSy(y) - grabDY, 0f, MainActivity.SURF_H - s.h);
                    } else if (mode == 2) {
                        float side = MainActivity.clampf(toSx(x) - s.x, 80f, Math.min(MainActivity.SURF_W, MainActivity.SURF_H));
                        s.w = side;
                        s.h = side;
                        if (s.x + s.w > MainActivity.SURF_W) s.x = MainActivity.SURF_W - s.w;
                        if (s.y + s.h > MainActivity.SURF_H) s.y = MainActivity.SURF_H - s.h;
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    mode = 0; // el borrador se aplica al pulsar Guardar
                    return true;
                }
                default:
                    return true;
            }
        }
    }
}
