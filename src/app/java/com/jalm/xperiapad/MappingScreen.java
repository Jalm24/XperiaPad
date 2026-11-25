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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Map;
import java.util.TreeMap;

/**
 * Pantalla unificada de Mapeo + Perfiles: el mapeo de botones fisicos y los
 * perfiles (mapeo + sticks + gamepad virtual) viven en la misma pagina,
 * todo dentro de un solo scroll.
 */
public class MappingScreen extends Screen {

    private LinearLayout listBox;
    private TextView activeView;
    private Spinner spinner;
    private String[] rawNames = new String[0];
    private String selectedProfile = "";
    private String pendingProtocol = null; // seleccion pendiente del radio (aplica en Guardar cambios)
    private TreeMap<Integer, Integer> draftKeyMap = null; // reasignaciones pendientes de Guardar

    public MappingScreen(MainActivity app) { super(app); }

    public String title() { return ctx.getString(R.string.title_mapping); }

    public void onShow() { rebuildAll(); }

    // ===== Perfil =====

    private void updateActive() {
        if (activeView == null) return;
        String a = app.activeProfile();
        activeView.setText(ctx.getString(R.string.map_active_profile, (a.length() > 0 ? a : ctx.getString(R.string.profile_none))));
    }

    private void rebuildSpinner() {
        if (spinner == null) return;
        String[] names = app.profileNames();
        rawNames = names;
        java.util.ArrayList<String> items = new java.util.ArrayList<String>();
        for (int i = 0; i < names.length; i++) {
            items.add(names[i] + (app.isBuiltinProfile(names[i]) ? ctx.getString(R.string.profile_factory) : ""));
        }
        if (items.isEmpty()) items.add(ctx.getString(R.string.map_no_profiles));

        ArrayAdapter<String> ad = new ArrayAdapter<String>(ctx,
                android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(ad);

        // preseleccionar el activo si existe
        int sel = 0;
        String active = app.activeProfile();
        for (int i = 0; i < names.length; i++) if (names[i].equals(active)) sel = i;
        spinner.setSelection(sel);
        selectedProfile = names[sel];
    }

    private void rebuildAll() {
        updateActive();
        rebuildSpinner();
        rebuildMapList();
    }

    private void loadSelected() {
        String[] names = app.profileNames();
        if (names.length == 0 || selectedProfile.length() == 0
                || selectedProfile.startsWith("(")) {
            Toast.makeText(ctx, ctx.getString(R.string.map_no_profiles_toast), Toast.LENGTH_SHORT).show();
            return;
        }
        app.loadProfile(selectedProfile);
        Toast.makeText(ctx, ctx.getString(R.string.map_profile_loaded, selectedProfile
                + (app.isBuiltinProfile(selectedProfile) ? ctx.getString(R.string.profile_factory) : "")),
                Toast.LENGTH_SHORT).show();
        draftKeyMap = null; // el perfil cargado sustituye los cambios pendientes
        rebuildAll();
    }

    private void pickNewName() {
        final EditText input = new EditText(ctx);
        input.setHint(ctx.getString(R.string.map_profile_hint));

        final RadioGroup rg = new RadioGroup(ctx);
        rg.setOrientation(RadioGroup.VERTICAL);
        final String[] consoles = { "generic", "ps4", "switch", "wiiu" };
        String[] labels = { ctx.getString(R.string.map_console_generic_hid), "PS4", "Switch", "Wii U" };
        for (int i = 0; i < labels.length; i++) {
            RadioButton rb = new RadioButton(ctx);
            rb.setText(labels[i]);
            rb.setId(i + 1);
            rg.addView(rb);
        }
        rg.check(1);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(input);
        box.addView(rg);

        new AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.map_new_profile))
                .setMessage(ctx.getString(R.string.map_new_profile_msg))
                .setView(box)
                .setPositiveButton(ctx.getString(R.string.action_save), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String name = input.getText().toString().trim();
                        if (!app.safeProfileName(name)) {
                            Toast.makeText(ctx, ctx.getString(R.string.map_invalid_name), Toast.LENGTH_SHORT).show();
                            return;
                        }
                        int id = rg.getCheckedRadioButtonId();
                        String console = (id >= 1 && id <= consoles.length) ? consoles[id - 1] : "generic";
                        if (!app.saveProfile(name, console)) {
                            Toast.makeText(ctx,
                                    ctx.getString(R.string.map_factory_overwrite),
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Toast.makeText(ctx, ctx.getString(R.string.map_profile_saved, name), Toast.LENGTH_SHORT).show();
                        rebuildAll();
                    }
                })
                .setNegativeButton(ctx.getString(R.string.action_cancel), null)
                .show();
    }

    private void pickRename() {
        final String oldName = selectedProfile;
        if (app.isBuiltinProfile(oldName)) {
            Toast.makeText(ctx, ctx.getString(R.string.map_factory_rename),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] names = app.profileNames();
        boolean exists = false;
        for (int i = 0; i < names.length; i++) if (names[i].equals(oldName)) exists = true;
        if (!exists) {
            Toast.makeText(ctx, ctx.getString(R.string.map_select_profile), Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText input = new EditText(ctx);
        input.setHint(ctx.getString(R.string.map_new_name_hint));
        new AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.map_rename_title, oldName))
                .setView(input)
                .setPositiveButton(ctx.getString(R.string.action_rename), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String name = input.getText().toString().trim();
                        if (!app.safeProfileName(name)) {
                            Toast.makeText(ctx, ctx.getString(R.string.map_invalid_name), Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!name.equals(oldName) && !app.isBuiltinProfile(name)) {
                            String[] mine = app.profileNames();
                            for (int k = 0; k < mine.length; k++) {
                                if (mine[k].equals(name)) {
                                    Toast.makeText(ctx, ctx.getString(R.string.map_name_exists, name), Toast.LENGTH_LONG).show();
                                    break;
                                }
                            }
                        }
                        app.renameProfile(oldName, name);
                        rebuildAll();
                    }
                })
                .setNegativeButton(ctx.getString(R.string.action_cancel), null)
                .show();
    }

    private void confirmDelete() {
        if (app.isBuiltinProfile(selectedProfile)) {
            Toast.makeText(ctx, ctx.getString(R.string.map_factory_delete),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] names = app.profileNames();
        boolean exists = false;
        for (int i = 0; i < names.length; i++) if (names[i].equals(selectedProfile)) exists = true;
        if (!exists) {
            Toast.makeText(ctx, ctx.getString(R.string.map_select_profile), Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.map_delete_title))
                .setMessage(ctx.getString(R.string.map_delete_msg, selectedProfile))
                .setPositiveButton(ctx.getString(R.string.action_delete), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        app.deleteProfile(selectedProfile);
                        rebuildAll();
                    }
                })
                .setNegativeButton(ctx.getString(R.string.action_cancel), null)
                .show();
    }

    private void exportSd() {
        final String[] names = app.profileNames();
        boolean exists = false;
        for (int i = 0; i < names.length; i++) if (names[i].equals(selectedProfile)) exists = true;
        if (!exists) {
            Toast.makeText(ctx, ctx.getString(R.string.map_select_profile), Toast.LENGTH_SHORT).show();
            return;
        }
        app.exportProfileToSd(selectedProfile, new Runnable() {
            public void run() {
                Toast.makeText(ctx, (app.sdOpOk ? ctx.getString(R.string.map_exported) : ctx.getString(R.string.map_export_failed))
                        + app.sdOpMsg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void pickSdImport() {
        final String[] names = app.listSdProfiles();
        if (names.length == 0) {
            Toast.makeText(ctx, ctx.getString(R.string.map_no_sd_profiles),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.map_import_title))
                .setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        final String nom = names[which];
                        String[] mine = app.profileNames();
                        boolean overwrite = false;
                        for (int k = 0; k < mine.length; k++)
                            if (mine[k].equals(nom) && !app.isBuiltinProfile(nom)) overwrite = true;
                        android.app.AlertDialog.Builder b2 = new AlertDialog.Builder(ctx)
                                .setTitle(ctx.getString(R.string.action_import))
                                .setMessage(overwrite ? ctx.getString(R.string.map_overwrite_msg, nom) : ctx.getString(R.string.map_import_msg, nom))
                                .setPositiveButton(ctx.getString(R.string.action_ok), new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dd, int w) {
                                        app.importProfileFromSd(nom, new Runnable() {
                                            public void run() {
                                                rebuildAll();
                                                Toast.makeText(ctx,
                                                        (app.sdOpOk ? ctx.getString(R.string.map_imported) : ctx.getString(R.string.map_import_failed))
                                                                + app.sdOpMsg, Toast.LENGTH_SHORT).show();
                                            }
                                        });
                                    }
                                })
                                .setNegativeButton(ctx.getString(R.string.action_cancel), null);
                        b2.show();
                    }
                })
                .setNegativeButton(ctx.getString(R.string.action_cancel), null)
                .show();
    }

    // ===== Mapeo =====

    private void ensureDraft() {
        if (draftKeyMap != null) return;
        draftKeyMap = new TreeMap<Integer, Integer>();
        for (Map.Entry<Integer, Integer> e : app.keyMap.entrySet()) {
            draftKeyMap.put(e.getKey(), e.getValue());
        }
    }

    private void rebuildMapList() {
        if (listBox == null) return;
        ensureDraft();
        listBox.removeAllViews();

        TreeMap<Integer, Integer> sorted = new TreeMap<Integer, Integer>();
        for (Map.Entry<Integer, Integer> e : draftKeyMap.entrySet()) {
            sorted.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<Integer, Integer> e : sorted.entrySet()) {
            final int kc = e.getKey().intValue();
            final int logical = e.getValue().intValue();

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView tv = new TextView(ctx);
            boolean none = (logical >= MainActivity.LOGICAL_NAMES.length - 1);
            tv.setText(app.keyName(kc) + "  →  " + app.logicalName(logical));
            tv.setTextColor(none ? 0xFFDD7777 : 0xFFEDEDF2);
            tv.setTextSize(13f);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(tv);

            Button b = new Button(ctx);
            b.setText(ctx.getString(R.string.map_change));
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { pickLogical(kc); }
            });
            row.addView(b);

            listBox.addView(row);
        }
        if (sorted.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText(ctx.getString(R.string.map_no_keys));
            empty.setTextColor(0xFF7C8290);
            empty.setTextSize(13f);
            listBox.addView(empty);
        }
    }

    private void pickLogical(final int kc) {
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(app.keyName(kc));
        b.setItems(MainActivity.LOGICAL_NAMES, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                ensureDraft();
                draftKeyMap.put(Integer.valueOf(kc), Integer.valueOf(which));
                rebuildMapList();
            }
        });
        b.setNegativeButton(ctx.getString(R.string.action_cancel), null);
        b.show();
    }

    public View build() {
        ScrollView scroll = new ScrollView(ctx);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121216);
        int p = app.dp(12);
        root.setPadding(p, p, p, p);
        scroll.addView(root);
        pendingProtocol = app.activeProtocol();
        ensureDraft();

        // --- bloque CONEXION Y PERFIL ---
        TextView hp = new TextView(ctx);
        hp.setText(ctx.getString(R.string.map_connection_profile));
        hp.setTextColor(0xFF9BA0AE);
        hp.setTextSize(13f);
        root.addView(hp);

        RadioGroup rgProto = new RadioGroup(ctx);
        rgProto.setOrientation(RadioGroup.VERTICAL);
        final String[] protos = { "generic", "ds3" };
        String[] protoLabels = { ctx.getString(R.string.proto_generic), "DS3" };
        for (int i = 0; i < protoLabels.length; i++) {
            RadioButton rb = new RadioButton(ctx);
            rb.setText(protoLabels[i]);
            rb.setId(i + 1);
            rgProto.addView(rb);
        }
        rgProto.check("ds3".equals(app.activeProtocol()) ? 2 : 1);
        rgProto.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                pendingProtocol = protos[checkedId - 1]; // aplica en Guardar cambios
            }
        });
        root.addView(rgProto);

        activeView = new TextView(ctx);
        activeView.setTextColor(0xFFEDEDF2);
        activeView.setTextSize(15f);
        activeView.setPadding(0, app.dp(2), 0, app.dp(4));
        root.addView(activeView);

        spinner = new Spinner(ctx);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = app.dp(4);
        spinner.setLayoutParams(slp);
        root.addView(spinner);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos >= 0 && pos < rawNames.length) selectedProfile = rawNames[pos];
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        LinearLayout row1 = new LinearLayout(ctx);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r1lp = new LinearLayout.LayoutParams(-1, -2);
        r1lp.topMargin = app.dp(8);
        row1.setLayoutParams(r1lp);
        Button bLoad = new Button(ctx);
        bLoad.setText(ctx.getString(R.string.action_load));
        bLoad.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        bLoad.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { loadSelected(); }
        });
        row1.addView(bLoad);
        Button bNew = new Button(ctx);
        bNew.setText(ctx.getString(R.string.action_new));
        bNew.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        bNew.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickNewName(); }
        });
        row1.addView(bNew);
        Button bSave = new Button(ctx);
        bSave.setText(ctx.getString(R.string.map_save_changes));
        bSave.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        bSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // El protocolo es global: se guarda aunque no haya perfil editable (E29).
                boolean protoChanged = pendingProtocol != null
                        && !pendingProtocol.equals(app.activeProtocol());
                if (protoChanged) app.setActiveProtocol(pendingProtocol); // persiste + MODE
                pendingProtocol = app.activeProtocol();

                // Las reasignaciones pendientes tambien se aplican aqui.
                ensureDraft();
                for (Map.Entry<Integer, Integer> e : draftKeyMap.entrySet()) {
                    Integer kc = e.getKey(), wanted = e.getValue();
                    Integer current = app.keyMap.get(kc);
                    if (current == null || !current.equals(wanted)) {
                        app.setKeyMapping(kc.intValue(), wanted.intValue());
                    }
                }

                String active = app.activeProfile();
                if (active.length() == 0) {
                    Toast.makeText(ctx, protoChanged
                            ? ctx.getString(R.string.map_proto_saved_no_profile)
                            : ctx.getString(R.string.map_no_active), Toast.LENGTH_LONG).show();
                } else if (app.isBuiltinProfile(active)) {
                    Toast.makeText(ctx, protoChanged
                            ? ctx.getString(R.string.map_proto_saved_factory)
                            : ctx.getString(R.string.map_factory_no_overwrite), Toast.LENGTH_LONG).show();
                } else {
                    app.saveActiveProfile();
                    Toast.makeText(ctx, ctx.getString(R.string.map_profile_updated, active),
                            Toast.LENGTH_SHORT).show();
                }
                if (protoChanged) {
                    new AlertDialog.Builder(ctx)
                            .setTitle(ctx.getString(R.string.map_proto_changed_title))
                            .setMessage(ctx.getString(R.string.map_proto_changed_msg,
                                    ("ds3".equals(app.activeProtocol()) ? "DS3" : ctx.getString(R.string.proto_generic))))
                            .setPositiveButton(ctx.getString(R.string.action_ok), null)
                            .show();
                }
            }
        });
        row1.addView(bSave);
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(ctx);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(-1, -2);
        r2lp.topMargin = app.dp(4);
        row2.setLayoutParams(r2lp);
        Button bRen = new Button(ctx);
        bRen.setText(ctx.getString(R.string.action_rename));
        bRen.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        bRen.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickRename(); }
        });
        row2.addView(bRen);
        Button bDel = new Button(ctx);
        bDel.setText(ctx.getString(R.string.action_delete));
        bDel.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        bDel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmDelete(); }
        });
        row2.addView(bDel);
        Button bSd = new Button(ctx);
        bSd.setText(ctx.getString(R.string.map_export_sd));
        bSd.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        bSd.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { exportSd(); }
        });
        row2.addView(bSd);
        Button bImp = new Button(ctx);
        bImp.setText(ctx.getString(R.string.map_import_sd));
        bImp.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        bImp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickSdImport(); }
        });
        row2.addView(bImp);
        root.addView(row2);

        // --- bloque MAPEO ---
        TextView hm = new TextView(ctx);
        hm.setText(ctx.getString(R.string.map_preset_standard));
        hm.setTextColor(0xFFEDEDF2);
        hm.setTextSize(14f);
        hm.setPadding(0, app.dp(12), 0, 0);
        root.addView(hm);

        TextView h2 = new TextView(ctx);
        h2.setText(ctx.getString(R.string.map_physical_buttons));
        h2.setTextColor(0xFF9BA0AE);
        h2.setTextSize(13f);
        h2.setPadding(0, app.dp(4), 0, 0);
        root.addView(h2);

        listBox = new LinearLayout(ctx);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);

        rebuildAll();
        return scroll;
    }
}
