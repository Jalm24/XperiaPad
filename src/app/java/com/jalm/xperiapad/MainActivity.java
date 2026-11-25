/* Copyright (C) 2026 Jalm24
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Additional GPLv3 §7(b) term: this author attribution must be
 * preserved in modified source files and in the project's legal notices. */

package com.jalm.xperiapad;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.bluetooth.BluetoothAdapter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.bluetooth.BluetoothDevice;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.Gravity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;
import android.content.SharedPreferences;
import java.util.LinkedHashMap;
import java.util.Locale;
import android.content.res.Configuration;
import java.util.Map;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;

public class MainActivity extends Activity {

    static final String APP_VERSION = "0.2.1";
    static final boolean DEBUG_OPEN_DRAWER = false;
    static final int IDX_HOME = 0;
    static final int IDX_CONTROLS = 1;
    static final int IDX_SETUP = 5;
    static final int IDX_GAME = 6;
    static final int IDX_CREDITS = 7;

    // ===== Shell (topbar + drawer + contenido) =====
    private LinearLayout topbar;
    private FrameLayout contentFrame;
    private LinearLayout panel;
    private View scrim;
    private TextView topTitle;
    private Button topAction;
    private boolean drawerOpen = false;
    private int panelOffset = 0;
    private int panelW = 0;
    Screen[] screens;
    int curScreen = 0;

    // ===== Motor =====
    LocalSocket sock;
    OutputStream sockOut;
    BufferedReader sockIn;
    final Object sockLock = new Object();
    final Handler handler = new Handler();
    volatile boolean foreground = false;
    volatile boolean connected = false;
    boolean armEnabled = true;
    boolean gameMode = false;
    boolean editorMode = false; // editando el gamepad virtual (vista de modo juego)
    boolean daemonEnabled = true;
    volatile boolean daemonRunning = false;
    volatile boolean discoverable = false;
    volatile boolean discDialogPending = false; // evita dos dialogos de descubrible
    volatile int discoverableRemain = 0;
    volatile int scanEnable = -1; // valor real leido del daemon (OK SCAN <n>)
    int discoverableSec = 120;
    boolean keepScreenOn = true;
    boolean stickEditorZoom = true; // vista ampliada del editor del touchpad

    // HID report 0x03 (generico/web): [0]=btn0-3, [1]=LX, [2]=LY, [3]=RX, [4]=RY, [5]=Hat+pad,
    // [6]=pad, [7]=btn4-11, [8]=btn12-15+pad, [9]=pad
    final byte[] r3 = new byte[10];
    final Object r3lock = new Object();
    boolean dUp, dDown, dLeft, dRight;

    final Map<Integer, Integer> keyMap = new LinkedHashMap<Integer, Integer>();
    static final String PREFS = "xperiapad";
    static final String PREF_PRESET = "preset";
    static final String PREF_STICKS = "sticks_v2";
    static final String PREF_DAEMON = "daemon_enabled";
    static final String PREF_KEEPSCREEN = "keep_screen_on";
    static final String PREF_DISC_SEC = "discover_seconds";
    static final String PREF_LOCALE = "locale_mode"; // system | es | en
    String presetName = "standard";
    String localeMode = "system";
    final Locale systemLocale = Locale.getDefault(); // idioma real del SO, antes de cualquier override

    // Superficie touchpad (Synaptics, por defecto 966x360)
    float tpMinX = 0f, tpMaxX = 966f, tpMinY = 0f, tpMaxY = 360f;
    boolean tpRangeKnown = false;

    static class StickConfig {
        String id;
        String output; // "left" (LX/LY) o "right" (RX/RY)
        float x, y, w, h; // rect en coords de superficie
        float deadzone;   // 0..0.9 fraccion del radio
        float sensitivity; // multiplicador (1.0 normal)
        boolean invertY;
    }
    final java.util.ArrayList<StickConfig> sticks = new java.util.ArrayList<StickConfig>();
    final java.util.HashMap<Integer, String> pointerStick = new java.util.HashMap<Integer, String>();

    /** Boton en pantalla del modo juego. Coordenadas relativas al area de contenido. */
    static class ButtonConfig {
        String id;      // etiqueta visible
        float cx, cy;   // centro
        float r;        // radio
        int logical;    // boton logico 0-19 (-1 si no aplica)
        boolean dpad;   // d-pad: 4 direcciones por cuadrante
        String output;  // null en botones; "left"/"right" en sticks virtuales
    }
    final java.util.ArrayList<ButtonConfig> buttons = new java.util.ArrayList<ButtonConfig>();
    static final String PREF_BUTTONS = "buttons_v2";
    String buttonPreset = "clasico";

    // estado actual de los 20 botones logicos (para que el overlay refleje los fisicos)
    final boolean[] logicalState = new boolean[20];
    Runnable buttonStateListener;

    static final String[] LOGICAL_NAMES = new String[]{
        "A/Cross (0)", "B/Circle (1)", "X/Square (2)", "Y/Triangle (3)", "LB (4)", "RB (5)", "LT (6)", "RT (7)",
        "Back/Select (8)", "Start (9)", "LS (10)", "RS (11)", "D-Up/Hat (12)", "D-Down/Hat (13)", "D-Left/Hat (14)", "D-Right/Hat (15)", "Guide (16)", "Extra (BTN_C)", "Extra (BTN_Z)", "Extra (16)", "(none)"
    };

    // ===== Log (buffer circular) =====
    final java.util.ArrayList<String> logLines = new java.util.ArrayList<String>();
    static final int LOG_MAX = 300;
    final Object logLock = new Object();
    Runnable logListener;
    Runnable statusListener;

    // ===== Sticks sobre touchpad =====
    // SOURCE_TOUCHPAD es una constante propia de Sony (no existe en AOSP/android-10).
    static final int SOURCE_TOUCHPAD = 0x100008;
    static final int AXIS_X = 0x00000000; // API 10 no expone MotionEvent.AXIS_X
    static final int AXIS_Y = 0x00000001;
    static final float SURF_W = 966f; // ancho logico de la superficie (coords de config)
    static final float SURF_H = 360f; // alto logico de la superficie
    int padLogCount = 0;
    int stickLogCount = 0;
    int baseOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    int connGen = 0; // token de generacion: un lector viejo no cierra el socket nuevo
    final ExecutorService daemonOps = Executors.newSingleThreadExecutor();
    final ExecutorService miscOps = Executors.newSingleThreadExecutor();
    volatile boolean rootChecked = false;
    volatile boolean rootAvailable = false;
    volatile boolean sdOpOk = false;   // resultado real de export/import SD
    volatile String sdOpMsg = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        baseOrientation = getRequestedOrientation(); // -1 = sigue al sistema (auto-rotacion)
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        // fullscreen SOLO en modo juego/editor (se aplica en applyGameModeChrome)
        resetReport();
        loadOrInitMapping();
        loadOrInitSticks();
        loadOrInitButtons();
        loadPrefs();
        applyLanguage();

        screens = new Screen[]{
            new HomeScreen(this),        // IDX_HOME
            new ControlsScreen(this),
            new MappingScreen(this),     // mapeo + perfiles
            new DiagnosticsScreen(this),
            new AjustesScreen(this),     // ajustes persistentes
            new SystemSetupScreen(this), // IDX_SETUP (no esta en el drawer)
            new GameScreen(this),        // IDX_GAME (no esta en el drawer)
            new CreditsScreen(this),     // IDX_CREDITS
        };

        buildShell();
        buildPanel();
        registerBtReceiver();
        // Pantalla inicial segun el estado cacheado del parche (sin flash de Home)
        String cached = getSharedPreferences(PREFS, MODE_PRIVATE).getString("last_patched_state", "");
        setScreen("no".equals(cached) ? IDX_SETUP : 0);
        if (DEBUG_OPEN_DRAWER) openDrawer();
        runSetupDetection(new Runnable() {
            public void run() {
                setupDetected = true;
                final boolean patched = patchedEnough();
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("last_patched_state", patched ? "si" : "no").commit();
                handler.post(new Runnable() {
                    public void run() {
                        if (!patched) {
                            if (curScreen != IDX_SETUP) setScreen(IDX_SETUP); // corrige cache viejo
                            // el daemon no se habilita sin parche, nunca
                            if (daemonEnabled) {
                                daemonEnabled = false;
                                getSharedPreferences(PREFS, MODE_PRIVATE)
                                        .edit().putBoolean(PREF_DAEMON, false).commit();
                                appendLog("daemon disabled: system not patched");
                            }
                            if (daemonRunning) {
                                stopDaemon();
                                appendLog("daemon stopped: system not patched");
                            }
                        } else if (daemonEnabled && !connected) {
                            if (!probeDaemon()) {
                                // cold-close real: el daemon ya no esta, exigir activacion manual
                                daemonEnabled = false;
                                getSharedPreferences(PREFS, MODE_PRIVATE)
                                        .edit().putBoolean(PREF_DAEMON, false).commit();
                                appendLog("daemon off on launch: enable it manually");
                            } else {
                                ensureDaemon();
                            }
                        }
                        if (curScreen == IDX_SETUP && patched) setScreen(0); // ya aplicado
                        refreshStatus(); // repintar: la UI se dibujó con setupBtPatch="?"
                    }
                });
            }
        });

