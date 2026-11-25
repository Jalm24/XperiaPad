/* Copyright (C) 2026 Jalm24
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Additional GPLv3 §7(b) term: this author attribution must be
 * preserved in modified source files and in the project's legal notices. */

package com.jalm.xperiapad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Interruptor visual (pista + perilla) para Android 2.3, que no tiene el
 * widget Switch (llego en ICS). Animacion manual con postDelayed.
 */
public class SwitchView extends View {

    public interface OnCheckedChangeListener {
        void onCheckedChanged(SwitchView v, boolean checked);
    }

    private OnCheckedChangeListener listener;
    private boolean checked;
    private float knob = 0f; // 0=off 1=on
    private boolean animating;
    private boolean enabledState = true;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobP = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Runnable stepper = new Runnable() {
        public void run() {
            float target = checked ? 1f : 0f;
            float step = 0.2f;
            if (Math.abs(knob - target) <= step) {
                knob = target;
                animating = false;
                invalidate();
                return;
            }
            knob += (knob < target ? step : -step);
            invalidate();
            postDelayed(this, 16);
        }
    };

    public SwitchView(Context c) {
        super(c);
        knobP.setColor(0xFFEDEDF2);
        setClickable(true); // necesario para recibir el toque y llamar performClick()
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener l) { listener = l; }

    public boolean isChecked() { return checked; }

    /** Cambia el estado visual sin notificar al listener. */
    public void setChecked(boolean c) {
        if (checked == c) return;
        checked = c;
        if (!animating) { animating = true; post(stepper); }
        invalidate();
    }

    public void toggle() { setChecked(!checked, true); }

    /** Cambia el estado notificando al listener. */
    public void setChecked(boolean c, boolean notify) {
        if (checked == c) return;
        checked = c;
        if (!animating) { animating = true; post(stepper); }
        if (notify && listener != null) listener.onCheckedChanged(this, checked);
        invalidate();
    }

    @Override public void setEnabled(boolean en) {
        enabledState = en;
        invalidate();
    }

    @Override public boolean isEnabled() { return enabledState; }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onMeasure(int wms, int hms) {
        setMeasuredDimension(
                View.resolveSize((int) dp(56), wms),
                View.resolveSize((int) dp(30), hms));
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        float trackH = h * 0.8f;
        float ty = (h - trackH) / 2f;
        float r = trackH / 2f;

        if (!enabledState) track.setColor(0xFF2C2C34);
        else if (checked) track.setColor(0xFF3E9E4E);
        else track.setColor(0xFF42424E);
        cv.drawRoundRect(new RectF(0, ty, w, ty + trackH), r, r, track);

        // la perilla llena la altura de la pista (proporcion 2:1, como el sistema)
        float knobR = trackH * 0.5f;
        float cy = ty + trackH / 2f;
        float kx = knobR + (w - 2 * knobR) * knob;
        knobP.setColor(enabledState ? 0xFFEDEDF2 : 0xFF8A8A94);
        cv.drawCircle(kx, cy, knobR, knobP);
    }

    @Override
    public boolean performClick() {
        if (!enabledState) return true;
        setChecked(!checked, true);
        return super.performClick();
    }
}
