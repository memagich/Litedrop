package com.litedrop.core;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pronalazak računara na lokalnoj mreži: šalje UDP "LITEDROP?" i čeka JSON odgovor
 * {"app":"litedrop","name":"...","port":53400}. IP adresa se čita iz izvora paketa.
 */
public final class Discovery {
    public static final int UDP_PORT = 53401;

    public static final class Found {
        public final String host;
        public final int port;
        public final String name;

        public Found(String host, int port, String name) {
            this.host = host;
            this.port = port;
            this.name = name;
        }

        @Override
        public String toString() {
            return name + " (" + host + ")";
        }
    }

    private Discovery() {
    }

    public static Found find(int timeoutMs, String wantName) {
        return find(UDP_PORT, timeoutMs, null, wantName);
    }

    /**
     * @param wantName ako nije null, prihvata se samo računar s tim imenom
     * @param extra    dodatne adrese kojima se šalje upit (npr. u testovima 127.0.0.1)
     */
    public static Found find(int udpPort, int timeoutMs, List<InetAddress> extra, String wantName) {
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket();
            sock.setBroadcast(true);
            byte[] msg = "LITEDROP?".getBytes(StandardCharsets.UTF_8);

            Set<InetAddress> targets = new LinkedHashSet<InetAddress>();
            targets.add(InetAddress.getByName("255.255.255.255"));
            targets.addAll(broadcastAddresses());
            if (extra != null) targets.addAll(extra);

            long end = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[512];
            while (System.currentTimeMillis() < end) {
                for (InetAddress t : targets) {
                    try {
                        sock.send(new DatagramPacket(msg, msg.length, t, udpPort));
                    } catch (Exception ignored) {
                        // neka mrežna sučelja ne dozvoljavaju broadcast
                    }
                }
                long sliceEnd = Math.min(end, System.currentTimeMillis() + 700);
                while (true) {
                    long left = sliceEnd - System.currentTimeMillis();
                    if (left <= 0) break;
                    sock.setSoTimeout((int) left);
                    DatagramPacket in = new DatagramPacket(buf, buf.length);
                    try {
                        sock.receive(in);
                    } catch (SocketTimeoutException e) {
                        break;
                    }
                    Found f = parse(in);
                    if (f != null && (wantName == null || wantName.equals(f.name))) return f;
                }
            }
        } catch (Exception ignored) {
            // nema mreže: vrati null
        } finally {
            if (sock != null) sock.close();
        }
        return null;
    }

    private static Found parse(DatagramPacket p) {
        try {
            Map<String, Object> m = Json.parseObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
            if (!"litedrop".equals(m.get("app"))) return null;
            String name = Json.asString(m.get("name"));
            int port = (int) Json.asLong(m.get("port"), Api.DEFAULT_PORT);
            return new Found(p.getAddress().getHostAddress(), port, name == null ? "PC" : name);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<InetAddress> broadcastAddresses() {
        List<InetAddress> out = new ArrayList<InetAddress>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) out.add(ia.getBroadcast());
                }
            }
        } catch (Exception ignored) {
            // ostaje samo 255.255.255.255
        }
        return out;
    }
}