        applyKeepScreenOn(); // respeta la preferencia, nunca la fuerza a ON
    }

    // ===== Shell =====

    int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121216);

        // --- topbar ---
        topbar = new LinearLayout(this);
        topbar.setOrientation(LinearLayout.HORIZONTAL);
        topbar.setGravity(Gravity.CENTER_VERTICAL);
        topbar.setBackgroundColor(0xFF1E1E26);
        int topbarH = dp(48);
        topbar.setLayoutParams(new LinearLayout.LayoutParams(-1, topbarH));

        View ham = new HamburgerView(this);
        ham.setLayoutParams(new LinearLayout.LayoutParams(topbarH, topbarH));
        ham.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleDrawer(); }
        });
        topbar.addView(ham);

        topTitle = new TextView(this);
        topTitle.setTextColor(0xFFEDEDF2);
        topTitle.setTextSize(18f);
        topTitle.setPadding(dp(4), 0, 0, 0);
        topTitle.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        topbar.addView(topTitle);

        topAction = new Button(this);
        topAction.setVisibility(View.GONE);
        topAction.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Screen s = screens[curScreen];
                if (s != null) s.onAction();
            }
        });
        topbar.addView(topAction);

        root.addView(topbar);

        // --- body (contenido + scrim + drawer) ---
        FrameLayout body = new FrameLayout(this);
        body.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));

        contentFrame = new FrameLayout(this);
        contentFrame.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        body.addView(contentFrame);

        scrim = new View(this);
        scrim.setBackgroundColor(0x99000000);
        scrim.setVisibility(View.GONE);
        scrim.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        scrim.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeDrawer(); }
        });
        body.addView(scrim);

        panelW = Math.min(dp(300), (int) (getWindowManager().getDefaultDisplay().getWidth() * 0.6f));
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF23232C);
        panel.setPadding(0, dp(8), 0, dp(8));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(panelW, -1);
        plp.gravity = Gravity.LEFT | Gravity.TOP;
        plp.leftMargin = -panelW; // cerrado: fuera de la pantalla
        panel.setLayoutParams(plp);
        body.addView(panel);

        root.addView(body);
        setContentView(root);

        panelOffset = -panelW; // estado logico: cerrado
    }

    private TextView[] panelItems;

    private void buildPanel() {
        // La pantalla de modo juego y la de configuracion del sistema no se
        // listan en el menu lateral (la configuracion vive dentro de Ajustes).
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        panelItems = new TextView[screens.length];
        for (int i = 0; i < screens.length; i++) {
            if (i == IDX_GAME || i == IDX_SETUP) continue;
            final int idx = i;
            TextView tv = new TextView(this);
            tv.setText(screens[i].title());
            tv.setTextSize(16f);
            tv.setTextColor(0xFFEDEDF2);
            tv.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            tv.setPadding(dp(20), 0, dp(12), 0);
            tv.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(52)));
            tv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { setScreen(idx); }
            });
            panelItems[i] = tv;
            list.addView(tv);
        }
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.addView(list);
        panel.addView(sc, new LinearLayout.LayoutParams(-1, -1));
    }

    void updateDrawerHighlight() {
        if (panelItems == null) return;
        for (int i = 0; i < panelItems.length; i++) {
            TextView tv = panelItems[i];
            if (tv == null) continue;
            if (i == curScreen) {
                tv.setBackgroundColor(0xFF2F3448);
                tv.setTextColor(0xFFFFFFFF);
            } else {
                tv.setBackgroundDrawable(null);
                tv.setTextColor(0xFFB9BECA);
            }
        }
    }

    void setScreen(int idx) {
        if (screens == null || idx < 0 || idx >= screens.length) return;
        boolean wasGame = gameMode;
        Screen prev = screens[curScreen];
        if (prev != null) prev.onHide();
        curScreen = idx;
        gameMode = (idx == IDX_GAME); // unica fuente de verdad del modo juego
        if (gameMode && !wasGame) {
            // baseOrientation viene de onCreate (preferencia del usuario). Si es
            // UNSPECIFIED, al salir se usa SENSOR para que el sistema reevalue.
            setArm(true); // forzar ARM al entrar
        }
        applyGameModeChrome();
        Screen s = screens[idx];
        contentFrame.removeAllViews();
        contentFrame.addView(s.view(), new FrameLayout.LayoutParams(-1, -1));
        topTitle.setText(s.title());
        refreshTopbar();
        updateDrawerHighlight();
        s.onShow();
        closeDrawer();
    }

    void refreshTopbar() {
        Screen s = screens[curScreen];
        if (s == null) return;
        String al = s.actionLabel();
        if (al == null) {
            topAction.setVisibility(View.GONE);
        } else {
            topAction.setText(al);
            topAction.setVisibility(View.VISIBLE);
        }
    }

    /** Modo juego/editor: sin topbar (el overlay trae su boton Salir) y fullscreen.
     * El resto de la app muestra la barra de estado. */
    private void applyGameModeChrome() {
        topbar.setVisibility(gameMode ? View.GONE : View.VISIBLE);
        if (gameMode) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
            // modo juego siempre landscape (el overlay esta disenado en landscape)
            if (getRequestedOrientation() != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            }
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
            // restaurar la preferencia previa del usuario (auto-rotacion si no habia una)
            int restore = baseOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR : baseOrientation;
            if (getRequestedOrientation() != restore) {
                setRequestedOrientation(restore);
            }
        }
    }

    /** Orientacion fisica actual como valor explicito de ActivityInfo. */
    private int currentOrientation() {
        int o = getResources().getConfiguration().orientation;
        if (o == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            return android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        }
        return android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
    }

    /** Entra al editor del gamepad virtual: vista de modo juego con controles de edicion. */
    void enterEditorMode() {
        editorMode = true;
        setScreen(IDX_GAME);
    }

    void exitEditorMode() {
        editorMode = false;
        resetReport();
        setScreen(IDX_CONTROLS);
    }

    private void toggleDrawer() { if (drawerOpen) closeDrawer(); else openDrawer(); }

    private void openDrawer() {
        if (drawerOpen || gameMode) return; // en modo juego el drawer esta bloqueado
        drawerOpen = true;
        scrim.setVisibility(View.VISIBLE);
        animatePanel(0);
    }

    void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        animatePanel(-panelW);
        handler.postDelayed(new Runnable() {
            public void run() { if (!drawerOpen) scrim.setVisibility(View.GONE); }
        }, 220);
    }

    private void animatePanel(final int target) {
        final int from = panelOffset;
        final int steps = 10;
        final int[] i = new int[]{0};
        handler.post(new Runnable() {
            public void run() {
                i[0]++;
                int off = from + (target - from) * i[0] / steps;
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
                lp.leftMargin = off;
                panel.setLayoutParams(lp); // reubica el panel de verdad (soportado por FrameLayout)
                panelOffset = off;
                if (i[0] < steps) handler.postDelayed(this, 16);
            }
        });
    }

    /** Boton hamburguesa dibujado (no depende de glifos de la fuente del sistema). */
    private class HamburgerView extends View {
        private final Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG);
        HamburgerView(android.content.Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            hp.setColor(0xFFEDEDF2);
            float barW = w * 0.46f, x0 = (w - barW) / 2f;
            float th = Math.max(2f, h * 0.055f);
            float y = h * 0.36f, gap = h * 0.14f;
            cv.drawRect(x0, y, x0 + barW, y + th, hp);
            cv.drawRect(x0, y + gap, x0 + barW, y + gap + th, hp);
            cv.drawRect(x0, y + 2 * gap, x0 + barW, y + 2 * gap + th, hp);
        }
    }

    // ===== Modo juego (pantalla dedicada) =====

    /** Entra al modo juego: se navega a la pantalla dedicada. */
    void enterGameMode() {
        setScreen(IDX_GAME);
    }

    /** Sale del modo juego: suelta todo pero el daemon sigue armado. */
    void exitGameMode() {
        resetReport();
        if (connected) {
            sendReportState();
            appendLog("game mode: off (inputs stopped, HID still armed)");
        }
        setScreen(IDX_HOME);
    }

    void refreshStatus() {
        // Puede invocarse desde el hilo del socket: publicar en el hilo UI.
        final Runnable l = statusListener;
        if (l != null) handler.post(l);
    }

    // ===== Preferencias =====

    private void loadPrefs() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        daemonEnabled = sp.getBoolean(PREF_DAEMON, true);
        keepScreenOn = sp.getBoolean(PREF_KEEPSCREEN, true);
        stickEditorZoom = sp.getBoolean("stick_editor_zoom", true);
        localeMode = sp.getString(PREF_LOCALE, "system");
        if (!"es".equals(localeMode) && !"en".equals(localeMode)) localeMode = "system";
        discoverableSec = sp.getInt(PREF_DISC_SEC, 120);
        if (discoverableSec < 15) discoverableSec = 15;
        if (discoverableSec > 300) discoverableSec = 300;
        // el daemon no puede estar habilitado si el cache dice "sin parche"
        if ("no".equals(sp.getString("last_patched_state", ""))) daemonEnabled = false;
        // migracion del protocolo (E29): derivar de la base la primera vez
        if (sp.getString("active_protocol", "").length() == 0) {
            String base = sp.getString("active_console", "generic");
            String proto = ("switch".equals(base) || "ps4".equals(base) || "ds3".equals(base)) ? "ds3" : "generic";
            sp.edit().putString("active_protocol", proto).commit();
        }
        applyKeepScreenOn();
    }

    /** Aplica el idioma efectivo: preferencia custom o idioma del sistema. */
    void applyLanguage() {
        Locale loc = "es".equals(localeMode) ? new Locale("es")
                : "en".equals(localeMode) ? Locale.ENGLISH
                : systemLocale;
        Locale.setDefault(loc);
        Configuration c = getResources().getConfiguration();
        c.locale = loc;
        getResources().updateConfiguration(c, getResources().getDisplayMetrics());
    }

    void setLocaleMode(String mode) {
        localeMode = mode;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_LOCALE, mode).commit();
        applyLanguage();
        retranslateUi();
    }

    /** Repinta toda la UI en caliente sin reiniciar la Activity (daemon y socket siguen vivos). */
    void retranslateUi() {
        if (screens == null) return;
        for (Screen sc : screens) if (sc != null) sc.invalidate();
        refreshPanelLabels();
        setScreen(curScreen);
    }

    private void refreshPanelLabels() {
        if (panelItems == null) return;
        for (int i = 0; i < screens.length; i++) {
            if (panelItems[i] != null) panelItems[i].setText(screens[i].title());
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyLanguage();   // en modo "system" adopta el nuevo idioma; en custom lo mantiene
        retranslateUi();
    }

    void setDaemonEnabled(boolean on) {
        if (on && !patchedEnough()) {
            daemonEnabled = false;
            Toast.makeText(this, getString(R.string.toast_system_unpatched),
                    Toast.LENGTH_LONG).show();
            refreshStatus();
            return;
        }
        if (on && !btEnabled()) {
            appendLog("BT off: turn on Bluetooth to start the daemon");
            refreshStatus();
            return;
        }
        daemonEnabled = on;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_DAEMON, on).commit();
        if (on) startDaemon(); else stopDaemon();
        refreshStatus();
    }

    private void applyKeepScreenOn() {
        if (keepScreenOn) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    void setKeepScreenOn(boolean on) {
        keepScreenOn = on;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_KEEPSCREEN, on).commit();
        applyKeepScreenOn();
    }

    /** Vista del editor del touchpad: true = ampliada, false = escala real. */
    void setStickEditorZoom(boolean zoom) {
        stickEditorZoom = zoom;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("stick_editor_zoom", zoom).commit();
    }

    /** Nivel de bateria 0-100, o -1 si no se puede leer. */
    int batteryPct() {
        try {
            android.content.IntentFilter f = new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
            android.content.Intent i = registerReceiver(null, f);
            if (i == null) return -1;
            int lvl = i.getIntExtra("level", -1);
            int scale = i.getIntExtra("scale", 100);
            if (lvl < 0 || scale <= 0) return -1;
            return lvl * 100 / scale;
        } catch (Exception e) { return -1; }
    }

    // ===== Bluetooth =====

    BluetoothAdapter btAdapter() {
        try { return BluetoothAdapter.getDefaultAdapter(); } catch (Throwable t) { return null; }
    }

    volatile int btPending = 0; // 0 = estable, 1 = encendiendo, -1 = apagando

    private String btStateName(int st) {
        if (st == BluetoothAdapter.STATE_ON) return "ON";
        if (st == BluetoothAdapter.STATE_OFF) return "OFF";
        if (st == BluetoothAdapter.STATE_TURNING_ON) return "TURNING_ON";
        if (st == BluetoothAdapter.STATE_TURNING_OFF) return "TURNING_OFF";
        return "unknown";
    }

    /** Escucha el estado real del BT: la UI se sincroniza aunque tarde. */
    private void registerBtReceiver() {
        android.content.BroadcastReceiver stopped = new android.content.BroadcastReceiver() {
            public void onReceive(android.content.Context c, android.content.Intent i) {
                daemonEnabled = false;
                handler.post(new Runnable() {
                    public void run() { refreshStatus(); }
                });
                appendLog("daemon stopped (anchor service)");
            }
        };
        registerReceiver(stopped, new IntentFilter(DaemonService.ACTION_STOPPED),
                DaemonService.PERM, null);
        stoppedReceiver = stopped;

        android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
            public void onReceive(android.content.Context c, android.content.Intent i) {
                int st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                appendLog("bluetooth: state -> " + btStateName(st));
                if (st == BluetoothAdapter.STATE_OFF) setDiscoverableOff();
                if (st == BluetoothAdapter.STATE_ON && daemonRunning && !connected) {
                    // C5: BT llega tarde: reintentar START sin tocar una sesion activa
                    new Thread(new Runnable() {
                        public void run() {
                            String s = daemonLine("STATE");
                            if (s != null && s.contains("active=0")) {
                                appendLog("BT on: sending START to daemon");
                                daemonLine("START");
                            }
                        }
                    }).start();
                }
                refreshStatus();
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(r, f); // broadcast del sistema: sin restriccion de permiso
        btReceiver = r;
    }

    private android.content.BroadcastReceiver btReceiver;
    private android.content.BroadcastReceiver stoppedReceiver;

    boolean btEnabled() {
        BluetoothAdapter a = btAdapter();
        return a != null && a.getState() == BluetoothAdapter.STATE_ON;
    }

    int btState() {
        BluetoothAdapter a = btAdapter();
        return a == null ? -1 : a.getState();
    }

    private boolean invokeBt(String method, Class<?>[] sig, Object[] args) {
        try {
            BluetoothAdapter a = btAdapter();
            if (a == null) { appendLog("bt " + method + " ERR: no adapter"); return false; }
            Object r = a.getClass().getMethod(method, sig).invoke(a, args);
            return !(r instanceof Boolean) || ((Boolean) r).booleanValue();
        } catch (Throwable t) {
            // encadenar la causa real (InvocationTargetException la oculta)
            StringBuilder sb = new StringBuilder("bt " + method + " ERR: " + t.toString());
            Throwable c = t.getCause();
            for (int i = 0; i < 3 && c != null; i++) {
                sb.append(" <- ").append(c.toString());
                c = c.getCause();
            }
            appendLog(sb.toString());
            return false;
        }
    }

    /** Modo de escaneo real del adaptador (22 = descubrible), o -1 si no se puede leer. */
    int btScanMode() {
        try {
            BluetoothAdapter a = btAdapter();
            if (a == null) return -1;
            Object r = a.getClass().getMethod("getScanMode", new Class[0]).invoke(a, new Object[0]);
            return ((Integer) r).intValue();
        } catch (Throwable t) { return -1; }
    }

    /** Detiene el daemon SIN hilo aparte (el llamador controla el orden). */
    private void stopDaemonNow() {
        daemonRunning = false;
        closeSocket();
        stopService(new Intent(this, DaemonService.class));
        stopExistingDaemon();
    }

    /** Enciende o apaga el Bluetooth. Apagar detiene antes el daemon (sincrono). */
    void setBtEnabled(final boolean on) {
        daemonOps.execute(new Runnable() {
            public void run() {
                btPending = on ? 1 : -1; // transicion: el switch se queda donde lo dejaste
                refreshStatus();
                if (!on) {
                    // Detener el daemon AHORA (sin hilo) para que suelte hci0 con el BT vivo.
                    if (daemonEnabled || daemonRunning) {
                        daemonEnabled = false;
                        getSharedPreferences(PREFS, MODE_PRIVATE)
                                .edit().putBoolean(PREF_DAEMON, false).commit();
                        stopDaemonNow();
                        appendLog("daemon stopped before turning BT off");
                        handler.post(new Runnable() {
                            public void run() {
                                Toast.makeText(MainActivity.this,
                                        getString(R.string.toast_bt_off_daemon_stopped),
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                    setDiscoverableOff();
                    try { Thread.sleep(500); } catch (InterruptedException e) { return; } // settle
                }
                // enable()/disable() pueden devolver false si el servicio sigue parandose
                boolean ok = invokeBt(on ? "enable" : "disable", new Class[0], new Object[0]);
                if (!ok) {
                    for (int t = 0; t < 20 && btEnabled() != on; t++) {
                        appendLog("bluetooth: retrying " + (on ? "enable" : "disable")
                                + " (" + (t + 1) + ")");
                        try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                        ok = invokeBt(on ? "enable" : "disable", new Class[0], new Object[0]);
                        if (ok && btEnabled() == on) break;
                    }
                }
                // sondeo de respaldo (el broadcast es la via principal): hasta 10 s
                boolean settled = false;
                for (int i = 0; i < 50; i++) {
                    if (btEnabled() == on) { settled = true; break; }
                    try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                    if (i % 5 == 0) refreshStatus();
                }
                btPending = 0;
                if (!settled) {
                    appendLog("bluetooth: timeout waiting for change; actual state "
                            + (btEnabled() ? "ON" : "OFF"));
                }
                appendLog("bluetooth: " + (btEnabled() ? "ON" : "OFF"));
                refreshStatus();
            }
        });
    }

    void setDiscoverableSec(int sec) {
        if (sec < 15) sec = 15;
        if (sec > 300) sec = 300;
        discoverableSec = sec;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_DISC_SEC, sec).commit();
    }

    /** Marca/desmarca el descubrimiento. Via daemon (SCAN); fallback: dialogo del sistema. */
    void setDiscoverable(final boolean on) {
        if (!on) { setDiscoverableOff(); return; }
        if (!btEnabled()) {
            appendLog("BT off: cannot make discoverable");
            refreshStatus();
            return;
        }
        // unico camino fiable en esta ROM: el dialogo del sistema. El SCAN del
        // daemon falla con EPERM (hibrido) o es inerte, y hacia creer que si estaba.
        requestSystemDiscoverable();
        return;
    }

    private void requestSystemDiscoverable() {
        if (discDialogPending) return;
        discDialogPending = true;
        handler.post(new Runnable() {
            public void run() {
                try {
                    android.content.Intent i = new android.content.Intent(
                            BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                    i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, discoverableSec);
                    startActivityForResult(i, 4242);
                } catch (Throwable t) {
                    appendLog("discoverable fallback ERR: " + t.toString());
                }
            }
        });
    }

    void setDiscoverableOff() {
        boolean was = discoverable;
        handler.removeCallbacks(discTick);
        discoverable = false;
        discoverableRemain = 0;
        if (connected) {
            sendCmd("SCAN 0");
            appendLog("discoverable off via daemon: SCAN 0");
        } else if (was && btEnabled()) {
            // sin enlace: intentar la via directa como ultimo recurso
            invokeBt("setScanMode",
                    new Class[]{int.class, int.class},
                    new Object[]{Integer.valueOf(21), Integer.valueOf(0)});
        }
        refreshStatus();
    }

    private final Runnable discTick = new Runnable() {
        public void run() {
            if (!discoverable) return;
            discoverableRemain--;
            if (discoverableRemain <= 0) {
                setDiscoverableOff();
                appendLog("discoverability expired");
                return;
            }
            // reafirmar por si BlueZ tiene su propio timeout corriendo
            if (discoverableRemain % 10 == 0 && connected) sendCmd("SCAN 1");
            // H1: bluetoothd regenera CoD/EIR al cambiar visibilidad; sin enlace
            // HID tambien hay que reafirmar mientras se esta emparejando.
            if (discoverableRemain % 2 == 0 && daemonRunning && sockOut != null) sendCmd("REASSERT");
            refreshStatus();
            handler.postDelayed(this, 1000);
        }
    };

    void clearLog() {
        synchronized (logLock) { logLines.clear(); }
        Runnable l = logListener;
        if (l != null) handler.post(l);
    }

    java.util.ArrayList<String> snapshotLog() {
        synchronized (logLock) { return new java.util.ArrayList<String>(logLines); }
    }

    String deviceInfo() {
        return "Android " + android.os.Build.VERSION.RELEASE
                + " (API " + android.os.Build.VERSION.SDK_INT + ")"
                + "  |  " + android.os.Build.MODEL
                + "  |  " + android.os.Build.DISPLAY;
    }

    void setArm(boolean on) {
        armEnabled = on;
        if (connected) {
            if (on) { sendCmd("ARM 1"); sendReportNow(); }
            else { sendReportState(); sendCmd("DISARM"); }
        }
        refreshStatus();
    }

    // ===== Report / estado =====

    void resetReport() {
        synchronized (r3lock) {
            for (int i = 0; i < r3.length; i++) r3[i] = 0;
            r3[1] = (byte) 0x80; r3[2] = (byte) 0x80; r3[3] = (byte) 0x80; r3[4] = (byte) 0x80;
            r3[5] = 0x08; // hat null
            dUp = dDown = dLeft = dRight = false;
        }
        synchronized (pointerStick) { pointerStick.clear(); }
        for (int i = 0; i < logicalState.length; i++) logicalState[i] = false;
        notifyButtons();
    }

    void appendLog(final String s) {
        Log.i("XperiaPad", s);
        synchronized (logLock) {
            logLines.add(s);
            while (logLines.size() > LOG_MAX) logLines.remove(0);
        }
        final Runnable l = logListener;
        if (l != null) handler.post(l);
    }

    // ===== Daemon / socket =====

    private String daemonPath() {
        String libDir = getApplicationInfo().nativeLibraryDir;
        return libDir + "/libxpd.so";
    }

    /** El daemon solo corre con P2 aplicado (P1 retirado, E27; el framework queda stock). */
    boolean patchedEnough() {
        return "si".equals(setupBtPatch);
    }

    /** Asegura el daemon: si ya responde por UDS solo conecta; si no, lo arranca. */
    void ensureDaemon() {
        if (!patchedEnough()) {
            appendLog("daemon not started: system not patched (use System setup)");
            return;
        }
        daemonOps.execute(new Runnable() {
            public void run() {
                if (probeDaemon()) {
                    daemonRunning = true;
                    appendLog("daemon already running");
                    handler.post(new Runnable() {
                        public void run() { connectSocket(); }
                    });
                } else {
                    startDaemon();
                }
            }
        });
    }

    /** Detiene el daemon por completo: cierra el enlace y manda STOP. */
    void stopDaemon() {
        daemonRunning = false;
        closeSocket();
        stopService(new Intent(this, DaemonService.class));
        daemonOps.execute(new Runnable() {
            public void run() {
                stopExistingDaemon();
                appendLog("daemon stopped");
                refreshStatus();
            }
        });
    }

    void startDaemon() {
        if (!patchedEnough()) {
            appendLog("daemon not started: system not patched (use System setup)");
            return;
        }
        daemonOps.execute(new Runnable() {
            public void run() {
                try {
                    // 1) Avisar a un daemon ya vivo (libera el socket @xperiapad).
                    stopExistingDaemon();

                    // 2) Arrancar: killall + launch en UNA SOLA llamada su (un aviso)
                    String bin = daemonPath();
                    File log = new File(getFilesDir(), "xpd.log");
                    String cmd = "killall xpd 2>/dev/null; killall libxpd.so 2>/dev/null; "
                            + bin + " --uid " + android.os.Process.myUid()
                            + " --log " + log.getAbsolutePath() + " >/dev/null 2>&1 &";
                    appendLog("$ su -c " + cmd);
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                    int rc = p.waitFor();
                    appendLog("su rc=" + rc);
                    if (rc != 0) {
                        appendLog("su failed (rc=" + rc + "): grant root permission to XperiaPad");
                    }

                    // 4) Sondear hasta que el daemon publique el socket y luego conectar.
                    for (int i = 0; i < 20; i++) {
                        Thread.sleep(250);
                        if (probeDaemon()) {
                            daemonRunning = true;
                            startService(new Intent(MainActivity.this, DaemonService.class));
                            appendLog("daemon ready");
                            handler.post(new Runnable() {
                                public void run() { connectSocket(); }
                            });
                            return;
                        }
                    }
                    appendLog("daemon did not publish socket (check " + log.getAbsolutePath() + ")");
                } catch (Exception e) {
                    appendLog("ERR startDaemon: " + e.getMessage());
                }
            }
        });
    }

    /** Comando UDS sincronico de una sola linea (sondeo/estado). */
    private String daemonLine(String cmd) {
        try {
            LocalSocket s = new LocalSocket();
            s.connect(new LocalSocketAddress("xperiapadctl", LocalSocketAddress.Namespace.ABSTRACT));
            OutputStream o = s.getOutputStream();
            o.write((cmd + "\n").getBytes());
            o.flush();
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String line = r.readLine();
            s.close();
            return line;
        } catch (Exception e) { return null; }
    }

    /** Conecta al UDS y manda PING; devuelve true si el daemon responde. */
    private boolean probeDaemon() {
        try {
            LocalSocket s = new LocalSocket();
            s.connect(new LocalSocketAddress("xperiapadctl", LocalSocketAddress.Namespace.ABSTRACT));
            OutputStream o = s.getOutputStream();
            o.write("PING\n".getBytes());
            o.flush();
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String reply = r.readLine();
            s.close();
            if (reply == null) return false;
            // C5: PING responde aunque el daemon haya arrancado sin BT listo.
            String st = daemonLine("STATE");
            if (st != null && st.contains("active=0")) {
                appendLog("daemon alive but inactive (BT not ready): sending START");
                daemonLine("START");
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Detiene un daemon previo: STOP por el UDS y, si no responde, kill. */
    private void stopExistingDaemon() {
        try {
            LocalSocket s = new LocalSocket();
            s.connect(new LocalSocketAddress("xperiapad", LocalSocketAddress.Namespace.ABSTRACT));
            OutputStream o = s.getOutputStream();
            o.write("STOP\n".getBytes());
            o.flush();
            Thread.sleep(800);
            s.close();
            appendLog("STOP sent to previous daemon");
            return;
        } catch (Exception e) {
            appendLog("no previous daemon at @xperiapad");
        }
        try { Thread.sleep(250); } catch (Exception ignored) {}
        if (probeDaemon()) { // el STOP por UDS no bastó: un su para matar
            try {
                Runtime.getRuntime().exec(new String[]{"su", "-c",
                        "killall xpd 2>/dev/null; killall libxpd.so 2>/dev/null; true"}).waitFor();
                Thread.sleep(300);
            } catch (Exception ignored) {}
        }
    }

    void connectSocket() {
        final int myGen;
        synchronized (this) { myGen = ++connGen; }
        new Thread(new Runnable() {
            public void run() {
                closeSocket();
                try {
                    appendLog("connecting to @xperiapad...");
                    LocalSocket s = new LocalSocket();
                    s.connect(new LocalSocketAddress("xperiapad", LocalSocketAddress.Namespace.ABSTRACT));
                    synchronized (sockLock) {
                        sock = s;
                        sockOut = s.getOutputStream();
                        sockIn = new BufferedReader(new InputStreamReader(s.getInputStream()));
                        connected = true;
                    }
                    appendLog("socket connected");
                    refreshStatus();
                    sendCmd("MODE " + consoleMode());
                    if (foreground && armEnabled) sendCmd("ARM 1");
                    sendReportNow();
                    String line;
                    BufferedReader reader = sockIn;
                    while (reader != null && (line = reader.readLine()) != null) {
                        if (line.startsWith("OK R3")) continue; // no inundar el log
                        if (line.startsWith("OK SCAN ")) {
                            try { scanEnable = Integer.parseInt(line.substring(8).trim()); }
                            catch (Exception ignored) { scanEnable = -1; }
                            appendLog("< " + line);
                            refreshStatus();
                            continue;
                        }
                        appendLog("< " + line);
                    }
                } catch (Exception e) {
                    appendLog("socket: " + e.getMessage());
                } finally {
                    if (myGen == connGen) { // solo el lector actual decide el estado
                        connected = false;
                        closeSocket();
                        appendLog("socket closed");
                        refreshStatus();
                    }
                }
            }
        }).start();
    }

    /** Cierra el socket FUERA del hilo UI: cerrar el lector puede bloquear en readLine. */
    void closeSocket() {
        final LocalSocket s;
        final OutputStream out;
        final BufferedReader in;
        synchronized (sockLock) {
            s = sock; out = sockOut; in = sockIn;
            sock = null; sockOut = null; sockIn = null;
            connected = false;
            connGen++; // invalida al lector anterior
        }
        new Thread(new Runnable() {
            public void run() {
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                try { if (out != null) out.close(); } catch (Exception ignored) {}
                try { if (s != null) s.close(); } catch (Exception ignored) {}
            }
        }).start();
    }

    void sendCmd(final String cmd) {
        final OutputStream out;
        synchronized (sockLock) {
            out = sockOut;
        }
        if (out == null) { appendLog("not connected"); return; }
        try {
            out.write((cmd + "\n").getBytes());
            out.flush();
        } catch (Exception e) {
            appendLog("send ERR: " + e.getMessage());
        }
    }

    /** H3: baja los enlaces ACL para que el host re-resuelva SDP y abra HIDP. */
    void reconnectHid() {
        if (!daemonRunning || sockOut == null) {
            appendLog("reconnect HID: daemon not connected");
            return;
        }
        appendLog("reconnect HID: dropping ACL links");
        sendCmd("LINKRESET");
    }

    static String toHex(byte[] b) {
        char[] hex = "0123456789abcdef".toCharArray();
        char[] out = new char[b.length * 3];
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xFF;
            out[i * 3] = hex[v >>> 4];
            out[i * 3 + 1] = hex[v & 0x0F];
            out[i * 3 + 2] = (i == b.length - 1) ? '\0' : ' ';
        }
        return new String(out, 0, out.length - 1);
    }

    private void sendReportNow() {
        if (!connected || !foreground || !armEnabled) return;
        sendReportState();
    }

    // ===== Modos por consola (contratos en docs/04 y E27/E28) =====

    /** Protocolo de conexion (E29): capa de identidad/bytes, independiente de la
     * base (perfil/mapeo). Valores: "generic" (HID plano, web/PC) y "ds3"
     * (DualShock 3, traducido por MissionControl en la Switch). */
    String activeProtocol() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("active_protocol", "generic");
    }

    void setActiveProtocol(String p) {
        if (!"ds3".equals(p) && !"generic".equals(p)) p = "generic";
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("active_protocol", p).commit();
        if (connected) {
            sendCmd("MODE " + p);
            sendReportState();
        }
    }

    /** El modo del daemon es el protocolo activo (ya no se deriva de la base). */
    String consoleMode() {
        return activeProtocol();
    }

    /** Report 0x01 DS3 (48 datos; contrato de dualshock3_controller, E28):
     * [0]=unk, [1]=sel,L3,R3,start,up,right,down,left, [2]=L2,R2,L1,R1,tri,cir,crs,sq,
     * [3]=PS, [4]=unk, [5..8]=sticks u8 centro 128 (sin invertir), [9..12]=unk,
     * [13..16]=presion dpad, [17]/[18]=L2/R2 (ZL/ZR reales), [19..20]=unk,
     * [21..24]=presion botones, [25..27]=unk, [28]=charge, [29]=bateria (0xEE),
     * [30]=connection, [31..39]=unk, [40..47]=acelerometro neutro.
     * La BASE define la semantica de caras (Switch invierte; PS3 nativa). */
    private byte[] buildDs3Report() {
        byte[] g = new byte[48];
        int b1 = 0, b2 = 0, b3 = 0;
        if (logicalState[8])  b1 |= 0x01; // fisica Select -> select (Minus)
        if (logicalState[10]) b1 |= 0x02; // fisica LS -> L3
        if (logicalState[11]) b1 |= 0x04; // fisica RS -> R3
        if (logicalState[9])  b1 |= 0x08; // fisica Start -> start (Plus)
        if (dUp)    b1 |= 0x10; // dpad up
        if (dRight) b1 |= 0x20; // dpad right
        if (dDown)  b1 |= 0x40; // dpad down
        if (dLeft)  b1 |= 0x80; // dpad left
        if (logicalState[6])  b2 |= 0x01; // fisica LT -> L2 (boton)
        if (logicalState[7])  b2 |= 0x02; // fisica RT -> R2 (boton)
        if (logicalState[4])  b2 |= 0x04; // fisica LB -> L1
        if (logicalState[5])  b2 |= 0x08; // fisica RB -> R1
        // la BASE define la semantica (E29): Switch invierte caras (A=right),
        // PS3 usa la convencion nativa (A=cross/sur).
        boolean swBase = "switch".equals(activeConsole());
        if (logicalState[0])  b2 |= swBase ? 0x20 : 0x40; // A -> circle(Sw) / cross(PS)
        if (logicalState[1])  b2 |= swBase ? 0x40 : 0x20; // B -> cross(Sw) / circle(PS)
        if (logicalState[2])  b2 |= 0x80; // X -> square (ambas bases)
        if (logicalState[3])  b2 |= 0x10; // Y -> triangle (ambas bases)
        if (logicalState[16]) b3 |= 0x01; // fisica Guide -> PS (Switch Home)
        g[1] = (byte) b1;
        g[2] = (byte) b2;
        g[3] = (byte) b3;
        g[5] = r3[1]; g[6] = r3[2]; // LX, LY (centro 128; sin invertir)
        g[7] = r3[3]; g[8] = r3[4]; // RX, RY
        g[13] = (byte) (dUp ? (byte) 0xFF : 0);    g[14] = (byte) (dRight ? (byte) 0xFF : 0);
        g[15] = (byte) (dDown ? (byte) 0xFF : 0);  g[16] = (byte) (dLeft ? (byte) 0xFF : 0);
        g[17] = (byte) (logicalState[6] ? (byte) 0xFF : 0); // L2 presion (ZL)
        g[18] = (byte) (logicalState[7] ? (byte) 0xFF : 0); // R2 presion (ZR)
        g[21] = (byte) (logicalState[3] ? (byte) 0xFF : 0); // tri
        g[22] = (byte) (logicalState[0] ? (byte) 0xFF : 0); // cir
        g[23] = (byte) (logicalState[1] ? (byte) 0xFF : 0); // crs
        g[24] = (byte) (logicalState[2] ? (byte) 0xFF : 0); // sq
        // (las presiones son simetricas entre convenciones: mismo fisico -> mismo sol)
        g[29] = (byte) 0xEE; // bateria: cargando/llena (MC la trata como Full)
        g[40] = 0x01; g[41] = (byte) 0xFF; // accel neutro (SwapEndian==511)
        g[42] = 0x01; g[43] = (byte) 0xFF;
        g[44] = 0x01; g[45] = (byte) 0xFF;
        g[46] = 0x01; g[47] = (byte) 0xFF;
        return g;
    }

    /** Envia el estado actual en el formato del modo activo. */
    void sendReportState() {
        if ("ds3".equals(consoleMode())) {
            byte[] g;
            synchronized (r3lock) { g = buildDs3Report(); }
            sendCmd("R3 " + toHex(g));
            return;
        }
        byte[] copy;
        synchronized (r3lock) { copy = (byte[]) r3.clone(); }
        sendCmd("R3 " + toHex(copy));
    }

    private void updateButton(int maskByte, int bit, boolean down) {
        synchronized (r3lock) {
            int v = r3[maskByte] & 0xFF;
            if (down) v |= bit; else v &= ~bit;
            r3[maskByte] = (byte) v;
        }
        sendReportNow();
    }

    private boolean handleGameKey(int keyCode, boolean down) {
        Integer logical = keyMap.get(Integer.valueOf(keyCode));
        if (logical == null) return false;
        int l = logical.intValue();
        if (l < 0 || l > 19) return true; // asignado a "ninguno"
        setLogicalButton(l, down);
        return true;
    }

    void setLogicalButton(int logical, boolean down) {
        if (logical < 0 || logical >= logicalState.length) return; // "(none)" / fuera de rango
        logicalState[logical] = down;
        // Slots HID alineados con evdev: byte0 bits0-3 = slots 1-4, byte7 = slots 5-12,
        // byte8 bits0-3 = slots 13-16. Hat en byte5.
        switch (logical) {
            case 0:  updateButton(0, 0x01, down); break;
            case 1:  updateButton(0, 0x02, down); break;
            case 2:  updateButton(7, 0x01, down); break;
            case 3:  updateButton(0, 0x08, down); break;
            case 4:  updateButton(7, 0x04, down); break;
            case 5:  updateButton(7, 0x08, down); break;
            case 6:  updateButton(7, 0x10, down); break;
            case 7:  updateButton(7, 0x20, down); break;
            case 8:  updateButton(7, 0x40, down); break;
            case 9:  updateButton(7, 0x80, down); break;
            case 10: updateButton(8, 0x02, down); break;
            case 11: updateButton(8, 0x04, down); break;
            case 12: case 13: case 14: case 15: updateHat(logical, down); break;
            case 16: updateButton(8, 0x01, down); break;
            case 17: updateButton(0, 0x04, down); break;
            case 18: updateButton(7, 0x02, down); break;
            case 19: updateButton(8, 0x08, down); break;
            default: break;
        }
        notifyButtons();
    }

    /** Avisa al overlay de botones que cambie el resaltado. */
    void notifyButtons() {
        Runnable l = buttonStateListener;
        if (l != null) handler.post(l);
    }

    private void updateHat(int logical, boolean down) {
        synchronized (r3lock) {
            if (logical == 12) dUp = down;
            else if (logical == 13) dDown = down;
            else if (logical == 14) dLeft = down;
            else if (logical == 15) dRight = down;
            r3[5] = (byte) ((r3[5] & 0xF0) | (computeHat() & 0x0F));
        }
        sendReportNow();
    }

    private int computeHat() {
        boolean u = dUp, d = dDown, l = dLeft, r = dRight;
        if (u && r) return 1;
        if (d && r) return 3;
        if (d && l) return 5;
        if (u && l) return 7;
        if (u) return 0;
        if (r) return 2;
        if (d) return 4;
        if (l) return 6;
        return 8;
    }

    // ===== Botones en pantalla =====

    private void loadOrInitButtons() {
        buttons.clear();
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        buttonPreset = sp.getString("buttons_preset", "clasico");
        String data = sp.getString(PREF_BUTTONS, null);
        if (data != null && data.length() > 0) {
            try {
                String[] items = data.split("\\|");
                for (int i = 0; i < items.length; i++) {
                    String it = items[i];
                    if (it == null || it.trim().length() == 0) continue;
                    String[] p = it.split(",");
                    if (p.length < 6) continue;
                    ButtonConfig b = new ButtonConfig();
                    b.id = p[0];
                    b.cx = Float.parseFloat(p[1]);
                    b.cy = Float.parseFloat(p[2]);
                    b.r = Float.parseFloat(p[3]);
                    b.logical = Integer.parseInt(p[4]);
                    b.dpad = "1".equals(p[5]);
                    b.output = (p.length >= 7 && p[6].length() > 0) ? p[6] : null;
                    buttons.add(b);
                }
            } catch (Exception ignored) { buttons.clear(); }
        }
        if (buttons.isEmpty()) {
            loadButtonPreset(buttonPreset);
            saveButtons();
        }
    }

    /** Perfiles de layout ya hechos; cicla entre ellos. Devuelve el nombre activo. */
    String cycleButtonPreset() {
        String[] names = presetNames();
        int idx = 0;
        for (int i = 0; i < names.length; i++) if (names[i].equals(buttonPreset)) idx = i;
        buttonPreset = names[(idx + 1) % names.length];
        loadButtonPreset(buttonPreset);
        saveButtons();
        return buttonPreset;
    }

    /** Nombres disponibles: integrados + guardados por el usuario. */
    String[] presetNames() {
        java.util.ArrayList<String> names = new java.util.ArrayList<String>();
        names.add("clasico");
        names.add("doblestick");
        names.add("arcade");
        String extra = getSharedPreferences(PREFS, MODE_PRIVATE).getString("user_presets", "");
        if (extra.length() > 0) {
            String[] parts = extra.split(",");
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].trim().length() > 0) names.add(parts[i].trim());
            }
        }
        return names.toArray(new String[names.size()]);
    }

    void saveButtonsToString(String key) {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        sp.edit().putString(key, buttonsToString()).commit();
    }

    boolean loadButtonsFromString(String data) {
        buttons.clear();
        if (data == null || data.length() == 0) return false;
        try {
            String[] items = data.split("\\|");
            for (int i = 0; i < items.length; i++) {
                String it = items[i];
                if (it == null || it.trim().length() == 0) continue;
                String[] p = it.split(",");
                if (p.length < 6) continue;
                ButtonConfig b = new ButtonConfig();
                b.id = p[0];
                b.cx = Float.parseFloat(p[1]);
                b.cy = Float.parseFloat(p[2]);
                b.r = Float.parseFloat(p[3]);
                b.logical = Integer.parseInt(p[4]);
                b.dpad = "1".equals(p[5]);
                b.output = (p.length >= 7 && p[6].length() > 0) ? p[6] : null;
                buttons.add(b);
            }
        } catch (Exception e) { buttons.clear(); }
        return !buttons.isEmpty();
    }

    String buttonsToString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < buttons.size(); i++) {
            ButtonConfig b = buttons.get(i);
            if (i > 0) sb.append('|');
            sb.append(b.id).append(',')
              .append(b.cx).append(',').append(b.cy).append(',').append(b.r).append(',')
              .append(b.logical).append(',').append(b.dpad ? "1" : "0").append(',')
              .append(b.output == null ? "" : b.output);
        }
        return sb.toString();
    }

    /** Guarda el layout actual como un preset nuevo con nombre. */
    void saveAsNewPreset(String name) {
        String n = name.trim();
        if (n.length() == 0) return;
        saveButtonsToString("preset_" + n);
        buttonPreset = n;
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String extra = sp.getString("user_presets", "");
        String[] parts = extra.length() > 0 ? extra.split(",") : new String[0];
        boolean have = false;
        for (int i = 0; i < parts.length; i++) if (parts[i].equals(n)) have = true;
        if (!have) {
            extra = (extra.length() == 0) ? n : extra + "," + n;
            sp.edit().putString("user_presets", extra).commit();
        }
        sp.edit().putString("buttons_preset", n).commit();
    }

    /** Sobrescribe el preset actual con el layout en pantalla. */
    void overwritePreset() {
        saveButtonsToString("preset_" + buttonPreset);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("buttons_preset", buttonPreset).commit();
    }

    /** Layouts del gamepad virtual: usuario primero, luego integrados. */
    void loadButtonPreset(String name) {
        buttonPreset = name;
        buttons.clear();
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString("preset_" + name, null);
        if (saved != null && loadButtonsFromString(saved)) return;
        if ("doblestick".equals(name)) {
            addBtn("LS", 170, 300, 70, -1, false, "left");
            addBtn("RS", 684, 300, 70, -1, false, "right");
            addBtn("D-PAD", 150, 110, 62, -1, true, null);
            addBtn("X", 556, 110, 26, 2, false, null);
            addBtn("Y", 620, 46, 26, 3, false, null);
            addBtn("A", 620, 174, 26, 0, false, null);
            addBtn("B", 684, 110, 26, 1, false, null);
            addBtn("L1", 96, 40, 28, 4, false, null);
            addBtn("R1", 758, 40, 28, 5, false, null);
            addBtn("SEL", 350, 444, 22, 8, false, null);
            addBtn("STA", 500, 444, 22, 9, false, null);
        } else if ("arcade".equals(name)) {
            addBtn("D-PAD", 150, 280, 78, -1, true, null);
            addBtn("X", 560, 250, 40, 2, false, null);
            addBtn("Y", 650, 160, 40, 3, false, null);
            addBtn("A", 650, 340, 40, 0, false, null);
            addBtn("B", 740, 250, 40, 1, false, null);
            addBtn("L1", 96, 60, 32, 4, false, null);
            addBtn("R1", 758, 60, 32, 5, false, null);
            addBtn("SEL", 400, 444, 24, 8, false, null);
            addBtn("STA", 500, 444, 24, 9, false, null);
        } else { // clasico
            addBtn("D-PAD", 150, 280, 78, -1, true, null);
            addBtn("X", 566, 240, 30, 2, false, null);
            addBtn("Y", 630, 176, 30, 3, false, null);
            addBtn("A", 630, 304, 30, 0, false, null);
            addBtn("B", 694, 240, 30, 1, false, null);
            addBtn("L1", 96, 70, 30, 4, false, null);
            addBtn("R1", 758, 70, 30, 5, false, null);
            addBtn("L2", 176, 44, 26, 6, false, null);
            addBtn("R2", 678, 44, 26, 7, false, null);
            addBtn("SEL", 380, 440, 24, 8, false, null);
            addBtn("STA", 474, 440, 24, 9, false, null);
        }
    }

    void addBtn(String id, float cx, float cy, float r, int logical, boolean dpad, String output) {
        ButtonConfig b = new ButtonConfig();
        b.id = id; b.cx = cx; b.cy = cy; b.r = r; b.logical = logical; b.dpad = dpad; b.output = output;
        buttons.add(b);
    }

    void saveButtons() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        sp.edit().putString(PREF_BUTTONS, buttonsToString()).putString("buttons_preset", buttonPreset).commit();
    }

    // ===== Perfiles =====

    static final String[] BUILTIN_PROFILES = { "generic", "ps4", "switch", "wiiu" };

    boolean isBuiltinProfile(String n) {
        if (n == null) return false;
        for (int i = 0; i < BUILTIN_PROFILES.length; i++) if (BUILTIN_PROFILES[i].equals(n)) return true;
        return false;
    }

    String activeConsole() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("active_console", "generic");
    }

    void setActiveConsole(String c) {
        if (c == null || c.length() == 0) c = "generic";
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("active_console", c).commit();
        if (connected) {
            sendCmd("MODE " + consoleMode());
            sendReportState();
        }
    }

    String[] profileNames() {
        java.util.ArrayList<String> names = new java.util.ArrayList<String>();
        for (int i = 0; i < BUILTIN_PROFILES.length; i++) names.add(BUILTIN_PROFILES[i]);
        String extra = getSharedPreferences(PREFS, MODE_PRIVATE).getString("profiles", "");
        if (extra.length() > 0) {
            String[] parts = extra.split(",");
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].trim().length() > 0 && !names.contains(parts[i].trim())) {
                    names.add(parts[i].trim());
                }
            }
        }
        return names.toArray(new String[names.size()]);
    }

    String activeProfile() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("active_profile", "");
    }

    /** Serializa el estado completo de juego a JSON. */
    String exportProfile() {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("preset", presetName);
            org.json.JSONObject keys = new org.json.JSONObject();
            for (Map.Entry<Integer, Integer> e : keyMap.entrySet()) {
                keys.put(String.valueOf(e.getKey()), e.getValue().intValue());
            }
            o.put("keys", keys);
            org.json.JSONArray st = new org.json.JSONArray();
            for (int i = 0; i < sticks.size(); i++) {
                StickConfig s = sticks.get(i);
                org.json.JSONObject j = new org.json.JSONObject();
                j.put("id", s.id); j.put("output", s.output);
                j.put("x", s.x); j.put("y", s.y); j.put("w", s.w); j.put("h", s.h);
                j.put("dz", s.deadzone); j.put("sens", s.sensitivity);
                j.put("inv", s.invertY ? 1 : 0);
                st.put(j);
            }
            o.put("sticks", st);
            org.json.JSONArray bt = new org.json.JSONArray();
            for (int i = 0; i < buttons.size(); i++) {
                ButtonConfig b = buttons.get(i);
                org.json.JSONObject j = new org.json.JSONObject();
                j.put("id", b.id); j.put("cx", b.cx); j.put("cy", b.cy); j.put("r", b.r);
                j.put("l", b.logical); j.put("d", b.dpad ? 1 : 0);
                j.put("o", b.output == null ? "" : b.output);
                bt.put(j);
            }
            o.put("buttons", bt);
            o.put("bpreset", buttonPreset);
            o.put("console", activeConsole());
            return o.toString();
        } catch (Throwable t) {
            appendLog("profile export ERR: " + t.toString());
            return null;
        }
    }

    /** Aplica un perfil (mapeo + sticks + botones). Devuelve true si todo ok. */
    boolean applyProfileJson(String data) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(data);
            String preset = o.optString("preset", "standard");
            if ("web-compat".equals(preset)) preset = "standard";
            applyPreset(preset, false);
            String console = o.optString("console", "generic");
            if ("xbox".equals(console)) console = "generic"; // migracion
            setActiveConsole(console);
            org.json.JSONObject keys = o.getJSONObject("keys");
            java.util.Iterator<String> it = keys.keys();
            while (it.hasNext()) {
                String k = it.next();
                try {
                    keyMap.put(Integer.valueOf(Integer.parseInt(k)), Integer.valueOf(keys.getInt(k)));
                } catch (Exception ignored) {}
            }
            saveKeyMap(presetName);

            sticks.clear();
            org.json.JSONArray st = o.optJSONArray("sticks");
            if (st != null) {
                for (int i = 0; i < st.length(); i++) {
                    org.json.JSONObject j = st.getJSONObject(i);
                    StickConfig s = new StickConfig();
                    s.id = j.optString("id", "left");
                    s.output = j.optString("output", "left");
                    s.x = (float) j.optDouble("x", 0);
                    s.y = (float) j.optDouble("y", 0);
                    s.w = (float) j.optDouble("w", 320);
                    s.h = (float) j.optDouble("h", 320);
                    s.deadzone = (float) j.optDouble("dz", 0.18);
                    s.sensitivity = (float) j.optDouble("sens", 1);
                    s.invertY = j.optInt("inv", 1) != 0;
                    sticks.add(s);
                }
            }
            if (sticks.isEmpty()) initDefaultSticks();
            saveSticks();

            buttons.clear();
            org.json.JSONArray bt = o.optJSONArray("buttons");
            if (bt != null) {
                for (int i = 0; i < bt.length(); i++) {
                    org.json.JSONObject j = bt.getJSONObject(i);
                    ButtonConfig b = new ButtonConfig();
                    b.id = j.optString("id", "BTN");
                    b.cx = (float) j.optDouble("cx", 427);
                    b.cy = (float) j.optDouble("cy", 240);
                    b.r = (float) j.optDouble("r", 30);
                    b.logical = j.optInt("l", 0);
                    b.dpad = j.optInt("d", 0) != 0;
                    String out = j.optString("o", "");
                    b.output = (out.length() > 0) ? out : null;
                    buttons.add(b);
                }
            }
            if (buttons.isEmpty()) loadButtonPreset("clasico");
            buttonPreset = o.optString("bpreset", "clasico");
            saveButtons();
            return true;
        } catch (Throwable t) {
            appendLog("profile apply ERR: " + t.toString());
            return false;
        }
    }

    /** Nombres de perfil seguros para rutas y comandos root (A1). */
    boolean safeProfileName(String n) {
        if (n == null) return false;
        int len = n.length();
        if (len < 1 || len > 32) return false;
        for (int i = 0; i < len; i++) {
            char c = n.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-') continue;
            return false;
        }
        return true;
    }

    /** Guarda (o sobrescribe) un perfil con el estado actual y lo marca activo.
     * Los perfiles de fabrica no se pueden sobrescribir: devuelve false. */
    boolean saveProfile(String name, String console) {
        String n = name.trim();
        if (!safeProfileName(n)) return false;
        if (isBuiltinProfile(n)) return false;
        setActiveConsole(console);
        String json = exportProfile();
        if (json == null) return false;
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        sp.edit().putString("profile_" + n, json).putString("active_profile", n).commit();
        StringBuilder sb = new StringBuilder();
        boolean have = false;
        for (String x : profileNames()) {
            if (x.equals(n)) have = true;
            if (sb.length() > 0) sb.append(',');
            sb.append(x);
        }
        if (!have) {
            if (sb.length() > 0) sb.append(',');
            sb.append(n);
        }
        sp.edit().putString("profiles", sb.toString()).commit();
        appendLog("profile saved: " + n);
        return true;
    }

    void loadProfile(String name) {
        if (isBuiltinProfile(name)) {
            String json = factoryProfileJson(name);
            if (json != null && applyProfileJson(json)) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("active_profile", name).commit();
                appendLog("factory profile loaded: " + name);
                refreshStatus();
            }
            return;
        }
        String json = getSharedPreferences(PREFS, MODE_PRIVATE).getString("profile_" + name, null);
        if (json == null) { appendLog("profile not found: " + name); return; }
        if (applyProfileJson(json)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("active_profile", name).commit();
            appendLog("profile loaded: " + name);
            refreshStatus();
        }
    }

    /** JSON del perfil de fabrica: mapeo estandar + sticks y layout por defecto. */
    String factoryProfileJson(String console) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("console", console);
            o.put("preset", "standard");
            org.json.JSONObject keys = new org.json.JSONObject();
            for (Map.Entry<Integer, Integer> e : keyMap.entrySet()) {
                keys.put(String.valueOf(e.getKey()), e.getValue().intValue());
            }
            o.put("keys", keys);
            // sticks por defecto (zona izq y der del touchpad)
            org.json.JSONArray st = new org.json.JSONArray();
            float[][] sticksDef = {
                { 12f, 0f, 320f, 320f, 0.18f, 1.0f, 1f },
                { 646f, 0f, 320f, 320f, 0.18f, 1.0f, 1f }
            };
            String[] stickIds = { "left", "right" };
            for (int i = 0; i < 2; i++) {
                org.json.JSONObject j = new org.json.JSONObject();
                j.put("id", stickIds[i]); j.put("output", stickIds[i]);
                j.put("x", sticksDef[i][0]); j.put("y", sticksDef[i][1]);
                j.put("w", sticksDef[i][2]); j.put("h", sticksDef[i][3]);
                j.put("dz", sticksDef[i][4]); j.put("sens", sticksDef[i][5]);
                j.put("inv", (int) sticksDef[i][6]);
                st.put(j);
            }
            o.put("sticks", st);
            // botones por defecto (mismas coordenadas del preset clasico)
            org.json.JSONArray bt = new org.json.JSONArray();
            Object[][] btnDef = {
                { "D-PAD", 150f, 280f, 78f, -1, 1, "" },
                { "X", 566f, 240f, 30f, 2, 0, "" },
                { "Y", 630f, 176f, 30f, 3, 0, "" },
                { "A", 630f, 304f, 30f, 0, 0, "" },
                { "B", 694f, 240f, 30f, 1, 0, "" },
                { "L1", 96f, 70f, 30f, 4, 0, "" },
                { "R1", 758f, 70f, 30f, 5, 0, "" },
                { "L2", 176f, 44f, 26f, 6, 0, "" },
                { "R2", 678f, 44f, 26f, 7, 0, "" },
                { "SEL", 380f, 440f, 24f, 8, 0, "" },
                { "STA", 474f, 440f, 24f, 9, 0, "" }
            };
            for (int i = 0; i < btnDef.length; i++) {
                org.json.JSONObject j = new org.json.JSONObject();
                j.put("id", (String) btnDef[i][0]);
                j.put("cx", ((Float) btnDef[i][1]).floatValue());
                j.put("cy", ((Float) btnDef[i][2]).floatValue());
                j.put("r", ((Float) btnDef[i][3]).floatValue());
                j.put("l", ((Integer) btnDef[i][4]).intValue());
                j.put("d", ((Integer) btnDef[i][5]).intValue());
                j.put("o", (String) btnDef[i][6]);
                bt.put(j);
            }
            o.put("buttons", bt);
            o.put("bpreset", "clasico");
            return o.toString();
        } catch (Throwable t) {
            appendLog("factory profile ERR: " + t.toString());
            return null;
        }
    }

    /** Reescribe el perfil activo con el estado actual (prohibido en fabrica). */
    void saveActiveProfile() {
        String a = activeProfile();
        if (a.length() == 0) return;
        if (isBuiltinProfile(a)) return; // la UI redirige a "guardar como nuevo"
        String json = exportProfile();
        if (json == null) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("profile_" + a, json).commit();
        appendLog("profile updated: " + a);
    }

    void deleteProfile(String name) {
        if (isBuiltinProfile(name)) return; // de fabrica: no se borra
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        sp.edit().remove("profile_" + name).commit();
        StringBuilder sb = new StringBuilder();
        for (String x : profileNames()) {
            if (x.equals(name)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(x);
        }
        sp.edit().putString("profiles", sb.toString()).commit();
        if (activeProfile().equals(name)) sp.edit().putString("active_profile", "").commit();
    }

    void renameProfile(String oldName, String newName) {
        if (isBuiltinProfile(oldName) || isBuiltinProfile(newName)) return;
        String json = getSharedPreferences(PREFS, MODE_PRIVATE).getString("profile_" + oldName, null);
        if (json == null) return;
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        sp.edit().putString("profile_" + newName, json).commit();
        deleteProfile(oldName);
        if (activeProfile().equals(oldName)) sp.edit().putString("active_profile", newName).commit();
    }

    /** Exporta un perfil a /sdcard/XperiaPad/profiles/ (via su, sin permisos nuevos). */
    void exportProfileToSd(final String name, final Runnable onDone) {
        new Thread(new Runnable() {
            public void run() {
                sdOpOk = false; sdOpMsg = getString(R.string.sd_invalid_name);
                if (!safeProfileName(name)) {
                    appendLog("export ERR: invalid profile name");
                    if (onDone != null) handler.post(onDone);
                    return;
                }
                try {
                    String json = (isBuiltinProfile(name))
                            ? factoryProfileJson(name)
                            : getSharedPreferences(PREFS, MODE_PRIVATE).getString("profile_" + name, null);
                    if (json == null) { appendLog("profile not found: " + name);
                        sdOpMsg = getString(R.string.sd_profile_missing); if (onDone != null) handler.post(onDone); return; }
                    java.io.File tmp = new java.io.File(getFilesDir(), "export.json");
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp);
                    fo.write(json.getBytes());
                    fo.close();
                    String dir = "/sdcard/XperiaPad/profiles";
                    String cmd = "mkdir -p " + dir + "; cp '" + tmp.getAbsolutePath() + "' '" + dir + "/" + name + ".json'";
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                    int rc = p.waitFor();
                    tmp.delete();
                    sdOpOk = (rc == 0);
                    sdOpMsg = (rc == 0) ? " " + dir + "/" + name + ".json" : getString(R.string.sd_root_error) + rc;
                    appendLog((rc == 0 ? "profile exported to SD: " : "export ERR rc=" + rc + ": ")
                            + dir + "/" + name + ".json");
                } catch (Throwable t) {
                    sdOpOk = false; sdOpMsg = " " + t.toString();
                    appendLog("export ERR: " + t.toString());
                }
                if (onDone != null) handler.post(onDone);
            }
        }).start();
    }

    /** Nombres de perfiles disponibles en la SD. */
    String[] listSdProfiles() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "ls /sdcard/XperiaPad/profiles/*.json 2>/dev/null"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            java.util.ArrayList<String> out = new java.util.ArrayList<String>();
            String line;
            while ((line = r.readLine()) != null) {
                int i = line.lastIndexOf('/');
                if (i >= 0) line = line.substring(i + 1);
                if (line.endsWith(".json")) line = line.substring(0, line.length() - 5);
                if (safeProfileName(line)) out.add(line);
            }
            p.waitFor();
            return out.toArray(new String[out.size()]);
        } catch (Exception e) {
            appendLog("sd list ERR: " + e.toString());
            return new String[0];
        }
    }

    /** Importa un perfil desde la SD y lo guarda en la app. */
    void importProfileFromSd(final String name, final Runnable onDone) {
        new Thread(new Runnable() {
            public void run() {
                if (!safeProfileName(name)) {
                    appendLog("import ERR: invalid profile name");
                    if (onDone != null) handler.post(onDone);
                    return;
                }
                boolean ok = false;
                sdOpOk = false; sdOpMsg = " importacion fallida";
                try {
                    java.io.File dst = new java.io.File(getFilesDir(), "import.json");
                    String cmd = "mkdir -p /sdcard/XperiaPad/profiles; cp '/sdcard/XperiaPad/profiles/"
                            + name + ".json' '" + dst.getAbsolutePath() + "'";
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                    p.waitFor();
                    StringBuilder sb = new StringBuilder();
                    BufferedReader r = new BufferedReader(new java.io.FileReader(dst));
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                    dst.delete();
                    if (applyProfileJson(sb.toString())) {
                        if (!saveProfile(name, activeConsole())) {
                            appendLog("cannot overwrite a factory profile: " + name);
                            ok = false;
                        } else {
                            ok = true;
                            appendLog("profile imported from SD: " + name);
                        }
                    }
                } catch (Throwable t) {
                    appendLog("import ERR: " + t.toString());
                }
                sdOpOk = ok;
                sdOpMsg = ok ? " " + name : getString(R.string.sd_import_failed) + name;
                appendLog("import: " + sdOpMsg);
                if (onDone != null) handler.post(onDone);
            }
        }).start();
    }

    // ===== Configuracion del sistema (parche P1/P2 + limpieza) =====

    volatile boolean setupRoot = false;
    volatile String setupBtPatch = "?"; // "si" / "no" / "?" (BT-Patch, antes P2)
    volatile boolean setupBusy = false;
    boolean setupDetected = false; // tras la primera deteccion

    // Stack real que consume la app: informativo, nunca bloqueante.
    volatile boolean stackDbus = false;
    volatile boolean stackSdp = false;
    volatile boolean stackBluez4 = false;
    volatile boolean stackConf = false;
    volatile boolean stackTouchpad = false;
    volatile boolean stackTouchpadChecked = false;
    volatile String stackDetected = "...";
    volatile String[] stackWarnings = new String[0];

    // Solo referencia historica. Si difiere se avisa; no impide aplicar ni arrancar.
    static final String STACK_REF_DISPLAY = "4.0.2.A.0.62";
    static final String STACK_REF_KERNEL = "2.6.32";
    static final String BACKUP_DIR = "/sdcard/XperiaPad/backup";

    /** Log de las secuencias del sistema que sobrevive al reinicio (files/setup.log). */
    void setupLog(String s) {
        appendLog(s);
        try {
            java.io.FileOutputStream fo = new java.io.FileOutputStream(
                    new java.io.File(getFilesDir(), "setup.log"), true);
            fo.write((String.valueOf(System.currentTimeMillis()) + " " + s + "\n").getBytes());
            fo.close();
        } catch (Throwable t) { /* el log en archivo es best-effort */ }
    }

    /** Marca de ejecucion en el hilo principal (para medir bloqueos del hilo main). */
    void setupLogMain(String tag) {
        setupLog(tag + " [main @" + System.currentTimeMillis() + "]");
    }

    /** Ejecuta un comando como root y devuelve su salida. */
    /** Lee un archivo legible por todos sin invocar su. */
    String readTextFile(String path) {
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader r = new BufferedReader(new java.io.FileReader(path));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
        } catch (Throwable t) { return ""; }
        return sb.toString();
    }

    String runSu(String cmd) {
        setupLog("su START: " + cmd);
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            p.waitFor();
            setupLog("su END(" + p.exitValue() + "): " + cmd);
            return sb.toString();
        } catch (Throwable t) {
            setupLog("su ERR: " + cmd + " -> " + t.toString());
            return "";
        }
    }

    int suRC(String cmd) {
        setupLog("su START: " + cmd);
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            int rc = p.waitFor();
            setupLog("su END(" + rc + "): " + cmd);
            return rc;
        } catch (Throwable t) {
            setupLog("su ERR: " + cmd + " -> " + t.toString());
            return -1;
        }
    }

    static String sha256(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Throwable t) { return null; }
    }

    // (P1 retirado, E27: sin sha del jar, sin asset embebido, sin extraccion)

    /** Comprueba root SOLO cuando se abre la pantalla de sistema (una vez). */
    void ensureRootCheck(final Runnable onDone) {
        if (rootChecked) {
            if (onDone != null) handler.post(onDone);
            return;
        }
        miscOps.execute(new Runnable() {
            public void run() {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String line = r.readLine();
                    rootAvailable = line != null && line.contains("uid=0");
                    r.close();
                    p.waitFor();
                } catch (Throwable t) { rootAvailable = false; }
                rootChecked = true;
                if (onDone != null) handler.post(onDone);
            }
        });
    }

    /** Deteccion async del estado del sistema y del stack real que consume la app. */
    void runSetupDetection(final Runnable onDone) {
        new Thread(new Runnable() {
            public void run() {
                boolean root = rootAvailable; // comprobado solo al abrir Configuracion del sistema
                String display = (android.os.Build.DISPLAY == null) ? "" : android.os.Build.DISPLAY;
                String kernel = System.getProperty("os.version", "");

                // Stack real: sockets y plugin que usa libxpd.so. Nada de esto bloquea.
                boolean dbus = new java.io.File("/dev/socket/dbus").exists();
                boolean sdp = new java.io.File("/dev/socket/bluetooth").exists();
                boolean bluez4 = new java.io.File("/system/lib/bluez-plugin/input.so").exists()
                        || new java.io.File("/system/lib/bluez/input.so").exists();

                // P1 retirado (E27): framework stock, sin swap de jar
                String p2 = "?";
                String conf = readTextFile("/etc/bluetooth/main.conf"); // 644: sin root
                if (conf.length() > 0) {
                    boolean found = false;
                    String[] lines = conf.split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        String t = lines[i].trim();
                        if (t.startsWith("#") || t.startsWith(";")) continue;
                        if (t.startsWith("DisablePlugins") && t.contains("input")) { found = true; break; }
                    }
                    p2 = found ? "si" : "no";
                }

                // Entrada: solo enumerable desde API 16; en la referencia (API 10) es "n/a".
                boolean touchpad = false;
                boolean touchpadChecked = false;
                if (android.os.Build.VERSION.SDK_INT >= 16) {
                    try {
                        int[] ids = InputDevice.getDeviceIds();
                        for (int i = 0; i < ids.length; i++) {
                            InputDevice d = InputDevice.getDevice(ids[i]);
                            if (d == null) continue;
                            boolean isPad = (d.getSources() & SOURCE_TOUCHPAD) != 0;
                            if (!isPad && d.getName() != null)
                                isPad = d.getName().toLowerCase().indexOf("touchpad") >= 0;
                            if (isPad) { touchpad = true; break; }
                        }
                        touchpadChecked = true;
                    } catch (Throwable t) {
                        appendLog("stack probe: touchpad enumeration failed");
                    }
                }

                java.util.ArrayList<String> warn = new java.util.ArrayList<String>();
                if (!dbus) warn.add("dbus");
                if (!sdp) warn.add("sdp");
                if (!bluez4) warn.add("bluez4-plugin");
                if (conf.length() == 0) warn.add("main.conf");
                if (touchpadChecked && !touchpad) warn.add("touchpad-source");
                boolean refDisplay = display.contains(STACK_REF_DISPLAY);
                boolean refKernel = kernel.startsWith(STACK_REF_KERNEL);
                if (!refDisplay || !refKernel) warn.add("build/kernel");

                setupRoot = root;
                setupBtPatch = p2;
                stackDbus = dbus;
                stackSdp = sdp;
                stackBluez4 = bluez4;
                stackConf = conf.length() > 0;
                stackTouchpad = touchpad;
                stackTouchpadChecked = touchpadChecked;
                stackDetected = "Android " + android.os.Build.VERSION.RELEASE + " / "
                        + (kernel.length() > 0 ? kernel : "?")
                        + (refDisplay && refKernel ? " (ref)" : "");
                stackWarnings = warn.toArray(new String[warn.size()]);

                appendLog("stack probe: dbus=" + dbus + " sdp=" + sdp + " bluez4=" + bluez4
                        + " main.conf=" + (conf.length() > 0)
                        + " touchpad=" + (touchpadChecked ? String.valueOf(touchpad) : "n/a")
                        + " ref=" + refDisplay + "/" + refKernel);
                if (warn.size() > 0)
                    appendLog("unverified stack: may fail horribly ("
                            + android.text.TextUtils.join(", ", warn) + ")");
                else
                    appendLog("stack probe: all expected capabilities present");

                // cache solo si la deteccion fue completa (nada en "?")
                boolean known = p2.equals("si") || p2.equals("no");
                if (known) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString("last_patched_state", p2.equals("si") ? "si" : "no").commit();
                }
                if (onDone != null) handler.post(onDone);
            }
        }).start();
    }

    /** Aplica el parche: solo P2 (P1 retirado, E27). Sin reboot, sin tocar el framework. */
    void applyPatchSequence(final Runnable onDone) {
        if (setupBusy) return;
        final boolean needP2 = !"si".equals(setupBtPatch);
        if (!needP2) {
            setupLog("nothing to do: BT-Patch already applied");
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                final boolean[] notified = {false};
                final Runnable finishSetup = new Runnable() {
                    public void run() {
                        if (notified[0]) return;
                        notified[0] = true;
                        if (onDone != null) handler.post(onDone);
                    }
                };
                setupBusy = true;
                try {
                    setupLog("== APPLY BT-Patch (framework stays stock) ==");
                    // respaldo de main.conf en la SD: solo si no existe, nunca se pisa
                    if (suRC("test -f '" + BACKUP_DIR + "/main.conf.stock'") == 0) {
                        setupLog("main.conf backup already on SD: keeping it");
                    } else {
                        if (suRC("mkdir -p " + BACKUP_DIR) != 0) {
                            setupLog("ABORTED: could not create " + BACKUP_DIR);
                            return;
                        }
                        if (suRC("cp /etc/bluetooth/main.conf " + BACKUP_DIR + "/main.conf.stock") != 0) {
                            setupLog("WARN: main.conf backup failed (continuing)");
                        } else {
                            setupLog("backup main.conf OK");
                        }
                    }
                    String conf = runSu("cat /etc/bluetooth/main.conf");
                    boolean already = false;
                    String[] lines = conf.split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        String t = lines[i].trim();
                        if (t.startsWith("DisablePlugins") && t.contains("input")) already = true;
                    }
                    if (already) {
                        setupLog("BT-Patch was already applied");
                    } else {
                        StringBuilder nb = new StringBuilder();
                        boolean inserted = false;
                        for (int i = 0; i < lines.length; i++) {
                            String t = lines[i];
                            if (!inserted && t.trim().startsWith("[General]")) {
                                nb.append("[General]\n").append("DisablePlugins = input\n");
                                inserted = true;
                            } else {
                                nb.append(t).append('\n');
                            }
                        }
                        if (!inserted) nb.insert(0, "[General]\nDisablePlugins = input\n");
                        java.io.File tmpc = new java.io.File(getFilesDir(), "main.conf.new");
                        java.io.FileOutputStream fo = new java.io.FileOutputStream(tmpc);
                        fo.write(nb.toString().getBytes());
                        fo.close();
                        if (suRC("mount -o remount,rw /system") != 0
                                && suRC("mount -o remount,rw /") != 0) {
                            setupLog("ABORTED: could not remount /system");
                            tmpc.delete();
                            return;
                        }
                        if (suRC("cp '" + tmpc.getAbsolutePath() + "' /etc/bluetooth/main.conf") != 0) {
                            setupLog("ABORTED: main.conf write failed");
                            tmpc.delete();
                            return;
                        }
                        suRC("chmod 644 /etc/bluetooth/main.conf");
                        suRC("mount -o remount,ro /system 2>/dev/null");
                        tmpc.delete();
                        setupLog("DisablePlugins = input written");
                    }
                    // respawn de bluetoothd: el framework lo relanza solo y relee la config
                    // (archivo de texto plano: sin dexopt, sin cascada, sin reboot)
                    setupLog("== restarting bluetoothd ==");
                    suRC("killall bluetoothd 2>/dev/null; busybox killall bluetoothd 2>/dev/null; true");
                    if (connected) sendCmd("BTRESTART"); // re-registrar SDP cuando vuelva bluetoothd
                    setupLog("BT-Patch ready; HID gate bypassed with BT-Patch + host trusted (E27)");
                    setupLog("(if daemon was already running, send START again to re-register SDP)");
                    runSetupDetection(new Runnable() {
                        public void run() {
                            handler.post(new Runnable() {
                                public void run() {
                                    setupLogMain("apply: BT-Patch=" + setupBtPatch);
                                    if (onDone != null) onDone.run();
                                }
                            });
                        }
                    });
                } catch (Throwable t) {
                    setupLog("ABORTED ERR: " + t.toString());
                } finally {
                    setupBusy = false;
                    finishSetup.run(); // onDone tambien en los return de error
                }
            }
        }).start();
    }

    /** Revierte todo lo que la app hizo al sistema (solo P2; el framework nunca se toca). */
    void revertSystemSequence(final Runnable onDone) {
        if (setupBusy) return;
        new Thread(new Runnable() {
            public void run() {
                final boolean[] notified = {false};
                final Runnable finishSetup = new Runnable() {
                    public void run() {
                        if (notified[0]) return;
                        notified[0] = true;
                        if (onDone != null) handler.post(onDone);
                    }
                };
                setupBusy = true;
                try {
                    setupLog("== REVERT ==");
                    // 1) quitar DisablePlugins de main.conf (archivo de texto: sin cascada)
                    String conf = runSu("cat /etc/bluetooth/main.conf");
                    if (conf.length() > 0) {
                        StringBuilder nb = new StringBuilder();
                        String[] confLines = conf.split("\n");
                        boolean had = false;
                        for (int i = 0; i < confLines.length; i++) {
                            String t = confLines[i].trim();
                            if (t.startsWith("DisablePlugins") && t.contains("input")) { had = true; continue; }
                            nb.append(confLines[i]).append('\n');
                        }
                        if (had) {
                            java.io.File tmpc = new java.io.File(getFilesDir(), "main.conf.new");
                            java.io.FileOutputStream fo = new java.io.FileOutputStream(tmpc);
                            fo.write(nb.toString().getBytes());
                            fo.close();
                            if (suRC("mount -o remount,rw /system") != 0
                                    && suRC("mount -o remount,rw /") != 0) {
                                setupLog("ABORTED: could not remount /system");
                                tmpc.delete();
                                return;
                            }
                            suRC("cp '" + tmpc.getAbsolutePath() + "' /etc/bluetooth/main.conf");
                            suRC("chmod 644 /etc/bluetooth/main.conf");
                            suRC("mount -o remount,ro /system 2>/dev/null");
                            tmpc.delete();
                            setupLog("DisablePlugins removed");
                        } else {
                            setupLog("BT-Patch was not applied");
                        }
                    }
                    // 2) restos en /data/local/tmp (respetando los respaldos)
                    suRC("rm -f /data/local/tmp/xpdctl /data/local/tmp/xpd*.log /data/local/tmp/xpd "
                            + "/data/local/tmp/htest /data/local/tmp/htest2 /data/local/tmp/htest3 /data/local/tmp/htest4 "
                            + "/data/local/tmp/fb*.raw /data/local/tmp/fb*.png 2>/dev/null");
                    setupLog("/data/local/tmp leftovers cleaned");
                    // 3) respawn de bluetoothd para releer la config
                    setupLog("== restarting bluetoothd ==");
                    suRC("killall bluetoothd 2>/dev/null; busybox killall bluetoothd 2>/dev/null; true");
                    if (connected) sendCmd("BTRESTART");
                    setupLog("system clean (framework was never touched)");
                    runSetupDetection(new Runnable() {
                        public void run() {
                            handler.post(new Runnable() {
                                public void run() {
                                    setupLogMain("revert: BT-Patch=" + setupBtPatch);
                                    if (onDone != null) onDone.run();
                                }
                            });
                        }
                    });
                } catch (Throwable t) {
                    setupLog("ABORTED ERR: " + t.toString());
                } finally {
                    setupBusy = false;
                    finishSetup.run(); // onDone tambien en los return de error
                }
            }
        }).start();
    }

    // ===== Mapeo =====

    private void loadOrInitMapping() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String preset = sp.getString(PREF_PRESET, "standard");
        if ("web-compat".equals(preset)) preset = "standard"; // migracion de la identidad obsoleta
        applyPreset(preset, false);
        for (Map.Entry<?, ?> e : sp.getAll().entrySet()) {
            String k = String.valueOf(e.getKey());
            if (!k.startsWith("kc_")) continue;
            try {
                int kc = Integer.parseInt(k.substring(3));
                int lv = ((Integer) e.getValue()).intValue();
                keyMap.put(Integer.valueOf(kc), Integer.valueOf(lv));
            } catch (Exception ignored) {}
        }
        appendLog("preset: " + preset);
    }

    void saveKeyMap(String preset) {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit();
        // Borrado quirurgico: SOLO las claves kc_ obsoletas. Nunca clear(): en estas
        // prefs viven tambien el gamepad virtual, los perfiles y los ajustes.
        java.util.ArrayList<String> stale = new java.util.ArrayList<String>();
        for (String k : sp.getAll().keySet()) {
            if (k.startsWith("kc_")) stale.add(k);
        }
        for (int i = 0; i < stale.size(); i++) ed.remove(stale.get(i));
        ed.putString(PREF_PRESET, preset);
        for (Map.Entry<Integer, Integer> e : keyMap.entrySet()) {
            ed.putInt("kc_" + e.getKey(), e.getValue().intValue());
        }
        ed.commit();
    }

    /** Reasigna una tecla fisica a un boton logico (-1 = sin asignar) y persiste. */
    void setKeyMapping(int kc, int logical) {
        if (logical < 0) keyMap.remove(Integer.valueOf(kc));
        else keyMap.put(Integer.valueOf(kc), Integer.valueOf(logical));
        saveKeyMap(presetName);
    }

    String logicalName(int logical) {
        if (logical < 0 || logical >= LOGICAL_NAMES.length) return "(unknown)";
        return LOGICAL_NAMES[logical];
    }

    /** Etiqueta corta para dibujar en un boton virtual, segun consola. */
    static String shortLabel(int logical, String console) {
        String[] s = { "A", "B", "X", "Y", "LB", "RB", "LT", "RT",
                "SEL", "STA", "LS", "RS", "UP", "DOWN", "LEFT", "RIGHT",
                "GDE", "C", "Z", "E16", "NONE" };
        if (logical < 0 || logical >= s.length) return "BTN";
        if ("switch".equals(console) || "wiiu".equals(console)) {
            // layout Nintendo: abajo B, derecha A, izquierda Y, arriba X
            if (logical == 0) return "B";
            if (logical == 1) return "A";
            if (logical == 2) return "Y";
            if (logical == 3) return "X";
        }
        if ("ps4".equals(console)) {
            // 0..3 se dibujan como formas (cruz, circulo, cuadrado, triangulo)
            if (logical == 4) return "L1";
            if (logical == 5) return "R1";
            if (logical == 6) return "L2";
            if (logical == 7) return "R2";
        }
        return s[logical];
    }

    String keyName(int kc) {        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_UP: return "DPAD_UP";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "DPAD_DOWN";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "DPAD_LEFT";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "DPAD_RIGHT";
            case KeyEvent.KEYCODE_DPAD_CENTER: return "DPAD_CENTER";
            case KeyEvent.KEYCODE_BACK: return "BACK";
            case KeyEvent.KEYCODE_MENU: return "MENU";
            case KeyEvent.KEYCODE_HOME: return "HOME";
            case KeyEvent.KEYCODE_SEARCH: return "SEARCH";
            case KeyEvent.KEYCODE_BUTTON_A: return "BUTTON_A";
            case KeyEvent.KEYCODE_BUTTON_B: return "BUTTON_B";
            case KeyEvent.KEYCODE_BUTTON_C: return "BUTTON_C";
            case KeyEvent.KEYCODE_BUTTON_X: return "BUTTON_X";
            case KeyEvent.KEYCODE_BUTTON_Y: return "BUTTON_Y";
            case KeyEvent.KEYCODE_BUTTON_Z: return "BUTTON_Z";
            case KeyEvent.KEYCODE_BUTTON_L1: return "BUTTON_L1";
            case KeyEvent.KEYCODE_BUTTON_L2: return "BUTTON_L2";
            case KeyEvent.KEYCODE_BUTTON_R1: return "BUTTON_R1";
            case KeyEvent.KEYCODE_BUTTON_R2: return "BUTTON_R2";
            case KeyEvent.KEYCODE_BUTTON_START: return "BUTTON_START";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "BUTTON_SELECT";
            case KeyEvent.KEYCODE_BUTTON_MODE: return "BUTTON_MODE";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "BUTTON_THUMBL";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "BUTTON_THUMBR";
            default: return "KEYCODE_" + kc;
        }
    }

    void applyPreset(String preset, boolean persist) {
        presetName = "standard"; // unico mapeo: la identidad web-compat quedo obsoleta
        keyMap.clear();
        keyMap.put(Integer.valueOf(KeyEvent.KEYCODE_DPAD_UP), 12);
        keyMap.put(Integer.valueOf(KeyEvent.KEYCODE_DPAD_DOWN), 13);
        keyMap.put(Integer.valueOf(KeyEvent.KEYCODE_DPAD_LEFT), 14);
        keyMap.put(Integer.valueOf(KeyEvent.KEYCODE_DPAD_RIGHT), 15);
        keyMap.put(Integer.valueOf(KeyEvent.KEYCODE_DPAD_CENTER), 0);
        keyMap.put(Integer.valueOf(KeyEvent.KEYCODE_BACK), 1);
        keyMap.put(Integer.valueOf(KeyEvent.KEYCODE_BUTTON_X), 2);
        keyMap.put(Integer.valueOf(KeyEvent.KEYCODE_BUTTON_C), 2);
        keyMap.put(Integer.valueOf(KeyEvent.KEYCODE_BUTTON_Y), 3);
        keyMap.put(Integer.valueOf(KeyEvent.KEYCODE_BUTTON_Z), 3);
        keyMap.put(Integer.valueOf(KeyEvent.KEYCODE_BUTTON_L1), 4);
        keyMap.put(Integer.valueOf(KeyEvent.KEYCODE_BUTTON_L2), 4);
        keyMap.put(Integer.valueOf(KeyEvent.KEYCODE_BUTTON_R1), 5);
        keyMap.put(Integer.valueOf(KeyEvent.KEYCODE_BUTTON_R2), 5);
        keyMap.put(Integer.valueOf(KeyEvent.KEYCODE_BUTTON_SELECT), 8);
        keyMap.put(Integer.valueOf(KeyEvent.KEYCODE_MENU), 8);
        keyMap.put(Integer.valueOf(KeyEvent.KEYCODE_BUTTON_START), 9);
        keyMap.put(Integer.valueOf(KeyEvent.KEYCODE_SEARCH), 9);
        keyMap.put(Integer.valueOf(KeyEvent.KEYCODE_HOME), 16);
        if (persist) saveKeyMap(preset);
    }

    // ===== Sticks =====

    private void loadOrInitSticks() {
        sticks.clear();
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String data = sp.getString(PREF_STICKS, null);
        if (data != null && data.length() > 0) {
            try {
                String[] items = data.split("\\|");
                for (int i = 0; i < items.length; i++) {
                    String it = items[i];
                    if (it == null || it.trim().length() == 0) continue;
                    String[] p = it.split(",");
                    if (p.length < 8) continue;
                    StickConfig s = new StickConfig();
                    s.id = p[0];
                    s.output = p[1];
                    s.x = Float.parseFloat(p[2]);
                    s.y = Float.parseFloat(p[3]);
                    s.w = Float.parseFloat(p[4]);
                    s.h = Float.parseFloat(p[5]);
                    s.deadzone = Float.parseFloat(p[6]);
                    s.sensitivity = Float.parseFloat(p[7]);
                    s.invertY = (p.length >= 9) ? "1".equals(p[8]) : true;
                    sticks.add(s);
                }
            } catch (Exception ignored) { sticks.clear(); }
        }
        if (sticks.isEmpty()) {
            initDefaultSticks();
            saveSticks();
        }
    }

    void initDefaultSticks() {
        sticks.clear();
        fillDefaultSticks(sticks);
    }

    /** Llena un destino con las zonas por defecto sin tocar el estado vivo. */
    void fillDefaultSticks(java.util.List<StickConfig> out) {
        // Centros medidos de los indicadores del touchpad (getevent, superficie 966x360):
        // izquierdo ~(172,148), derecho ~(806,160). Cuadrado 320x320 recortado a los limites.
        StickConfig left = new StickConfig();
        left.id = "left"; left.output = "left";
        left.x = 12f; left.y = 0f; left.w = 320f; left.h = 320f;
        left.deadzone = 0.18f; left.sensitivity = 1.0f; left.invertY = true;
        out.add(left);
        StickConfig right = new StickConfig();
        right.id = "right"; right.output = "right";
        right.x = 646f; right.y = 0f; right.w = 320f; right.h = 320f;
        right.deadzone = 0.18f; right.sensitivity = 1.0f; right.invertY = true;
        out.add(right);
    }

    void saveSticks() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sticks.size(); i++) {
            StickConfig s = sticks.get(i);
            if (i > 0) sb.append('|');
            sb.append(s.id).append(',')
              .append(s.output).append(',')
              .append(s.x).append(',').append(s.y).append(',')
              .append(s.w).append(',').append(s.h).append(',')
              .append(s.deadzone).append(',').append(s.sensitivity).append(',')
              .append(s.invertY ? "1" : "0");
        }
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        sp.edit().putString(PREF_STICKS, sb.toString()).commit();
    }

    static float clampf(float v, float lo, float hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    int displayW() { return getWindowManager().getDefaultDisplay().getWidth(); }
    int displayH() { return getWindowManager().getDefaultDisplay().getHeight(); }

    private boolean isPadEvent(MotionEvent ev) {
        if (ev.getSource() == SOURCE_TOUCHPAD) return true;
        InputDevice d = ev.getDevice();
        if (d != null && d.getName() != null) {
            return d.getName().toLowerCase().indexOf("touchpad") >= 0;
        }
        return false;
    }

    // Habilita la entrega de eventos de clase POSITION (touchpad) en el ViewRoot de esta ROM.
    private boolean tryEnablePositionEvents() {
        try {
            Object wm = getWindowManager();
            java.lang.reflect.Field wmf = wm.getClass().getDeclaredField("mWindowManager");
            wmf.setAccessible(true);
            Object impl = wmf.get(wm);
            if (impl == null) return false;
            java.lang.reflect.Field f = impl.getClass().getDeclaredField("mRoots");
            f.setAccessible(true);
            Object[] roots = (Object[]) f.get(impl);
            if (roots == null || roots.length == 0) return false;
            java.lang.reflect.Method m = null;
            int n = 0;
            for (int i = 0; i < roots.length; i++) {
                Object r = roots[i];
                if (r == null) continue;
                if (m == null) m = r.getClass().getMethod("setProcessPositionEvents", boolean.class);
                m.invoke(r, Boolean.TRUE);
                n++;
            }
            if (n > 0) { appendLog("posEvents enabled (" + n + ")"); return true; }
            return false;
        } catch (Throwable t) {
            appendLog("posEvents ERR: " + t);
            return true; // no reintentar si es un error real
        }
    }

    private boolean posEventsEnabled = false;

    private void schedulePositionEvents() {
        if (posEventsEnabled) return;
        handler.postDelayed(new Runnable() {
            int tries = 0;
            public void run() {
                if (posEventsEnabled) return;
                posEventsEnabled = tryEnablePositionEvents();
                tries++;
                if (!posEventsEnabled && tries < 10) handler.postDelayed(this, 300);
            }
        }, 300);
    }

    private void detectTouchpadRanges() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 16) return; // getDeviceIds es API 16
            int[] ids = InputDevice.getDeviceIds();
            for (int k = 0; k < ids.length; k++) {
                InputDevice d = InputDevice.getDevice(ids[k]);
                if (d == null || d.getName() == null) continue;
                if (d.getName().toLowerCase().indexOf("touchpad") < 0) continue;
                InputDevice.MotionRange rx = d.getMotionRange(AXIS_X);
                InputDevice.MotionRange ry = d.getMotionRange(AXIS_Y);
                if (rx != null) { tpMinX = rx.getMin(); tpMaxX = rx.getMax(); }
                if (ry != null) { tpMinY = ry.getMin(); tpMaxY = ry.getMax(); }
                tpRangeKnown = true;
                appendLog("touchpad dev: " + d.getName() + " src=0x" + Integer.toHexString(d.getSources())
                        + " X[" + tpMinX + ".." + tpMaxX + "] Y[" + tpMinY + ".." + tpMaxY + "]");
            }
        } catch (Throwable t) { appendLog("detect pad ERR: " + t); }
    }

    private float padNormX(MotionEvent ev, int i) {
        float span = (tpMaxX - tpMinX) > 0 ? (tpMaxX - tpMinX) : SURF_W;
        float x = ev.getX(i);
        if (ev.getSource() != SOURCE_TOUCHPAD) {
            int dw = displayW();
            if (dw > 0) x = x / (float) dw * span;
        }
        return (x - tpMinX) / span;
    }

    private float padNormY(MotionEvent ev, int i) {
        float span = (tpMaxY - tpMinY) > 0 ? (tpMaxY - tpMinY) : SURF_H;
        float y = ev.getY(i);
        if (ev.getSource() != SOURCE_TOUCHPAD) {
            int dh = displayH();
            if (dh > 0) y = y / (float) dh * span;
        }
        return (y - tpMinY) / span;
    }

    private float padSurfaceX(MotionEvent ev, int i) { return padNormX(ev, i) * SURF_W; }
    private float padSurfaceY(MotionEvent ev, int i) { return padNormY(ev, i) * SURF_H; }

    private StickConfig findStick(String id) {
        for (int i = 0; i < sticks.size(); i++) {
            if (sticks.get(i).id.equals(id)) return sticks.get(i);
        }
        return null;
    }

    private void assignPointer(int pid, float sx, float sy) {
        Integer key = Integer.valueOf(pid);
        if (pointerStick.containsKey(key)) return;
        for (int i = 0; i < sticks.size(); i++) {
            StickConfig s = sticks.get(i);
            if (sx >= s.x && sx <= s.x + s.w && sy >= s.y && sy <= s.y + s.h) {
                if (pointerStick.containsValue(s.id)) continue; // ya ocupado por otro dedo
                pointerStick.put(key, s.id);
                return;
            }
        }
    }

    private void updateStickFromPointer(String id, float sx, float sy) {
        StickConfig s = findStick(id);
        if (s == null) return;
        float cx = s.x + s.w / 2f;
        float cy = s.y + s.h / 2f;
        float hx = s.w / 2f; if (hx <= 0f) hx = 1f;
        float hy = s.h / 2f; if (hy <= 0f) hy = 1f;
        float nx = (sx - cx) / hx;
        float ny = (sy - cy) / hy;
        float m = (float) Math.sqrt(nx * nx + ny * ny);
        float outx = 0f, outy = 0f;
        if (m > 1e-6f) {
            float mm = m > 1f ? 1f : m;
            if (mm >= s.deadzone) {
                float scaled = (mm - s.deadzone) / (1f - s.deadzone);
                if (scaled > 1f) scaled = 1f;
                outx = (nx / m) * scaled;
                outy = (ny / m) * scaled;
            }
        }
        outx *= s.sensitivity;
        outy *= s.sensitivity;
        if (outx > 1f) outx = 1f; else if (outx < -1f) outx = -1f;
        if (outy > 1f) outy = 1f; else if (outy < -1f) outy = -1f;
        if (s.invertY) outy = -outy;
        setStickAxis(s.output, outx, outy);
    }

    private static byte axisByte(float v) {
        int b = Math.round(128f + v * 127f);
        if (b < 0) b = 0; else if (b > 255) b = 255;
        return (byte) (b & 0xFF);
    }

    void setStickAxis(String output, float ax, float ay) {
        synchronized (r3lock) {
            if ("left".equals(output)) {
                r3[1] = axisByte(ax); r3[2] = axisByte(ay);
            } else if ("right".equals(output)) {
                r3[3] = axisByte(ax); r3[4] = axisByte(ay);
            }
            if (stickLogCount < 300) {
                stickLogCount++;
                appendLog("STICK " + output + " ax=" + ax + " ay=" + ay
                        + " -> " + (r3[1] & 0xFF) + "," + (r3[2] & 0xFF)
                        + "," + (r3[3] & 0xFF) + "," + (r3[4] & 0xFF));
            }
        }
        sendReportNow();
    }

    private void releasePointer(int pid) {
        String id = pointerStick.remove(Integer.valueOf(pid));
        if (id != null) {
            StickConfig s = findStick(id);
            if (s != null) setStickAxis(s.output, 0f, 0f);
        }
    }

    private void releaseAllPointers() {
        java.util.Iterator<String> it = pointerStick.values().iterator();
        java.util.HashSet<String> ids = new java.util.HashSet<String>();
        while (it.hasNext()) ids.add(it.next());
        pointerStick.clear();
        for (String id : ids) {
            StickConfig s = findStick(id);
            if (s != null) setStickAxis(s.output, 0f, 0f);
        }
    }

    private void handlePadTouch(MotionEvent ev) {
        int action = ev.getActionMasked();
        int count = ev.getPointerCount();
        if (sticks.isEmpty()) loadOrInitSticks();
        if (action == MotionEvent.ACTION_DOWN) pointerStick.clear();
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            releaseAllPointers();
            return;
        }
        int upIndex = -1;
        if (action == MotionEvent.ACTION_POINTER_UP) {
            upIndex = ev.getActionIndex();
            releasePointer(ev.getPointerId(upIndex));
        }
        for (int i = 0; i < count; i++) {
            if (i == upIndex) continue;
            int pid = ev.getPointerId(i);
            float sx = padSurfaceX(ev, i);
            float sy = padSurfaceY(ev, i);
            assignPointer(pid, sx, sy);
            String id = pointerStick.get(Integer.valueOf(pid));
            if (id != null) updateStickFromPointer(id, sx, sy);
        }
        if (padLogCount < 400) {
            padLogCount++;
            appendLog("PAD act=" + action + " n=" + count + " stk=" + pointerStick.values());
        }
    }

    // ===== Ciclo de vida / eventos =====

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        try {
            if (gameMode && isPadEvent(ev)) { handlePadTouch(ev); return true; }
        } catch (Exception e) { appendLog("touch ERR: " + e); }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int action = event.getAction();
        int kc = event.getKeyCode();
        if (editorMode) return true; // en el editor no se envian inputs ni navegacion
        if (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) {
            boolean down = (action == KeyEvent.ACTION_DOWN);
            appendLog("KEY " + (down ? "DN" : "UP") + " code=" + kc);
            if (gameMode && handleGameKey(kc, down)) return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        padLogCount = 0;
        stickLogCount = 0;
        detectTouchpadRanges();
        schedulePositionEvents();
        if (connected && armEnabled) { sendCmd("ARM 1"); sendReportNow(); }
        appendLog("focus: on");
        refreshStatus();
        // reconexion solo si ya sabemos que hay parche (el gate vive en ensureDaemon)
        if (setupDetected && daemonEnabled && !connected && patchedEnough()) ensureDaemon();
    }

    @Override
    public void onBackPressed() {
        if (gameMode) {
            if (editorMode) exitEditorMode(); else exitGameMode();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 4242) return;
        discDialogPending = false;
        if (res == android.app.Activity.RESULT_CANCELED) {
            discoverable = false;
            discoverableRemain = 0;
            handler.removeCallbacks(discTick);
            appendLog("discoverable: cancelled by user");
        } else if (res > 0) {
            discoverable = true;
            discoverableRemain = res;
            handler.removeCallbacks(discTick);
            handler.postDelayed(discTick, 1000);
            appendLog("discoverable confirmed by system: " + res + " s");
            // el dialogo del sistema reescribe CoD/EIR a telefono (H1): reafirmar ya
            sendCmd("REASSERT");
            appendLog("CoD/EIR re-assert requested after system discoverable");
        }
        refreshStatus();
    }

    @Override
    protected void onPause() {
        foreground = false;
        resetReport();
        loadOrInitMapping();
        if (connected) { sendReportState(); sendCmd("DISARM"); }
        appendLog("focus: off (disarm)");
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) stopDaemonNow(); // cierre real: no dejar daemon suelto
        try { if (btReceiver != null) unregisterReceiver(btReceiver); } catch (Exception ignored) {}
        try { if (stoppedReceiver != null) unregisterReceiver(stoppedReceiver); } catch (Exception ignored) {}
        resetReport();
        loadOrInitMapping();
        if (connected) { sendCmd("DISARM"); }
        closeSocket();
        super.onDestroy();
    }
}
