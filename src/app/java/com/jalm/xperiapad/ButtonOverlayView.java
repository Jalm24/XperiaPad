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

import java.util.HashMap;

/**
 * Overlay del gamepad virtual (modo juego). Multitactil real: cada puntero
 * queda asociado a su boton/stick hasta levantar el dedo. Ademas refleja el
 * estado de los botones fisicos y del touchpad (app.logicalState).
 * Tipos de elemento: boton (tecla logica), d-pad (4 cuadrantes) y stick
 * virtual (eje LX/LY o RX/RY).
 */
public class ButtonOverlayView extends View {

    private final MainActivity app;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    // puntero -> indice de elemento en app.buttons
    private final HashMap<Integer, Integer> ptrBtn = new HashMap<Integer, Integer>();
    // puntero -> direccion del d-pad (12/13/14/15)
    private final HashMap<Integer, Integer> ptrDir = new HashMap<Integer, Integer>();
    // puntero -> [x,y] normalizado del stick virtual (para dibujar la perilla)
    private final HashMap<Integer, float[]> ptrStick = new HashMap<Integer, float[]>();

    private final Runnable stateListener = new Runnable() {
        public void run() { invalidate(); }
    };

    // modo editor
    int sel = -1;
    private int mode = 0; // 0 nada, 1 mover, 2 redimensionar
    private float grabDX = 0f, grabDY = 0f;
    Runnable selectionListener;

    int getSel() { return sel; }

    void setSel(int v) {
        if (sel == v) return;
        sel = v;
        if (selectionListener != null) selectionListener.run();
    }

