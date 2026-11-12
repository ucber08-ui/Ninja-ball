package com.ninjaball.game;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** UDP LAN beacon (host) + listener (guest) so the joiner can find rooms without typing the IP. No internet. */
public class LanDiscovery {
    public static final int UDP_PORT = 8766;
    public interface Found { void onFound(String ip, int port, String name); }

    private volatile boolean beaconOn, listenOn;
    private DatagramSocket beaconSock, listenSock;

    public synchronized void startBeacon(final String name, final int port) {
        stopBeacon();
        beaconOn = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    beaconSock = new DatagramSocket();
                    beaconSock.setBroadcast(true);
                    byte[] data = ("NINJABALL1|" + name.replace('|', ' ') + "|" + port).getBytes(StandardCharsets.UTF_8);
                    while (beaconOn) {
                        for (InetAddress a : broadcastTargets()) {
                            try { beaconSock.send(new DatagramPacket(data, data.length, a, UDP_PORT)); } catch (Exception ignored) { }
                        }
                        Thread.sleep(1000);
                    }
                } catch (Exception ignored) {
                } finally { if (beaconSock != null) beaconSock.close(); }
            }
        }, "beacon");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stopBeacon() {
        beaconOn = false;
        if (beaconSock != null) beaconSock.close();
    }

    public synchronized void startListen(final Found cb) {
        stopListen();
        listenOn = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    listenSock = new DatagramSocket(null);
                    listenSock.setReuseAddress(true);
                    listenSock.bind(new InetSocketAddress(UDP_PORT));
                    byte[] buf = new byte[512];
                    while (listenOn) {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        listenSock.receive(p);
                        String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                        String[] f = s.split("\\|");
                        if (f.length >= 3 && f[0].equals("NINJABALL1")) {
                            try { cb.onFound(p.getAddress().getHostAddress(), Integer.parseInt(f[2].trim()), f[1]); } catch (NumberFormatException ignored) { }
                        }
                    }
                } catch (Exception ignored) {
                } finally { if (listenSock != null) listenSock.close(); }
            }
        }, "discover");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stopListen() {
        listenOn = false;
        if (listenSock != null) listenSock.close();
    }

    static List<InetAddress> broadcastTargets() {
        List<InetAddress> r = new ArrayList<InetAddress>();
        try { r.add(InetAddress.getByName("255.255.255.255")); } catch (Exception ignored) { }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) r.add(ia.getBroadcast());
                }
            }
        } catch (Exception ignored) { }
        return r;
    }

    /** Site-local IPv4 addresses of this device (hotspot AP interface first). */
    public static List<String[]> localIps() {
        List<String[]> r = new ArrayList<String[]>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) r.add(new String[]{ni.getName(), a.getHostAddress()});
                }
            }
        } catch (Exception ignored) { }
        Collections.sort(r, new Comparator<String[]>() {
            public int compare(String[] x, String[] y) { return score(y[0]) - score(x[0]); }
            int score(String n) { n = n.toLowerCase(Locale.ROOT); return (n.startsWith("ap") || n.contains("softap") || n.startsWith("swlan")) ? 2 : n.startsWith("wlan") ? 1 : 0; }
        });
        return r;
    }
}
