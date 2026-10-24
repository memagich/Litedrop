package com.litedrop.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.Toast;

import com.litedrop.core.Api;
import com.litedrop.core.Discovery;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Servis u prvom planu koji drži vezu s računarom (long-poll) i čim se pojavi novi fajl
 * ga sam preuzima u Downloads, bez ikakvog pitanja. Isti servis šalje fajlove na računar.
 */
public class SyncService extends Service {
    static final String ACTION_UPLOAD = "com.litedrop.app.UPLOAD";

    private static final String CH_STATUS = "status";
    private static final String CH_EVENTS = "events";
    private static final int ID_STATUS = 1;
    private static final int ID_EVENT = 2;
    private static final int LONG_POLL_SEC = 50;
    private static final int MAX_FAILURES = 3;

    // zajedničko za sve instance servisa: već preuzeti fajlovi se ne preuzimaju ponovo
    private static final Set<String> HANDLED = new HashSet<String>();

    private Store store;
    private NotificationManager nm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService uploader = Executors.newSingleThreadExecutor();
    private final Map<String, Integer> failures = new HashMap<String, Integer>();
    private volatile boolean running;
    private volatile boolean destroyed;
    private Thread loop;
    private String lastStatus = "";

    // ---- pokretanje izvana ----

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, SyncService.class));
        } catch (RuntimeException ignored) {
            // Android ne dozvoljava pokretanje iz pozadine u ovom trenutku
        }
    }

    static void startUpload(Context c, List<Uri> uris) {
        Intent i = new Intent(c, SyncService.class).setAction(ACTION_UPLOAD);
        ClipData cd = null;
        for (Uri u : uris) {
            if (cd == null) {
                cd = ClipData.newRawUri("files", u);
            } else {
                cd.addItem(new ClipData.Item(u));
            }
        }
        if (cd == null) return;
        i.setClipData(cd);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            c.startForegroundService(i);
        } catch (RuntimeException e) {
            Toast.makeText(c, "Slanje nije moguće pokrenuti: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ---- životni ciklus ----

    @Override
    public void onCreate() {
        super.onCreate();
        store = new Store(this);
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH_STATUS, "Status veze", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_EVENTS, "Primljeno i poslano", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(ID_STATUS, buildStatus(lastStatus.isEmpty() ? "Pokrećem…" : lastStatus),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);

        if (intent != null && ACTION_UPLOAD.equals(intent.getAction())) {
            final List<Uri> uris = new ArrayList<Uri>();
            ClipData cd = intent.getClipData();
            if (cd != null) {
                for (int k = 0; k < cd.getItemCount(); k++) {
                    Uri u = cd.getItemAt(k).getUri();
                    if (u != null) uris.add(u);
                }
            }
            if (!uris.isEmpty()) {
                final int id = startId;
                uploader.execute(new Runnable() {
                    @Override
                    public void run() {
                        doUpload(uris, id);
                    }
                });
            }
        }
        if (store.enabled()) ensureLoop();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        running = false;
        if (loop != null) loop.interrupt();
        uploader.shutdownNow();
        super.onDestroy();
    }

    private void ensureLoop() {
        if (loop != null && loop.isAlive()) return;
        running = true;
        loop = new Thread(new Runnable() {
            @Override
            public void run() {
                runLoop();
            }
        }, "litedrop-sync");
        loop.start();
    }

    // ---- primanje s računara ----

    private void runLoop() {
        Api api = null;
        long version = -1;
        long backoff = 2000;
        while (running) {
            try {
                if (!store.isPaired()) {
                    status("Nije upareno s računarom");
                    pause(3000);
                    continue;
                }
                if (!Net.bindWifi(this)) {
                    api = null;
                    status("Čekam Wi-Fi…");
                    pause(15000);
                    continue;
                }
                if (api == null) {
                    status("Tražim računar…");
                    api = resolve();
                    version = -1;
                    if (api == null) {
                        pause(backoff);
                        backoff = Math.min(backoff * 2, 30000);
                        continue;
                    }
                }
                Api.Outbox box = api.waitOutbox(version, LONG_POLL_SEC);
                version = box.version;
                backoff = 2000;
                status("Povezano s " + store.pcName());
                for (Api.Item it : box.items) {
                    if (!running) break;
                    receive(api, it);
                }
            } catch (Api.ApiException e) {
                api = null;
                if (e.status == 401) {
                    store.clearToken();
                    status("Uparivanje je opozvano. Upari ponovo u aplikaciji.");
                } else {
                    status("Greška: " + e.getMessage());
                    pause(backoff);
                    backoff = Math.min(backoff * 2, 30000);
                }
            } catch (Exception e) {
                if (!running) break;
                api = null;
                status("Veza prekinuta, pokušavam ponovo…");
                pause(backoff);
                backoff = Math.min(backoff * 2, 30000);
            }
        }
    }

    private void receive(Api api, Api.Item it) throws IOException {
        synchronized (HANDLED) {
            if (HANDLED.contains(it.id)) {
                deleteQuietly(api, it.id);
                return;
            }
        }
        Integer failed = failures.get(it.id);
        if (failed != null && failed >= MAX_FAILURES) return;

        status("Primam: " + it.name);
        try {
            String saved = Saver.saveDownload(this, api, it, progress("Primam: ", it.name, it.size));
            synchronized (HANDLED) {
                HANDLED.add(it.id);
            }
            notifyEvent("Primljeno", saved, android.R.drawable.stat_sys_download_done);
            deleteQuietly(api, it.id);
        } catch (Api.ApiException e) {
            if (e.status == 404) {
                // fajl više ne postoji na računaru
                synchronized (HANDLED) {
                    HANDLED.add(it.id);
                }
                return;
            }
            countFailure(it);
            throw e;
        } catch (IOException e) {
            countFailure(it);
            throw e;
        }
    }

    private void countFailure(Api.Item it) {
        Integer n = failures.get(it.id);
        int now = n == null ? 1 : n + 1;
        failures.put(it.id, now);
        if (now >= MAX_FAILURES) {
            notifyEvent("Primanje nije uspjelo", it.name, android.R.drawable.stat_notify_error);
        }
    }

    private static void deleteQuietly(Api api, String id) {
        try {
            api.delete(id);
        } catch (IOException ignored) {
            // ako ne uspije, uklanja se pri sljedećem pokušaju
        }
    }

    /** Provjerava zadanu adresu, a ako ne radi traži računar na mreži. */
    private synchronized Api resolve() {
        String token = store.token();
        if (token == null) return null;
        String host = store.host();
        int port = store.port();
        if (host != null) {
            try {
                Api.ping(host, port, 1500);
                return new Api(host, port, token);
            } catch (Exception ignored) {
                // IP se možda promijenio
            }
        }
        String want = store.pcName().isEmpty() ? null : store.pcName();
        Discovery.Found f = Discovery.find(2500, want);
        if (f == null) return null;
        store.saveAddress(f.host, f.port);
        return new Api(f.host, f.port, token);
    }

    // ---- slanje na računar ----

    private void doUpload(List<Uri> uris, int startId) {
        try {
            if (!store.isPaired()) {
                toast("Prvo upari Litedrop s računarom.");
                return;
            }
            Net.bindWifi(this);
            Api api = resolve();
            if (api == null) {
                toast("Računar nije dostupan na Wi-Fi mreži.");
                notifyEvent("Slanje nije uspjelo", "Računar nije dostupan na Wi-Fi mreži.",
                        android.R.drawable.stat_notify_error);
                return;
            }
            int ok = 0;
            String lastError = null;
            for (int i = 0; i < uris.size(); i++) {
                Uri u = uris.get(i);
                String name = displayName(u);
                String prefix = "Šaljem " + (i + 1) + "/" + uris.size() + ": ";
                status(prefix + name);
                InputStream in = null;
                try {
                    in = getContentResolver().openInputStream(u);
                    if (in == null) throw new IOException("ne mogu otvoriti fajl");
                    // chunked (dužina -1): sigurno i kad provider prijavi pogrešnu veličinu
                    api.upload(name, in, -1, progress(prefix, name, sizeOf(u)));
                    ok++;
                } catch (Exception e) {
                    lastError = name + ": " + e.getMessage();
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (IOException ignored) {
                            // nebitno
                        }
                    }
                }
            }
            String summary = ok + " od " + uris.size() + (lastError == null ? "" : " (" + lastError + ")");
            if (ok == uris.size()) {
                toast("Poslano na PC: " + ok);
                notifyEvent("Poslano na PC", summary, android.R.drawable.stat_sys_upload_done);
            } else {
                toast("Slanje nije potpuno uspjelo");
                notifyEvent("Slanje nije potpuno uspjelo", summary, android.R.drawable.stat_notify_error);
            }
            status("Povezano s " + store.pcName());
        } finally {
            // zaustavi servis samo ako prijem nije uključen i nije stigao novi zahtjev
            if (!store.enabled()) stopSelf(startId);
        }
    }

    private String displayName(Uri u) {
        Cursor c = null;
        try {
            c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (RuntimeException ignored) {
            // probaj zadnji segment putanje
        } finally {
            if (c != null) c.close();
        }
        String last = u.getLastPathSegment();
        return last != null ? last : "fajl";
    }

    private long sizeOf(Uri u) {
        Cursor c = null;
        try {
            c = getContentResolver().query(u, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (RuntimeException ignored) {
            // veličina je samo za prikaz napretka
        } finally {
            if (c != null) c.close();
        }
        return -1;
    }

    // ---- obavijesti ----

    private Api.Progress progress(final String prefix, final String name, final long sizeHint) {
        return new Api.Progress() {
            private long last = 0;

            @Override
            public void onProgress(long done, long total) {
                long now = System.currentTimeMillis();
                if (now - last < 700) return;
                last = now;
                long t = total > 0 ? total : sizeHint;
                String pct = t > 0 ? " (" + Math.min(99, done * 100 / t) + "%)" : "";
                status(prefix + name + pct);
            }
        };
    }

    private synchronized void status(String text) {
        if (destroyed || text.equals(lastStatus)) return;
        lastStatus = text;
        store.setStatus(text);
        nm.notify(ID_STATUS, buildStatus(text));
    }

    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildStatus(String text) {
        return new Notification.Builder(this, CH_STATUS)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Litedrop")
                .setContentText(text)
                .setContentIntent(openApp())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void notifyEvent(String title, String text, int icon) {
        Notification n = new Notification.Builder(this, CH_EVENTS)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build();
        nm.notify(ID_EVENT, n);
    }

    private void toast(final String msg) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private static boolean pause(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }
}