    public ButtonOverlayView(Context c, MainActivity app) {
        super(c);
        this.app = app;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        app.buttonStateListener = stateListener;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (app.buttonStateListener == stateListener) app.buttonStateListener = null;
        releaseAll();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas cv) {
        cv.drawColor(Color.rgb(10, 10, 14));

        for (int i = 0; i < app.buttons.size(); i++) {
            MainActivity.ButtonConfig b = app.buttons.get(i);
            boolean touched = ptrBtn.containsValue(Integer.valueOf(i));
            boolean selected = app.editorMode && (i == sel);

            if (b.output != null) {
                drawStick(cv, b, i, selected);
            } else if (b.dpad) {
                boolean any = touched || app.logicalState[12] || app.logicalState[13]
                        || app.logicalState[14] || app.logicalState[15];
                drawDpad(cv, b, any, selected);
            } else {
                boolean pressed = touched || (b.logical >= 0 && b.logical < app.logicalState.length
                        && app.logicalState[b.logical]);
                drawButton(cv, b, pressed, selected);
            }
        }

        if (app.editorMode) {
            if (sel >= 0 && sel < app.buttons.size()) {
                MainActivity.ButtonConfig s = app.buttons.get(sel);
                // mango de redimension en la esquina inferior derecha del radio
                float hx = s.cx + s.r * 0.7071f, hy = s.cy + s.r * 0.7071f;
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.rgb(255, 110, 110));
                cv.drawRect(hx - 10f, hy - 10f, hx + 10f, hy + 10f, p);
            }
            tp.setColor(Color.rgb(220, 220, 230));
            tp.setTextSize(dp(12));
            cv.drawText(getContext().getString(R.string.editor_hint),
                    dp(6), getHeight() - dp(6), tp);
        }
    }

    private void drawButton(Canvas cv, MainActivity.ButtonConfig b, boolean pressed, boolean selected) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(pressed ? Color.rgb(80, 180, 90) : Color.rgb(52, 52, 64));
        cv.drawCircle(b.cx, b.cy, b.r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(selected ? 5f : 2.5f);
        p.setColor(selected ? Color.rgb(120, 230, 120) : Color.rgb(150, 150, 165));
        cv.drawCircle(b.cx, b.cy, b.r, p);

        String console = app.activeConsole();
        // PS4: los 4 simbolos de cara se dibujan como formas (la fuente puede no tenerlos)
        if ("ps4".equals(console) && b.logical >= 0 && b.logical <= 3) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(3f, b.r * 0.14f));
            p.setColor(Color.WHITE);
            float s = b.r * 0.45f;
            if (b.logical == 0) { // cruz
                cv.drawLine(b.cx - s, b.cy - s, b.cx + s, b.cy + s, p);
                cv.drawLine(b.cx - s, b.cy + s, b.cx + s, b.cy - s, p);
            } else if (b.logical == 1) { // circulo
                cv.drawCircle(b.cx, b.cy, s, p);
            } else if (b.logical == 2) { // cuadrado
                cv.drawRect(b.cx - s * 0.9f, b.cy - s * 0.9f, b.cx + s * 0.9f, b.cy + s * 0.9f, p);
            } else { // triangulo (apunta arriba)
                android.graphics.Path t = new android.graphics.Path();
                t.moveTo(b.cx, b.cy - s);
                t.lineTo(b.cx + s, b.cy + s * 0.7f);
                t.lineTo(b.cx - s, b.cy + s * 0.7f);
                t.close();
                cv.drawPath(t, p);
            }
            return;
        }

        tp.setColor(Color.WHITE);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(b.r * 0.72f);
        cv.drawText(MainActivity.shortLabel(b.logical, console), b.cx, b.cy + b.r * 0.26f, tp);
        tp.setTextAlign(Paint.Align.LEFT);
    }

    private void drawDpad(Canvas cv, MainActivity.ButtonConfig b, boolean pressed, boolean selected) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(pressed || selected ? 5f : 3f);
        p.setColor(selected ? Color.rgb(120, 230, 120)
                : (pressed ? Color.rgb(120, 230, 120) : Color.rgb(160, 160, 175)));
        cv.drawCircle(b.cx, b.cy, b.r, p);
        p.setColor(Color.rgb(70, 70, 85));
        p.setStyle(Paint.Style.FILL);
        cv.drawCircle(b.cx, b.cy, b.r * 0.55f, p);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(b.r * 0.32f);
        tp.setColor(Color.rgb(230, 230, 240));
        cv.drawText("▲", b.cx, b.cy - b.r * 0.5f, tp);
        cv.drawText("▼", b.cx, b.cy + b.r * 0.92f, tp);
        cv.drawText("◀", b.cx - b.r * 0.62f, b.cy + b.r * 0.12f, tp);
        cv.drawText("▶", b.cx + b.r * 0.62f, b.cy + b.r * 0.12f, tp);
        tp.setTextAlign(Paint.Align.LEFT);
    }

    private void drawStick(Canvas cv, MainActivity.ButtonConfig b, int index, boolean selected) {
        boolean touched = ptrBtn.containsValue(Integer.valueOf(index));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(selected ? 5f : (touched ? 5f : 3f));
        p.setColor(selected ? Color.rgb(120, 230, 120)
                : (touched ? Color.rgb(120, 230, 120) : Color.rgb(160, 160, 175)));
        cv.drawCircle(b.cx, b.cy, b.r, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(40, 255, 255, 255));
        cv.drawCircle(b.cx, b.cy, b.r, p);

        // perilla: posicion del dedo (normalizada) o centro
        float kx = b.cx, ky = b.cy;
        Object[] ids = ptrBtn.keySet().toArray();
        for (int i = 0; i < ids.length; i++) {
            Integer pid = (Integer) ids[i];
            if (ptrBtn.get(pid).intValue() != index) continue;
            float[] v = ptrStick.get(pid);
            if (v != null) { kx = b.cx + v[0] * b.r; ky = b.cy + v[1] * b.r; }
        }
        p.setColor(Color.rgb(90, 90, 105));
        cv.drawCircle(kx, ky, b.r * 0.42f, p);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(b.r * 0.26f);
        tp.setColor(Color.rgb(220, 220, 230));
        cv.drawText(b.id, b.cx, b.cy + b.r * 0.09f, tp);
        tp.setTextAlign(Paint.Align.LEFT);
    }

    private int hitElement(float x, float y) {
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < app.buttons.size(); i++) {
            MainActivity.ButtonConfig b = app.buttons.get(i);
            float dx = x - b.cx, dy = y - b.cy;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d <= b.r * 1.15f && d < bestD) { best = i; bestD = d; }
        }
        return best;
    }

    private int dpadDir(MainActivity.ButtonConfig b, float x, float y) {
        float dx = x - b.cx, dy = y - b.cy;
        if (Math.abs(dy) >= Math.abs(dx)) return (dy < 0) ? 12 : 13; // arriba / abajo
        return (dx < 0) ? 14 : 15; // izquierda / derecha
    }

    private void press(MainActivity.ButtonConfig b, int pointerId, int dir) {
        int idx = app.buttons.indexOf(b);
        ptrBtn.put(Integer.valueOf(pointerId), Integer.valueOf(idx));
        if (b.dpad) {
            ptrDir.put(Integer.valueOf(pointerId), Integer.valueOf(dir));
            app.setLogicalButton(dir, true);
        } else if (b.output != null) {
            updateStick(b, pointerId, b.cx, b.cy);
        } else {
            app.setLogicalButton(b.logical, true); // setLogicalButton valida el rango
        }
    }

    private void updateStick(MainActivity.ButtonConfig b, int pointerId, float x, float y) {
        float rr = b.r > 0 ? b.r : 1f;
        float dx = x - b.cx, dy = y - b.cy;
        float nx = dx / rr, ny = dy / rr;
        float m = (float) Math.sqrt(nx * nx + ny * ny);
        if (m > 1f) { nx /= m; ny /= m; m = 1f; }
        // zona muerta fija del stick virtual
        float dead = 0.12f;
        float ox = 0f, oy = 0f;
        if (m > dead) {
            float sc = (m - dead) / (1f - dead);
            if (sc > 1f) sc = 1f;
            ox = (nx / m) * sc;
            oy = (ny / m) * sc;
        }
        ptrStick.put(Integer.valueOf(pointerId), new float[]{nx, ny});
        // eje Y de pantalla: abajo = +1 (convencion SDL)
        app.setStickAxis(b.output, ox, oy);
    }

    private void release(int pointerId) {
        Integer idx = ptrBtn.remove(Integer.valueOf(pointerId));
        Integer dir = ptrDir.remove(Integer.valueOf(pointerId));
        ptrStick.remove(Integer.valueOf(pointerId));
        if (idx == null) return;
        MainActivity.ButtonConfig b = app.buttons.get(idx.intValue());
        if (b.dpad && dir != null) app.setLogicalButton(dir.intValue(), false);
        else if (b.output != null) app.setStickAxis(b.output, 0f, 0f);
        else if (!b.dpad) app.setLogicalButton(b.logical, false);
    }

    private void releaseAll() {
        Object[] ids = ptrBtn.keySet().toArray();
        for (int i = 0; i < ids.length; i++) release(((Integer) ids[i]).intValue());
        ptrBtn.clear();
        ptrDir.clear();
        ptrStick.clear();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int act = ev.getActionMasked();
        int idx = ev.getActionIndex();
        if (app.editorMode) { handleEditorTouch(ev, act, idx); invalidate(); return true; }
        float x, y;
        switch (act) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                x = ev.getX(idx);
                y = ev.getY(idx);
                int bi = hitElement(x, y);
                if (bi >= 0) {
                    MainActivity.ButtonConfig b = app.buttons.get(bi);
                    press(b, ev.getPointerId(idx), b.dpad ? dpadDir(b, x, y) : -1);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < ev.getPointerCount(); i++) {
                    Integer bi = ptrBtn.get(Integer.valueOf(ev.getPointerId(i)));
                    if (bi == null) continue;
                    MainActivity.ButtonConfig b = app.buttons.get(bi.intValue());
                    if (b.dpad) {
                        int nd = dpadDir(b, ev.getX(i), ev.getY(i));
                        Integer old = ptrDir.get(Integer.valueOf(ev.getPointerId(i)));
                        if (old == null || old.intValue() != nd) {
                            if (old != null) app.setLogicalButton(old.intValue(), false);
                            app.setLogicalButton(nd, true);
                            ptrDir.put(Integer.valueOf(ev.getPointerId(i)), Integer.valueOf(nd));
                        }
                    } else if (b.output != null) {
                        updateStick(b, ev.getPointerId(i), ev.getX(i), ev.getY(i));
                    }
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                release(ev.getPointerId(idx));
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                releaseAll();
                break;
            }
            default:
                break;
        }
        invalidate();
        return true;
    }

    // ===== Modo editor =====

    private void handleEditorTouch(MotionEvent ev, int act, int idx) {
        float x, y;
        switch (act) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                x = ev.getX(idx);
                y = ev.getY(idx);
                if (sel >= 0 && sel < app.buttons.size()) {
                    MainActivity.ButtonConfig s = app.buttons.get(sel);
                    float hx = s.cx + s.r * 0.7071f, hy = s.cy + s.r * 0.7071f;
                    if (Math.abs(x - hx) <= 22f && Math.abs(y - hy) <= 22f) { mode = 2; return; }
                }
                int found = hitElement(x, y);
                if (found >= 0) {
                    setSel(found);
                    mode = 1;
                    MainActivity.ButtonConfig b = app.buttons.get(found);
                    grabDX = x - b.cx;
                    grabDY = y - b.cy;
                } else {
                    setSel(-1);
                    mode = 0;
                }
                return;
            }
            case MotionEvent.ACTION_MOVE: {
                if (sel < 0 || sel >= app.buttons.size()) return;
                MainActivity.ButtonConfig b = app.buttons.get(sel);
                if (mode == 1) {
                    x = ev.getX(idx == -1 ? 0 : idx);
                    y = ev.getY(idx == -1 ? 0 : idx);
                    // usa el primer puntero activo
                    if (ev.getPointerCount() > 0) {
                        x = ev.getX(0);
                        y = ev.getY(0);
                    }
                    b.cx = MainActivity.clampf(x - grabDX, b.r, 854f - b.r);
                    b.cy = MainActivity.clampf(y - grabDY, b.r, 480f - b.r);
                } else if (mode == 2) {
                    x = ev.getX(0);
                    y = ev.getY(0);
                    float dx = x - b.cx, dy = y - b.cy;
                    b.r = MainActivity.clampf((float) Math.sqrt(dx * dx + dy * dy), 18f, 140f);
                }
                return;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mode != 0) app.saveButtons();
                mode = 0;
                return;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                return;
            }
            default:
                return;
        }
    }
}
