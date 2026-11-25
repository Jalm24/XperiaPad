/* Copyright (C) 2026 Jalm24
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Additional GPLv3 §7(b) term: this author attribution must be
 * preserved in modified source files and in the project's legal notices. */

package com.jalm.xperiapad;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Locale;

/** Entrada del drawer que muestra los creditos incluidos en el APK. */
public class CreditsScreen extends Screen {

    public CreditsScreen(MainActivity app) { super(app); }

    public String title() { return ctx.getString(R.string.title_credits); }

    public View build() {
        ScrollView scroll = new ScrollView(ctx);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121216);
        int p = app.dp(14);
        root.setPadding(p, p, p, p);

        TextView tv = new TextView(ctx);
        tv.setTextColor(0xFFD7DAE3);
        tv.setTextSize(13f);
        tv.setText(loadCredits());
        root.addView(tv);

        scroll.addView(root);
        return scroll;
    }

    private String loadCredits() {
        try {
            String asset = "es".equals(Locale.getDefault().getLanguage())
                    ? "legal/CREDITS.md" : "legal/CREDITS-en.md";
            InputStream in = ctx.getAssets().open(asset);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line.replace("**", "").replace("#", "").trim()).append('\n');
            }
            r.close();
            String text = sb.toString();
            int start = text.indexOf("Credits\n");
            return start >= 0 ? text.substring(start) : text;
        } catch (Exception e) {
            return ctx.getString(R.string.title_credits);
        }
    }
}
