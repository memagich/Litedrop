import com.litedrop.core.Api;
import com.litedrop.core.Discovery;
import com.litedrop.core.Json;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * JVM test mrežne jezgre protiv PRAVOG Litedrop servera.
 * Argumenti: putanja do litedrop binarke, port, LITEDROP_DIR, LITEDROP_RECV.
 */
public class CoreTest {
    static int failures = 0;

    static void check(boolean ok, String msg) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + msg);
        if (!ok) failures++;
    }

    static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    static String localPin(int port, String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/local/state").openConnection();
        c.setRequestProperty("X-Local-Key", key);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = c.getInputStream().read(buf)) != -1) b.write(buf, 0, n);
        return Json.asString(Json.parseObject(new String(b.toByteArray(), StandardCharsets.UTF_8)).get("pin"));
    }

    static void jsonTests() throws Exception {
        Map<String, Object> m = Json.parseObject(
                "{\"a\":[1,2,{\"b\":\"c\\\"d\\u0107\\n\"}],\"t\":true,\"f\":false,\"n\":null,\"x\":-1.5e2, \"e\":{} }");
        check(m.get("t") == Boolean.TRUE && m.get("f") == Boolean.FALSE && m.containsKey("n") && m.get("n") == null, "json: true/false/null");
        check(Json.asLong(m.get("x"), 0) == -150, "json: broj s eksponentom");
        List<?> a = (List<?>) m.get("a");
        Map<?, ?> inner = (Map<?, ?>) a.get(2);
        check("c\"dć\n".equals(inner.get("b")), "json: escape i \\u");
        check(Json.quote("a\"b\\c\n\u0001č").equals("\"a\\\"b\\\\c\\n\\u0001č\""), "json: quote");
        boolean bad = false;
        try {
            Json.parse("{\"a\":}");
        } catch (Exception e) {
            bad = true;
        }
        check(bad, "json: neispravan ulaz baca grešku");
        check(Json.parse("[]") instanceof List && ((List<?>) Json.parse("[]")).isEmpty(), "json: prazan niz");
    }

    public static void main(String[] a) throws Exception {
        String bin = a[0];
        int port = Integer.parseInt(a[1]);
        String dir = a[2];
        File recv = new File(a[3]);
        String host = "127.0.0.1";
        String key = new String(Files.readAllBytes(new File(dir, "local.key").toPath()), StandardCharsets.UTF_8).trim();

        jsonTests();

        // pronalazak (UDP): na loopbacku šaljemo direktno na 127.0.0.1
        Discovery.Found f = Discovery.find(port + 1, 4000, Arrays.asList(InetAddress.getByName("127.0.0.1")), null);
        check(f != null && f.port == port && f.name != null, "discovery pronalazi server (" + f + ")");
        check(Discovery.find(port + 1, 1500, Arrays.asList(InetAddress.getByName("127.0.0.1")), "NEPOSTOJECE-IME") == null,
                "discovery filtrira po imenu");

        Map<String, Object> ping = Api.ping(host, port, 2000);
        check("litedrop".equals(ping.get("app")), "ping");

        // uparivanje
        String pin = localPin(port, key);
        String wrong = pin.equals("111111") ? "222222" : "111111";
        try {
            Api.pair(host, port, wrong, "Test");
            check(false, "pogrešan PIN treba odbiti");
        } catch (Api.ApiException e) {
            check(e.status == 403, "pogrešan PIN -> 403 (" + e.getMessage() + ")");
        }
        String token = Api.pair(host, port, pin, "Test telefon čš");
        check(token.length() >= 32, "pair vraća token");
        Api api = new Api(host, port, token);

        try {
            new Api(host, port, "los-token").outbox();
            check(false, "lošem tokenu treba 401");
        } catch (Api.ApiException e) {
            check(e.status == 401, "loš token -> 401");
        }

        Api.Outbox box = api.outbox();
        check(box.items.isEmpty(), "red je na početku prazan");

        // long-poll: čeka dok PC ne pošalje fajl
        File src = new File(recv.getParentFile(), "video ćšđ test.mp4");
        byte[] data = randomBytes(2 * 1024 * 1024 + 123, 1);
        Files.write(src.toPath(), data);

        final Api.Outbox[] result = new Api.Outbox[1];
        final Exception[] err = new Exception[1];
        final long since = box.version;
        final long t0 = System.currentTimeMillis();
        Thread poller = new Thread(new Runnable() {
            public void run() {
                try {
                    result[0] = new Api(host, port, token).waitOutbox(since, 20);
                } catch (Exception e) {
                    err[0] = e;
                }
            }
        });
        poller.start();
        Thread.sleep(600);
        check(poller.isAlive(), "long-poll čeka dok nema promjene");
        Process pr = new ProcessBuilder(bin, "send", src.getAbsolutePath()).inheritIO().start();
        check(pr.waitFor() == 0, "litedrop send (Send to) uspješan");
        poller.join(10000);
        long took = System.currentTimeMillis() - t0;
        check(err[0] == null && result[0] != null && result[0].items.size() == 1 && took < 5000,
                "long-poll vraćen odmah nakon slanja (" + took + " ms) " + (err[0] == null ? "" : err[0]));
        Api.Item item = result[0].items.get(0);
        check(item.name.equals(src.getName()) && item.size == data.length, "ime (s dijakriticima) i veličina stavke");

        // preuzimanje
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        final long[] lastDone = new long[1];
        long n = api.download(item, got, new Api.Progress() {
            public void onProgress(long done, long total) {
                lastDone[0] = done;
            }
        });
        check(n == data.length && Arrays.equals(got.toByteArray(), data) && lastDone[0] == data.length,
                "preuzimanje je identično (" + n + " B)");

        api.delete(item.id);
        check(api.outbox().items.isEmpty(), "delete uklanja stavku");
        check(src.exists(), "original na računaru je netaknut");
        try {
            api.download(item, new ByteArrayOutputStream(), null);
            check(false, "preuzimanje obrisane stavke treba 404");
        } catch (Api.ApiException e) {
            check(e.status == 404, "obrisana stavka -> 404");
        }

        // slanje na PC: fiksna dužina
        byte[] up1 = randomBytes(3 * 1024 * 1024 + 7, 2);
        String s1 = api.upload("foto čšđ 1.jpg", new ByteArrayInputStream(up1), up1.length, null);
        check(s1.equals("foto čšđ 1.jpg"), "upload (fiksna dužina) ime: " + s1);
        check(Arrays.equals(Files.readAllBytes(new File(recv, s1).toPath()), up1), "upload (fiksna dužina) sadržaj identičan");

        // slanje: chunked, isto ime -> (1)
        byte[] up2 = randomBytes(1024 * 1024 + 1, 3);
        String s2 = api.upload("foto čšđ 1.jpg", new ByteArrayInputStream(up2), -1, null);
        check(s2.equals("foto čšđ 1 (1).jpg"), "upload (chunked) ime s nastavkom: " + s2);
        check(Arrays.equals(Files.readAllBytes(new File(recv, s2).toPath()), up2), "upload (chunked) sadržaj identičan");

        // sigurnost imena
        String s3 = api.upload("../../x.txt", new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)), 3, null);
        check(s3.equals("x.txt") && new File(recv, "x.txt").exists(), "ime s putanjom je očišćeno: " + s3);

        // prazan fajl
        String s4 = api.upload("prazan.txt", new ByteArrayInputStream(new byte[0]), 0, null);
        check(new File(recv, s4).length() == 0, "prazan fajl se šalje");

        System.out.println(failures == 0 ? "\nSVE PROŠLO" : "\nGREŠKE: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
