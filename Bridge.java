package com.ninjaball.game;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * JavaScript <-> native bridge, exposed to the game as window.NinjaNative.
 * Host phone: native TCP/WebSocket server (WsServer) + UDP beacon. Everything is local, no internet.
 * Native -> JS events are delivered through window.__nn(eventName, payload).
 */
public class Bridge {
    private final Activity act;
    private final WebView web;
    private WsServer server;
    private final LanDiscovery lan = new LanDiscovery();
    private WifiManager.MulticastLock mlock;

    public Bridge(Activity a, WebView w) {
        act = a;
        web = w;
    }

    private void emit(final String ev, final String payload) {
        act.runOnUiThread(new Runnable() {
            public void run() {
                web.evaluateJavascript("window.__nn&&window.__nn(" + JSONObject.quote(ev) + "," + JSONObject.quote(payload) + ")", null);
            }
        });
    }

    private void acquireMulticast() {
        try {
            if (mlock == null) {
                WifiManager wm = (WifiManager) act.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                mlock = wm.createMulticastLock("ninjaball");
                mlock.setReferenceCounted(false);
            }
            if (!mlock.isHeld()) mlock.acquire();
        } catch (Exception ignored) { }
    }

    private void releaseMulticast() {
        try { if (mlock != null && mlock.isHeld()) mlock.release(); } catch (Exception ignored) { }
    }

    @JavascriptInterface
    public boolean isNative() { return true; }

    /** Starts the room server. Returns {"ok":true,"port":8765,"ips":[{"n":"wlan0","ip":"192.168.43.1"}]} */
    @JavascriptInterface
    public synchronized String startServer(int port) {
        try {
            stopServer();
            acquireMulticast();
            server = new WsServer(port, new WsServer.Listener() {
                public void onOpen(String addr) { emit("client_open", addr); }
                public void onMessage(String text) { emit("client_msg", text); }
                public void onClose() { emit("client_close", ""); }
            });
            server.start();
            JSONArray ips = new JSONArray();
            List<String[]> l = LanDiscovery.localIps();
            for (String[] x : l) {
                JSONObject o = new JSONObject();
                o.put("n", x[0]);
                o.put("ip", x[1]);
                ips.put(o);
            }
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("port", server.getPort());
            r.put("ips", ips);
            return r.toString();
        } catch (Exception e) {
            try {
                JSONObject r = new JSONObject();
                r.put("ok", false);
                r.put("err", String.valueOf(e.getMessage()));
                return r.toString();
            } catch (Exception ex) {
                return "{\"ok\":false,\"err\":\"unknown\"}";
            }
        }
    }

    @JavascriptInterface
    public synchronized void stopServer() {
        lan.stopBeacon();
        if (server != null) {
            server.stop();
            server = null;
        }
        releaseMulticast();
    }

    @JavascriptInterface
    public boolean send(String msg) {
        WsServer s = server;
        return s != null && s.send(msg);
    }

    @JavascriptInterface
    public void kick() {
        WsServer s = server;
        if (s != null) s.kick();
    }

    @JavascriptInterface
    public void startBeacon(String name) {
        WsServer s = server;
        if (s != null) lan.startBeacon(name, s.getPort());
    }

    @JavascriptInterface
    public void stopBeacon() { lan.stopBeacon(); }

    @JavascriptInterface
    public void startDiscovery() {
        acquireMulticast();
        lan.startListen(new LanDiscovery.Found() {
            public void onFound(String ip, int port, String name) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("ip", ip);
                    o.put("port", port);
                    o.put("name", name);
                    emit("found", o.toString());
                } catch (Exception ignored) { }
            }
        });
    }

    @JavascriptInterface
    public void stopDiscovery() {
        lan.stopListen();
        if (server == null) releaseMulticast();
    }

    public void shutdown() {
        lan.stopListen();
        stopServer();
    }
}
