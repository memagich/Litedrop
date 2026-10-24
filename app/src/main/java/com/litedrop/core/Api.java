package com.litedrop.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Klijent za Litedrop PC server (vidi docs/API.md). Bez Android ovisnosti, pa se testira na JVM-u. */
public final class Api {
    public static final int DEFAULT_PORT = 53400;
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int BUF = 64 * 1024;

    public static final class Item {
        public final String id;
        public final String name;
        public final long size;

        Item(String id, String name, long size) {
            this.id = id;
            this.name = name;
            this.size = size;
        }
    }

    public static final class Outbox {
        public final long version;
        public final List<Item> items;

        Outbox(long version, List<Item> items) {
            this.version = version;
            this.items = items;
        }
    }

    public static final class ApiException extends IOException {
        private static final long serialVersionUID = 1L;
        public final int status;

        public ApiException(int status, String msg) {
            super("HTTP " + status + (msg == null || msg.isEmpty() ? "" : ": " + msg));
            this.status = status;
        }
    }

    public interface Progress {
        void onProgress(long done, long total);
    }

    private final String base;
    private final String token;

    public Api(String host, int port, String token) {
        this.base = baseUrl(host, port);
        this.token = token;
    }

    static String baseUrl(String host, int port) {
        String h = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
        return "http://" + h + ":" + port;
    }

    // ---- bez tokena ----

    public static Map<String, Object> ping(String host, int port, int timeoutMs) throws IOException {
        HttpURLConnection c = open(baseUrl(host, port) + "/api/ping", "GET", null, timeoutMs, timeoutMs);
        try {
            Map<String, Object> m = Json.parseObject(body(c));
            if (!"litedrop".equals(m.get("app"))) throw new IOException("Nije Litedrop");
            return m;
        } finally {
            c.disconnect();
        }
    }

    /** Uparivanje PIN-om; vraća token. ApiException status 403 = pogrešan PIN, 429 = zaključano. */
    public static String pair(String host, int port, String pin, String device) throws IOException {
        HttpURLConnection c = open(baseUrl(host, port) + "/api/pair", "POST", null, CONNECT_TIMEOUT, 10000);
        try {
            byte[] data = ("{\"pin\":" + Json.quote(pin) + ",\"device\":" + Json.quote(device) + "}")
                    .getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(data.length);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream out = c.getOutputStream();
            try {
                out.write(data);
            } finally {
                out.close();
            }
            String tok = Json.asString(Json.parseObject(body(c)).get("token"));
            if (tok == null || tok.isEmpty()) throw new IOException("Server nije vratio token");
            return tok;
        } finally {
            c.disconnect();
        }
    }

    // ---- s tokenom ----

    public Outbox outbox() throws IOException {
        HttpURLConnection c = open(base + "/api/outbox", "GET", token, CONNECT_TIMEOUT, 15000);
        try {
            return parseOutbox(body(c));
        } finally {
            c.disconnect();
        }
    }

    /**
     * Long-poll: vraća se odmah ako se verzija razlikuje od since, inače tek kad se red
     * promijeni ili istekne timeoutSec. since = -1 vraća trenutno stanje odmah.
     */
    public Outbox waitOutbox(long since, int timeoutSec) throws IOException {
        HttpURLConnection c = open(base + "/api/outbox/wait?since=" + since + "&timeout=" + timeoutSec,
                "GET", token, CONNECT_TIMEOUT, (timeoutSec + 15) * 1000);
        try {
            return parseOutbox(body(c));
        } finally {
            c.disconnect();
        }
    }

    /** Preuzima fajl u out (ne zatvara ga). Vraća broj bajtova. */
    public long download(Item it, OutputStream out, Progress p) throws IOException {
        HttpURLConnection c = open(base + "/api/outbox/" + URLEncoder.encode(it.id, "UTF-8"),
                "GET", token, CONNECT_TIMEOUT, 30000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw apiError(c, code);
            long expected = c.getContentLengthLong();
            long total = expected >= 0 ? expected : it.size;
            long done = 0;
            byte[] buf = new byte[BUF];
            InputStream in = c.getInputStream();
            try {
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    done += n;
                    if (p != null) p.onProgress(done, total);
                }
            } finally {
                in.close();
            }
            if (expected >= 0 && done != expected) {
                throw new IOException("Nepotpun prijenos: " + done + " od " + expected + " B");
            }
            return done;
        } finally {
            c.disconnect();
        }
    }

    public void delete(String id) throws IOException {
        HttpURLConnection c = open(base + "/api/outbox/" + URLEncoder.encode(id, "UTF-8"),
                "DELETE", token, CONNECT_TIMEOUT, 10000);
        try {
            body(c);
        } finally {
            c.disconnect();
        }
    }

    /**
     * Šalje fajl na računar. length &gt;= 0: poznata dužina (fiksni Content-Length);
     * length &lt; 0: chunked. Vraća ime pod kojim je spremljen na računaru.
     */
    public String upload(String name, InputStream in, long length, Progress p) throws IOException {
        HttpURLConnection c = open(base + "/api/upload?name=" + URLEncoder.encode(name, "UTF-8"),
                "POST", token, CONNECT_TIMEOUT, 60000);
        try {
            c.setDoOutput(true);
            if (length >= 0) {
                c.setFixedLengthStreamingMode(length);
            } else {
                c.setChunkedStreamingMode(BUF);
            }
            c.setRequestProperty("Content-Type", "application/octet-stream");
            OutputStream out = c.getOutputStream();
            try {
                byte[] buf = new byte[BUF];
                long sent = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    sent += n;
                    if (p != null) p.onProgress(sent, length);
                }
            } finally {
                out.close();
            }
            String saved = Json.asString(Json.parseObject(body(c)).get("saved"));
            return saved == null ? name : saved;
        } finally {
            c.disconnect();
        }
    }

    // ---- pomoćne ----

    private static HttpURLConnection open(String url, String method, String token, int connectTimeout, int readTimeout)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(connectTimeout);
        c.setReadTimeout(readTimeout);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(false);
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }

    /** Čita odgovor; za status != 200 baca ApiException s porukom servera. */
    private static String body(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        if (code != 200) throw apiError(c, code);
        return readAll(c.getInputStream());
    }

    private static ApiException apiError(HttpURLConnection c, int code) {
        String msg = "";
        try {
            InputStream es = c.getErrorStream();
            if (es != null) {
                String text = readAll(es);
                Object err = Json.parseObject(text).get("error");
                msg = err instanceof String ? (String) err : text;
            }
        } catch (Exception ignored) {
            // poruka nije bitna
        }
        return new ApiException(code, msg);
    }

    private static String readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                b.write(buf, 0, n);
                if (b.size() > 8 * 1024 * 1024) throw new IOException("Odgovor je prevelik");
            }
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static Outbox parseOutbox(String text) throws IOException {
        Map<String, Object> m = Json.parseObject(text);
        List<Item> items = new ArrayList<Item>();
        Object raw = m.get("items");
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> im = (Map<String, Object>) o;
                String id = Json.asString(im.get("id"));
                String name = Json.asString(im.get("name"));
                if (id == null || name == null) continue;
                items.add(new Item(id, name, Json.asLong(im.get("size"), -1)));
            }
        }
        return new Outbox(Json.asLong(m.get("version"), 0), items);
    }
}
