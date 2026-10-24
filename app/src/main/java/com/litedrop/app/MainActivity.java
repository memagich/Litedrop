package com.litedrop.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.litedrop.core.Api;
import com.litedrop.core.Discovery;

import java.util.ArrayList;
import java.util.List;

/** Jedini ekran: uparivanje, uključi/isključi prijem, ručno slanje fajlova. Gradi se u kodu (bez XML layouta). */
public class MainActivity extends Activity {
    private static final int REQ_PICK = 1;
    private static final int REQ_NOTIF = 2;

    private Store store;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private TextView statusView;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (statusView != null) statusView.setText(statusText());
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        if (store.isPaired() && store.enabled()) SyncService.start(this);
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        super.onPause();
    }

    // ---- UI ----

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private String statusText() {
        if (!store.isPaired()) return "";
        if (!store.enabled()) return "Prijem je isključen";
        String s = store.status();
        return s.isEmpty() ? "Pokrećem…" : s;
    }

    private void render() {
        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        sv.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);
        root.addView(text("Litedrop", 26, true));
        statusView = null;
        if (store.isPaired()) renderPaired();
        else renderPairing();
    }

    private void renderPairing() {
        root.addView(text("Upari telefon s računarom", 18, true));
        root.addView(text("1. Na računaru pokreni litedrop.exe (otvara se stranica s PIN-om).\n"
                + "2. Telefon mora biti na istom Wi-Fi-ju.\n"
                + "3. Upiši PIN i dodirni Upari.", 15, false));

        final EditText pin = new EditText(this);
        pin.setHint("PIN (6 cifara)");
        pin.setInputType(InputType.TYPE_CLASS_NUMBER);
        pin.setGravity(Gravity.CENTER);
        pin.setTextSize(24);
        root.addView(pin);

        final EditText ip = new EditText(this);
        ip.setHint("IP računara (neobavezno, npr. 192.168.0.2)");
        ip.setInputType(InputType.TYPE_CLASS_PHONE);
        root.addView(ip);

        final TextView msg = text("", 14, false);
        root.addView(msg);

        final Button go = button("Upari", null);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doPair(pin.getText().toString().trim(), ip.getText().toString().trim(), msg, go);
            }
        });
        root.addView(go);
    }

    private void doPair(final String pin, final String ip, final TextView msg, final Button go) {
        if (pin.length() != 6) {
            msg.setText("PIN ima 6 cifara.");
            return;
        }
        go.setEnabled(false);
        msg.setText("Tražim računar…");
        final Context appCtx = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result = null;
                try {
                    Net.bindWifi(appCtx);
                    String host = ip;
                    int port = Api.DEFAULT_PORT;
                    String name = "PC";
                    if (host.isEmpty()) {
                        Discovery.Found f = Discovery.find(4000, null);
                        if (f == null) {
                            result = "Računar nije pronađen. Provjeri da je litedrop.exe pokrenut i da su "
                                    + "oba uređaja na istom Wi-Fi-ju, ili upiši IP računara (piše na stranici s PIN-om).";
                        } else {
                            host = f.host;
                            port = f.port;
                            name = f.name;
                        }
                    } else {
                        java.util.Map<String, Object> p = Api.ping(host, port, 3000);
                        String n = com.litedrop.core.Json.asString(p.get("name"));
                        if (n != null) name = n;
                    }
                    if (result == null) {
                        String token = Api.pair(host, port, pin, Build.MODEL);
                        store.savePairing(host, port, token, name);
                    }
                } catch (Api.ApiException e) {
                    if (e.status == 403) result = "Pogrešan PIN.";
                    else if (e.status == 429) result = "Previše pokušaja. Pričekaj minut i pročitaj novi PIN na računaru.";
                    else result = "Greška: " + e.getMessage();
                } catch (Exception e) {
                    result = "Nema veze s računarom: " + e.getMessage();
                }
                final String r = result;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (r == null) {
                            SyncService.start(MainActivity.this);
                            render();
                        } else {
                            msg.setText(r);
                            go.setEnabled(true);
                        }
                    }
                });
            }
        }).start();
    }

    private void renderPaired() {
        root.addView(text("Upareno: " + store.pcName() + " (" + store.host() + ")", 16, false));
        statusView = text(statusText(), 15, false);
        root.addView(statusView);

        root.addView(button(store.enabled() ? "Pauziraj prijem" : "Uključi prijem", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = !store.enabled();
                store.setEnabled(on);
                if (on) SyncService.start(MainActivity.this);
                else stopService(new Intent(MainActivity.this, SyncService.class));
                render();
            }
        }));

        root.addView(button("Pošalji fajlove na PC", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(i, REQ_PICK);
            }
        }));

        root.addView(text("Fajlovi s računara (desni klik, Send to, Litedrop) stižu sami u mapu Downloads. "
                + "Iz galerije ili bilo koje aplikacije: Podijeli, Pošalji na PC.", 14, false));

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            root.addView(text("Da prijem radi pouzdano kad je ekran ugašen, isključi uštedu baterije za Litedrop.",
                    14, false));
            root.addView(button("Isključi uštedu baterije", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (RuntimeException e) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                }
            }));
        }

        root.addView(button("Odpari", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopService(new Intent(MainActivity.this, SyncService.class));
                store.clearAll();
                render();
            }
        }));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<Uri>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri u = data.getClipData().getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        SyncService.startUpload(this, uris);
        Toast.makeText(this, "Šaljem na PC (" + uris.size() + ")…", Toast.LENGTH_SHORT).show();
    }
}
