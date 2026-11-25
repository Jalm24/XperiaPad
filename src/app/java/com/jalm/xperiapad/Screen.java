/* Copyright (C) 2026 Jalm24
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Additional GPLv3 §7(b) term: this author attribution must be
 * preserved in modified source files and in the project's legal notices. */

package com.jalm.xperiapad;

import android.view.View;

/**
 * Una pantalla del shell. Construye su contenido una vez (build) y declara el
 * titulo y la accion de la topbar. El motor (socket, captura de teclas, ARM)
 * vive en MainActivity; las pantallas solo presentan y editan su estado.
 */
public abstract class Screen {
    protected final MainActivity app;
    protected final android.content.Context ctx;
    private View root;

    protected Screen(MainActivity app) {
        this.app = app;
        this.ctx = app;
    }

    /** Titulo que se muestra en la topbar y en el menu lateral. */
    public abstract String title();

    /** Construye la vista raiz de la pantalla. */
    public abstract View build();

    /** Etiqueta de la accion de la topbar (null = sin accion). */
    public String actionLabel() { return null; }

    /** Se pulso la accion de la topbar. */
    public void onAction() {}

    /** La pantalla pasa a ser visible. */
    public void onShow() {}

    /** La pantalla deja de ser visible. */
    public void onHide() {}

    /** Descarta la vista cacheada para reconstruirla con otro idioma. */
    final void invalidate() {
        root = null;
        onInvalidate();
    }

    /** Hook para que una pantalla suelte sus referencias a vistas descartadas. */
    protected void onInvalidate() {}

    /** Vista raiz, construida una sola vez. */
    public final View view() {
        if (root == null) root = build();
        return root;
    }
}
