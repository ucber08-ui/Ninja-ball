package com.ninjaball.game;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * Minimal RFC 6455 WebSocket server (text frames only), no external libraries,
 * no internet. Holds at most ONE client (2-player room: host + 1 guest).
 * A second connection is refused with HTTP 409 unless it uses the same ?id= (reconnect).
 */
public class WsServer {
    public interface Listener {
        void onOpen(String addr);
        void onMessage(String text);
        void onClose();
    }

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private final int port;
    private final Listener listener;
    private ServerSocket server;
    private volatile boolean running;
    private final Object lock = new Object();
    private Conn current;
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<Runnable>(512), new ThreadPoolExecutor.DiscardOldestPolicy());

    public WsServer(int port, Listener l) {
        this.port = port;
        this.listener = l;
    }

    public void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port)); // 0.0.0.0 -> reachable over hotspot / Wi-Fi
        running = true;
        Thread t = new Thread(new Runnable() { public void run() { acceptLoop(); } }, "ws-accept");
        t.setDaemon(true);
        t.start();
    }

    public int getPort() { return server.getLocalPort(); }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        Conn c;
        synchronized (lock) { c = current; current = null; }
        if (c != null) c.close();
        writer.shutdownNow();
    }

    /** Send a text message to the connected client (asynchronous, never blocks the caller). */
    public boolean send(final String text) {
        final Conn c;
        synchronized (lock) { c = current; }
        if (c == null || !c.alive) return false;
        try {
            writer.execute(new Runnable() { public void run() { c.sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8)); } });
        } catch (RejectedExecutionException e) { return false; }
        return true;
    }

    public void kick() {
        Conn c;
        synchronized (lock) { c = current; }
        if (c != null) c.close();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                Thread t = new Thread(new Runnable() { public void run() { handle(s); } }, "ws-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void handle(Socket s) {
        Conn c = null;
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(8000); // handshake timeout
            InputStream in = new BufferedInputStream(s.getInputStream(), 8192);
            OutputStream out = s.getOutputStream();
            String head = readHeaders(in);
            if (head == null) { s.close(); return; }
            String[] lines = head.split("\r\n");
            String[] rl = lines[0].split(" ");
            String path = rl.length > 1 ? rl[1] : "/";
            Map<String, String> h = new HashMap<String, String>();
            for (int i = 1; i < lines.length; i++) {
                int k = lines[i].indexOf(':');
                if (k > 0) h.put(lines[i].substring(0, k).trim().toLowerCase(Locale.ROOT), lines[i].substring(k + 1).trim());
            }
            String key = h.get("sec-websocket-key");
            String up = h.get("upgrade");
            if (key == null || up == null || !up.equalsIgnoreCase("websocket")) {
                httpReply(out, "400 Bad Request", "Ninja Ball room: WebSocket only");
                s.close();
                return;
            }
            String id = queryParam(path, "id");
            synchronized (lock) {
                if (current != null && current.alive) {
                    if (id != null && id.equals(current.id)) {
                        current.close(); // same player reconnecting: replace silently
                    } else {
                        httpReply(out, "409 Conflict", "Room is full");
                        s.close();
                        return;
                    }
                }
                c = new Conn(s, in, out, id);
                current = c;
            }
            String accept = base64(MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1)));
            String resp = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n";
            synchronized (c.outLock) { out.write(resp.getBytes(StandardCharsets.ISO_8859_1)); out.flush(); }
            s.setSoTimeout(0);
            listener.onOpen(String.valueOf(s.getInetAddress().getHostAddress()));
            c.readLoop();
        } catch (Exception e) {
            // connection error -> falls through to cleanup
        } finally {
            if (c != null) {
                boolean wasCurrent;
                synchronized (lock) { wasCurrent = (current == c); if (wasCurrent) current = null; }
                c.close();
                if (wasCurrent && running) listener.onClose();
            } else {
                try { s.close(); } catch (IOException ignored) { }
            }
        }
    }

    private static String readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0, x;
        while ((x = in.read()) >= 0) {
            b.write(x);
            if (b.size() > 8192) return null;
            if ((state == 0 || state == 2) && x == '\r') state++;
            else if ((state == 1 || state == 3) && x == '\n') { state++; if (state == 4) break; }
            else state = 0;
        }
        return x < 0 ? null : new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static void httpReply(OutputStream out, String status, String body) throws IOException {
        String r = "HTTP/1.1 " + status + "\r\nContent-Type: text/plain\r\nContent-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body;
        out.write(r.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static String queryParam(String path, String name) {
        int q = path.indexOf('?');
        if (q < 0) return null;
        for (String kv : path.substring(q + 1).split("&")) {
            int e = kv.indexOf('=');
            if (e > 0 && kv.substring(0, e).equals(name)) return kv.substring(e + 1);
        }
        return null;
    }

    private static String base64(byte[] d) {
        final String A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i += 3) {
            int n = (d[i] & 255) << 16 | (i + 1 < d.length ? (d[i + 1] & 255) << 8 : 0) | (i + 2 < d.length ? (d[i + 2] & 255) : 0);
            sb.append(A.charAt(n >> 18 & 63)).append(A.charAt(n >> 12 & 63));
            sb.append(i + 1 < d.length ? A.charAt(n >> 6 & 63) : '=').append(i + 2 < d.length ? A.charAt(n & 63) : '=');
        }
        return sb.toString();
    }

    private class Conn {
        final Socket s; final InputStream in; final OutputStream out; final String id;
        final Object outLock = new Object();
        volatile boolean alive = true;

        Conn(Socket s, InputStream in, OutputStream out, String id) { this.s = s; this.in = in; this.out = out; this.id = id; }

        void close() {
            alive = false;
            try { s.close(); } catch (IOException ignored) { }
        }

        void sendFrame(int op, byte[] p) {
            if (!alive) return;
            try {
                synchronized (outLock) {
                    out.write(0x80 | op);
                    if (p.length < 126) out.write(p.length);
                    else if (p.length < 65536) { out.write(126); out.write(p.length >> 8); out.write(p.length & 255); }
                    else { out.write(127); for (int i = 7; i >= 0; i--) out.write((int) (((long) p.length >> (8 * i)) & 255)); }
                    out.write(p);
                    out.flush();
                }
            } catch (IOException e) { close(); }
        }

        void readLoop() throws IOException {
            DataInputStream d = new DataInputStream(in);
            ByteArrayOutputStream frag = null;
            while (alive) {
                int b0 = d.read();
                if (b0 < 0) return;
                int b1 = d.read();
                if (b1 < 0) return;
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0f;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7f;
                if (len == 126) len = d.readUnsignedShort();
                else if (len == 127) len = d.readLong();
                if (len < 0 || len > (1 << 20)) return;
                byte[] mask = new byte[4];
                if (masked) d.readFully(mask);
                byte[] p = new byte[(int) len];
                d.readFully(p);
                if (masked) for (int i = 0; i < p.length; i++) p[i] ^= mask[i & 3];
                switch (op) {
                    case 0x8: sendFrame(0x8, new byte[0]); return;
                    case 0x9: sendFrame(0xA, p); break;
                    case 0xA: break;
                    case 0x1: case 0x2: case 0x0:
                        if (op != 0) { frag = new ByteArrayOutputStream(); }
                        if (frag == null) break;
                        frag.write(p);
                        if (fin) {
                            String text = new String(frag.toByteArray(), StandardCharsets.UTF_8);
                            frag = null;
                            listener.onMessage(text);
                        }
                        break;
                    default: break;
                }
            }
        }
    }
}
