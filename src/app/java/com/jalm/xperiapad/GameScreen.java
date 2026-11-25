/* Copyright (C) 2026 Jalm24
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Additional GPLv3 §7(b) term: this author attribution must be
 * preserved in modified source files and in the project's legal notices. */

package com.jalm.xperiapad;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * Pantalla de modo juego. A pantalla completa; el contenido es el gamepad
 * virtual (botones, d-pad y sticks en pantalla). En modo normal trae un boton
 * "Salir" arriba al centro; en modo editor trae Salir / Anadir / Menu y un
 * boton ctx.getString(R.string.game_delete) flotante junto al elemento seleccionado.
 */
public class GameScreen extends Screen {

    private ButtonOverlayView overlay;
    private Button deleteBtn;

    public GameScreen(MainActivity app) { super(app); }

    public String title() { return ctx.getString(R.string.title_game); }

    public void onAction() { if (!app.editorMode) app.exitGameMode(); }

    private final Runnable refresh = new Runnable() {
        public void run() { }
    };

    private boolean toastShown = false; // evita repetir el Toast al reconstruir por idioma

    public void onShow() {
        if (toastShown) return;
        toastShown = true;
        android.widget.Toast.makeText(ctx,
                app.editorMode ? ctx.getString(R.string.game_editor_mode) : ctx.getString(R.string.game_mode_on),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    public void onHide() { toastShown = false; }

    private void update() { }

    private void editorMenu() {
        new AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.game_editor_title))
                .setItems(new String[]{
                        ctx.getString(R.string.game_add_button),
                        ctx.getString(R.string.game_add_stick),
                        ctx.getString(R.string.game_assign_key),
                        ctx.getString(R.string.game_load_preset_menu),
                        ctx.getString(R.string.game_reset_layout),
                        ctx.getString(R.string.game_save_as_new),
                        ctx.getString(R.string.game_overwrite, app.buttonPreset),
                        ctx.getString(R.string.game_exit_editor)},
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                if (which == 0) {
                                    app.addBtn("BTN", 427, 240, 30, 0, false, null);
                                    app.saveButtons();
                                    overlay.setSel(app.buttons.size() - 1);
                                    overlay.invalidate();
                                } else if (which == 1) {
                                    app.addBtn("LS", 427, 240, 70, -1, false, "left");
                                    app.saveButtons();
                                    overlay.setSel(app.buttons.size() - 1);
                                    overlay.invalidate();
                                } else if (which == 2) {
                                    pickLogical();
                                } else if (which == 3) {
                                    pickLoadPreset();
                                } else if (which == 4) {
                                    app.loadButtonPreset(app.buttonPreset);
                                    app.saveButtons();
                                    overlay.setSel(-1);
                                    overlay.invalidate();
                                } else if (which == 5) {
                                    pickSaveAsNew();
                                } else if (which == 6) {
                                    app.overwritePreset();
                                    Toast.makeText(ctx, ctx.getString(R.string.game_preset_updated, app.buttonPreset), Toast.LENGTH_SHORT).show();
                                } else {
                                    app.exitEditorMode();
                                }
                            }
                        })
                .setNegativeButton(ctx.getString(R.string.action_cancel), null)
                .show();
    }

    private void pickLogical() {
        int sel = (overlay == null) ? -1 : overlay.getSel();
        if (sel < 0 || sel >= app.buttons.size()) {
            Toast.makeText(ctx, ctx.getString(R.string.game_select_first), Toast.LENGTH_SHORT).show();
            return;
        }
        final MainActivity.ButtonConfig b = app.buttons.get(sel);
        if (b.output != null) {
            // stick virtual: elegir eje
            new AlertDialog.Builder(ctx)
                    .setTitle(ctx.getString(R.string.game_stick_title, b.id))
                    .setItems(new String[]{ctx.getString(R.string.game_stick_left), ctx.getString(R.string.game_stick_right)},
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int which) {
                                    b.output = (which == 0) ? "left" : "right";
                                    b.id = (which == 0) ? "LS" : "RS";
                                    app.saveButtons();
                                    overlay.invalidate();
                                }
                            })
                    .setNegativeButton(ctx.getString(R.string.action_cancel), null)
                    .show();
        } else if (b.dpad) {
            Toast.makeText(ctx, ctx.getString(R.string.game_dpad_hint), Toast.LENGTH_SHORT).show();
        } else {
            new AlertDialog.Builder(ctx)
                    .setTitle(b.id)
                    .setItems(MainActivity.LOGICAL_NAMES, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int which) {
                            b.logical = (which >= MainActivity.LOGICAL_NAMES.length - 1) ? -1 : which;
                            b.id = MainActivity.shortLabel(which, app.activeConsole());
                            app.saveButtons();
                            overlay.invalidate();
                        }
                    })
                    .setNegativeButton(ctx.getString(R.string.action_cancel), null)
                    .show();
        }
    }

    private void pickLoadPreset() {
        final String[] names = app.presetNames();
        new AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.game_load_preset))
                .setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        app.loadButtonPreset(names[which]);
                        app.saveButtons();
                        overlay.setSel(-1);
                        overlay.invalidate();
                        Toast.makeText(ctx, ctx.getString(R.string.game_preset_selected, names[which]), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(ctx.getString(R.string.action_cancel), null)
                .show();
    }

    private void pickSaveAsNew() {
        final EditText input = new EditText(ctx);
        input.setHint(ctx.getString(R.string.game_preset_hint));
        new AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.game_save_as_new))
                .setView(input)
                .setPositiveButton(ctx.getString(R.string.action_save), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String name = input.getText().toString();
                        if (name.trim().length() == 0) {
                            Toast.makeText(ctx, ctx.getString(R.string.game_empty_name), Toast.LENGTH_SHORT).show();
                            return;
                        }
                        app.saveAsNewPreset(name);
                        overlay.setSel(-1);
                        overlay.invalidate();
                        Toast.makeText(ctx, ctx.getString(R.string.game_preset_saved, name.trim()),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(ctx.getString(R.string.action_cancel), null)
                .show();
    }

    private void positionDelete() {
        if (deleteBtn == null) return;
        int sel = (overlay == null) ? -1 : overlay.getSel();
        if (sel < 0 || sel >= app.buttons.size()) {
            deleteBtn.setVisibility(View.GONE);
            return;
        }
        MainActivity.ButtonConfig b = app.buttons.get(sel);
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) deleteBtn.getLayoutParams();
        int w = 854, h = 480;
        lp.leftMargin = clampi((int) (b.cx + b.r) - app.dp(60), 0, w - app.dp(130));
        lp.topMargin = clampi((int) (b.cy - b.r) - app.dp(20), 0, h - app.dp(50));
        deleteBtn.setLayoutParams(lp);
        deleteBtn.setVisibility(View.VISIBLE);
    }

    private static int clampi(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    public View build() {
        android.widget.FrameLayout root = new android.widget.FrameLayout(ctx);
        root.setBackgroundColor(0xFF0A0A0E);

        overlay = new ButtonOverlayView(ctx, app);
        root.addView(overlay, new android.widget.FrameLayout.LayoutParams(-1, -1));

        if (app.editorMode) {
            overlay.selectionListener = new Runnable() {
                public void run() { positionDelete(); }
            };

            // unico boton cuadrado arriba al centro: abre el menu del editor
            Button menuBtn = new Button(ctx);
            menuBtn.setText(ctx.getString(R.string.game_menu));
            menuBtn.setTextSize(11f);
            menuBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { editorMenu(); }
            });
            android.widget.FrameLayout.LayoutParams mlp =
                    new android.widget.FrameLayout.LayoutParams(app.dp(52), app.dp(52));
            mlp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            mlp.topMargin = app.dp(6);
            menuBtn.setLayoutParams(mlp);
            root.addView(menuBtn);

            deleteBtn = new Button(ctx);
            deleteBtn.setText(ctx.getString(R.string.game_delete));
            deleteBtn.setVisibility(View.GONE);
            deleteBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    int sel = overlay.getSel();
                    if (sel < 0 || sel >= app.buttons.size()) return;
                    app.buttons.remove(sel);
                    app.saveButtons();
                    overlay.setSel(-1);
                    overlay.invalidate();
                }
            });
            root.addView(deleteBtn, new android.widget.FrameLayout.LayoutParams(-2, -2));
        } else {
            Button exit = new Button(ctx);
            exit.setText(ctx.getString(R.string.game_exit));
            android.widget.FrameLayout.LayoutParams elp =
                    new android.widget.FrameLayout.LayoutParams(-2, -2);
            elp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            elp.topMargin = app.dp(6);
            exit.setLayoutParams(elp);
            exit.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { app.exitGameMode(); }
            });
            root.addView(exit);
        }

        return root;
    }
}
