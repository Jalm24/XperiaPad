package com.jalm.xperiapad.probe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Sonda de permisos sin root (GID bluetooth 1002). No modifica el sistema:
 * solo abre/bind-connect sockets y los cierra al instante. */
public class ProbeActivity extends Activity {
    static { System.loadLibrary("probe"); }

    private native String runProbe();

    private TextView out;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sc = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = 12;
        root.setPadding(p, p, p, p);
        sc.addView(root);

        Button go = new Button(this);
        go.setText("Probar");
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                StringBuilder extra = new StringBuilder();
                try {
                    java.lang.reflect.Method m = android.bluetooth.BluetoothDevice.class
                            .getDeclaredMethod("setTrust", boolean.class);
                    extra.append("7) BluetoothDevice.setTrust(bool): EXISTE (oculto)\n");
                } catch (Throwable t) {
                    extra.append("7) BluetoothDevice.setTrust(bool): NO (").append(t.getClass().getSimpleName()).append(")\n");
                }
                try {
                    java.lang.reflect.Method m = android.bluetooth.BluetoothAdapter.class
                            .getDeclaredMethod("getBluetoothService", android.os.IBinder.class);
                    extra.append("7) BluetoothAdapter.getBluetoothService: EXISTE (oculto)\n");
                } catch (Throwable t) {
                    extra.append("7) BluetoothAdapter.getBluetoothService: NO\n");
                }
                String r = runProbe();
                out.setText(extra.toString() + r);
                Log.i("XPadProbe", "RESULTADO:\n" + extra + r);
            }
        });
        root.addView(go);
        out = new TextView(this);
        out.setTextSize(12f);
        root.addView(out);
        setContentView(sc);
    }
}
